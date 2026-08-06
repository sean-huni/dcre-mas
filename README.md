# dcre-mas

Mandate Affordability / score gate: the third stage of the M10 mandates flow (SCRUM-76) that gates every MRV-validated CREATE mandate request through a bureau affordability enquiry, records a durable write-ahead enquiry in `man_affordability_enquiry`, and transitions each spine row's `spine_state` from `VALIDATED` to `SCORE_PASSED`, `SCORE_DECLINED`, or `SCORE_PENDING` in `dcre_man`.

## What it does

MAS is the DAG successor of MRV (`MRR -> MRV -> MAS -> MIT -> { MIR || MRW }`). AGT launches it as a short-lived Kubernetes Job with `arrival.id` as the identifying JobParameter (R-16). It runs **after** MRV, so a billed bureau enquiry never fires for an invalid row: only `VALIDATED` (and carried `SCORE_PENDING`) spine rows are ever scored. It is **action-code-scoped**: only `CREATE` rows are scored; `AMEND`/`CANCEL` are a valid no-op, left at `VALIDATED` for MIT (ruling note 2: MRR writes spine ROWS, MRV/MAS/MIT each advance the state columns they own).

### The affordability gate (R-08)

For every READY `CREATE` row (spine_state `VALIDATED` fresh, or `SCORE_PENDING` carried over):

1. **Mint** a deterministic idempotency key from the full business identity of the enquiry (the spine entry `(arrival_id, sequence)` + `mandate_ref`) via `domain/IntentKeyMinter`.
2. **Write-ahead persist** the enquiry intent (`ScoreWriteService.persistIntentPending`, `REQUIRES_NEW`): commit the `man_affordability_enquiry` intent row **and** the `VALIDATED -> SCORE_PENDING` spine mark **before** the (billed) bureau call. The guarded `INSERT ... ON CONFLICT (arrival_id, sequence) DO NOTHING` is first-write-wins on the full identity, so a resume is a zero-duplicate no-op (never UPSERT on the PK; CRDB resolves UPSERT on PK only).
3. **Call** the `common/AffordabilityProvider` with the idempotency key. The default `common/StubAffordabilityProvider` (A-59 SYNTHETIC, behind `dcre.mas.provider=stub`) returns a deterministic score derived from the debtor account digits.
4. **Settle** against the per-client threshold (`dcre.mas.threshold.<client>`, default `dcre.mas.threshold.default: 600`):
   - score **at/above** threshold -> `SCORE_PASSED`, enquiry outcome `PASS`;
   - score **below** threshold -> `SCORE_DECLINED`, enquiry outcome `FAIL_SCORE_BELOW_THRESHOLD` (reportable via MIR);
   - provider **UNAVAILABLE** (empty result) -> the row **stays** `SCORE_PENDING` (enquiry outcome `HOLD_BUREAU_UNAVAILABLE`, `completed_at` NULL) and the **next** MAS run's READY scan picks it up (R-12 carry-over; a provider outage is **never** a decline).

### Crash-safety and resume (R-08, chaos-monkey gate)

The write-ahead intent row + the deterministic idempotency key together guarantee **exactly-one enquiry** across a kill-resume cycle:

- because the intent commits **before** the bureau call, a crash between persist and call leaves a durable `SCORE_PENDING` marker; the next run's READY scan re-picks it,
- because the key is a deterministic digest of the entry identity, the resume re-issues the **identical** bureau request, so the bureau bills at most once,
- because the intent write is `INSERT ... ON CONFLICT DO NOTHING` on `(arrival_id, sequence)` (also `intent_key` UNIQUE), the resume never creates a second enquiry row.

Every `spine_state` transition is a GUARDED atomic UPDATE on the entry identity AND the expected prior state (`WHERE ... AND spine_state = 'VALIDATED'` / `'SCORE_PENDING'`), so it is idempotent + resumable (a re-run touches zero already-advanced rows) and non-clobbering (a settled `SCORE_PASSED`/`SCORE_DECLINED` row is excluded from the READY scan and never re-billed). Verified by `MasJobIT.crashBetweenIntentPersistAndProviderCallDoesNotDoubleEnquireOnResume` and `reRunIsIdempotentZeroDuplicateAndSpineStable`: `count(*) == count(DISTINCT (arrival_id, sequence))`, the spine is stable, and settled rows are never re-billed.

## Architecture

Ephemeral Spring Boot 4.1.0 / Spring Batch 6 / Java 25 batch job cloned from the MRR/MRV skeleton: `ExitCodeMain` wires the Batch outcome into the JVM exit code (R-34), CockroachDB via the PostgreSQL driver, platform-batch persistent JobRepository (`@Import BatchJdbcConfig`, `MAS_BATCH_` prefix), layer-first packages (`config/`, `common/`, `domain/`, `service/`, `data/model/`, `data/repo/`). Runs on the default SERIALIZABLE isolation (only PRG carries READ COMMITTED, SCRUM-90).

1. `headerStep` (tasklet): resolves the R-08 per-client threshold token into the job execution context.
2. `scoreStep` (tasklet): the READY-scan scoring pass, writing each enquiry intent ahead of the bureau call. Carries the shared `CrdbRetryExceptionHandler` (40001 re-runs the tasklet).
3. `rollupStep` (tasklet): the seam verdict (`SCORE_COMPLETE`, or `SCORE_CARRIED` when a bureau outage left rows pending), read off the spine.

`BatchMetaConfig` sweeps stale `MAS_BATCH_` executions to ABANDONED before the runner fires (A-39a).

## Database

Liquibase owns the schema in the shared `dcre_man`, per-service history tables (`mas_databasechangelog` / `mas_databasechangeloglock`), calendar layout `2026/07/`, pure-XML typed changesets (MARK_RAN bootstrap guards):

- `000-man-core-bootstrap.xml`: byte-equivalent VERBATIM copy of the shared-core bootstrap (only the changeset ids are `mas-` prefixed, per the shared-core canon) so concurrent first runs of any M-service converge.
- `001-man-affordability-enquiry.xml`: `man_affordability_enquiry` (`arrival_id`, `sequence`, `intent_key`, `requested_at`, `outcome`, `score`, `completed_at`; UNIQUE `(arrival_id, sequence)` and UNIQUE `intent_key`). MAS is the sole writer (R-04).
- `002-batch-metadata.xml`: Liquibase-owned Spring Batch 6.0.4 DDL (`batch-metadata-mas.sql`), prefixed `MAS_BATCH_`, EXIT_MESSAGE widened to TEXT for CockroachDB.

MAS reads the MRR-owned spine (`mandate_request_header` / `mandate_request_entry`); it never re-declares the spine in its own changelog (MRV reads it the same way).

## Configuration

- `dcre.mas.provider` (`stub`): selects the `AffordabilityProvider`. The A-59 `StubAffordabilityProvider` is the default until a real bureau adapter arrives.
- `dcre.mas.threshold.default` (`600`) + `dcre.mas.threshold.<client>`: the per-client score threshold.

12FactorApp Alignment (https://12factor.net/): config strictly from the environment, working `.yml` defaults committed (clean-clone boots with no `.env`), every deployed context overrides via env.

## Tests

- `domain/IntentKeyMinterTest`, `common/StubAffordabilityProviderTest`, `config/MasThresholdPropertiesTest`: pure-unit coverage of the deterministic key minting, the digit-derived stub scoring (pinned fixtures), and per-client threshold resolution.
- `MasJobIT`: the full job over real CockroachDB (Testcontainers) for every R-08 path end to end: PASS/DECLINE threshold verdicts, per-client threshold, AMEND/CANCEL no-op, invalid-row never-billed, R-12 provider-unavailable carry-over, and the R-08 crash-between-intent-persist-and-provider-call resume with the zero-duplicate audit.

# dcre-mas

> Part of the DCRE fleet. For the fleet map, the rulings and the diagrams that specify every stage, start at the [DCRE design register](https://github.com/sean-huni/dcre-design-register); the complete list of live repositories is its [Repositories](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories) table.

Mandate Affordability / score gate: the third stage of the M10 mandates flow (SCRUM-76) that gates every MRV-validated CREATE mandate request through a bureau affordability enquiry, records a durable write-ahead enquiry in `man_affordability_enquiry`, and transitions each spine row's `spine_state` from `VALIDATED` to `SCORE_PASSED`, `SCORE_DECLINED`, or `SCORE_PENDING` in `dcre_man`.

## What it does

| | |
|---|---|
| Stage code | `MAS` (AGT `Stage.MAS`); renamed from MAF, and the Batch job bean is still `mafJob` |
| Family | Mandates (pain.009, `dcre_man`) |
| Leg | REQ |
| Trigger | Arrival-launched: AGT launches it when MRV completes on an `onhost-req-man` arrival |
| Upstream | `MRV` |
| Downstream | `MIT` |
| Diagram sheet | `dcre-mandates-req` in the design register |

DAG position read from AGT `origin/dev` `RouteDags.java` and `Stage.java` (checked 2026-09-28): `MRR -> MRV -> MAS -> MIT -> fork {MIR, MRW}`.

MAS is the DAG successor of MRV (`MRR -> MRV -> MAS -> MIT -> { MIR || MRW }`). AGT launches it as a short-lived Kubernetes Job with `arrival.id` as the identifying JobParameter (R-16). It runs **after** MRV, so a billed bureau enquiry never fires for an invalid row: only `VALIDATED` (and carried `SCORE_PENDING`) spine rows are ever scored. It is **action-code-scoped**: only `CREATE` rows are scored; `AMEND`/`CANCEL` are a valid no-op, left at `VALIDATED` for MIT (ruling note 2: MRR writes spine ROWS, MRV/MAS/MIT each advance the state columns they own).

### The affordability gate (R-08)

For every READY `CREATE` row (spine_state `VALIDATED` fresh, or `SCORE_PENDING` carried over):

1. **Mint** a deterministic idempotency key from the full business identity of the enquiry (the spine entry `(arrival_id, sequence)` + `mandate_ref`) via `domain/IntentKeyMinter`.
2. **Write-ahead persist** the enquiry intent (`ScoreWriteService.persistIntentPending`, `REQUIRES_NEW`): commit the `man_affordability_enquiry` intent row **and** the `VALIDATED -> SCORE_PENDING` spine mark **before** the (billed) bureau call. The guarded `INSERT ... ON CONFLICT (arrival_id, sequence) DO NOTHING` is first-write-wins on the full identity, so a resume is a zero-duplicate no-op (never UPSERT on the PK; CRDB resolves UPSERT on PK only).
3. **Call** the `common/AffordabilityProvider` with the idempotency key. The default `common/StubAffordabilityProvider` (A-59 SYNTHETIC, behind `dcre.mas.provider=stub`) returns a deterministic score derived from the debtor account digits.
4. **Settle** against the per-client threshold (`dcre.mas.threshold.<client>`, default `dcre.mas.threshold.default: 600`):
   - score **at/above** threshold -> `SCORE_PASSED`, enquiry outcome `PASS`;
   - score **below** threshold -> `SCORE_DECLINED`, enquiry outcome `FAIL_SCORE_BELOW_THRESHOLD` (reportable via MIR);
   - provider **UNAVAILABLE** (empty result) -> the row **stays** `SCORE_PENDING` (enquiry outcome `HOLD_BUREAU_UNAVAILABLE`, `completed_at` NULL) and the **next** MAS run on that arrival picks it up in its READY scan (R-12 carry-over; a provider outage is **never** a decline). A next run needs a new JobInstance: `MasJobIT` adds an identifying `attempt` parameter, while AGT's `serviceArgs` pass only `arrival.id` (checked 2026-09-28).

### Crash-safety and resume (R-08, chaos-monkey gate)

The write-ahead intent row + the deterministic idempotency key together guarantee **exactly-one enquiry** across a kill-resume cycle:

- because the intent commits **before** the bureau call, a crash between persist and call leaves a durable `SCORE_PENDING` marker; the next run's READY scan re-picks it,
- because the key is a deterministic digest of the entry identity, the resume re-issues the **identical** bureau request, so the bureau bills at most once,
- because the intent write is `INSERT ... ON CONFLICT DO NOTHING` on `(arrival_id, sequence)` (also `intent_key` UNIQUE), the resume never creates a second enquiry row.

Every `spine_state` transition is a GUARDED atomic UPDATE on the entry identity AND the expected prior state (`WHERE ... AND spine_state = 'VALIDATED'` / `'SCORE_PENDING'`), so it is idempotent + resumable (a re-run touches zero already-advanced rows) and non-clobbering (a settled `SCORE_PASSED`/`SCORE_DECLINED` row is excluded from the READY scan and never re-billed). Verified by `MasJobIT.crashBetweenIntentPersistAndProviderCallDoesNotDoubleEnquireOnResume` and `reRunIsIdempotentZeroDuplicateAndSpineStable`: `count(*) == count(DISTINCT (arrival_id, sequence))`, the spine is stable, and settled rows are never re-billed.

## Architecture and principles

Ephemeral Spring Boot 4.1.0 / Spring Batch 6 / Java 25 batch job cloned from the MRR/MRV skeleton: `ExitCodeMain` wires the Batch outcome into the JVM exit code (R-34), CockroachDB via the PostgreSQL driver, platform-batch persistent JobRepository (`@Import BatchJdbcConfig`, `MAS_BATCH_` prefix), layer-first packages (`config/`, `common/`, `domain/`, `service/`, `data/model/`, `data/repo/`). Runs on the default SERIALIZABLE isolation (only CRG, the collections report generator, carries READ COMMITTED, SCRUM-90).

1. `headerStep` (tasklet): resolves the R-08 per-client threshold token into the job execution context.
2. `scoreStep` (tasklet): the READY-scan scoring pass, writing each enquiry intent ahead of the bureau call. Carries the shared `CrdbRetryExceptionHandler` (40001 re-runs the tasklet).
3. `rollupStep` (tasklet): the domain rollup read off the spine, `SCORE_COMPLETE`, or `SCORE_CARRIED` when a bureau outage left CREATE rows `SCORE_PENDING`. These are the Batch exit statuses only. The seam file (`OutcomeSeamListener("mas", ...)`, `<DCRE_EXCHANGE_ROOT>/outcomes/<JOB_NAME>`) carries the AGT Outcome name `MasRollupService.seamOutcome` maps them to: `BUSINESS_ACCEPTED` or `BUSINESS_PARTIAL`; an unmapped value throws rather than emit a token AGT cannot parse.

`BatchMetaConfig` sweeps stale `MAS_BATCH_` executions to ABANDONED before the runner fires (A-39a).

### Database

One business datasource: `dcre_man` via `DCRE_DB_URL` / `DCRE_DB_USER` / `DCRE_DB_PASSWORD`. A second datasource (`DCRE_AGTOPS_DB_*`, platform-batch `HeartbeatDatasourceConfig`) carries only the `HeartbeatWriter` liveness stamp into `agt_ops`.

- Writes: `man_affordability_enquiry` (intent insert, then outcome/score/`completed_at` update); `mandate_request_entry.spine_state` (`VALIDATED -> SCORE_PENDING` for CREATE rows, then `SCORE_PENDING -> SCORE_PASSED | SCORE_DECLINED`); its own `MAS_BATCH_*` metadata.
- Reads: `mandate_request_header` (client token for the threshold) and `mandate_request_entry` (READY scan: CREATE rows in `VALIDATED` or `SCORE_PENDING`).

Liquibase owns the schema in the shared `dcre_man`, per-service history tables (`mas_databasechangelog` / `mas_databasechangeloglock`), calendar layout `2026/07/`, pure-XML typed changesets throughout.

The changelog is the **v1 baseline** (SCRUM-107, owner directive 2026-08-08): every DCRE database is dropped and recreated for the direct cut-over, so it has never run anywhere. It therefore carries no `validCheckSum`, no defensive `IF NOT EXISTS`, and no retrofit changesets; every table is minted in its final shape. The only `MARK_RAN` preconditions left are **convergence** guards, on the three shared-core reference tables that a second writer (the `dcre-infra` seed, or a sibling M-service migrating the same `dcre_man`) can legitimately create first.

- `000-man-core-bootstrap.xml`: the shared-core bootstrap, structurally identical (comments and the `mas-` changeset id prefix aside) to the copies in mrr, mrv, mit and mir (checked 2026-09-28), so concurrent first runs of any mandates service converge. This is where the convergence guards live.
- `001-man-affordability-enquiry.xml`: `man_affordability_enquiry` (`arrival_id`, `sequence`, `intent_key`, `requested_at`, `outcome`, `score`, `completed_at`; UNIQUE `(arrival_id, sequence)` and UNIQUE `intent_key`). MAS is the sole writer (R-04), so this changeset carries **no** guard: nothing else can create the table.
- `002-batch-metadata.xml`: Liquibase-owned Spring Batch 6.0.4 DDL as typed XML, one changeset per object, prefixed `MAS_BATCH_`, EXIT_MESSAGE widened to TEXT for CockroachDB. MAS is the only creator of its own `MAS_BATCH_` objects, so these changesets carry no guard either; a kill mid-migration resumes at the object it died on because each commits its own history row (A-81).

MAS reads the MRR-owned spine (`mandate_request_header` / `mandate_request_entry`); it never re-declares the spine in its own changelog (MRV reads it the same way).

## Prerequisites

- Java 25: `.sdkmanrc` pins `java=25-tem` (`sdk env`); `build.gradle` sets source/target compatibility 25.
- Gradle 9.5.1 through the committed wrapper (`gradle/wrapper/gradle-wrapper.properties`).
- Docker: Testcontainers CockroachDB for the tests, and the image build.
- Platform libs in Maven Local: `za.co.fnb.dcre:platform-persistence:0.1.0` and `platform-batch:0.1.0` (`platform.model` arrives transitively).
- For a real local run: CockroachDB on `localhost:26257` with `dcre_man` (MRR's spine already present) and `agt_ops`.

## Quickstart

Clean clone, no `.env` needed (working dev defaults committed in `application.yml`):

```bash
./gradlew test          # full suite, Docker required
./gradlew bootJar       # build/libs/mas-2.0.jar
java -jar build/libs/mas-2.0.jar 'arrival.id=<uuid>,java.lang.String,true'
```

## Configuration

All keys live in `src/main/resources/application.yml` (the only profile). Spring relaxed binding lets any property be overridden by its environment-variable form, so this table is the documented set, not a closed total. 12FactorApp Alignment (https://12factor.net/): working defaults committed, a clean clone boots with no `.env`, every deployed context overrides via env.

| Env | Default | Purpose |
|---|---|---|
| `DCRE_DB_URL` | `jdbc:postgresql://localhost:26257/dcre_man?sslmode=disable` | Mandates DB (CockroachDB) |
| `DCRE_DB_USER` | `root` | DB user |
| `DCRE_DB_PASSWORD` | (empty) | DB password |
| `DCRE_AGTOPS_DB_URL` | `jdbc:postgresql://localhost:26257/agt_ops?sslmode=disable` | Heartbeat datasource |
| `DCRE_AGTOPS_DB_USER` | `root` | Heartbeat DB user |
| `DCRE_AGTOPS_DB_PASSWORD` | (empty) | Heartbeat DB password |
| `DCRE_EXCHANGE_ROOT` | `../../../../../../infra/dcre-infra/exchange` | Exchange root for the outcome seam file |
| `DCRE_MAS_PROVIDER` | `stub` | Selects the `AffordabilityProvider`; `StubAffordabilityProvider` is `@ConditionalOnProperty(havingValue = "stub", matchIfMissing = true)` and is the only implementation in `src/main` |
| `JOB_NAME` | unset | Set by AGT; names the outcome seam file |

Keys without an env placeholder in yml:

- `dcre.mas.threshold.default` (`600`, and `MasThresholdProperties.FALLBACK` is also 600) and `dcre.mas.threshold.<CLIENT>`: the per-client R-08 threshold. The lookup is an exact match on the stripped client token, which is uppercase. Spring Boot lowercases map keys bound from environment variables, so a per-client override belongs in yml (bracketed key, e.g. `"[FNBRF01]": 650`), not in an env var.
- `dcre.batch.table-prefix: MAS_BATCH_`, Liquibase history tables `mas_databasechangelog` / `mas_databasechangeloglock`.

## Testing

`./gradlew test` (Docker required; `useJUnitPlatform()` with no filter, so `*IT` classes run in the same task). Testcontainers image: `cockroachdb/cockroach:v26.2.3`.

- `domain/IntentKeyMinterTest`, `common/StubAffordabilityProviderTest`, `config/MasThresholdPropertiesTest`: pure-unit coverage of the deterministic key minting, the digit-derived stub scoring (pinned fixtures), and per-client threshold resolution.
- `config/MasThresholdBindingTest`: the `dcre.mas` prefix and the yml key actually bind (asserts the map is populated, since the default and the fallback are both 600).
- `service/MasRollupServiceSeamTest`: the SCORE_* rollup maps to the canonical seam Outcome names.
- `MasJobIT`: the full job over real CockroachDB for every R-08 path end to end: PASS/DECLINE threshold verdicts, per-client threshold, AMEND/CANCEL no-op, invalid-row never-billed, R-12 provider-unavailable carry-over, and the R-08 crash-between-intent-persist-and-provider-call resume with the zero-duplicate audit.
- `HalfAppliedBatchMetadataIT`: A-81, a kill part-way through the batch-metadata migration resumes at the object it died on.

No Cucumber features ship in this repo, although the Cucumber dependencies are declared.

## Local cluster deployment

```bash
./gradlew bootJar
docker build -t dcre-mas:<version> .
kind load docker-image --name dcre-dev dcre-mas:<version>
```

Image base: `eclipse-temurin:25-jre-alpine` (`Dockerfile` copies `build/libs/mas-2.0.jar`). AGT launches MAS as a K8s Job in the mandates flow namespace (`AGT_NAMESPACE_MAN`, default `dcre-man`) with the image from `AGT_MAS_IMAGE` (empty default = launch-disabled) and the single arg `arrival.id=<uuid>`. AGT injects `JOB_NAME`, `DCRE_DB_URL` (from `AGT_MAN_SERVICE_DB_URL`, default `dcre_man` on `crdb.dcre.svc.cluster.local`), `DCRE_EXCHANGE_ROOT=/exchange` (the `dcre-exchange` PVC), `DCRE_AGTOPS_DB_URL` and `DCRE_AGTOPS_DB_USER`; it does not set `DCRE_MAS_PROVIDER`, so the stub is what runs (AGT `origin/dev` `JobLauncher.java` and `application.yml`, checked 2026-09-28). The cluster itself, and the fleet-wide image switch, live in dcre-infra.

## Related repositories

The complete, current list of live DCRE repositories (stage services, orchestrator, platform libraries, infra and tooling) lives in one place: the [DCRE design register README](https://github.com/sean-huni/dcre-design-register/blob/dev/README.md#repositories). Deprecated and archived repositories are deliberately absent from it. This README does not copy that list, so it cannot drift.

- Design register: https://github.com/sean-huni/dcre-design-register (start at `docs/specs/DESIGN-REGISTER.md`; the diagrams in `docs/diagrams/` are the specification)

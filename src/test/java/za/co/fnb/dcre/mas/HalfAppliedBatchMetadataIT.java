package za.co.fnb.dcre.mas;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.CockroachContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-81 (SCRUM-107), found by the mandates chaos gate on 2026-08-07: a SIGKILL partway through the
 * batch-metadata migration left MAS with a schema that could never repair itself.
 *
 * <p>The original shape was ONE changeset running ONE {@code sqlFile} that created nine objects,
 * six tables first and three sequences last, behind a {@code MARK_RAN} precondition that sampled
 * exactly ONE of the nine, {@code MAS_BATCH_JOB_INSTANCE}. Liquibase records a changeset only once
 * it completes, so the kill left the tables present and no history row; on the re-run the sampled
 * table existed, the precondition marked the whole changeset applied, and the three sequences were
 * never created. MAS then failed forever with
 * {@code relation "mas_batch_job_instance_seq" does not exist}.
 *
 * <p><b>What v1 changed, and why this test was rewritten (SCRUM-107, owner directive
 * 2026-08-08).</b> The old test reproduced the kill by hand-creating {@code MAS_BATCH_JOB_INSTANCE}
 * with no history row and requiring the migration to converge over it. That asserted a property
 * only a re-run guard can provide, and v1 abolishes the guard: every DCRE database is dropped and
 * recreated, {@code MAS_BATCH_} objects have exactly one creator, and a
 * {@code 002-batch-metadata.xml} changeset therefore carries no {@code MARK_RAN}. Run against the
 * v1 changelog the old fixture fails at the first changeset with
 * {@code relation "mas_batch_job_instance" already exists}, which is CORRECT: on a v1 database a
 * pre-existing table with no history row is not a state the schema is required to survive, because
 * nothing can produce it.
 *
 * <p>The A-81 defect is fixed STRUCTURALLY instead, by one changeset per object. Each object now
 * commits its own history row, so an interrupted migration resumes at the exact object it died on,
 * which is the convergence the defensive clause was approximating. That is the property asserted
 * below, and the fixture now reproduces the kill the way Liquibase actually leaves one: by applying
 * a PREFIX of the changelog, rather than by forging a table Liquibase has no record of.
 */
class HalfAppliedBatchMetadataIT {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.xml";

    /**
     * The changeset count that lands exactly on the old kill point: 5 shared-core bootstrap + 1
     * affordability enquiry + the 6 {@code MAS_BATCH_} tables, stopping before the first of the 3
     * sequences. The number is not trusted blind; the test asserts the state it produces (6 tables,
     * 0 sequences), so a changelog that grows or reorders fails this test loudly rather than
     * quietly measuring a different cut.
     */
    private static final int CHANGESETS_UP_TO_LAST_BATCH_TABLE = 12;

    private static CockroachContainer crdb;

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void startDb() {
        crdb = new CockroachContainer(DockerImageName.parse("cockroachdb/cockroach:v26.2.3"));
        crdb.start();
        final DriverManagerDataSource ds = new DriverManagerDataSource(
                crdb.getJdbcUrl(), crdb.getUsername(), crdb.getPassword());
        ds.setDriverClassName("org.postgresql.Driver");
        jdbc = new JdbcTemplate(ds);
    }

    @AfterAll
    static void stopDb() {
        if (crdb != null) {
            crdb.stop();
        }
    }

    /**
     * Reproduces the A-81 kill point on a v1 database: the migration is interrupted after the last
     * batch TABLE and before the first SEQUENCE, then resumed, and every object must be present.
     */
    @Test
    @Timeout(300)
    void aMigrationInterruptedBetweenTablesAndSequencesResumesAtTheObjectItDiedOn() throws Exception {
        final String url = freshDatabase("mas_a81");

        // The kill point, produced the way a killed pod produces it: Liquibase applied a prefix of
        // the changelog and committed a history row per completed object.
        migratePrefix(url, CHANGESETS_UP_TO_LAST_BATCH_TABLE);

        assertThat(batchTableCount(url))
                .as("fixture check: the interruption must land AFTER the six MAS_BATCH_ tables,"
                        + " otherwise this test is measuring some other cut of the changelog")
                .isEqualTo(6);
        assertThat(sequenceCount(url))
                .as("control: the interrupted state genuinely has no sequences, so a pass below"
                        + " cannot come from them having been there all along")
                .isZero();

        migrate(url);

        assertThat(sequenceCount(url))
                .as("resuming must create the three sequences the interrupted run never reached."
                        + " Under the pre-v1 single changeset guarded by a MARK_RAN sampling one"
                        + " surviving table this stays 0 and MAS fails forever with"
                        + " 'relation mas_batch_job_instance_seq does not exist'")
                .isEqualTo(3);
        assertThat(batchTableCount(url))
                .as("resuming must not disturb the objects the interrupted run did complete")
                .isEqualTo(6);
    }

    /** A second migration over a complete schema must stay a no-op rather than erroring. */
    @Test
    @Timeout(300)
    void reMigratingACompleteSchemaIsANoOp() throws Exception {
        final String url = freshDatabase("mas_a81_repeat");

        migrate(url);
        migrate(url);

        assertThat(sequenceCount(url)).isEqualTo(3);
        assertThat(batchTableCount(url)).isEqualTo(6);
    }

    private String freshDatabase(final String db) {
        jdbc.execute("DROP DATABASE IF EXISTS " + db + " CASCADE");
        jdbc.execute("CREATE DATABASE " + db);
        return crdb.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
    }

    private void migrate(final String url) throws Exception {
        withLiquibase(url, liquibase -> liquibase.update(new Contexts(), new LabelExpression()));
    }

    /** Applies only the first {@code changesets} changesets, leaving the migration half applied. */
    private void migratePrefix(final String url, final int changesets) throws Exception {
        withLiquibase(url, liquibase -> liquibase.update(changesets, new Contexts(), new LabelExpression()));
    }

    private void withLiquibase(final String url, final LiquibaseAction action) throws Exception {
        try (Connection c = open(url)) {
            final Database db = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(c));
            db.setDatabaseChangeLogTableName("mas_databasechangelog");
            db.setDatabaseChangeLogLockTableName("mas_databasechangeloglock");
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), db)) {
                action.run(liquibase);
            }
        }
    }

    private Connection open(final String url) throws Exception {
        return DriverManager.getConnection(url, crdb.getUsername(), crdb.getPassword());
    }

    private long sequenceCount(final String url) throws Exception {
        return count(url, "SELECT count(*) FROM information_schema.sequences"
                + " WHERE sequence_name LIKE 'mas_batch%'");
    }

    private long batchTableCount(final String url) throws Exception {
        return count(url, "SELECT count(*) FROM information_schema.tables"
                + " WHERE table_name LIKE 'mas_batch%' AND table_type = 'BASE TABLE'");
    }

    private long count(final String url, final String sql) throws Exception {
        try (Connection c = open(url); var s = c.createStatement(); var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @FunctionalInterface
    private interface LiquibaseAction {
        void run(Liquibase liquibase) throws Exception;
    }
}

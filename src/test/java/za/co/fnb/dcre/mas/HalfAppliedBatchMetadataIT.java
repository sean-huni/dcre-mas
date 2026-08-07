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
 * A-81 (SCRUM-107), found by the mandates chaos gate on 2026-08-07, as a direct consequence of
 * fixing A-80: once the stale lock stopped wedging the pod, the migration got far enough to
 * reveal that it had been left HALF APPLIED by the original kill.
 *
 * <p>{@code 002-batch-metadata-mas} runs one {@code sqlFile} that creates nine objects: six
 * tables first, three sequences LAST. Liquibase writes its changelog row only after the whole
 * changeset succeeds, so a SIGKILL between the last table and the first sequence leaves the
 * tables present and no history row at all. Observed on dcre_man:
 *
 * <pre>
 * mas_batch_job_instance, ..._execution, ..._execution_params, ..._step_execution   present
 * mas_batch_job_instance_seq and the other two sequences                            ABSENT
 * MAS attempts 0,1,2                                       TECH_FAILED
 *   relation "mas_batch_job_instance_seq" does not exist
 * </pre>
 *
 * <p>The changeset's {@code MARK_RAN} precondition tested exactly ONE of those nine objects,
 * {@code MAS_BATCH_JOB_INSTANCE}. On the re-run that table existed, so the changeset was recorded
 * as applied and the three missing sequences were never created, permanently. Half-applied plus a
 * guard that reads one surviving object as proof of completion equals a schema that can never
 * repair itself.
 *
 * <p>Fix: idempotency belongs IN the DDL ({@code IF NOT EXISTS} on every CREATE), which converges
 * from any partial state, not in a precondition that samples one object and declares the rest
 * done.
 */
class HalfAppliedBatchMetadataIT {

    private static final String CHANGELOG = "db/changelog/db.changelog-master.xml";

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
     * Reproduces the kill point exactly: the tables the pod managed to create, and nothing else,
     * then migrates and requires the sequences to appear.
     */
    @Test
    @Timeout(300)
    void aMigrationInterruptedBetweenTablesAndSequencesConverges() throws Exception {
        final String db = "mas_a81";
        jdbc.execute("DROP DATABASE IF EXISTS " + db + " CASCADE");
        jdbc.execute("CREATE DATABASE " + db);
        final String url = crdb.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");

        // What the SIGKILLed pod left behind: the tables created before the kill, no sequences,
        // and no changelog row, because Liquibase records the changeset only once it completes.
        execute(url, "CREATE TABLE MAS_BATCH_JOB_INSTANCE (JOB_INSTANCE_ID BIGINT NOT NULL PRIMARY KEY,"
                + " VERSION BIGINT, JOB_NAME VARCHAR(100) NOT NULL, JOB_KEY VARCHAR(32) NOT NULL,"
                + " constraint JOB_INST_UN unique (JOB_NAME, JOB_KEY))");

        assertThat(sequenceCount(url))
                .as("control: the half-applied state genuinely has no sequences, so a pass below"
                        + " cannot come from them having been there all along")
                .isZero();

        migrate(url);

        assertThat(sequenceCount(url))
                .as("the interrupted changeset must COMPLETE the objects it never reached. Under a"
                        + " MARK_RAN precondition sampling one surviving table this stays 0 and MAS"
                        + " fails forever with 'relation mas_batch_job_instance_seq does not exist'")
                .isEqualTo(3);
    }

    /** A second migration over a complete schema must stay a no-op rather than erroring. */
    @Test
    @Timeout(300)
    void reMigratingACompleteSchemaIsANoOp() throws Exception {
        final String db = "mas_a81_repeat";
        jdbc.execute("DROP DATABASE IF EXISTS " + db + " CASCADE");
        jdbc.execute("CREATE DATABASE " + db);
        final String url = crdb.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");

        migrate(url);
        migrate(url);

        assertThat(sequenceCount(url)).isEqualTo(3);
    }

    private void migrate(final String url) throws Exception {
        try (Connection c = open(url)) {
            final Database db = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(c));
            db.setDatabaseChangeLogTableName("mas_databasechangelog");
            db.setDatabaseChangeLogLockTableName("mas_databasechangeloglock");
            try (Liquibase liquibase = new Liquibase(CHANGELOG, new ClassLoaderResourceAccessor(), db)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private Connection open(final String url) throws Exception {
        return DriverManager.getConnection(url, crdb.getUsername(), crdb.getPassword());
    }

    private void execute(final String url, final String sql) throws Exception {
        try (Connection c = open(url); var s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private long sequenceCount(final String url) throws Exception {
        try (Connection c = open(url); var s = c.createStatement();
                var rs = s.executeQuery("SELECT count(*) FROM information_schema.sequences"
                        + " WHERE sequence_name LIKE 'mas_batch%'")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}

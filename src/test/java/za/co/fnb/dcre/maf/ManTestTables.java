package za.co.fnb.dcre.maf;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * Test seeding helpers. account / account_type / mandate come from MAF's own
 * Liquibase core bootstrap (000-man-core-bootstrap.xml), and man_affordability_enquiry
 * from 001; this helper only stands up the MRR-owned spine tables
 * (mandate_request_header / mandate_request_entry, NOT in MAF's changelog, exactly
 * as MRV's ManTestTables stands up the mrr-owned spine) with the subset of columns
 * MAF's read models map. Rows are seeded at the post-MRV spine_state MAF reads
 * (VALIDATED for a scoreable CREATE; REJECTED to prove invalid rows are never
 * billed).
 */
public final class ManTestTables {

    private ManTestTables() {
    }

    public static void createSpine(final JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mandate_request_header (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    arrival_id UUID NOT NULL UNIQUE,
                    client_token VARCHAR(16),
                    destination_id VARCHAR(16) NOT NULL,
                    entry_count INT NOT NULL)""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS mandate_request_entry (
                    id UUID NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
                    version BIGINT NOT NULL DEFAULT 0,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                    arrival_id UUID NOT NULL,
                    sequence INT NOT NULL,
                    record_type VARCHAR(2) NOT NULL,
                    action_code VARCHAR(16) NOT NULL,
                    mandate_ref VARCHAR(35) NOT NULL,
                    contract_ref VARCHAR(14),
                    debtor_account VARCHAR(32),
                    currency VARCHAR(3) NOT NULL,
                    dup_in_file BOOLEAN NOT NULL DEFAULT false,
                    spine_state VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
                    UNIQUE (arrival_id, sequence))""");
    }

    public static void insertHeader(final JdbcTemplate jdbc, final UUID arrival, final String client,
                                    final int entryCount) {
        jdbc.update("""
                INSERT INTO mandate_request_header (arrival_id, client_token, destination_id, entry_count)
                VALUES (?,?,?,?)""", arrival, client, client, entryCount);
    }

    /** Seed one spine row at the given post-MRV state (VALIDATED / REJECTED / ...). */
    public static void insertEntry(final JdbcTemplate jdbc, final UUID arrival, final int sequence,
                                   final String action, final String ref, final String debtorAccount,
                                   final String spineState) {
        jdbc.update("""
                INSERT INTO mandate_request_entry (arrival_id, sequence, record_type, action_code,
                    mandate_ref, contract_ref, debtor_account, currency, spine_state)
                VALUES (?,?, 'MD', ?,?, 'CTR1', ?, 'ZAR', ?)""",
                arrival, sequence, action, ref, debtorAccount, spineState);
    }
}

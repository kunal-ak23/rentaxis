package com.datagami.rentaxis.domain;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Changeset 83 turns the lease into a posting document (charge-type lines, a
 * contract date, a renewal chain, its own receivable/income accounts) and
 * promotes cheques out of payment_schedules into a register of their own. The
 * guarantees that later tasks lean on — a positive amount, one PDC number per
 * lease, a property code unique inside a tenant — are database constraints, so
 * they are asserted against a real Postgres rather than through the services.
 */
@SpringBootTest
@Testcontainers
class LeaseChequeSchemaIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbc;

    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO landlord_org (id, name, slug) VALUES (?, ?, ?)", id, "T-" + id, "t-" + id);
        return id;
    }

    private UUID property(UUID tenant, String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO properties (id, tenant_id, name_en, emirate, code) VALUES (?,?,?,?,?)",
                id, tenant, "P-" + id, "DUBAI", code);
        return id;
    }

    /** How many of {@code names} exist as columns of {@code public.table}. */
    private int columns(String table, String... names) {
        String placeholders = String.join(",", java.util.Collections.nCopies(names.length, "?"));
        Object[] args = new Object[names.length + 1];
        args[0] = table;
        System.arraycopy(names, 0, args, 1, names.length);
        return jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = ?"
                        + " AND column_name IN (" + placeholders + ")",
                Integer.class, args);
    }

    @Test
    void theFourNewTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = 'public'"
                        + " AND table_name IN ('charge_types','lease_lines','cheques','penalty_assessments')"
                        + " ORDER BY table_name",
                String.class);
        assertThat(tables).containsExactly("charge_types", "cheques", "lease_lines", "penalty_assessments");
    }

    @Test
    void chequeAmountMustBePositive() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = 'ck_cheques_amount_positive' AND contype = 'c'",
                Integer.class);
        assertThat(n).isEqualTo(1);
    }

    @Test
    void pdcNumbersAreUniqueWithinALease() {
        String def = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'ux_cheques_lease_number'",
                String.class);
        assertThat(def)
                .contains("UNIQUE")
                .contains("lease_id")
                .contains("cheque_number")
                .contains("'PDC'");
    }

    @Test
    void leaseCarriesTheV2PostingColumns() {
        assertThat(columns("leases",
                "contract_date", "total_days", "grace_period_days", "first_due_date",
                "renewed_from_lease_id", "chain_id", "receivable_account_id", "income_account_id",
                "posting_journal_id", "posted_at", "posted_by"))
                .isEqualTo(11);
    }

    @Test
    void leaseCarriesRenterAcceptanceAndDepositCarryForward() {
        // Task 6 stops renter acceptance from activating a lease and records the
        // timestamp instead; Task 8's renewal carries the old deposit forward.
        assertThat(columns("leases", "renter_accepted_at", "carry_deposit_forward")).isEqualTo(2);
        Boolean carry = jdbc.queryForObject(
                "SELECT is_nullable = 'NO' FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = 'leases' AND column_name = 'carry_deposit_forward'",
                Boolean.class);
        assertThat(carry).isTrue();
    }

    @Test
    void companionColumnsLandOnTheSettingsAndPaymentTables() {
        assertThat(columns("landlord_org_fine_settings",
                "bounces_before_penalty", "auto_propose_cheque_return", "auto_propose_late_payment")).isEqualTo(3);
        assertThat(columns("rent_collection_settings", "bounces_before_penalty")).isEqualTo(1);
        assertThat(columns("tenant_gateway_configs", "settlement_account_id")).isEqualTo(1);
        assertThat(columns("online_payments", "cheque_id")).isEqualTo(1);
        assertThat(columns("meeting_details", "cheque_ids")).isEqualTo(1);
        assertThat(columns("properties", "code")).isEqualTo(1);
    }

    @Test
    void propertyCodeIsUniquePerTenantButNotAcrossTenants() {
        UUID t1 = tenant();
        property(t1, "TWR");
        assertThatThrownBy(() -> property(t1, "TWR"))
                .isInstanceOf(DataIntegrityViolationException.class);

        UUID t2 = tenant();
        assertThatCode(() -> property(t2, "TWR")).doesNotThrowAnyException();
    }
}

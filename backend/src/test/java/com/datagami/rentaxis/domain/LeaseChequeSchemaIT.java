package com.datagami.rentaxis.domain;

import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

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
class LeaseChequeSchemaIT extends AbstractPostgresIT {

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

    /**
     * No two <em>live</em> tenancies on a unit for overlapping dates (F14-14,
     * changeset 116). Changesets 80/86 allowed one ACTIVE/NOTICE_GIVEN lease per unit
     * whatever the dates, which refused a back-to-back letting; the exclusion
     * constraint judges by the terms instead.
     */
    @Test
    void noOverlappingLiveLeasesPerUnit() {
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public'"
                        + " AND indexname = 'ux_leases_one_active_per_unit'",
                Integer.class)).isZero();
        String def = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ex_leases_no_overlap_per_unit'",
                String.class);
        assertThat(def)
                .contains("EXCLUDE")
                .contains("unit_id")
                .contains("'ACTIVE'")
                .contains("'NOTICE_GIVEN'");
    }

    /**
     * …and the database enforces it, not only the service: an overlapping second
     * tenancy is refused at the row whatever path wrote it, a back-to-back one (the
     * day after the first ends) is not.
     */
    @Test
    void theDatabaseRefusesAnOverlappingLiveLeaseButAllowsBackToBack() {
        UUID tenant = tenant();
        UUID property = property(tenant, "LIVE-" + UUID.randomUUID().toString().substring(0, 4));
        UUID unit = unit(tenant, property);
        UUID renter = renter(tenant);
        insertLease(tenant, unit, renter, "NOTICE_GIVEN");

        assertThatThrownBy(() -> insertLease(tenant, unit, renter, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertLease(tenant, unit, renter, "ACTIVE",
                java.time.LocalDate.of(2026, 12, 31), java.time.LocalDate.of(2027, 12, 30)))
                .as("sharing the last day is an overlap")
                .isInstanceOf(DataIntegrityViolationException.class);

        insertLease(tenant, unit, renter, "ACTIVE",
                java.time.LocalDate.of(2027, 1, 1), java.time.LocalDate.of(2027, 12, 31));

        // An ended tenancy never claimed the slot.
        jdbc.update("UPDATE leases SET status = 'TERMINATED' WHERE unit_id = ? AND status = 'NOTICE_GIVEN'", unit);
        insertLease(tenant, unit, renter, "ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM leases WHERE unit_id = ? AND status IN ('ACTIVE','NOTICE_GIVEN')",
                Integer.class, unit)).isEqualTo(2);
    }

    private UUID unit(UUID tenant, UUID property) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO units (id, tenant_id, property_id, unit_number) VALUES (?,?,?,?)",
                id, tenant, property, "U-" + id);
        return id;
    }

    private UUID renter(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO renters (id, tenant_id, name_en) VALUES (?,?,?)", id, tenant, "R-" + id);
        return id;
    }

    private UUID insertLease(UUID tenant, UUID unit, UUID renter, String status) {
        return insertLease(tenant, unit, renter, status, java.time.LocalDate.of(2026, 1, 1),
                java.time.LocalDate.of(2026, 12, 31));
    }

    private UUID insertLease(UUID tenant, UUID unit, UUID renter, String status,
                             java.time.LocalDate start, java.time.LocalDate end) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date,"
                        + " status, rent_amount, deposit_amount) VALUES (?,?,?,?,?,?,?,?,?)",
                id, tenant, unit, renter, start, end, status,
                new java.math.BigDecimal("1200.00"), new java.math.BigDecimal("0.00"));
        return id;
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

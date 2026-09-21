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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Changeset 85 (spec §8-§9) introduces the per-day recognition schema:
 * rent_segments (one per RENT lease line, day rate = amount / actual days)
 * sliced into recognition_entries per calendar month, plus the termination
 * and settlement columns later tasks post against. The guarantees other
 * tasks lean on — a segment's stored day count really matches its date
 * range, an entry's day count is positive, and a segment can only be billed
 * once per period — are database constraints, so they are asserted against a
 * real Postgres rather than through the services.
 */
@SpringBootTest
@Testcontainers
class RecognitionSchemaIT {

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

    private UUID property(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO properties (id, tenant_id, name_en, emirate) VALUES (?,?,?,?)",
                id, tenant, "P-" + id, "DUBAI");
        return id;
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

    private UUID lease(UUID tenant, UUID unit, UUID renter) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date, status, rent_amount, deposit_amount)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                id, tenant, unit, renter, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), "ACTIVE", new BigDecimal("1200.00"), new BigDecimal("0.00"));
        return id;
    }

    private UUID chargeType(UUID tenant) {
        UUID id = UUID.randomUUID();
        // role/behaviour are unconstrained varchar columns (no CHECK/FK), so any
        // string would pass the database, but RENTAL_INCOME/RENT are the real
        // AccountRole/ChargeBehaviour enum members this fixture is standing in for.
        jdbc.update("INSERT INTO charge_types (id, tenant_id, code, name_en, role, behaviour) VALUES (?,?,?,?,?,?)",
                id, tenant, "RENT-" + id.toString().substring(0, 8), "Rent", "RENTAL_INCOME", "RENT");
        return id;
    }

    private UUID leaseLine(UUID tenant, UUID lease, UUID chargeType) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO lease_lines (id, tenant_id, lease_id, seq_no, charge_type_id) VALUES (?,?,?,?,?)",
                id, tenant, lease, 1, chargeType);
        return id;
    }

    private UUID rentSegment(UUID tenant, UUID lease, UUID leaseLine, LocalDate from, LocalDate to) {
        UUID id = UUID.randomUUID();
        int days = (int) (to.toEpochDay() - from.toEpochDay()) + 1;
        jdbc.update("INSERT INTO rent_segments (id, tenant_id, lease_id, lease_line_id, from_date, to_date, amount, days, day_rate)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                id, tenant, lease, leaseLine, from, to, new BigDecimal("1200.00"), days, new BigDecimal("39.726027"));
        return id;
    }

    /** Minimal lease_settlements row: only lease_id/tenant_id are required, everything else defaults. */
    private UUID settlement(UUID tenant, UUID lease) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO lease_settlements (id, tenant_id, lease_id) VALUES (?,?,?)", id, tenant, lease);
        return id;
    }

    /** Asserts that {@code callable} is rejected by a foreign-key constraint. */
    private void assertRejectsDanglingForeignKey(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable) {
        assertThatThrownBy(callable).isInstanceOf(DataIntegrityViolationException.class);
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
    void theTwoNewTablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = 'public'"
                        + " AND table_name IN ('rent_segments','recognition_entries')"
                        + " ORDER BY table_name",
                String.class);
        assertThat(tables).containsExactly("recognition_entries", "rent_segments");
    }

    @Test
    void terminationAndSettlementColumnsExist() {
        assertThat(columns("leases", "terminated_on", "termination_journal_id", "termination_notes")).isEqualTo(3);
        assertThat(columns("lease_settlements", "journal_id")).isEqualTo(1);
        assertThat(columns("lease_settlement_deductions", "account_id")).isEqualTo(1);
    }

    @Test
    void recognitionEntryDaysMustBePositive() {
        UUID t = tenant();
        UUID u = unit(t, property(t));
        UUID r = renter(t);
        UUID l = lease(t, u, r);
        UUID ct = chargeType(t);
        UUID line = leaseLine(t, l, ct);
        UUID seg = rentSegment(t, l, line, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO recognition_entries (id, tenant_id, lease_id, segment_id, period_start, period_end, days, amount)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), t, l, seg, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), 0, new BigDecimal("0.00")))
                .hasMessageContaining("ck_recognition_days_positive");
    }

    @Test
    void rentSegmentDaysMustMatchTheDateRange() {
        UUID t = tenant();
        UUID u = unit(t, property(t));
        UUID r = renter(t);
        UUID l = lease(t, u, r);
        UUID ct = chargeType(t);
        UUID line = leaseLine(t, l, ct);

        // Jan 1 -> Jan 31 is 31 days inclusive; claiming 30 must be rejected.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO rent_segments (id, tenant_id, lease_id, lease_line_id, from_date, to_date, amount, days, day_rate)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), t, l, line, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), new BigDecimal("1200.00"), 30, new BigDecimal("38.709677")))
                .hasMessageContaining("ck_rs_dates");
    }

    @Test
    void validRentSegmentIsAccepted() {
        UUID t = tenant();
        UUID u = unit(t, property(t));
        UUID r = renter(t);
        UUID l = lease(t, u, r);
        UUID ct = chargeType(t);
        UUID line = leaseLine(t, l, ct);

        assertThatCode(() -> rentSegment(t, l, line, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .doesNotThrowAnyException();
    }

    @Test
    void uniqueConstraintCoversBothSegmentAndPeriodStart() {
        UUID t = tenant();
        UUID u = unit(t, property(t));
        UUID r = renter(t);
        UUID l = lease(t, u, r);
        UUID ct = chargeType(t);
        UUID line = leaseLine(t, l, ct);
        UUID seg1 = rentSegment(t, l, line, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        UUID seg2 = rentSegment(t, l, line, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31));

        jdbc.update("INSERT INTO recognition_entries (id, tenant_id, lease_id, segment_id, period_start, period_end, days, amount)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), t, l, seg1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 31, new BigDecimal("100.00"));

        // Same segment, different period_start: must be allowed — proves the
        // constraint is not scoped to segment_id alone.
        assertThatCode(() -> jdbc.update(
                "INSERT INTO recognition_entries (id, tenant_id, lease_id, segment_id, period_start, period_end, days, amount)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), t, l, seg1, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), 28, new BigDecimal("100.00")))
                .doesNotThrowAnyException();

        // Different segment, same period_start as the first row: must be allowed
        // too — proves the constraint is not scoped to period_start alone (which
        // is exactly the bug an unquoted `columnNames: segment_id, period_start`
        // would silently truncate to).
        assertThatCode(() -> jdbc.update(
                "INSERT INTO recognition_entries (id, tenant_id, lease_id, segment_id, period_start, period_end, days, amount)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), t, l, seg2, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 31, new BigDecimal("100.00")))
                .doesNotThrowAnyException();

        // Same segment AND same period_start as the first row: must be rejected.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO recognition_entries (id, tenant_id, lease_id, segment_id, period_start, period_end, days, amount)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), t, l, seg1, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 31, new BigDecimal("100.00")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_re_segment_period");
    }

    /**
     * Every foreign key changeset 85 (and 86's follow-up on
     * {@code rent_segments.lease_line_id}) declares is asserted here by
     * attempting to point it at a UUID that exists nowhere — the base rows
     * otherwise satisfy every NOT NULL column, so a rejection can only come
     * from the FK. {@code rent_segments.lease_line_id} became nullable in
     * changeset 86 (a retired segment outlives its lease line), but a
     * dangling *non-null* id must still be rejected — nullability and
     * referential integrity are different guarantees, and this proves both
     * still hold together.
     */
    /**
     * Changeset 86: {@code rent_segments.lease_line_id} is required but no longer a
     * foreign key.
     *
     * <p>Amending a posted lease deletes its lines, and the segments cut from them
     * have to survive — a POSTED recognition entry explains a {@code CIL} that is
     * still in the ledger. Keeping the column NOT NULL means a retired segment
     * still records <em>which</em> line it came from, and means no segment, live or
     * retired, can ever be line-less; dropping the FK is what lets the line go.</p>
     */
    @Test
    void retiredSegmentsKeepARequiredButUnconstrainedLineId() {
        assertThat(jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns"
                        + " WHERE table_name = 'rent_segments' AND column_name = 'lease_line_id'",
                String.class)).isEqualTo("NO");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints tc"
                        + " JOIN information_schema.key_column_usage k ON k.constraint_name = tc.constraint_name"
                        + " WHERE tc.table_name = 'rent_segments' AND tc.constraint_type = 'FOREIGN KEY'"
                        + " AND k.column_name = 'lease_line_id'",
                Long.class)).isZero();

        UUID t = tenant();
        UUID l = lease(t, unit(t, property(t)), renter(t));
        // The line this segment was cut from is gone, exactly as an amendment leaves it.
        assertThatCode(() -> rentSegment(t, l, UUID.randomUUID(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31)))
                .doesNotThrowAnyException();
        // ...but "no line at all" is still refused.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO rent_segments (id, tenant_id, lease_id, lease_line_id, from_date, to_date, amount, days, day_rate)"
                        + " VALUES (?,?,?,NULL,?,?,?,?,?)",
                UUID.randomUUID(), t, l, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                new BigDecimal("1200.00"), 31, new BigDecimal("38.709677")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void foreignKeysRejectDanglingIds() {
        UUID t = tenant();
        UUID u = unit(t, property(t));
        UUID r = renter(t);
        UUID l = lease(t, u, r);
        UUID ct = chargeType(t);
        UUID line = leaseLine(t, l, ct);
        UUID seg = rentSegment(t, l, line, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
        UUID settlement = settlement(t, l);
        UUID bogus = UUID.randomUUID();

        // rent_segments.lease_id
        assertRejectsDanglingForeignKey(() -> jdbc.update(
                "INSERT INTO rent_segments (id, tenant_id, lease_id, lease_line_id, from_date, to_date, amount, days, day_rate)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), t, bogus, line, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                new BigDecimal("1200.00"), 31, new BigDecimal("38.709677")));

        // rent_segments.lease_line_id is deliberately NOT in this list: changeset 86
        // drops fk_rs_line, so a dangling id is accepted there by design. See
        // retiredSegmentsKeepARequiredButUnconstrainedLineId for what replaces it.

        // recognition_entries.lease_id — denormalised off the segment so the lease's
        // schedule is one query, which makes it a second place the lease can be wrong.
        assertRejectsDanglingForeignKey(() -> jdbc.update(
                "INSERT INTO recognition_entries (id, tenant_id, lease_id, segment_id, period_start, period_end, days, amount)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), t, bogus, seg, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), 28, new BigDecimal("100.00")));

        // recognition_entries.segment_id
        assertRejectsDanglingForeignKey(() -> jdbc.update(
                "INSERT INTO recognition_entries (id, tenant_id, lease_id, segment_id, period_start, period_end, days, amount)"
                        + " VALUES (?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), t, l, bogus, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 31, new BigDecimal("100.00")));

        // recognition_entries.journal_id
        assertRejectsDanglingForeignKey(() -> jdbc.update(
                "INSERT INTO recognition_entries (id, tenant_id, lease_id, segment_id, period_start, period_end, days, amount, journal_id)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), t, l, seg, LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), 30, new BigDecimal("100.00"), bogus));

        // leases.termination_journal_id
        assertRejectsDanglingForeignKey(() -> jdbc.update(
                "UPDATE leases SET termination_journal_id = ? WHERE id = ?", bogus, l));

        // lease_settlements.journal_id
        assertRejectsDanglingForeignKey(() -> jdbc.update(
                "UPDATE lease_settlements SET journal_id = ? WHERE id = ?", bogus, settlement));

        // lease_settlements.refund_bank_account_id
        assertRejectsDanglingForeignKey(() -> jdbc.update(
                "UPDATE lease_settlements SET refund_bank_account_id = ? WHERE id = ?", bogus, settlement));

        // lease_settlements.collection_cheque_id
        assertRejectsDanglingForeignKey(() -> jdbc.update(
                "UPDATE lease_settlements SET collection_cheque_id = ? WHERE id = ?", bogus, settlement));

        // lease_settlement_deductions.account_id
        assertRejectsDanglingForeignKey(() -> jdbc.update(
                "INSERT INTO lease_settlement_deductions (id, tenant_id, settlement_id, category, amount, account_id)"
                        + " VALUES (?,?,?,?,?,?)",
                UUID.randomUUID(), t, settlement, "OTHER", new BigDecimal("10.00"), bogus));
    }
}

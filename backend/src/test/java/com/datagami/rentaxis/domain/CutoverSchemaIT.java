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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Changeset 88 — the cut-over schema (spec §10.3) plus the three columns/indexes
 * the controller added to it: {@code leases.external_contract_ref},
 * {@code cheques.imported_status}, and one live journal per voucher.
 *
 * <p>Every composite key here is asserted on <em>both</em> of its columns. The
 * repository has been bitten by an unquoted {@code columnNames: a, b} inside a
 * YAML flow mapping, which truncates to the first column and leaves a constraint
 * that looks right in the changelog and enforces half the rule in the database —
 * see {@code VoucherSchemaIT#lineNumbersAreUniqueWithinAVoucherButNotAcrossVouchers}
 * for the same guard on 87.</p>
 */
@SpringBootTest
@Testcontainers
class CutoverSchemaIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;

    /**
     * {@code landlord_org.slug} is NOT NULL (changeset 37a, added long after this
     * brief was written) — the brief's two-column insert fails with "null value in
     * column slug". Same adaptation {@code LedgerSchemaIT} and {@code VoucherSchemaIT}
     * already carry.
     */
    private UUID tenant() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO landlord_org (id, name, slug) VALUES (?, ?, ?)", id, "T-" + id, "t-" + id);
        return id;
    }

    private UUID batch(UUID tenant, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO import_batches (id, tenant_id, kind, status, label, created_at) "
                + "VALUES (?,?,'CONTRACT_IMPORT',?,'Al Ashram cut-over',now())", id, tenant, status);
        return id;
    }

    private UUID property(UUID tenant) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO properties (id, tenant_id, name_en, emirate, code) VALUES (?,?,?,?,?)",
                id, tenant, "P-" + id, "DUBAI", "C-" + id.toString().substring(0, 8));
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

    private UUID lease(UUID tenant, String externalContractRef) {
        UUID id = UUID.randomUUID();
        UUID unit = unit(tenant, property(tenant));
        jdbc.update("INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date, status,"
                        + " rent_amount, deposit_amount, external_contract_ref) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, tenant, unit, renter(tenant), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                "DRAFT", new BigDecimal("61000.00"), new BigDecimal("0.00"), externalContractRef);
        return id;
    }

    /** A bare journal header — enough columns to satisfy NOT NULL, nothing more. */
    private void journalEntry(UUID tenant, String number, String sourceType, UUID sourceId) {
        jdbc.update("INSERT INTO journal_entries (id, tenant_id, entry_number, doc_type, entry_date, source_type, source_id)"
                        + " VALUES (?,?,?,?,?,?,?)",
                UUID.randomUUID(), tenant, number, number.substring(0, number.indexOf('-')),
                LocalDate.of(2026, 1, 31), sourceType, sourceId);
    }

    // ---- import batches -----------------------------------------------------

    @Test
    void aBatchCarriesItsLeasesAndDroppingItDropsTheLinks() {
        UUID t = tenant();
        UUID b = batch(t, "DRAFT");
        UUID leaseId = UUID.randomUUID();
        jdbc.update("INSERT INTO import_batch_leases (batch_id, lease_id) VALUES (?,?)", b, leaseId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM import_batch_leases WHERE batch_id = ?", Integer.class, b))
                .isEqualTo(1);
        jdbc.update("DELETE FROM import_batches WHERE id = ?", b);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM import_batch_leases WHERE batch_id = ?", Integer.class, b))
                .isZero();
    }

    /**
     * Both halves of the primary key. A truncated {@code columnNames} would leave
     * "one row per batch" or "a lease may only ever be imported once", either of
     * which passes the duplicate assertion on its own.
     */
    @Test
    void theSameLeaseCannotBeLinkedToTheSameBatchTwice() {
        UUID t = tenant();
        UUID b = batch(t, "DRAFT");
        UUID otherBatch = batch(t, "DRAFT");
        UUID leaseId = UUID.randomUUID();
        jdbc.update("INSERT INTO import_batch_leases (batch_id, lease_id) VALUES (?,?)", b, leaseId);

        // The same lease under a DIFFERENT batch — proves batch_id is part of the key.
        assertThatCode(() -> jdbc.update("INSERT INTO import_batch_leases (batch_id, lease_id) VALUES (?,?)",
                otherBatch, leaseId)).doesNotThrowAnyException();
        // A SECOND lease under the same batch — proves the key did not collapse to batch_id.
        assertThatCode(() -> jdbc.update("INSERT INTO import_batch_leases (batch_id, lease_id) VALUES (?,?)",
                b, UUID.randomUUID())).doesNotThrowAnyException();

        assertThatThrownBy(() -> jdbc.update("INSERT INTO import_batch_leases (batch_id, lease_id) VALUES (?,?)", b, leaseId))
                .hasMessageContaining("import_batch_leases_pkey");
    }

    @Test
    void aBatchStatusIsAClosedSet() {
        UUID t = tenant();
        assertThatCode(() -> batch(t, "POSTED")).doesNotThrowAnyException();
        assertThatCode(() -> batch(t, "REVERSED")).doesNotThrowAnyException();
        assertThatThrownBy(() -> batch(t, "PARTIAL"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_import_batches_status");
    }

    @Test
    void importJobsCarryTheBatchId() {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'import_jobs' AND column_name = 'import_batch_id'",
                Integer.class);
        assertThat(n).isEqualTo(1);
    }

    // ---- opening balances ---------------------------------------------------

    @Test
    void aSnapshotRowIsUniquePerTenantAndAccountCode() {
        UUID t = tenant();
        UUID otherTenant = tenant();
        snapshot(t, "166269", "15000.00");

        // The same code for a DIFFERENT tenant — proves tenant_id is part of the key.
        assertThatCode(() -> snapshot(otherTenant, "166269", "900.00")).doesNotThrowAnyException();
        // A DIFFERENT code for the same tenant — proves the key did not collapse to tenant_id.
        assertThatCode(() -> snapshot(t, "166270", "900.00")).doesNotThrowAnyException();

        assertThatThrownBy(() -> snapshot(t, "166269", "1.00"))
                .hasMessageContaining("ux_opening_balance_snapshots_tenant_code");
    }

    private void snapshot(UUID tenant, String code, String debit) {
        jdbc.update("INSERT INTO opening_balance_snapshots (id, tenant_id, account_code, account_name, debit, credit, uploaded_at) "
                + "VALUES (?,?,?,'Rent Receivable - Tulip 7',?,0,now())", UUID.randomUUID(), tenant, code, new BigDecimal(debit));
    }

    /** A trial-balance row is a debit or a credit, never both — PACT exports one side per code. */
    @Test
    void aSnapshotRowCarriesOnlyOneSide() {
        UUID t = tenant();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO opening_balance_snapshots (id, tenant_id, account_code, account_name, debit, credit, uploaded_at) "
                        + "VALUES (?,?,'166999','both sides',10.00,5.00,now())", UUID.randomUUID(), t))
                .hasMessageContaining("ck_ob_snapshots_one_side");
    }

    /** A tenant opens its books once. A second OB row would mean two opening journals. */
    @Test
    void thereIsAtMostOneOpeningBalancePostingPerTenant() {
        UUID t = tenant();
        UUID otherTenant = tenant();
        jdbc.update("INSERT INTO opening_balance_postings (id, tenant_id, as_of, created_at) VALUES (?,?,CURRENT_DATE,now())",
                UUID.randomUUID(), t);
        // Another tenant opening its own books is untouched by the rule.
        assertThatCode(() -> jdbc.update(
                "INSERT INTO opening_balance_postings (id, tenant_id, as_of, created_at) VALUES (?,?,CURRENT_DATE,now())",
                UUID.randomUUID(), otherTenant)).doesNotThrowAnyException();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO opening_balance_postings (id, tenant_id, as_of, created_at) VALUES (?,?,CURRENT_DATE,now())",
                UUID.randomUUID(), t))
                .hasMessageContaining("ux_opening_balance_postings_tenant");
    }

    // ---- controller rulings -------------------------------------------------

    /**
     * PACT's contract number is alphanumeric ("TLP7/681"); {@code leases.contract_number}
     * is our own {@code bigint} sequence and cannot hold it, so the imported reference
     * gets a column of its own.
     */
    @Test
    void aLeaseCarriesPactsAlphanumericContractReference() {
        UUID t = tenant();
        UUID leaseId = lease(t, "TLP7/681");
        assertThat(jdbc.queryForObject("SELECT external_contract_ref FROM leases WHERE id = ?", String.class, leaseId))
                .isEqualTo("TLP7/681");
        // Two properties in one cut-over may legitimately reuse a reference the other
        // system scoped per building, and a re-import is looked up by it — so it is
        // indexed, not unique.
        assertThatCode(() -> lease(t, "TLP7/681")).doesNotThrowAnyException();
    }

    /** The lookup index covers BOTH columns, or a re-import scans every tenant's leases. */
    @Test
    void theExternalContractReferenceIsIndexedPerTenant() {
        String def = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_leases_tenant_external_ref'",
                String.class);
        assertThat(def).contains("tenant_id").contains("external_contract_ref");
    }

    /**
     * What an imported DRAFT cheque must become when the batch is bulk-posted. The
     * four values are the only ones the replay knows how to reach; a DRAFT or a
     * REPLACED parked here would be a row bulk post silently skipped.
     */
    @Test
    void anImportedChequeStatusIsOneOfFourValues() {
        UUID t = tenant();
        UUID leaseId = lease(t, "TLP7/682");
        for (String ok : new String[] { "REGISTERED", "DEPOSITED", "CLEARED", "BOUNCED" }) {
            assertThatCode(() -> cheque(t, leaseId, ok)).doesNotThrowAnyException();
        }
        // Null is the ordinary, non-imported cheque.
        assertThatCode(() -> cheque(t, leaseId, null)).doesNotThrowAnyException();

        assertThatThrownBy(() -> cheque(t, leaseId, "REPLACED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_cheques_imported_status");
    }

    private void cheque(UUID tenant, UUID leaseId, String importedStatus) {
        UUID propertyId = jdbc.queryForObject("SELECT u.property_id FROM leases l JOIN units u ON u.id = l.unit_id WHERE l.id = ?",
                UUID.class, leaseId);
        UUID renterId = jdbc.queryForObject("SELECT renter_id FROM leases WHERE id = ?", UUID.class, leaseId);
        jdbc.update("INSERT INTO cheques (id, tenant_id, lease_id, property_id, renter_id, seq_no, posting_date,"
                        + " cheque_date, amount, mode, status, imported_status, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?, 'PDC', 'DRAFT', ?, now())",
                UUID.randomUUID(), tenant, leaseId, propertyId, renterId, 1,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), new BigDecimal("5000.00"), importedStatus);
    }

    /**
     * One live journal per voucher, in the one place that can actually enforce it.
     *
     * <p>{@code VoucherService.post} refuses anything but a DRAFT and {@code amend}
     * posts a <em>new</em> voucher row, so no legitimate flow writes two
     * VOUCHER-sourced journals under one source id — the index closes the gap a row
     * lock cannot (a row edited by SQL, a restored backup, a future writer that skips
     * the lock). A reversal carries {@code source_type = 'REVERSAL'} and the original
     * <em>journal's</em> id, so it sits outside the predicate; the fourth case here is
     * what would break if that ever changed.</p>
     */
    @Test
    void atMostOneVoucherJournalPerVoucher() {
        UUID t = tenant();
        UUID voucher = UUID.randomUUID();

        assertThatCode(() -> journalEntry(t, "PISR-26/1", "VOUCHER", voucher)).doesNotThrowAnyException();
        assertThatThrownBy(() -> journalEntry(t, "PISR-26/2", "VOUCHER", voucher))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_je_voucher_source");

        // A different voucher is a different journal.
        assertThatCode(() -> journalEntry(t, "PISR-26/3", "VOUCHER", UUID.randomUUID())).doesNotThrowAnyException();
        // The amendment's reversal names the same id under a different source type.
        assertThatCode(() -> journalEntry(t, "PISR-26/4", "REVERSAL", voucher)).doesNotThrowAnyException();
        // ...and the index does not stray onto other document families.
        UUID cheque = UUID.randomUUID();
        assertThatCode(() -> journalEntry(t, "PDR-26/1", "CHEQUE", cheque)).doesNotThrowAnyException();
        assertThatCode(() -> journalEntry(t, "CRT-26/1", "CHEQUE", cheque)).doesNotThrowAnyException();
    }
}

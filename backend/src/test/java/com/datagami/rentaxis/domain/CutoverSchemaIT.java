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

    static final LocalDate DEP = LocalDate.of(2026, 9, 24);
    static final LocalDate CLR = LocalDate.of(2026, 9, 25);
    static final LocalDate BNC = LocalDate.of(2026, 9, 26);

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
                + "VALUES (?,?,'CONTRACT_IMPORT',?,'September cut-over',now())", id, tenant, status);
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
                + "VALUES (?,?,?,'Rent Receivable - ST1',?,0,now())", UUID.randomUUID(), tenant, code, new BigDecimal(debit));
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
     * PACT's contract number is alphanumeric ("SAMPLE-0001"); {@code leases.contract_number}
     * is our own {@code bigint} sequence and cannot hold it, so the imported reference
     * gets a column of its own.
     */
    @Test
    void aLeaseCarriesPactsAlphanumericContractReference() {
        UUID t = tenant();
        UUID leaseId = lease(t, "SAMPLE-0001");
        assertThat(jdbc.queryForObject("SELECT external_contract_ref FROM leases WHERE id = ?", String.class, leaseId))
                .isEqualTo("SAMPLE-0001");
        // One reference, one lease: see aContractReferenceIsUniqueWithinAnOrganisation
        // for the rule and ux_leases_tenant_external_ref for why it is partial.
        assertThatThrownBy(() -> lease(t, "SAMPLE-0001"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** The index covers BOTH columns, is UNIQUE, and is partial over the non-null rows. */
    @Test
    void theExternalContractReferenceIsUniquePerTenant() {
        String def = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'ux_leases_tenant_external_ref'",
                String.class);
        assertThat(def).contains("tenant_id").contains("external_contract_ref")
                .contains("UNIQUE").contains("WHERE");
    }

    /**
     * What an imported DRAFT cheque must become when the batch is bulk-posted. The
     * four values are the only ones the replay knows how to reach; a DRAFT or a
     * REPLACED parked here would be a row bulk post silently skipped.
     */
    @Test
    void anImportedChequeStatusIsOneOfFourValues() {
        UUID t = tenant();
        UUID leaseId = lease(t, "SAMPLE-0002");
        // Each with the dates ck_cheques_imported_dates requires of it; the pairing
        // itself is theImportedDatesMatchTheImportedStatus's subject.
        assertThatCode(() -> dated(t, leaseId, "PDC", "REGISTERED", null, null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> dated(t, leaseId, "PDC", "DEPOSITED", DEP, null, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> dated(t, leaseId, "PDC", "CLEARED", DEP, CLR, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> dated(t, leaseId, "PDC", "BOUNCED", DEP, null, BNC))
                .doesNotThrowAnyException();
        // Null is the ordinary, non-imported cheque.
        assertThatCode(() -> cheque(t, leaseId, null)).doesNotThrowAnyException();

        // Refused, and the message names one of the two constraints that now both
        // enumerate the vocabulary: ck_cheques_imported_dates lists a branch per
        // status, so a status outside the four fails it as well. Which one Postgres
        // reports first is not a rule, so the assertion is on the shared prefix.
        assertThatThrownBy(() -> cheque(t, leaseId, "REPLACED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_cheques_imported_");
    }

    /**
     * One contract reference, one lease, per organisation — the database backstop
     * under the validator's two checks (review C1).
     */
    @Test
    void aContractReferenceIsUniqueWithinAnOrganisation() {
        UUID a = tenant();
        UUID b = tenant();

        lease(a, "SAMPLE-0001");
        // Another organisation numbering its contracts the same way is normal.
        assertThatCode(() -> lease(b, "SAMPLE-0001")).doesNotThrowAnyException();
        // A different reference in the same organisation is normal.
        assertThatCode(() -> lease(a, "SAMPLE-0002")).doesNotThrowAnyException();
        // Every lease that was not imported carries NULL, and they must all coexist —
        // which is why the index is partial.
        assertThatCode(() -> lease(a, null)).doesNotThrowAnyException();
        assertThatCode(() -> lease(a, null)).doesNotThrowAnyException();

        assertThatThrownBy(() -> lease(a, "SAMPLE-0001"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_leases_tenant_external_ref");
    }

    /** A batch records the properties, units and renters it created, for Task 11's discard. */
    @Test
    void aBatchRecordsTheEntitiesItCreated() {
        UUID t = tenant();
        UUID batchId = batch(t, "DRAFT");
        UUID propertyId = UUID.randomUUID();

        jdbc.update("INSERT INTO import_batch_entities (batch_id, entity_type, entity_id) VALUES (?,?,?)",
                batchId, "PROPERTY", propertyId);
        // The same id under another type, and another id under the same type, are
        // both distinct rows — the key is all three columns, not the first one.
        assertThatCode(() -> jdbc.update(
                "INSERT INTO import_batch_entities (batch_id, entity_type, entity_id) VALUES (?,?,?)",
                batchId, "UNIT", propertyId)).doesNotThrowAnyException();
        assertThatCode(() -> jdbc.update(
                "INSERT INTO import_batch_entities (batch_id, entity_type, entity_id) VALUES (?,?,?)",
                batchId, "PROPERTY", UUID.randomUUID())).doesNotThrowAnyException();

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO import_batch_entities (batch_id, entity_type, entity_id) VALUES (?,?,?)",
                batchId, "PROPERTY", propertyId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("import_batch_entities_pkey");

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO import_batch_entities (batch_id, entity_type, entity_id) VALUES (?,?,?)",
                batchId, "JOURNAL", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_import_batch_entities_type");

        // The batch owns its links outright.
        jdbc.update("DELETE FROM import_batches WHERE id = ?", batchId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM import_batch_entities WHERE batch_id = ?", Integer.class, batchId))
                .isZero();
    }

    /**
     * Which status requires which date (review I3). Task 11's replay files a DATED
     * journal per transition, so a CLEARED row with no cleared date would post a CRT
     * on the one day the cheque certainly did not clear.
     */
    @Test
    void theImportedDatesMatchTheImportedStatus() {
        UUID t = tenant();
        UUID leaseId = lease(t, "SAMPLE-0004");
        LocalDate dep = LocalDate.of(2026, 9, 24);
        LocalDate clr = LocalDate.of(2026, 9, 25);
        LocalDate bnc = LocalDate.of(2026, 9, 26);

        // A row that is not an imported row has nowhere to hide a replay instruction.
        // NULL is not false in SQL, so this is the case a careless constraint lets
        // through: see the coalesce() note on ck_cheques_imported_dates.
        assertThatCode(() -> dated(t, leaseId, "PDC", null, null, null, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> dated(t, leaseId, "PDC", null, dep, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_cheques_imported_dates");

        // REGISTERED carries no dates at all.
        assertThatCode(() -> dated(t, leaseId, "PDC", "REGISTERED", null, null, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> dated(t, leaseId, "PDC", "REGISTERED", dep, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_cheques_imported_dates");

        // DEPOSITED needs its deposit date and nothing else.
        assertThatCode(() -> dated(t, leaseId, "PDC", "DEPOSITED", dep, null, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> dated(t, leaseId, "PDC", "DEPOSITED", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Only a cheque is banked; a cash receipt cannot be DEPOSITED at all.
        assertThatThrownBy(() -> dated(t, leaseId, "CASH", "DEPOSITED", dep, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        // CLEARED: a cheque was banked first, a cash receipt never was.
        assertThatCode(() -> dated(t, leaseId, "PDC", "CLEARED", dep, clr, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> dated(t, leaseId, "CASH", "CLEARED", null, clr, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> dated(t, leaseId, "PDC", "CLEARED", dep, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> dated(t, leaseId, "PDC", "CLEARED", null, clr, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> dated(t, leaseId, "CASH", "CLEARED", dep, clr, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // and it cannot have cleared before it was banked.
        assertThatThrownBy(() -> dated(t, leaseId, "PDC", "CLEARED", clr, dep, null))
                .isInstanceOf(DataIntegrityViolationException.class);

        // BOUNCED: deposited and bounced, with the clear date optional because a
        // cheque that cleared and was later returned credits the bank, not the PDC.
        assertThatCode(() -> dated(t, leaseId, "PDC", "BOUNCED", dep, null, bnc))
                .doesNotThrowAnyException();
        assertThatCode(() -> dated(t, leaseId, "PDC", "BOUNCED", dep, clr, bnc))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> dated(t, leaseId, "PDC", "BOUNCED", dep, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> dated(t, leaseId, "PDC", "BOUNCED", null, null, bnc))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> dated(t, leaseId, "PDC", "BOUNCED", dep, bnc, clr))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void dated(UUID tenant, UUID leaseId, String mode, String importedStatus,
                       LocalDate deposited, LocalDate cleared, LocalDate bounced) {
        UUID propertyId = jdbc.queryForObject("SELECT u.property_id FROM leases l JOIN units u ON u.id = l.unit_id WHERE l.id = ?",
                UUID.class, leaseId);
        UUID renterId = jdbc.queryForObject("SELECT renter_id FROM leases WHERE id = ?", UUID.class, leaseId);
        jdbc.update("INSERT INTO cheques (id, tenant_id, lease_id, property_id, renter_id, seq_no, posting_date,"
                        + " cheque_date, amount, mode, status, imported_status,"
                        + " imported_deposited_on, imported_cleared_on, imported_bounced_on, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?, ?, 'DRAFT', ?, ?, ?, ?, now())",
                UUID.randomUUID(), tenant, leaseId, propertyId, renterId, 1,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), new BigDecimal("5000.00"),
                mode, importedStatus, deposited, cleared, bounced);
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

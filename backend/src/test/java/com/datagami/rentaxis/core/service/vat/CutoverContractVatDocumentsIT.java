package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.dto.lease.AddChargeRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.api.dto.lease.ReduceLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.vat.TaxInvoiceDTO;
import com.datagami.rentaxis.api.dto.vat.VatReturnDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseReductionService;
import com.datagami.rentaxis.core.service.lease.LeaseTerminationService;
import com.datagami.rentaxis.core.service.lease.LeaseVariationService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.TaxInvoiceKind;
import com.datagami.rentaxis.domain.entity.enums.VatTiming;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S16-04: a cut-over CONTRACT-VAT lease (its contract tax invoice was issued by the
 * previous system, so the product holds no CONTRACT tax point for it) must still
 * document every Output VAT movement it makes afterwards — a tax credit note for the
 * VAT an early termination or a credit addendum hands back, a tax invoice for the VAT
 * an addendum adds — so the VAT return's output check ties. And a return whose output
 * check does not tie is refused unless the filer acknowledges the difference.
 */
@SpringBootTest
class CutoverContractVatDocumentsIT extends AbstractPostgresIT {

    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeasePostingService posting;
    @Autowired LeaseTerminationService termination;
    @Autowired LeaseReductionService reductions;
    @Autowired LeaseVariationService variations;
    @Autowired TaxInvoiceService taxInvoices;
    @Autowired VatReturnService vatReturns;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired TransactionTemplate tx;
    @Autowired VatTaxPointService vatTaxPointService;
    @Autowired com.datagami.rentaxis.core.service.lease.LeaseTransferService transfers;
    @Autowired com.datagami.rentaxis.core.service.ledger.AccountResolver resolver;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate Q1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate Q2 = LocalDate.of(2026, 4, 1);
    private static final LocalDate CONTRACT = LocalDate.of(2026, 1, 10);
    private static final LocalDate START = LocalDate.of(2026, 1, 15);
    private static final LocalDate END = LocalDate.of(2027, 1, 14);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    /** 60,000 + 3,000 VAT on CONTRACT timing, posted the cut-over way (import batch): no tax invoice of ours. */
    private UUID cutoverLease() {
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END, List.of(vatLine("RENT", "60000")));
        jdbc.update("update leases set vat_timing = ?, external_contract_ref = 'PACT-0077' where id = ?",
                VatTiming.CONTRACT.name(), leaseId);
        chequeGeneration.saveRows(leaseId, List.of(
                row("880041", START, "15750"), row("880042", LocalDate.of(2026, 4, 15), "15750"),
                row("880043", LocalDate.of(2026, 7, 15), "15750"), row("880044", LocalDate.of(2026, 10, 15), "15750")));
        UUID batch = UUID.randomUUID();
        jdbc.update("insert into import_batches (id, tenant_id, status) values (?, ?, 'POSTED')", batch, fixtures.tenantId());
        posting.post(leaseId, batch);
        assertThat(invoices(leaseId)).as("the previous system invoiced the contract").isEmpty();
        return leaseId;
    }

    private static ChequeRowInput row(String number, LocalDate date, String amount) {
        return new ChequeRowInput(null, null, CONTRACT, number, date, "Emirates NBD", null, null,
                new BigDecimal(amount), null, null);
    }

    private List<TaxInvoiceDTO> invoices(UUID leaseId) {
        return tx.execute(s -> taxInvoices.forLease(leaseId));
    }

    private VatReturnDTO.OutputCheck check(LocalDate quarter) {
        return tx.execute(s -> vatReturns.get(quarter)).outputCheck();
    }

    @Test
    void anEarlyTerminationHandsTheVatBackOnACreditNote() {
        UUID leaseId = cutoverLease();
        LocalDate t = LocalDate.of(2026, 5, 31);
        BigDecimal unearnedVat = tx.execute(s -> termination.preview(leaseId, t)).unearnedVat();
        assertThat(unearnedVat).isPositive();

        termination.terminate(leaseId, new TerminateLeaseRequest(t, null, null, null), null);

        assertThat(invoices(leaseId)).singleElement().satisfies(cn -> {
            assertThat(cn.kind()).isEqualTo(TaxInvoiceKind.CREDIT_NOTE);
            assertThat(cn.issueDate()).isEqualTo(t);
            assertThat(cn.vatAmount()).isEqualByComparingTo(unearnedVat);
        });
        assertThat(jdbc.queryForObject("select reference_note from tax_invoices where lease_id = ?", String.class, leaseId))
                .contains("previous system").contains("PACT-0077");
        VatReturnDTO.OutputCheck q2 = check(Q2);
        assertThat(q2.ledger()).isEqualByComparingTo(unearnedVat.negate());
        assertThat(q2.ok()).as("documents " + q2.documents() + " vs ledger " + q2.ledger()).isTrue();
    }

    @Test
    void aCreditAddendumHandsTheVatBackOnACreditNote() {
        UUID leaseId = cutoverLease();
        UUID rentLine = tx.execute(s -> leaseService.getLines(leaseId)).stream()
                .filter(l -> "RENT".equals(l.chargeTypeCode())).map(LeaseLineDTO::id).findFirst().orElseThrow();
        reductions.reduce(leaseId, new ReduceLeaseRequest(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 6, 25),
                "Rent renegotiated", null, List.of(new ReduceLeaseRequest.LineReduction(rentLine, new BigDecimal("48000"))),
                "CREDIT", List.of(), List.of()));

        assertThat(invoices(leaseId)).singleElement().satisfies(cn -> {
            assertThat(cn.kind()).isEqualTo(TaxInvoiceKind.CREDIT_NOTE);
            assertThat(cn.issueDate()).isEqualTo(LocalDate.of(2026, 6, 25));
        });
        VatReturnDTO.OutputCheck q2 = check(Q2);
        assertThat(q2.ledger()).isNegative();
        assertThat(q2.ok()).as("documents " + q2.documents() + " vs ledger " + q2.ledger()).isTrue();
    }

    @Test
    void anAddendumAddingAVatChargeIssuesItsTaxInvoice() {
        UUID leaseId = cutoverLease();
        LocalDate signed = LocalDate.of(2026, 5, 20);
        variations.addCharge(leaseId, new AddChargeRequest(LocalDate.of(2026, 6, 1), signed, null, "Second parking bay",
                List.of(vatLine("PARKING_FEE", "2000")),
                List.of(new ChequeRowInput(null, null, signed, "880050", LocalDate.of(2026, 6, 1), "Emirates NBD",
                        null, null, new BigDecimal("2100"), null, null))));

        assertThat(invoices(leaseId)).singleElement().satisfies(ti -> {
            assertThat(ti.kind()).isEqualTo(TaxInvoiceKind.TAX_INVOICE);
            assertThat(ti.issueDate()).isEqualTo(signed);
            assertThat(ti.vatAmount()).isEqualByComparingTo("100.00");
        });
        VatReturnDTO.OutputCheck q2 = check(Q2);
        assertThat(q2.ledger()).isEqualByComparingTo("100.00");
        assertThat(q2.ok()).isTrue();

        // R1 P3-7: the invoice names the addendum and covers its term.
        String description = jdbc.queryForObject("select description from tax_invoices where lease_id = ?", String.class, leaseId);
        assertThat(description).startsWith("Addendum ADD-").contains("Second parking bay").contains("01/06/2026 – 14/01/2027");
        // R1 P3-6: signed after go-live, the parking fee follows the new-lease rule even on a
        // cut-over lease — parked in Unearned charges and earned over the remaining term,
        // not income on the addendum date.
        UUID tco = jdbc.queryForObject("select tco_journal_id from lease_addenda where lease_id = ?", UUID.class, leaseId);
        UUID unearned = tx.execute(s -> resolver.resolve(com.datagami.rentaxis.domain.entity.enums.AccountRole.UNEARNED_CHARGES,
                fixtures.property().getId())).getId();
        BigDecimal parked = jdbc.queryForObject("select coalesce(sum(credit), 0) from journal_lines where journal_entry_id = ? and account_id = ?",
                BigDecimal.class, tco, unearned);
        assertThat(parked).isEqualByComparingTo("2000.00");
    }

    /**
     * The cut-over TCO's VAT (invoiced by the previous system) is in Q1's ledger with no
     * document of ours. R1 P2-1: the GET says a reason is required; the filing needs a
     * reason of at least 10 characters and the difference the filer saw; both are kept
     * on the return and its PDF / CSV.
     */
    @Test
    void aReturnWhoseOutputCheckFailsIsFiledOnlyWithAnAcknowledgedReason() {
        cutoverLease();
        VatReturnDTO open = tx.execute(s -> vatReturns.get(Q1));
        VatReturnDTO.OutputCheck q1 = open.outputCheck();
        assertThat(q1.ok()).isFalse();
        assertThat(open.canFile()).isTrue();
        assertThat(open.reasonRequired()).isTrue();

        assertThatThrownBy(() -> vatReturns.file(Q1, "FTA-1"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("does not match the Output VAT account")
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("vat.outputCheckFailed");
        assertThatThrownBy(() -> vatReturns.file(Q1, "FTA-1", "  ", q1.difference())).isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> vatReturns.file(Q1, "FTA-1", "legacy", q1.difference()))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("vat.overrideReasonShort");
        String reason = "Cut-over contracts invoiced by the previous system";
        assertThatThrownBy(() -> vatReturns.file(Q1, "FTA-1", reason, null))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("vat.outputCheckChanged");
        assertThatThrownBy(() -> vatReturns.file(Q1, "FTA-1", reason, q1.difference().add(BigDecimal.ONE)))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("vat.outputCheckChanged");
        Long returnsBefore = jdbc.queryForObject("select count(*) from vat_returns where tenant_id = ?", Long.class,
                fixtures.tenantId());
        assertThat(returnsBefore).isZero();

        VatReturnDTO filed = vatReturns.file(Q1, "FTA-1", reason, q1.difference());
        assertThat(filed.status()).isEqualTo("FILED");
        assertThat(filed.outputDifference()).isEqualByComparingTo(q1.difference());
        assertThat(filed.outputOverrideReason()).isEqualTo(reason);
        List<VatReturnDTO.Filing> filings = tx.execute(s -> vatReturns.filings());
        assertThat(filings).singleElement().satisfies(f -> {
            assertThat(f.outputDifference()).isEqualByComparingTo(q1.difference());
            assertThat(f.outputOverrideReason()).isEqualTo(reason);
        });
        String csv = new String(VatReturnExport.csv(filed, "en"), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("acknowledged at filing").contains(reason);
    }

    /** R1 P2-1: a tied return records 0.00, so a null difference means "filed before the check was recorded". */
    @Test
    void aTiedReturnRecordsAZeroDifference() {
        UUID leaseId = cutoverLease();
        termination.terminate(leaseId, new TerminateLeaseRequest(LocalDate.of(2026, 5, 31), null, null, null), null);
        assertThat(check(Q2).ok()).isTrue();
        assertThat(tx.execute(s -> vatReturns.get(Q2)).reasonRequired()).isFalse();
        VatReturnDTO filed = vatReturns.file(Q2, "FTA-2");
        assertThat(filed.outputDifference()).isEqualByComparingTo("0.00");
        assertThat(filed.outputOverrideReason()).isNull();
    }

    /**
     * R1 P3-1: a cut-over lease with an addendum invoice of ours, terminated — the credit
     * note names our addendum invoice and the previous system's contract invoice; a CONTRACT
     * lease of ours (never a cut-over) never names the previous system.
     */
    @Test
    void aCreditNoteNamesOurInvoicesAndThePreviousSystemsOnlyOnACutOver() {
        UUID leaseId = cutoverLease();
        LocalDate signed = LocalDate.of(2026, 5, 20);
        variations.addCharge(leaseId, new AddChargeRequest(LocalDate.of(2026, 6, 1), signed, null, "Second parking bay",
                List.of(vatLine("PARKING_FEE", "2000")),
                List.of(new ChequeRowInput(null, null, signed, "880060", LocalDate.of(2026, 6, 1), "Emirates NBD",
                        null, null, new BigDecimal("2100"), null, null))));
        String addendumTi = invoices(leaseId).get(0).invoiceNumber();
        termination.terminate(leaseId, new TerminateLeaseRequest(LocalDate.of(2026, 7, 31), null, null, null), null);
        String ref = jdbc.queryForObject("select reference_note from tax_invoices where lease_id = ? and kind = 'CREDIT_NOTE'",
                String.class, leaseId);
        assertThat(ref).contains(addendumTi).contains("previous system").contains("PACT-0077");

        // Ours: posted the ordinary way on CONTRACT timing (its contract invoice issued at posting).
        com.datagami.rentaxis.domain.entity.Unit other = tx.execute(s -> fixtures.createUnit(fixtures.property(), "OW-1"));
        UUID own = fixtures.draftLease(other, fixtures.createRenter("Own Tenant"), CONTRACT, START, END, List.of(vatLine("RENT", "60000")));
        jdbc.update("update leases set vat_timing = 'CONTRACT' where id = ?", own);
        chequeGeneration.saveRows(own, List.of(row("880071", START, "63000")));
        posting.post(own);
        termination.terminate(own, new TerminateLeaseRequest(LocalDate.of(2026, 7, 31), null, null, null), null);
        String ownRef = jdbc.queryForObject("select reference_note from tax_invoices where lease_id = ? and kind = 'CREDIT_NOTE'",
                String.class, own);
        assertThat(ownRef).startsWith("TI-").doesNotContain("previous system");
    }

    /**
     * R1 P3-3: with no TRN, every door that would hand contract VAT back on a credit note
     * refuses before writing anything — the termination and its preview, a credit addendum
     * and its preview, and a transfer's dry run — with the same words.
     */
    @Test
    void withoutATrnEveryCreditNoteDoorRefusesUpFront() {
        jdbc.update("update landlord_org set trn = null where id = ?", fixtures.tenantId());
        UUID leaseId = cutoverLease();
        LocalDate t = LocalDate.of(2026, 5, 31);
        assertThat(tx.execute(s -> termination.preview(leaseId, t)).problems())
                .singleElement().asString().contains("has no TRN");
        assertThatThrownBy(() -> termination.terminate(leaseId, new TerminateLeaseRequest(t, null, null, null), null))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("has no TRN");
        assertThat(jdbc.queryForObject("select count(*) from journal_entries where lease_id = ? and doc_type = 'TCR'",
                Long.class, leaseId)).isZero();

        UUID rentLine = tx.execute(s -> leaseService.getLines(leaseId)).stream()
                .filter(l -> "RENT".equals(l.chargeTypeCode())).map(LeaseLineDTO::id).findFirst().orElseThrow();
        ReduceLeaseRequest cut = new ReduceLeaseRequest(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 6, 25),
                "Rent renegotiated", null, List.of(new ReduceLeaseRequest.LineReduction(rentLine, new BigDecimal("48000"))),
                "CREDIT", List.of(), List.of());
        assertThat(tx.execute(s -> reductions.preview(leaseId, cut)).problems())
                .anySatisfy(p -> assertThat(p.message()).contains("has no TRN"));
        assertThatThrownBy(() -> reductions.reduce(leaseId, cut))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("has no TRN");

        com.datagami.rentaxis.domain.entity.Unit target = tx.execute(s -> fixtures.createUnit(fixtures.property(), "TR-1"));
        UUID b = transfers.draft(leaseId, new com.datagami.rentaxis.api.dto.lease.TransferLeaseRequest(t, target.getId(),
                null, null, List.of(vatLine("RENT", "30000")), null), posting).getId();
        assertThat(posting.dryRun(b).errors()).anySatisfy(e -> assertThat(e).contains("Ending or reducing this contract"));
    }

    /**
     * R1 P3-2: a pre-rule CONTRACT lease of ours (no contract invoice) takes a VAT
     * addendum, whose own tax invoice documents its VAT; the later backfill invoices the
     * contract's VAT only — never the addendum's a second time.
     */
    @Test
    void theContractBackfillDoesNotInvoiceAnAddendumAgain() {
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END, List.of(vatLine("RENT", "60000")));
        chequeGeneration.saveRows(leaseId, List.of(row("880081", START, "63000")));
        posting.post(leaseId);
        // Pre-rule shape: changeset 108 marked leases posted before VAT per instalment
        // CONTRACT; they carry no contract invoice of ours.
        jdbc.update("update leases set vat_timing = 'CONTRACT' where id = ?", leaseId);
        assertThat(invoices(leaseId)).isEmpty();
        LocalDate signed = LocalDate.of(2026, 5, 20);
        variations.addCharge(leaseId, new AddChargeRequest(LocalDate.of(2026, 6, 1), signed, null, "Second parking bay",
                List.of(vatLine("PARKING_FEE", "2000")),
                List.of(new ChequeRowInput(null, null, signed, "880082", LocalDate.of(2026, 6, 1), "Emirates NBD",
                        null, null, new BigDecimal("2100"), null, null))));
        vatTaxPointService.issueContractInvoice(leaseId);
        assertThat(invoices(leaseId)).extracting(TaxInvoiceDTO::vatAmount)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("100.00"), new BigDecimal("3000.00"));
    }

    private static com.datagami.rentaxis.api.dto.lease.LeaseLineInput resend(LeaseLineDTO l, String gross) {
        return new com.datagami.rentaxis.api.dto.lease.LeaseLineInput(l.chargeTypeId(), null,
                gross == null ? l.grossAmount() : new BigDecimal(gross), l.discountAmount(), l.narration(),
                l.vatApplicable(), l.creditAccountId(), l.periodStart(), l.periodEnd(), l.addendumId());
    }

    /**
     * #369 R1-P3-1: an amendment deletes and re-inserts every line. A post-go-live
     * addendum's parking fee on a cut-over lease was stamped with the new-lease rule;
     * re-inserted, it used to take the lease's legacy rule and the repost credited the
     * whole fee to income. It keeps its stamp and stays in Unearned charges.
     */
    @Test
    void anAmendmentKeepsANewRuleAddendumFeeOnTheNewRule() {
        UUID leaseId = cutoverLease();
        LocalDate signed = LocalDate.of(2026, 5, 20);
        variations.addCharge(leaseId, new AddChargeRequest(LocalDate.of(2026, 6, 1), signed, null, "Parking bay",
                List.of(com.datagami.rentaxis.testsupport.LeaseTestFixtures.line("PARKING_FEE", "2000")),
                List.of(new ChequeRowInput(null, null, signed, "880090", LocalDate.of(2026, 6, 1), "Emirates NBD",
                        null, null, new BigDecimal("2000"), null, null))));
        UUID unearned = tx.execute(s -> resolver.resolve(com.datagami.rentaxis.domain.entity.enums.AccountRole.UNEARNED_CHARGES,
                fixtures.property().getId())).getId();
        String parked = "select coalesce(sum(credit - debit), 0) from journal_lines where lease_id = ? and account_id = ?";
        assertThat(jdbc.queryForObject(parked, BigDecimal.class, leaseId, unearned)).isEqualByComparingTo("2000.00");

        List<com.datagami.rentaxis.api.dto.lease.LeaseLineInput> same = tx.execute(s -> leaseService.getLines(leaseId))
                .stream().map(l -> resend(l, null)).toList();
        LeaseTestFixtures.authenticateAsTenantAdmin();
        posting.amendLines(leaseId, same, "Narration correction");

        assertThat(jdbc.queryForObject("select posted_recognition from lease_lines where lease_id = ? and addendum_id is not null",
                String.class, leaseId)).isEqualTo("RENT_LIKE");
        assertThat(jdbc.queryForObject(parked, BigDecimal.class, leaseId, unearned)).isEqualByComparingTo("2000.00");
    }

    /**
     * #369 R1-P3-3: amending a cut-over CONTRACT-VAT lease so its VAT changes moves Output
     * VAT by the difference; with no contract invoice of ours to adjust, the difference is
     * its own credit note (VAT down), and the lease stays a cut-over lease for later
     * references.
     */
    @Test
    void anAmendmentThatLowersACutOverLeasesVatIssuesACreditNote() {
        UUID leaseId = cutoverLease();
        // The rent is re-split: 12,000 of it becomes a service charge without VAT, so the
        // contract (and the register's 63,000) is unchanged and only the VAT moves.
        List<com.datagami.rentaxis.api.dto.lease.LeaseLineInput> lower = new java.util.ArrayList<>(
                tx.execute(s -> leaseService.getLines(leaseId)).stream()
                        .map(l -> resend(l, "RENT".equals(l.chargeTypeCode()) ? "48000" : null)).toList());
        lower.add(com.datagami.rentaxis.testsupport.LeaseTestFixtures.line("ADMIN_FEE", "12600"));
        LeaseTestFixtures.authenticateAsTenantAdmin();
        posting.amendLines(leaseId, lower, "Rent re-split");

        assertThat(invoices(leaseId)).singleElement().satisfies(cn -> {
            assertThat(cn.kind()).isEqualTo(TaxInvoiceKind.CREDIT_NOTE);
            assertThat(cn.vatAmount()).isEqualByComparingTo("600.00");
        });
        assertThat(jdbc.queryForObject("select kind from vat_tax_points where lease_id = ?", String.class, leaseId))
                .isEqualTo("AMENDMENT");
        assertThat(jdbc.queryForObject("select reference_note from tax_invoices where lease_id = ?", String.class, leaseId))
                .contains("previous system");
        LocalDate today = LocalDate.now();
        LocalDate quarter = LocalDate.of(today.getYear(), ((today.getMonthValue() - 1) / 3) * 3 + 1, 1);
        VatReturnDTO.OutputCheck qc = check(quarter);
        assertThat(qc.ok()).as("the amendment Output VAT movement is documented: " + qc).isTrue();

        // Still a cut-over lease after its contract was re-posted under a TCO of ours.
        termination.terminate(leaseId, new TerminateLeaseRequest(today.plusDays(1), null, null, null), null);
        assertThat(jdbc.queryForList("select reference_note from tax_invoices where lease_id = ? and kind = 'CREDIT_NOTE'"
                + " order by created_at", String.class, leaseId)).last().asString().contains("previous system");
    }
}

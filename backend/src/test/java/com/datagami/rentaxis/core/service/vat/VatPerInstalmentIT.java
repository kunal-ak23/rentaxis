package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.cheque.ReplaceChequeRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.ExtendLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminationPreviewDTO;
import com.datagami.rentaxis.api.dto.vat.TaxInvoiceDTO;
import com.datagami.rentaxis.api.dto.vat.VatTaxPointDTO;
import com.datagami.rentaxis.api.dto.vat.VatTaxPointRunResult;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeDetailsService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseRenewalService;
import com.datagami.rentaxis.core.service.lease.LeaseTerminationService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.TaxInvoiceKind;
import com.datagami.rentaxis.domain.entity.enums.VatTaxPointKind;
import com.datagami.rentaxis.domain.entity.enums.VatTaxPointStatus;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
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

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VAT per instalment, end to end (spec 2026-09-24 §1, gap #56).
 *
 * <p>The spec's worked example is the spine: a commercial lease, 120,000 + VAT, TCO
 * dated 20/04/2026, term 01/05/2026 – 30/04/2027, four cheques of 31,500 on
 * 01/05, 01/08, 01/11 and 01/02. The contract parks 6,000 in "Output VAT – not yet
 * due"; each instalment's tax point moves 1,500 of it to Output VAT with a {@code VTP}
 * and a numbered tax invoice. Every figure below is the spec's, written down, not
 * asked of the code.</p>
 */
@SpringBootTest
class VatPerInstalmentIT extends AbstractPostgresIT {

    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired ChequeService chequeService;
    @Autowired ChequeDetailsService chequeDetails;
    @Autowired LeaseTerminationService termination;
    @Autowired LeaseRenewalService renewal;
    @Autowired VatTaxPointService vatTaxPoints;
    @Autowired VatTaxPointJob job;
    @Autowired TaxInvoiceService taxInvoices;
    @Autowired LeaseService leaseService;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired JournalEntryRepository journals;
    @Autowired JournalLineRepository journalLines;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT = LocalDate.of(2026, 4, 20);
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2027, 4, 30);
    private static final LocalDate MAY = LocalDate.of(2026, 5, 1);
    private static final LocalDate AUG = LocalDate.of(2026, 8, 1);
    private static final LocalDate NOV = LocalDate.of(2026, 11, 1);
    private static final LocalDate FEB = LocalDate.of(2027, 2, 1);

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

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** The spec's worked example, generated and posted. */
    private PostLeaseResponse workedExample() {
        return fixtures.postedLease(CONTRACT, START, END, List.of(vatLine("RENT", "120000")), 4, null);
    }

    private UUID account(AccountRole role) {
        return tx.execute(s -> resolver.resolve(role, fixtures.property().getId())).getId();
    }

    /** Closing balance on the lease, credit negative, optionally as of a date. */
    private BigDecimal balance(AccountRole role, UUID leaseId, LocalDate asOf) {
        return tx.execute(s -> ledger.accountLedger(account(role),
                new LedgerQueryService.LedgerFilter(null, asOf, null, null, leaseId, null)).closingBalance());
    }

    private BigDecimal balance(AccountRole role, UUID leaseId) {
        return balance(role, leaseId, null);
    }

    private List<VatTaxPointDTO> schedule(UUID leaseId) {
        return vatTaxPoints.scheduleFor(leaseId);
    }

    private List<JournalLine> linesOf(UUID entryId) {
        return tx.execute(s -> journalLines.findByEntry_IdOrderByLineNoAsc(entryId));
    }

    private JournalEntry journal(UUID id) {
        return tx.execute(s -> journals.findById(id).orElseThrow());
    }

    private List<ChequeDTO> register(UUID leaseId) {
        return chequeGeneration.list(leaseId);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ------------------------------------------------------------------
    // allocation and the TCO
    // ------------------------------------------------------------------

    @Test
    void theContractParksItsVatAndEachRowCarriesItsShare() {
        PostLeaseResponse r = workedExample();
        UUID leaseId = r.lease().getId();

        assertThat(r.cheques()).extracting(ChequeDTO::chequeDate).containsExactly(MAY, AUG, NOV, FEB);
        assertThat(r.cheques()).allSatisfy(c -> {
            assertThat(c.amount()).isEqualByComparingTo("31500.00");
            assertThat(c.vatAmount()).isEqualByComparingTo("1500.00");
            assertThat(c.vatTaxableAmount()).isEqualByComparingTo("30000.00");
        });

        // TCO-26/n: Dr RR 120,000 / Cr ADVANCE_RENT; Dr RR 6,000 / Cr OUTPUT_VAT_DEFERRED.
        List<JournalLine> tco = linesOf(r.tcoJournalId());
        UUID deferred = account(AccountRole.OUTPUT_VAT_DEFERRED);
        assertThat(tco).filteredOn(l -> deferred.equals(l.getAccountId()))
                .singleElement().satisfies(l -> assertThat(l.getCredit()).isEqualByComparingTo("6000.00"));
        assertThat(balance(AccountRole.OUTPUT_VAT, leaseId)).as("the contract is not a tax invoice").isEqualByComparingTo("0");

        List<VatTaxPointDTO> points = schedule(leaseId);
        assertThat(points).extracting(VatTaxPointDTO::taxPointDate).containsExactly(MAY, AUG, NOV, FEB);
        assertThat(points).allSatisfy(p -> {
            assertThat(p.status()).isEqualTo(VatTaxPointStatus.PLANNED);
            assertThat(p.kind()).isEqualTo(VatTaxPointKind.INSTALMENT);
            assertThat(p.vatAmount()).isEqualByComparingTo("1500.00");
            assertThat(p.taxableAmount()).isEqualByComparingTo("30000.00");
        });
    }

    /** The spec's headline: the Q2 return shows 1,500, not 6,000. */
    @Test
    void theWorkedExampleDeclaresEachQuarterOnItsOwnTaxPoint() {
        UUID leaseId = workedExample().lease().getId();

        VatTaxPointRunResult q2 = vatTaxPoints.runTo(LocalDate.of(2026, 6, 30), false);
        assertThat(q2.posted()).isEqualTo(1);
        assertThat(balance(AccountRole.OUTPUT_VAT, leaseId, LocalDate.of(2026, 6, 30)))
                .as("Q2 output VAT").isEqualByComparingTo("-1500.00");
        assertThat(balance(AccountRole.OUTPUT_VAT_DEFERRED, leaseId)).isEqualByComparingTo("-4500.00");

        VatTaxPointDTO may = schedule(leaseId).get(0);
        assertThat(may.status()).isEqualTo(VatTaxPointStatus.POSTED);
        assertThat(may.journalNumber()).isEqualTo("VTP-26/1");
        assertThat(may.invoiceNumber()).isEqualTo("TI-26/1");
        JournalEntry vtp = journal(may.journalId());
        assertThat(vtp.getDocType()).isEqualTo(JournalDocType.VTP);
        assertThat(vtp.getEntryDate()).isEqualTo(MAY);
        assertThat(linesOf(vtp.getId())).allSatisfy(l -> assertThat(l.getChequeId()).isEqualTo(may.chequeId()));

        // The rest, each on its own date; February is numbered in FY 2027.
        vatTaxPoints.runTo(FEB, false);
        assertThat(schedule(leaseId)).extracting(VatTaxPointDTO::journalNumber)
                .containsExactly("VTP-26/1", "VTP-26/2", "VTP-26/3", "VTP-27/1");
        assertThat(schedule(leaseId)).extracting(VatTaxPointDTO::invoiceNumber)
                .containsExactly("TI-26/1", "TI-26/2", "TI-26/3", "TI-27/1");
        assertThat(balance(AccountRole.OUTPUT_VAT, leaseId)).isEqualByComparingTo("-6000.00");
        assertThat(balance(AccountRole.OUTPUT_VAT_DEFERRED, leaseId)).isEqualByComparingTo("0.00");

        // Idempotent: a second run finds nothing.
        assertThat(vatTaxPoints.runTo(FEB, false).wouldPost()).isZero();

        // The tax invoice carries what the law asks of one.
        TaxInvoiceDTO first = taxInvoices.forLease(leaseId).get(0);
        assertThat(first.invoiceNumber()).isEqualTo("TI-26/1");
        assertThat(first.kind()).isEqualTo(TaxInvoiceKind.TAX_INVOICE);
        assertThat(first.issueDate()).isEqualTo(MAY);
        assertThat(first.periodStart()).isEqualTo(MAY);
        assertThat(first.periodEnd()).isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(first.taxableAmount()).isEqualByComparingTo("30000.00");
        assertThat(first.vatRate()).isEqualByComparingTo("0.05");
        assertThat(first.vatAmount()).isEqualByComparingTo("1500.00");
        assertThat(first.totalAmount()).isEqualByComparingTo("31500.00");
        assertThat(count("select count(*) from tax_invoices where lease_id = ? and supplier_trn = ?",
                leaseId, LeaseTestFixtures.FIXTURE_TRN)).isEqualTo(4);
        assertThat(taxInvoices.forLease(leaseId).get(3).periodEnd()).isEqualTo(END);
    }

    /** A preview writes nothing and says what would post. */
    @Test
    void aDryRunPostsNothing() {
        UUID leaseId = workedExample().lease().getId();

        VatTaxPointRunResult preview = vatTaxPoints.runTo(AUG, true);

        assertThat(preview.preview()).isTrue();
        assertThat(preview.posted()).isZero();
        assertThat(preview.wouldPost()).isEqualTo(2);
        assertThat(preview.vatAmount()).isEqualByComparingTo("3000.00");
        assertThat(schedule(leaseId)).allSatisfy(p -> assertThat(p.status()).isEqualTo(VatTaxPointStatus.PLANNED));
        assertThat(count("select count(*) from journal_entries where tenant_id = ? and doc_type = 'VTP'",
                fixtures.tenantId())).isZero();
    }

    /** The nightly job: per tenant, context set by the job itself. */
    @Test
    void theNightlyJobPostsWhatHasFallenDue() {
        UUID leaseId = workedExample().lease().getId();
        TenantContextHolder.clear();

        job.runFor(AUG);

        TenantContextHolder.setTenantId(fixtures.tenantId());
        assertThat(schedule(leaseId)).extracting(VatTaxPointDTO::status).containsExactly(
                VatTaxPointStatus.POSTED, VatTaxPointStatus.POSTED, VatTaxPointStatus.PLANNED, VatTaxPointStatus.PLANNED);
    }

    /** A lease with no VAT writes no tax points and never touches the deferred account. */
    @Test
    void aVatFreeLeaseHasNoSchedule() {
        UUID leaseId = fixtures.postedLease(CONTRACT, START, END, List.of(line("RENT", "120000")), 4, null)
                .lease().getId();

        assertThat(schedule(leaseId)).isEmpty();
        assertThat(balance(AccountRole.OUTPUT_VAT_DEFERRED, leaseId)).isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------
    // post-time guards
    // ------------------------------------------------------------------

    /** Σ row VAT must equal the contract's VAT, next to Σ amount = contract value. */
    @Test
    void aGridWhoseVatDoesNotAddUpIsRefused() {
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END, List.of(vatLine("RENT", "120000")));
        List<ChequeDTO> grid = fixtures.generateGrid(leaseId, 4, START);
        // Move 100 of VAT off row 1 and nowhere else: amounts still total 126,000.
        chequeGeneration.saveRows(leaseId, grid.stream().map(c -> new ChequeRowInput(c.id(), null, c.postingDate(),
                c.chequeNumber(), c.chequeDate(), c.payeeBank(), null, null, c.amount(), c.narration(), c.mode(),
                c.seqNo() == 1 ? new BigDecimal("1400.00") : c.vatAmount())).toList());

        assertThatThrownBy(() -> posting.post(leaseId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("The cheque grid carries VAT of 5,900.00 but the contract charges 6,000.00");
        assertThat(posting.dryRun(leaseId).errors())
                .anySatisfy(e -> assertThat(e).contains("carries VAT of 5,900.00"));
    }

    /** Rows typed without a VAT figure share the contract's VAT pro rata when the grid is saved. */
    @Test
    void rowsTypedWithoutVatGetTheProRataDefault() {
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END, List.of(vatLine("RENT", "120000")));
        List<ChequeDTO> saved = chequeGeneration.saveRows(leaseId, List.of(
                LeaseTestFixtures.chequeRow("63000", MAY),
                LeaseTestFixtures.chequeRow("42000", NOV),
                LeaseTestFixtures.chequeRow("21000", FEB)));

        assertThat(saved).extracting(ChequeDTO::vatAmount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("3000.00"), new BigDecimal("2000.00"), new BigDecimal("1000.00"));
        assertThat(saved).extracting(ChequeDTO::vatTaxableAmount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("60000.00"), new BigDecimal("40000.00"), new BigDecimal("20000.00"));
        posting.post(leaseId);
        assertThat(schedule(leaseId)).hasSize(3);
    }

    /** No TRN, no tax invoice — and so no VAT-bearing contract on the books. */
    @Test
    void aVatLeaseIsRefusedWhenTheOrganisationHasNoTrn() {
        tx.executeWithoutResult(s -> {
            LandlordOrg org = orgRepo.findById(fixtures.tenantId()).orElseThrow();
            org.setTrn(null);
            orgRepo.save(org);
        });
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END, List.of(vatLine("RENT", "120000")));
        fixtures.generateGrid(leaseId, 4, START);

        assertThatThrownBy(() -> posting.post(leaseId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("the organisation has no TRN");
        // A lease without VAT needs no TRN.
        UUID plain = fixtures.draftLease(fixtures.createUnit(fixtures.property(), "102"), fixtures.renter(),
                CONTRACT, START, END, List.of(line("RENT", "120000")));
        fixtures.generateGrid(plain, 4, START);
        posting.post(plain);
    }

    // ------------------------------------------------------------------
    // the cheque lifecycle
    // ------------------------------------------------------------------

    /** A transfer received before its due date reaches its tax point on the receipt date. */
    @Test
    void anEarlyReceiptMovesTheTaxPoint() {
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END, List.of(vatLine("RENT", "120000")));
        chequeGeneration.saveRows(leaseId, List.of(
                transfer("63000", MAY), transfer("63000", NOV)));
        posting.post(leaseId);
        ChequeDTO nov = register(leaseId).get(1);
        LocalDate received = LocalDate.of(2026, 9, 10);

        chequeService.receive(nov.id(), ChequeActionRequest.on(received));

        VatTaxPointDTO point = schedule(leaseId).stream().filter(p -> nov.id().equals(p.chequeId())).findFirst().orElseThrow();
        assertThat(point.status()).isEqualTo(VatTaxPointStatus.POSTED);
        assertThat(point.taxPointDate()).isEqualTo(received);
        assertThat(journal(point.journalId()).getEntryDate()).isEqualTo(received);
        assertThat(balance(AccountRole.OUTPUT_VAT, leaseId)).isEqualByComparingTo("-3000.00");
        // May's point is untouched and still waits for the job.
        assertThat(schedule(leaseId).get(0).status()).isEqualTo(VatTaxPointStatus.PLANNED);
    }

    private static ChequeRowInput transfer(String amount, LocalDate date) {
        return new ChequeRowInput(null, null, null, null, date, null, null, null, new BigDecimal(amount), null,
                ChequeMode.TRANSFER);
    }

    /** A bounced then replaced instalment declares its VAT once: the replacement carries none. */
    @Test
    void aBouncedAndReplacedInstalmentPostsVatOnce() {
        UUID leaseId = workedExample().lease().getId();
        ChequeDTO may = register(leaseId).get(0);
        chequeService.deposit(may.id(), ChequeActionRequest.on(MAY));
        chequeService.bounce(may.id(), new ChequeActionRequest(LocalDate.of(2026, 5, 5), null,
                ChequeFailureReason.BOUNCE, null));
        vatTaxPoints.runTo(LocalDate.of(2026, 5, 31), false);

        List<ChequeDTO> replacements = chequeService.replace(may.id(), new ReplaceChequeRequest(
                List.of(LeaseTestFixtures.chequeRow("31500", LocalDate.of(2026, 5, 20))), LocalDate.of(2026, 5, 10), null));
        assertThat(replacements).singleElement().satisfies(c -> assertThat(c.vatAmount()).isEqualByComparingTo("0"));
        chequeService.deposit(replacements.get(0).id(), ChequeActionRequest.on(LocalDate.of(2026, 5, 20)));
        chequeService.clear(replacements.get(0).id(), ChequeActionRequest.on(LocalDate.of(2026, 5, 21)));
        vatTaxPoints.runTo(LocalDate.of(2026, 7, 31), false);

        assertThat(schedule(leaseId)).filteredOn(p -> p.status() == VatTaxPointStatus.POSTED)
                .singleElement().satisfies(p -> assertThat(p.chequeId()).isEqualTo(may.id()));
        assertThat(count("select count(*) from journal_entries where lease_id = ? and doc_type = 'VTP'", leaseId))
                .isEqualTo(1);
        assertThat(balance(AccountRole.OUTPUT_VAT, leaseId)).isEqualByComparingTo("-1500.00");
    }

    /** A new date on a registered row moves its PLANNED tax point; a declared one stays. */
    @Test
    void aChequeDateChangeMovesAPlannedTaxPointOnly() {
        UUID leaseId = workedExample().lease().getId();
        vatTaxPoints.runTo(MAY, false);
        List<ChequeDTO> rows = register(leaseId);

        chequeDetails.updateDetails(rows.get(1).id(), new ChequeRowInput(null, null, null, null,
                LocalDate.of(2026, 8, 15), null, null, null, null, null, null));
        chequeDetails.updateDetails(rows.get(0).id(), new ChequeRowInput(null, null, null, null,
                LocalDate.of(2026, 5, 9), null, null, null, null, null, null));

        List<VatTaxPointDTO> points = schedule(leaseId);
        assertThat(points.get(0).taxPointDate()).as("declared: stays where it was").isEqualTo(MAY);
        assertThat(points.get(1).taxPointDate()).isEqualTo(LocalDate.of(2026, 8, 15));
    }

    /** A registered row carrying PLANNED VAT cannot be cancelled without saying where its VAT goes. */
    @Test
    void cancellingARowWithUndeclaredVatMustMoveIt() {
        UUID leaseId = workedExample().lease().getId();
        List<ChequeDTO> rows = register(leaseId);

        assertThatThrownBy(() -> chequeService.cancel(rows.get(3).id(), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("carries VAT of 1500.00 that has not been declared yet");
        assertThat(register(leaseId).get(3).status().name()).isEqualTo("REGISTERED");

        chequeService.cancel(rows.get(3).id(), null, rows.get(2).id());

        List<ChequeDTO> after = register(leaseId);
        assertThat(after.get(3).vatAmount()).isEqualByComparingTo("0");
        assertThat(after.get(2).vatAmount()).isEqualByComparingTo("3000.00");
        assertThat(schedule(leaseId)).filteredOn(p -> p.status() == VatTaxPointStatus.PLANNED)
                .extracting(VatTaxPointDTO::vatAmount).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1500.00"), new BigDecimal("1500.00"), new BigDecimal("3000.00"));
        // Nothing is stranded: every fils of the deferred 6,000 still has a tax point.
        assertThat(schedule(leaseId).stream().filter(p -> p.status() != VatTaxPointStatus.CANCELLED)
                .map(VatTaxPointDTO::vatAmount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("6000.00");
    }

    // ------------------------------------------------------------------
    // amendment, extension, the period lock
    // ------------------------------------------------------------------

    @Test
    void amendingLinesIsRefusedOnceAnInstalmentsVatIsDeclared() {
        UUID leaseId = workedExample().lease().getId();
        vatTaxPoints.runTo(MAY, false);

        assertThatThrownBy(() -> posting.amendLines(leaseId,
                List.of(vatLine("RENT", "110000"), vatLine("PARKING_FEE", "10000")), "re-split"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("VAT already declared on instalment")
                .hasMessageContaining("use an addendum");
    }

    @Test
    void amendingLinesBeforeAnyTaxPointRebuildsTheSchedule() {
        UUID leaseId = workedExample().lease().getId();

        posting.amendLines(leaseId, List.of(vatLine("RENT", "110000"), vatLine("PARKING_FEE", "10000")), "re-split");

        List<VatTaxPointDTO> points = schedule(leaseId);
        assertThat(points).filteredOn(p -> p.status() == VatTaxPointStatus.CANCELLED).hasSize(4);
        assertThat(points).filteredOn(p -> p.status() == VatTaxPointStatus.PLANNED).hasSize(4)
                .allSatisfy(p -> assertThat(p.vatAmount()).isEqualByComparingTo("1500.00"));
        assertThat(balance(AccountRole.OUTPUT_VAT_DEFERRED, leaseId)).isEqualByComparingTo("-6000.00");
    }

    @Test
    void anExtensionsRowsCarryTheNewLinesVatAndGetTaxPoints() {
        UUID leaseId = workedExample().lease().getId();
        LocalDate newEnd = LocalDate.of(2027, 7, 31);

        renewal.extend(leaseId, new ExtendLeaseRequest(newEnd, LocalDate.of(2027, 4, 15),
                List.of(vatLine("RENT", "30000")),
                List.of(LeaseTestFixtures.chequeRow("31500", LocalDate.of(2027, 5, 1)))));

        List<VatTaxPointDTO> points = schedule(leaseId);
        assertThat(points).hasSize(5);
        assertThat(points.get(4).taxPointDate()).isEqualTo(LocalDate.of(2027, 5, 1));
        assertThat(points.get(4).vatAmount()).isEqualByComparingTo("1500.00");
        assertThat(balance(AccountRole.OUTPUT_VAT_DEFERRED, leaseId)).isEqualByComparingTo("-7500.00");
    }

    /** A lock can never strand a tax point in a closed month. */
    @Test
    void lockingTheBooksIsRefusedWhileATaxPointIsWaiting() {
        workedExample();

        assertThatThrownBy(() -> fiscal.lockThrough(LocalDate.of(2026, 5, 31)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Post the VAT tax points through 2026-05-31 first");
        assertThat(fiscal.get().getBooksLockedThrough()).isNull();

        vatTaxPoints.runTo(LocalDate.of(2026, 5, 31), false);
        fiscal.lockThrough(LocalDate.of(2026, 5, 31));
        assertThat(fiscal.get().getBooksLockedThrough()).isEqualTo(LocalDate.of(2026, 5, 31));
    }

    // ------------------------------------------------------------------
    // termination (spec §1, "Termination at T")
    // ------------------------------------------------------------------

    /**
     * P > U, the spec's worked example: T = 31/10/2026, 184 of 365 days earned.
     * May and August are declared (3,000); November and February are pending
     * (P = 3,000); U = 2,975.34. The TCR reverses 2,975.34 out of the deferred account
     * and declares the remaining 24.66 at T, with a tax invoice for it.
     */
    @Test
    void terminationDeclaresVatOnEarnedDaysNoInstalmentCoveredWhenPExceedsU() {
        UUID leaseId = workedExample().lease().getId();
        LocalDate t = LocalDate.of(2026, 10, 31);
        vatTaxPoints.runTo(LocalDate.of(2026, 9, 30), false);

        TerminationPreviewDTO preview = termination.preview(leaseId, t);
        assertThat(preview.earnedRentThroughDate()).isEqualByComparingTo("60493.15");
        assertThat(preview.unearnedVat()).isEqualByComparingTo("2975.34");
        assertThat(preview.vatSettlement().pendingCancelled()).isEqualByComparingTo("3000.00");
        assertThat(preview.vatSettlement().reversedFromDeferred()).isEqualByComparingTo("2975.34");
        assertThat(preview.vatSettlement().declaredAtTermination()).isEqualByComparingTo("24.66");
        assertThat(preview.vatSettlement().creditedBack()).isEqualByComparingTo("0.00");

        termination.terminate(leaseId, new TerminateLeaseRequest(t, null, null, null), null);

        UUID tcrId = leaseService.getLeaseById(leaseId).getTerminationJournalId();
        List<JournalLine> tcr = linesOf(tcrId);
        UUID deferred = account(AccountRole.OUTPUT_VAT_DEFERRED);
        UUID outputVat = account(AccountRole.OUTPUT_VAT);
        assertThat(tcr).hasSize(6);
        assertThat(tcr).filteredOn(l -> deferred.equals(l.getAccountId()))
                .extracting(JournalLine::getDebit).usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("2975.34"), new BigDecimal("24.66"));
        assertThat(tcr).filteredOn(l -> outputVat.equals(l.getAccountId()))
                .singleElement().satisfies(l -> assertThat(l.getCredit()).isEqualByComparingTo("24.66"));

        // Declared output VAT = 3,000 + 24.66 = 3,024.66 = 5% × 60,493.15.
        assertThat(balance(AccountRole.OUTPUT_VAT, leaseId)).isEqualByComparingTo("-3024.66");
        assertThat(balance(AccountRole.OUTPUT_VAT_DEFERRED, leaseId)).isEqualByComparingTo("0.00");

        VatTaxPointDTO adjustment = schedule(leaseId).stream()
                .filter(p -> p.kind() == VatTaxPointKind.TERMINATION_ADJUSTMENT).findFirst().orElseThrow();
        assertThat(adjustment.status()).isEqualTo(VatTaxPointStatus.POSTED);
        assertThat(adjustment.vatAmount()).isEqualByComparingTo("24.66");
        assertThat(adjustment.taxableAmount()).isEqualByComparingTo("493.15");
        assertThat(adjustment.journalId()).isEqualTo(tcrId);
        TaxInvoiceDTO invoice = taxInvoices.forLease(leaseId).getLast();
        assertThat(invoice.kind()).isEqualTo(TaxInvoiceKind.TAX_INVOICE);
        assertThat(invoice.vatAmount()).isEqualByComparingTo("24.66");
        assertThat(invoice.periodStart()).isEqualTo(START);
        assertThat(invoice.periodEnd()).isEqualTo(t);
        assertThat(schedule(leaseId)).noneMatch(p -> p.status() == VatTaxPointStatus.PLANNED);
    }

    /**
     * P = U: the instalments still pending carry exactly the VAT on the unearned
     * rent. 73,000 + VAT over 365 days (200 a day), two instalments of 182 and 183
     * days' rent; T is the last day the first covers. The TCR reverses 1,830 out of
     * the deferred account and neither declares nor credits anything else.
     */
    @Test
    void terminationSettlesExactlyWhenPEqualsU() {
        LocalDate start = LocalDate.of(2027, 1, 1);
        LocalDate end = LocalDate.of(2027, 12, 31);
        UUID leaseId = fixtures.draftLease(LocalDate.of(2026, 12, 15), start, end, List.of(vatLine("RENT", "73000")));
        chequeGeneration.saveRows(leaseId, List.of(
                vatRow("38220", start, "1820.00"),
                vatRow("38430", LocalDate.of(2027, 7, 2), "1830.00")));
        posting.post(leaseId);
        LocalDate t = LocalDate.of(2027, 7, 1);

        TerminationPreviewDTO preview = termination.preview(leaseId, t);
        assertThat(preview.unearnedVat()).isEqualByComparingTo("1830.00");
        assertThat(preview.vatSettlement().pendingCancelled()).isEqualByComparingTo("1830.00");
        assertThat(preview.vatSettlement().declaredAtTermination()).isEqualByComparingTo("0.00");
        assertThat(preview.vatSettlement().creditedBack()).isEqualByComparingTo("0.00");

        termination.terminate(leaseId, new TerminateLeaseRequest(t, null, null, null), null);

        assertThat(linesOf(leaseService.getLeaseById(leaseId).getTerminationJournalId())).hasSize(4);
        assertThat(balance(AccountRole.OUTPUT_VAT, leaseId)).as("5% of 36,400 earned").isEqualByComparingTo("-1820.00");
        assertThat(balance(AccountRole.OUTPUT_VAT_DEFERRED, leaseId)).isEqualByComparingTo("0.00");
        assertThat(schedule(leaseId)).noneMatch(p -> p.kind() == VatTaxPointKind.TERMINATION_ADJUSTMENT);
    }

    private static ChequeRowInput vatRow(String amount, LocalDate date, String vat) {
        return new ChequeRowInput(null, null, null, LeaseTestFixtures.nextChequeNumber(), date, "Emirates NBD", null,
                null, new BigDecimal(amount), null, null, new BigDecimal(vat));
    }
}

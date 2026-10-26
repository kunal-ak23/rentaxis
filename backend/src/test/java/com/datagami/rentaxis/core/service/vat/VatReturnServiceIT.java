package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.dto.vat.VatReturnDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.entity.enums.UnitType;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #55: the VAT return for Q2 2026 — a VAT lease's first instalment (TI, box 1b
 * Dubai), a residential lease's rent (exempt, box 5), an office let without VAT
 * (reported apart), a supplier invoice with input VAT (box 9); the net payable,
 * the drill-down, the output check, filing and its lock, re-opening, PDF / CSV.
 */
@SpringBootTest
class VatReturnServiceIT extends AbstractPostgresIT {

    @Autowired VatReturnService service;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired com.datagami.rentaxis.core.service.ledger.PostingService postingService;
    @Autowired com.datagami.rentaxis.core.service.lease.LeaseTerminationService termination;
    @Autowired VatTaxPointService vatTaxPoints;
    @Autowired RecognitionService recognition;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired VoucherService vouchers;
    @Autowired VendorService vendorService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;
    private Vendor vendor;

    private static final LocalDate Q2 = LocalDate.of(2026, 4, 1);
    private static final LocalDate CONTRACT = LocalDate.of(2026, 4, 20);
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2027, 4, 30);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
        Vendor v = new Vendor();
        v.setNameEn("Gulf Cool LLC");
        v.setTrn("100200300400003");
        vendor = vendorService.createVendor(v);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private UUID invoice(LocalDate date, String number, String amount, String vatRate) {
        UUID id = vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR, date, vendor.getId(), number,
                "AC service", null, null, null, null, null, List.of(new VoucherService.VoucherLineInput(
                accountService.getAccountByCode("D-02-003").getId(), "service", new BigDecimal(amount),
                new BigDecimal(vatRate), null, null, true)))).getId();
        vouchers.post(id);
        return id;
    }

    /** A supplier invoice from {@code from}, one AC-service line per {@code serviceLine}. */
    private VoucherService.VoucherInput pisr(LocalDate date, Vendor from, String number,
                                             VoucherService.VoucherLineInput... lines) {
        return new VoucherService.VoucherInput(VoucherType.PISR, date, from.getId(), number, "AC service",
                null, null, null, null, null, List.of(lines));
    }

    private VoucherService.VoucherLineInput serviceLine(String amount, String vatRate) {
        return new VoucherService.VoucherLineInput(accountService.getAccountByCode("D-02-003").getId(), "service",
                new BigDecimal(amount), new BigDecimal(vatRate), null, null, true);
    }

    private void quarter() {
        // A VAT lease: 120,000 in four instalments; the first tax point, 01/05, declares 1,500 on 30,000.
        fixtures.postedLease(fixtures.unit(), fixtures.renter(), CONTRACT, START, END,
                List.of(vatLine("RENT", "120000")), 4, null);
        vatTaxPoints.runTo(LocalDate.of(2026, 6, 30), false);
        // A residential lease without VAT and an office let without VAT.
        Unit flat = fixtures.createUnit(fixtures.property(), "301");
        fixtures.postedLease(flat, fixtures.createRenter("Home Renter"), CONTRACT, START, END,
                List.of(line("RENT", "36500")), 4, null);
        Unit office = fixtures.createUnit(fixtures.property(), "OF-1");
        office.setType(UnitType.OFFICE);
        unitRepo.save(office);
        fixtures.postedLease(office, fixtures.createRenter("Office Co"), CONTRACT, START, END,
                List.of(line("RENT", "73000")), 4, null);
        recognition.runTo(LocalDate.of(2026, 6, 30), false);
        invoice(LocalDate.of(2026, 5, 20), "GC-1", "1000.00", "5");
    }

    private static VatReturnDTO.Box box(VatReturnDTO r, String code) {
        return r.boxes().stream().filter(b -> b.code().equals(code)).findFirst().orElseThrow(() -> new AssertionError(code));
    }

    @Test
    void theQuarterInVat201BoxesWithItsDrillDownAndCheck() {
        quarter();
        VatReturnDTO r = service.get(Q2);
        assertThat(r.periodEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(r.status()).isEqualTo("OPEN");
        assertThat(box(r, "1b").amount()).isEqualByComparingTo("30000.00");
        assertThat(box(r, "1b").vat()).isEqualByComparingTo("1500.00");
        assertThat(box(r, "1a").amount()).isEqualByComparingTo("0");
        // Two months of 36,500 a year (100/day): May 3,100 + June 3,000.
        assertThat(box(r, "5").amount()).isEqualByComparingTo("6100.00");
        assertThat(r.commercialWithoutVat()).isEqualByComparingTo("12200.00");
        assertThat(box(r, "9").amount()).isEqualByComparingTo("1000.00");
        assertThat(box(r, "9").vat()).isEqualByComparingTo("50.00");
        assertThat(box(r, "8").vat()).isEqualByComparingTo("1500.00");
        assertThat(box(r, "14").vat()).isEqualByComparingTo("1450.00");
        assertThat(r.netVat()).isEqualByComparingTo("1450.00");
        assertThat(r.outputCheck().ok()).isTrue();

        assertThat(service.documents(Q2, "1b")).singleElement()
                .satisfies(d -> assertThat(d.number()).startsWith("TI-"));
        assertThat(service.documents(Q2, "9")).singleElement()
                .satisfies(d -> assertThat(d.number()).isEqualTo("GC-1"));
        assertThat(service.documents(Q2, "5")).allSatisfy(d -> assertThat(d.party()).isEqualTo("Home Renter"));
        // Q3 has none of it.
        assertThat(box(service.get(LocalDate.of(2026, 7, 1)), "9").vat()).isEqualByComparingTo("0");

        String pdfHead = new String(VatReturnExport.pdf(r, "ar"), 0, 5, StandardCharsets.ISO_8859_1);
        assertThat(pdfHead).isEqualTo("%PDF-");
        assertThat(new String(VatReturnExport.csv(r, "ar"), StandardCharsets.UTF_8)).contains("التوريدات المعفاة").contains("1450.00");
        // F15-17: the CSV carries the commercial-without-VAT warning the screen shows.
        assertThat(new String(VatReturnExport.csv(r, "en"), StandardCharsets.UTF_8))
                .contains("Rent on commercial units without VAT").contains("12200.00");
    }

    /** F15-17: a SUPER_ADMIN (no tenant_id) filing inside the organisation is named on the filing. */
    @Test
    void aFilingBySuperAdminShowsWhoFiledIt() {
        quarter();
        UUID tenant = TenantContextHolder.getTenantId();
        TenantContextHolder.clear();
        com.datagami.rentaxis.domain.entity.User sa = new com.datagami.rentaxis.domain.entity.User();
        sa.setName("Platform Admin");
        sa.setEmail("vat-sa-" + UUID.randomUUID() + "@example.invalid");
        sa.setPasswordHash("x");
        sa.setRole(com.datagami.rentaxis.domain.entity.enums.UserRole.SUPER_ADMIN);
        sa.setTenantId(null);
        UUID saId = userRepo.saveAndFlush(sa).getId();
        TenantContextHolder.setTenantId(tenant);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(saId.toString(), null,
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SUPER_ADMIN"))));
        VatReturnDTO filed = service.file(Q2, "FTA-9");
        assertThat(filed.filedByName()).isEqualTo("Platform Admin");
        assertThat(service.filings()).singleElement().satisfies(f -> assertThat(f.filedByName()).isEqualTo("Platform Admin"));
        assertThat(new String(VatReturnExport.csv(service.get(Q2), "en"), StandardCharsets.UTF_8)).contains("Platform Admin");
    }

    /** A tax point falling in a filed quarter is declared on the first open day: it shows on the next return. */
    @Test
    void aTaxPointInAFiledQuarterIsDeclaredInTheNextOne() {
        fixtures.postedLease(fixtures.unit(), fixtures.renter(), CONTRACT, START, END,
                List.of(vatLine("RENT", "120000")), 4, null);
        service.file(Q2, null);
        vatTaxPoints.runTo(LocalDate.of(2026, 6, 30), false);
        assertThat(box(service.get(Q2), "1b").vat()).isEqualByComparingTo("0");
        VatReturnDTO q3 = service.get(LocalDate.of(2026, 7, 1));
        assertThat(box(q3, "1b").vat()).isEqualByComparingTo("1500.00");
        assertThat(service.documents(LocalDate.of(2026, 7, 1), "1b")).singleElement()
                .satisfies(d -> assertThat(d.date()).isEqualTo(LocalDate.of(2026, 7, 1)));
    }

    /** A termination inside the quarter credits VAT back: the credit note reduces box 1b and the check still ties. */
    @Test
    void aCreditNoteReducesTheStandardRatedBox() {
        UUID leaseId = fixtures.postedLease(fixtures.unit(), fixtures.renter(), CONTRACT, START, END,
                List.of(vatLine("RENT", "120000")), 4, null).lease().getId();
        vatTaxPoints.runTo(LocalDate.of(2026, 6, 30), false);
        termination.terminate(leaseId, new com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest(
                LocalDate.of(2026, 5, 31), null, null, null), null);
        VatReturnDTO r = service.get(Q2);
        List<VatReturnDTO.Document> docs = service.documents(Q2, "1b");
        assertThat(docs).extracting(VatReturnDTO.Document::kind).contains("TAX_INVOICE", "CREDIT_NOTE");
        BigDecimal credited = docs.stream().filter(d -> d.kind().equals("CREDIT_NOTE"))
                .map(VatReturnDTO.Document::vat).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(credited).isNegative();
        assertThat(box(r, "1b").vat()).isEqualByComparingTo(new BigDecimal("1500.00").add(credited));
        assertThat(r.outputCheck().ok()).isTrue();
    }

    @Test
    void aFiledQuarterKeepsItsFiguresAndLocksItsVatUntilReopened() {
        quarter();
        VatReturnDTO filed = service.file(Q2, "FTA-123");
        assertThat(filed.status()).isEqualTo("FILED");
        assertThat(filed.filingReference()).isEqualTo("FTA-123");
        assertThat(filed.netVat()).isEqualByComparingTo("1450.00");

        // VAT dated in the filed quarter is refused, with the first open day in the message.
        assertThatThrownBy(() -> invoice(LocalDate.of(2026, 6, 10), "GC-2", "200.00", "5"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("vat.periodFiled"))
                .hasMessageContaining("01/07/2026");
        // A posting with no VAT in the same quarter still goes through.
        invoice(LocalDate.of(2026, 6, 10), "GC-3", "200.00", "0");
        // The correction dated in the next quarter lands on that return.
        invoice(LocalDate.of(2026, 7, 2), "GC-2", "200.00", "5");
        assertThat(box(service.get(LocalDate.of(2026, 7, 1)), "9").vat()).isEqualByComparingTo("10.00");
        // Filing again is refused; the filed figures stand.
        assertThatThrownBy(() -> service.file(Q2, null)).isInstanceOf(BusinessRuleViolationException.class);
        assertThat(service.get(Q2).netVat()).isEqualByComparingTo("1450.00");
        // A quarter not yet ended cannot be filed.
        assertThatThrownBy(() -> service.file(LocalDate.of(2099, 1, 1), null))
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("vat.periodNotEnded"));

        service.reopen(filed.id(), "Late supplier invoice");
        invoice(LocalDate.of(2026, 6, 11), "GC-4", "100.00", "5");
        assertThat(service.get(Q2).status()).isEqualTo("OPEN");
        assertThat(service.filings()).extracting(VatReturnDTO.Filing::status).containsExactly("REOPENED");
    }

    /**
     * #101: the first two filings of a tenant, for overlapping quarters with different
     * starts (01/04 and 01/05), raced past the overlap check — no row existed to lock and
     * the unique index only covers the same start. The second now waits for the first
     * and is refused as overlapping.
     */
    @Test
    void concurrentFirstFilingsOfOverlappingQuartersAreSerialised() throws Exception {
        UUID tenant = fixtures.tenantId();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var filed = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        java.util.function.Function<java.util.concurrent.Callable<VatReturnDTO>, java.util.concurrent.Future<VatReturnDTO>> inTenant =
                work -> pool.submit(() -> {
                    TenantContextHolder.setTenantId(tenant);
                    LeaseTestFixtures.authenticateAsTenantAdmin();
                    try { return work.call(); } finally { TenantContextHolder.clear(); LeaseTestFixtures.clearAuth(); }
                });
        try {
            var first = inTenant.apply(() -> tx.execute(st -> {
                VatReturnDTO r = service.file(Q2, "FTA-A");
                filed.countDown();
                try { release.await(20, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { throw new RuntimeException(e); }
                return r;
            }));
            assertThat(filed.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var second = inTenant.apply(() -> service.file(LocalDate.of(2026, 5, 1), "FTA-B"));
            Thread.sleep(700);
            assertThat(second.isDone()).as("the second filing waits for the first").isFalse();
            release.countDown();
            assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS).status()).isEqualTo("FILED");
            assertThatThrownBy(() -> second.get(20, java.util.concurrent.TimeUnit.SECONDS))
                    .rootCause().isInstanceOf(BusinessRuleViolationException.class)
                    .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode()).isEqualTo("vat.periodOverlaps"));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(service.filings()).extracting(VatReturnDTO.Filing::status).containsExactly("FILED");
    }

    /**
     * PR #361 R1 P2-1 / P2-3: an opening balance carrying Input VAT (the cut-over quarter) is a check
     * line, not box 9; a purchase on a residential-only property stays in box 9 with a warning line.
     */
    @Test
    void cutOverInputVatIsACheckLineAndInputVatOnExemptPropertiesIsFlagged() {
        var d = com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions.none();
        postingService.post(new com.datagami.rentaxis.core.service.ledger.PostingRequest(
                com.datagami.rentaxis.domain.entity.enums.JournalDocType.OB, LocalDate.of(2026, 4, 1), "opening", d,
                com.datagami.rentaxis.domain.entity.enums.JournalSourceType.OPENING_BALANCE, null, null, List.of(
                com.datagami.rentaxis.core.service.ledger.PostingRequest.dr(
                        com.datagami.rentaxis.domain.entity.enums.AccountRole.INPUT_VAT, new BigDecimal("800")),
                com.datagami.rentaxis.core.service.ledger.PostingRequest.cr(
                        com.datagami.rentaxis.domain.entity.enums.AccountRole.OPENING_BALANCE_DIFFERENCE, new BigDecimal("800")))));
        // A purchase on the (residential-only) property: 1,000 + 50 VAT.
        UUID repairs = accountService.getAccountByCode("D-02-003").getId();
        UUID id = vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR, LocalDate.of(2026, 5, 20), vendor.getId(),
                "GC-9", "AC", fixtures.property().getId(), null, null, null, null, List.of(new VoucherService.VoucherLineInput(
                repairs, "ac", new BigDecimal("1000"), new BigDecimal("5"), fixtures.property().getId(), null)))).getId();
        vouchers.post(id);
        // A head-office cost (no property): recoverable, not flagged.
        invoice(LocalDate.of(2026, 5, 21), "GC-10", "100.00", "5");
        VatReturnDTO r = service.get(Q2);
        assertThat(box(r, "9").vat()).isEqualByComparingTo("55.00");
        assertThat(r.inputVatOther()).isEqualByComparingTo("800.00");
        assertThat(service.documents(Q2, "INPUT_OTHER")).singleElement().satisfies(x -> assertThat(x.kind()).isEqualTo("OB"));
        assertThat(r.inputVatOnExempt()).isEqualByComparingTo("50.00");
        assertThat(box(r, "14").vat()).isEqualByComparingTo("-55.00");
    }

    /**
     * Box 9 is the net of the standard-rated lines: a supplier invoice of 1,000 at 5 % and
     * 400 at 0 % declares 1,000 against its 50 of input VAT, not 1,400.
     */
    @Test
    void box9CountsOnlyTheStandardRatedLinesOfAMixedRateInvoice() {
        vouchers.post(vouchers.createDraft(pisr(LocalDate.of(2026, 5, 20), vendor, "GC-1",
                serviceLine("1000.00", "5"), serviceLine("400.00", "0"))).getId());
        VatReturnDTO r = service.get(Q2);
        assertThat(box(r, "9").amount()).isEqualByComparingTo("1000.00");
        assertThat(box(r, "9").vat()).isEqualByComparingTo("50.00");
        assertThat(box(r, "11").amount()).isEqualByComparingTo("1000.00");
        assertThat(service.documents(Q2, "9")).singleElement()
                .satisfies(d -> assertThat(d.amount()).isEqualByComparingTo("1000.00"));
    }

    /** A supplier credit note of 200 at 5 % and 100 at 0 % takes back 200 and its 10 of input VAT. */
    @Test
    void aSupplierCreditNoteTakesBackOnlyItsStandardRatedNet() {
        vouchers.post(vouchers.createDraft(pisr(LocalDate.of(2026, 5, 20), vendor, "GC-1",
                serviceLine("1000.00", "5"), serviceLine("400.00", "0"))).getId());
        vouchers.post(vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PCN, LocalDate.of(2026, 6, 1),
                vendor.getId(), "CN-1", "Credit note", null, null, null, null, null,
                List.of(serviceLine("200.00", "5"), serviceLine("100.00", "0")))).getId(), List.of());
        assertThat(service.documents(Q2, "9")).extracting(d -> d.kind() + " " + d.amount().toPlainString())
                .containsExactlyInAnyOrder("PISR 1000.00", "PCN -200.00");
        VatReturnDTO r = service.get(Q2);
        assertThat(box(r, "9").amount()).isEqualByComparingTo("800.00");
        assertThat(box(r, "9").vat()).isEqualByComparingTo("40.00");
    }

    /**
     * An amended supplier invoice: the reversal of the original (its journal's source is
     * the original journal, not the voucher) still names the supplier and their invoice
     * number, and takes back only the standard-rated net the original declared.
     */
    @Test
    void anAmendedInvoiceKeepsItsSupplierAndNumberOnTheReversal() {
        UUID original = vouchers.createDraft(pisr(LocalDate.of(2026, 5, 20), vendor, "GC-1",
                serviceLine("1000.00", "5"), serviceLine("400.00", "0"))).getId();
        vouchers.post(original);
        vouchers.amend(original, LocalDate.of(2026, 6, 5), "Wrong amount",
                pisr(LocalDate.of(2026, 6, 5), vendor, "GC-1", serviceLine("800.00", "5")));

        List<VatReturnDTO.Document> docs = service.documents(Q2, "9");
        assertThat(docs).hasSize(3).allSatisfy(d -> {
            assertThat(d.number()).isEqualTo("GC-1");
            assertThat(d.party()).isEqualTo("Gulf Cool LLC");
        });
        assertThat(docs).extracting(d -> d.amount().toPlainString() + "/" + d.vat().toPlainString())
                .containsExactlyInAnyOrder("1000.00/50.00", "-1000.00/-50.00", "800.00/40.00");
        VatReturnDTO r = service.get(Q2);
        assertThat(box(r, "9").amount()).isEqualByComparingTo("800.00");
        assertThat(box(r, "9").vat()).isEqualByComparingTo("40.00");
    }

    /**
     * Another organisation with a supplier of the same name: its invoice stays on its own
     * return, and the vendor join is bound to this organisation — a voucher row pointing at
     * the other organisation's vendor (a bad import or a hand edit) does not borrow its name.
     */
    @Test
    void anotherOrganisationsSupplierOfTheSameNameStaysOnItsOwnReturn() {
        UUID mine = fixtures.tenantId();
        UUID ours = vouchers.createDraft(pisr(LocalDate.of(2026, 5, 20), vendor, "GC-1",
                serviceLine("1000.00", "5"))).getId();
        vouchers.post(ours);

        new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService).bootstrap();
        Vendor twin = new Vendor();
        twin.setNameEn("Gulf Cool LLC");
        twin.setTrn("100200300400003");
        twin = vendorService.createVendor(twin);
        vouchers.post(vouchers.createDraft(pisr(LocalDate.of(2026, 5, 20), twin, "GC-1",
                serviceLine("5000.00", "5"))).getId());
        assertThat(box(service.get(Q2), "9").vat()).isEqualByComparingTo("250.00");

        TenantContextHolder.setTenantId(mine);
        assertThat(box(service.get(Q2), "9").vat()).isEqualByComparingTo("50.00");
        jdbc.update("update vouchers set vendor_id = ? where id = ?", twin.getId(), ours);
        assertThat(service.documents(Q2, "9")).singleElement().satisfies(d -> {
            assertThat(d.number()).isEqualTo("GC-1");
            assertThat(d.party()).isNull();
        });
    }
}

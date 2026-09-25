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
}

package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.dto.vat.TaxInvoiceDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tax invoices a VAT tax point issues (product decision 2026-09-24): gapless
 * numbering per tenant and fiscal year, renter isolation (P0), and the PDF.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TaxInvoiceIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired VatTaxPointService vatTaxPoints;
    @Autowired VatTaxPointPoster poster;
    @Autowired TaxInvoiceService taxInvoices;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT = LocalDate.of(2026, 4, 20);
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2027, 4, 30);

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

    private UUID vatLease(Unit unit, Renter renter) {
        return fixtures.postedLease(unit, renter, CONTRACT, START, END, List.of(vatLine("RENT", "120000")), 4, null)
                .lease().getId();
    }

    /**
     * Two tax points of one tenant posted at the same instant, each in its own
     * transaction, take TI-26/1 and TI-26/2 — no duplicate, no gap. The second
     * waits on the sequence row's lock rather than reading the same next value.
     */
    @Test
    void numberingIsGaplessPerTenantAndFiscalYearUnderTwoConcurrentPosts() throws Exception {
        UUID a = vatLease(fixtures.unit(), fixtures.renter());
        UUID b = vatLease(fixtures.createUnit(fixtures.property(), "202"), fixtures.createRenter("Renter B"));
        UUID pointA = vatTaxPoints.scheduleFor(a).get(0).id();
        UUID pointB = vatTaxPoints.scheduleFor(b).get(0).id();
        UUID tenantId = fixtures.tenantId();

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<UUID>> posts = List.of(pointA, pointB).stream().map(id -> pool.submit(() -> {
                TenantContextHolder.setTenantId(tenantId);
                LeaseTestFixtures.authenticateAsTenantAdmin();
                try {
                    go.await();
                    return poster.post(id);
                } finally {
                    TenantContextHolder.clear();
                    LeaseTestFixtures.clearAuth();
                }
            })).toList();
            go.countDown();
            for (Future<UUID> f : posts) f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<String> numbers = jdbc.queryForList(
                "select invoice_number from tax_invoices where tenant_id = ? order by invoice_number", String.class,
                tenantId);
        assertThat(numbers).containsExactly("TI-26/1", "TI-26/2");

        // A third, later, continues the series rather than reusing a number.
        vatTaxPoints.runTo(LocalDate.of(2026, 8, 1), false);
        assertThat(jdbc.queryForList("select invoice_number from tax_invoices where tenant_id = ?", String.class, tenantId)
                .stream().collect(Collectors.toSet()))
                .isEqualTo(Set.of("TI-26/1", "TI-26/2", "TI-26/3", "TI-26/4"));
    }

    /** Each organisation has its own series: the first invoice of a second tenant is TI-26/1 too. */
    @Test
    void theSeriesIsPerTenant() {
        vatLease(fixtures.unit(), fixtures.renter());
        vatTaxPoints.runTo(START, false);
        UUID first = fixtures.tenantId();

        LeaseTestFixtures other = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap().withLeaseServices(leaseService, chequeGeneration, posting);
        other.postedLease(CONTRACT, START, END, List.of(vatLine("RENT", "120000")), 4, null);
        vatTaxPoints.runTo(START, false);

        assertThat(jdbc.queryForObject("select invoice_number from tax_invoices where tenant_id = ?", String.class, first))
                .isEqualTo("TI-26/1");
        assertThat(jdbc.queryForObject("select invoice_number from tax_invoices where tenant_id = ?", String.class,
                other.tenantId())).isEqualTo("TI-26/1");
    }

    /** The PDF: non-empty, and it carries the supplier's TRN, the number and both headings. */
    @Test
    void thePdfCarriesTheTrnAndTheInvoiceNumber() throws Exception {
        UUID leaseId = vatLease(fixtures.unit(), fixtures.renter());
        vatTaxPoints.runTo(START, false);
        TaxInvoiceDTO invoice = taxInvoices.forLease(leaseId).get(0);

        TaxInvoiceService.Pdf pdf = taxInvoices.pdf(invoice.id());

        assertThat(pdf.bytes()).isNotEmpty();
        assertThat(new String(pdf.bytes(), 0, 5)).isEqualTo("%PDF-");
        assertThat(pdf.fileName()).isEqualTo("TI-26-1.pdf");
        try (PDDocument doc = Loader.loadPDF(pdf.bytes())) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains(LeaseTestFixtures.FIXTURE_TRN);
            assertThat(text).contains("TI-26/1");
            assertThat(text).contains("TAX INVOICE");
            assertThat(text).contains("30,000.00").contains("1,500.00").contains("31,500.00");
        }
    }

    // ------------------------------------------------------------------
    // renter isolation (P0)
    // ------------------------------------------------------------------

    /** At the service: renter A asking for renter B's invoice is told it does not exist. */
    @Test
    void aRenterCannotReadAnotherRentersInvoice() {
        Renter renterB = fixtures.createRenter("Renter B");
        UUID a = vatLease(fixtures.unit(), fixtures.renter());
        UUID b = vatLease(fixtures.createUnit(fixtures.property(), "202"), renterB);
        vatTaxPoints.runTo(START, false);
        UUID invoiceOfA = taxInvoices.forLease(a).get(0).id();
        UUID invoiceOfB = taxInvoices.forLease(b).get(0).id();

        asRenter(fixtures.renter());
        assertThat(taxInvoices.pdf(invoiceOfA).bytes()).isNotEmpty();
        assertThatThrownBy(() -> taxInvoices.pdf(invoiceOfB)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> taxInvoices.forLease(b)).isInstanceOf(NotFoundException.class);
        assertThat(taxInvoices.mine()).extracting(TaxInvoiceDTO::id).containsExactly(invoiceOfA);
    }

    /** Over HTTP, the way the portal asks: 200 for your own, 404 for somebody else's. */
    @Test
    void overHttpARenterGetsTheirOwnPdfAndA404ForAnotherRenters() {
        Renter renterB = fixtures.createRenter("Renter B");
        UUID a = vatLease(fixtures.unit(), fixtures.renter());
        UUID b = vatLease(fixtures.createUnit(fixtures.property(), "202"), renterB);
        vatTaxPoints.runTo(START, false);
        UUID invoiceOfA = taxInvoices.forLease(a).get(0).id();
        UUID invoiceOfB = taxInvoices.forLease(b).get(0).id();
        TenantContextHolder.clear();

        ResponseEntity<byte[]> own = getAsRenter(fixtures.renter(), "/api/v1/tax-invoices/" + invoiceOfA + "/pdf");
        assertThat(own.getStatusCode().value()).isEqualTo(200);
        assertThat(new String(own.getBody(), 0, 5)).isEqualTo("%PDF-");

        ResponseEntity<byte[]> theirs = getAsRenter(fixtures.renter(), "/api/v1/tax-invoices/" + invoiceOfB + "/pdf");
        assertThat(theirs.getStatusCode().value()).isIn(403, 404);

        ResponseEntity<byte[]> mine = getAsRenter(fixtures.renter(), "/api/v1/tax-invoices/mine");
        assertThat(mine.getStatusCode().value()).isEqualTo(200);
        String body = new String(mine.getBody());
        assertThat(body).contains(invoiceOfA.toString()).doesNotContain(invoiceOfB.toString());

        // The staff-only VAT schedule is closed to a renter at the role gate.
        assertThat(getAsRenter(fixtures.renter(), "/api/v1/leases/" + a + "/vat-schedule").getStatusCode().value())
                .isEqualTo(403);
    }

    private void asRenter(Renter renter) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                renter.getUserId().toString(), null, List.of(new SimpleGrantedAuthority("ROLE_RENTER"))));
    }

    private ResponseEntity<byte[]> getAsRenter(Renter renter, String path) {
        return RestClient.builder().baseUrl("http://localhost:" + port).build()
                .get().uri(path)
                .header("X-User-Id", renter.getUserId().toString())
                .header("X-User-Role", "RENTER")
                .header("X-Tenant-Id", fixtures.tenantId().toString())
                .header("X-User-Tenant-Id", fixtures.tenantId().toString())
                .retrieve().onStatus(s -> true, (rq, rs) -> { })
                .toEntity(byte[].class);
    }
}

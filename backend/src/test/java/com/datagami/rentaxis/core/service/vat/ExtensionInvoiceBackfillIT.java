package com.datagami.rentaxis.core.service.vat;

import com.datagami.rentaxis.api.dto.lease.ExtendLeaseRequest;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.lease.LeaseRenewalService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Unit;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sim r24 S24-03: extensions of CONTRACT-VAT leases posted before #425 moved Output VAT with
 * no tax point and no invoice. The admin backfill issues the missing invoice dated at the
 * extension's TCO — unless that date is inside a filed VAT return (ruling R11), when it is
 * listed for disclosure and nothing is issued. Idempotent; a dry run writes nothing.
 *
 * <p>The main-era extension is reproduced by running {@code extend} with its tax-point
 * collaborator unset, which is exactly the code before #425.</p>
 */
@SpringBootTest
class ExtensionInvoiceBackfillIT extends AbstractPostgresIT {

    @Autowired ExtensionInvoiceBackfill backfill;
    @Autowired VatReturnService returns;
    @Autowired VatTaxPointService vatTaxPoints;
    @Autowired TaxInvoiceService taxInvoices;
    @Autowired LeaseRenewalService renewal;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired com.datagami.rentaxis.core.service.ledger.PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate Q2 = LocalDate.of(2026, 4, 1);
    private static final LocalDate CONTRACT = LocalDate.of(2026, 4, 20);
    private static final LocalDate START = LocalDate.of(2026, 5, 1);
    private static final LocalDate END = LocalDate.of(2027, 4, 30);
    private static final LocalDate EXT_END = LocalDate.of(2027, 7, 31);

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

    /** A posted CONTRACT-timing VAT lease, 60,000 + VAT. */
    private UUID contractVatLease(String unit, String renter, String firstNumber) {
        Unit shop = fixtures.createUnit(fixtures.property(), unit);
        UUID leaseId = fixtures.draftLease(shop, fixtures.createRenter(renter), CONTRACT, START, END,
                List.of(vatLine("RENT", "60000")));
        jdbc.update("update leases set vat_timing = 'CONTRACT' where id = ?", leaseId);
        fixtures.generateGrid(leaseId, 4, START);
        fixtures.numberGrid(leaseId, firstNumber);
        posting.post(leaseId);
        return leaseId;
    }

    /** {@code extend} as it ran before #425: no tax point, no invoice for the extension's VAT. */
    private void extendAsBefore425(UUID leaseId, LocalDate on) {
        Object target = AopTestUtils.getTargetObject(renewal);
        Object keep = ReflectionTestUtils.getField(target, "vatTaxPoints");
        ReflectionTestUtils.setField(target, "vatTaxPoints", null);
        try {
            renewal.extend(leaseId, new ExtendLeaseRequest(EXT_END, on, List.of(vatLine("RENT", "18000")),
                    List.of(LeaseTestFixtures.chequeRow("18900", LocalDate.of(2027, 5, 1)))));
        } finally {
            ReflectionTestUtils.setField(target, "vatTaxPoints", keep);
        }
    }

    private int extensionPoints(UUID leaseId) {
        return jdbc.queryForObject("""
                select count(*) from vat_tax_points p join journal_entries j on j.id = p.journal_id
                 where p.lease_id = ? and j.narration like 'Extension to %'""", Integer.class, leaseId);
    }

    @Test
    void issuesTheMissingInvoiceOnceAndListsAFiledQuartersExtensionForDisclosure() {
        UUID open = contractVatLease("SH-11", "Shop Eleven", "760001");
        UUID filed = contractVatLease("SH-12", "Shop Twelve", "770001");
        extendAsBefore425(open, null);                          // today: an open quarter
        extendAsBefore425(filed, LocalDate.of(2026, 6, 15));    // Q2, filed below
        assertThat(extensionPoints(open)).as("the pre-#425 state").isZero();
        assertThat(extensionPoints(filed)).isZero();
        // The state S24-03 found: the filed return's documents fall short of its ledger by the
        // undocumented extension's VAT, filed with the difference acknowledged.
        returns.file(Q2, "FTA-S24-03", "Extension VAT without its tax invoice (sim r24 S24-03)", new BigDecimal("900.00"));
        int openBefore = taxInvoices.forLease(open).size();
        int filedBefore = taxInvoices.forLease(filed).size();

        // A dry run says what would happen and writes nothing.
        var dry = backfill.run(true);
        assertThat(dry.candidates()).isEqualTo(2);
        assertThat(dry.items()).extracting(ExtensionInvoiceBackfill.Item::leaseId, ExtensionInvoiceBackfill.Item::outcome)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(open, ExtensionInvoiceBackfill.Outcome.WOULD_ISSUE),
                        org.assertj.core.groups.Tuple.tuple(filed, ExtensionInvoiceBackfill.Outcome.FILED_PERIOD));
        assertThat(extensionPoints(open)).isZero();
        assertThat(taxInvoices.forLease(open)).hasSize(openBefore);

        var run = backfill.run(false);
        assertThat(run.issued()).isEqualTo(1);
        assertThat(run.issuedVat()).isEqualByComparingTo("900.00");
        assertThat(run.filedPeriod()).isEqualTo(1);
        assertThat(run.filedPeriodVat()).isEqualByComparingTo("900.00");
        var issued = taxInvoices.forLease(open);
        assertThat(issued).hasSize(openBefore + 1);
        var inv = issued.stream().filter(i -> i.vatAmount().compareTo(new BigDecimal("900.00")) == 0).findFirst().orElseThrow();
        assertThat(inv.taxableAmount()).isEqualByComparingTo("18000.00");
        LocalDate tcoDate = jdbc.queryForObject("select entry_date from journal_entries where source_id = ?"
                + " and narration like 'Extension to %'", LocalDate.class, open);
        assertThat(inv.issueDate()).as("dated at the extension's tax point").isEqualTo(tcoDate);
        assertThat(run.items()).filteredOn(i -> i.leaseId().equals(open)).singleElement()
                .satisfies(i -> assertThat(i.invoiceNumber()).isEqualTo(inv.invoiceNumber()));
        // R11: nothing dated inside the filed return.
        assertThat(taxInvoices.forLease(filed)).hasSize(filedBefore);
        assertThat(extensionPoints(filed)).isZero();

        // Idempotent: a second run issues nothing and still lists the filed one.
        var again = backfill.run(false);
        assertThat(again.issued()).isZero();
        assertThat(again.items()).extracting(ExtensionInvoiceBackfill.Item::outcome)
                .containsExactly(ExtensionInvoiceBackfill.Outcome.FILED_PERIOD);
        assertThat(taxInvoices.forLease(open)).hasSize(openBefore + 1);
        assertThat(extensionPoints(open)).isEqualTo(1);
    }

    /**
     * Nothing is issued on figures that do not agree: the extension's lines rebuilt to carry
     * other VAT than its entry are listed for review; with no TRN anywhere, listed until added.
     */
    @Test
    void linesThatDisagreeOrAMissingTrnIssueNothing() {
        UUID lease = contractVatLease("SH-15", "Shop Fifteen", "791001");
        extendAsBefore425(lease, null);
        jdbc.update("update lease_lines set gross_amount = 20000, net_amount = 20000 where lease_id = ? and period_end = ?",
                lease, EXT_END);
        var r = backfill.run(false);
        assertThat(r.items()).extracting(ExtensionInvoiceBackfill.Item::outcome)
                .containsExactly(ExtensionInvoiceBackfill.Outcome.LINES_DIFFER);
        assertThat(extensionPoints(lease)).isZero();

        jdbc.update("update lease_lines set gross_amount = 18000, net_amount = 18000 where lease_id = ? and period_end = ?",
                lease, EXT_END);
        jdbc.update("update landlord_org set trn = null where id = ?", fixtures.tenantId());
        jdbc.update("update leases set vat_trn = null where id = ?", lease);
        assertThat(backfill.run(false).items()).extracting(ExtensionInvoiceBackfill.Item::outcome)
                .containsExactly(ExtensionInvoiceBackfill.Outcome.NO_TRN);
        assertThat(extensionPoints(lease)).isZero();
    }

    /** An extension made on today's code already has its invoice: nothing to backfill. */
    @Test
    void anExtensionWithItsInvoiceIsNotACandidate() {
        UUID lease = contractVatLease("SH-13", "Shop Thirteen", "780001");
        renewal.extend(lease, new ExtendLeaseRequest(EXT_END, null, List.of(vatLine("RENT", "18000")),
                List.of(LeaseTestFixtures.chequeRow("18900", LocalDate.of(2027, 5, 1)))));
        assertThat(backfill.run(false).candidates()).isZero();
    }

    /** Tenant-scoped: another organisation's undocumented extension is never seen. */
    @Test
    void anotherOrganisationsExtensionIsNotTouched() {
        UUID lease = contractVatLease("SH-14", "Shop Fourteen", "790001");
        extendAsBefore425(lease, null);
        UUID other = fixtures.tenantId();
        LeaseTestFixtures second = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService).bootstrap();
        assertThat(second.tenantId()).isNotEqualTo(other);
        assertThat(backfill.run(false).candidates()).isZero();
        assertThat(extensionPoints(lease)).isZero();
    }

    @Test
    void needsAnOrganisation() {
        TenantContextHolder.clear();
        assertThatThrownBy(() -> backfill.run(true))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class);
    }
}

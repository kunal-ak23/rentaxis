package com.datagami.rentaxis.core.service.report.tenantledger;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.ledger.LedgerRowDTO;
import com.datagami.rentaxis.api.dto.report.ReceivableAccountDTO;
import com.datagami.rentaxis.api.dto.report.ReportGridDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.PropertyScope;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService.LedgerFilter;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.report.grid.ExportFormat;
import com.datagami.rentaxis.core.service.report.grid.ReportCaps;
import com.datagami.rentaxis.core.service.report.grid.ReportLabels;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.GroupKey;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.GroupRow;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.LineRow;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.PageBounds;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.Scope;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LaterBusinessDayConfig;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.cr;
import static com.datagami.rentaxis.core.service.ledger.PostingRequest.dr;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R1 Tenant Ledger end to end on synthetic data (reports spec §2): the service reads, the grid feeds the writer, and
 * the screen's JSON is what these tests read.
 *
 * <p>Tenant A holds one contract, dated 2026-09-16 for 2026-10-02..2027-10-01: deposit 3,000, rent 60,000 and an
 * admin fee of 500, collected by six post-dated cheques and one transfer, all registered on the contract date.
 * Cheques 1-6 are banked and cleared on their dates, the transfer is received on its date, and cheques 5 and 6
 * bounce after clearing on their dates (2027-04-02 and 2027-06-02). The bank events run on a 2027 timeline, so the
 * suite moves the business day ({@link LaterBusinessDayConfig}).</p>
 */
@Import(LaterBusinessDayConfig.class)
@SpringBootTest
class TenantLedgerReportServiceIT extends AbstractPostgresIT {

    static final LocalDate CONTRACT = D("2026-09-16");
    static final LocalDate START = D("2026-10-02");
    static final LocalDate END = D("2027-10-01");
    static final LocalDate FROM = D("2026-09-01");
    static final LocalDate TO = D("2027-09-30");

    @Autowired TenantLedgerReportService service;
    @Autowired ReportLabels labels;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService grid;
    @Autowired LeasePostingService leasePosting;
    @Autowired ChequeService cheques;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired AccountResolver resolver;
    @Autowired PostingService posting;
    @Autowired LedgerQueryService ledger;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired NamedParameterJdbcTemplate namedJdbc;
    @Autowired PropertyScope propertyScope;
    @Autowired ReportCaps caps;
    @Autowired Clock clock;

    LeaseTestFixtures fx;
    UUID propertyId;
    Renter tenantA;
    UUID leaseA;
    Account rr;
    String rrName;
    String depositName;
    String advanceRentName;
    String adminFeeName;
    String pdcName;
    String bankName;

    @BeforeEach
    void setUp() {
        fx = fixtures();
        propertyId = fx.property().getId();
        tenantA = fx.createRenter("Tenant A");
        leaseA = fx.draftLease(fx.unit(), tenantA, CONTRACT, START, END,
                List.of(line("SECURITY_DEPOSIT", "3000"), line("RENT", "60000"), line("ADMIN_FEE", "500")));
        grid.saveRows(leaseA, List.of(
                pdc(1, "000201", "2026-10-02", "9500", "Rent - 1st Installment"),
                pdc(2, "000202", "2026-10-02", "3000", "Security Deposit"),
                pdc(3, "000203", "2026-12-02", "10200", "Rent - 2nd Installment"),
                pdc(4, "000204", "2027-02-02", "10200", "Rent - 3rd Installment"),
                pdc(5, "000205", "2027-04-02", "10200", "Rent - 4th Installment"),
                pdc(6, "000206", "2027-06-02", "10200", "Rent - 5th Installment"),
                new ChequeRowInput(null, 7, CONTRACT, null, D("2027-08-02"), null, null, null,
                        new BigDecimal("10200"), "Rent - 6th Installment", ChequeMode.TRANSFER)));
        leasePosting.post(leaseA);

        List<ChequeDTO> rows = grid.list(leaseA);
        assertThat(rows).hasSize(7);
        for (int i = 0; i < 6; i++) {
            ChequeDTO c = rows.get(i);
            cheques.deposit(c.id(), ChequeActionRequest.on(c.chequeDate()));
            cheques.clear(c.id(), ChequeActionRequest.on(c.chequeDate()));
        }
        cheques.receive(rows.get(6).id(), ChequeActionRequest.on(rows.get(6).chequeDate()));
        for (int i = 4; i < 6; i++) {
            ChequeDTO c = rows.get(i);
            cheques.bounce(c.id(), new ChequeActionRequest(c.chequeDate(), null, ChequeFailureReason.BOUNCE, null));
        }

        rr = resolver.resolve(AccountRole.RENT_RECEIVABLE, propertyId);
        rrName = rr.getName();
        depositName = resolver.resolve(AccountRole.SECURITY_DEPOSIT, propertyId).getName();
        advanceRentName = resolver.resolve(AccountRole.ADVANCE_RENT, propertyId).getName();
        adminFeeName = resolver.resolve(AccountRole.ADMIN_FEE, propertyId).getName();
        pdcName = resolver.resolve(AccountRole.PDC_RECEIVABLE, propertyId).getName();
        bankName = resolver.resolve(AccountRole.BANK, propertyId).getName();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void oneLeaseReadsLikePactsTenantLedger() {
        ReportGridDTO g = json(filter(FROM, TO));
        assertThat(bands(g)).containsExactly("ACCOUNT|" + rrName, "PARTY|Tenant Name : Tenant A");
        List<Map<String, String>> r = rows(g);
        assertThat(r).hasSize(12);
        assertThat(r).extracting(c -> c.get("particular")).startsWith(depositName, advanceRentName, adminFeeName);
        assertThat(r.subList(3, 10)).allSatisfy(c -> assertThat(c.get("particular")).isEqualTo(pdcName));
        assertThat(r.subList(10, 12)).allSatisfy(c -> assertThat(c.get("particular")).isEqualTo(bankName));
        assertThat(r).extracting(c -> c.get("balance")).containsExactly("3000.00", "63000.00", "63500.00", "54000.00",
                "51000.00", "40800.00", "30600.00", "20400.00", "10200.00", "0.00", "10200.00", "20400.00");
        assertThat(r.subList(3, 10)).extracting(c -> c.get("chqNo"))
                .containsExactly("000201", "000202", "000203", "000204", "000205", "000206", "TT");
        assertThat(r.get(9)).containsEntry("payeeBank", "TRANSFER").containsEntry("convertedDate", "2027-08-02");
        assertThat(r.get(3)).containsEntry("payeeBank", "Emirates NBD").containsEntry("convertedDate", "2026-10-02");
        assertThat(r.get(10)).containsEntry("chqNo", "000205").doesNotContainKey("convertedDate");
        assertThat(r).extracting(c -> c.get("bdDays"))
                .containsExactly(null, null, null, null, null, null, null, "1", "1", null, "1", "1");
        assertThat(r.get(0)).containsEntry("docDate", "2026-09-16").containsEntry("debit", "3000.00")
                .doesNotContainKey("credit").doesNotContainKey("chqNo");
        assertThat(r.get(0).get("docNo")).startsWith("TCO");
        assertThat(openings(g)).containsExactly("0.00");
        assertThat(subtotal(g, 2)).containsEntry("debit", "83900.00").containsEntry("credit", "63500.00")
                .containsEntry("balance", "20400.00");
        assertThat(g.items()).noneMatch(i -> i.type().equals("subtotal") && i.level() == 1);   // one tenant: no account Sub Total
        assertThat(subtotal(g, 2)).doesNotContainKey("bdDays");
        assertThat(total(g)).containsEntry("debit", "83900.00").containsEntry("credit", "63500.00")
                .containsEntry("balance", "20400.00");

        // Drill-down ids and the title band.
        ReportGridDTO.Item first = g.items().stream().filter(i -> i.type().equals("row")).findFirst().orElseThrow();
        assertThat(first.refs()).containsKeys("entryId", "leaseId").doesNotContainKey("chequeId");
        assertThat(first.refs().get("leaseId")).isEqualTo(leaseA.toString());
        assertThat(g.meta().title()).isEqualTo("Tenant Ledger");
        assertThat(g.meta().reportKey()).isEqualTo(TenantLedgerGrid.KEY);
        assertThat(g.meta().lines()).contains("01/09/2026 – 30/09/2027", "Accounts: All receivable accounts");
        assertThat(g.meta().generated()).startsWith("Generated ").endsWith(" by it");
        assertThat(g.page().pageCount()).isEqualTo(1);
        assertThat(g.page().totalRows()).isEqualTo(12);
        assertThat(g.page().totalGroups()).isEqualTo(1);
    }

    @Test
    void bdDaysFollowsTheAsOfRule() {
        assertThat(rows(json(filter(FROM, D("2027-04-01"))))).allSatisfy(c -> assertThat(c).doesNotContainKey("bdDays"));
        assertThat(rows(json(filter(FROM, D("2027-05-01"))))).filteredOn(c -> "1".equals(c.get("bdDays"))).hasSize(2);
    }

    @Test
    void twoTenantsMidWindowHaveTheirOwnOpeningsAndAnAccountSubTotal() {
        addTenantB();
        ReportGridDTO g = json(filter(D("2027-05-01"), TO));
        assertThat(bands(g)).containsExactly("ACCOUNT|" + rrName, "PARTY|Tenant Name : Tenant A", "PARTY|Tenant Name : Tenant B");
        assertThat(openings(g)).containsExactly("10200.00", "6000.00");
        assertThat(subtotal(g, 1)).containsEntry("debit", "10200.00").containsEntry("balance", "26400.00");
        assertThat(subtotalLabel(g, 1)).isEqualTo("Sub Total (" + rrName + ")");
        assertThat(total(g)).containsEntry("balance", "26400.00");
    }

    @Test
    void aRenterlessJvIsTheNoTenantGroupAndTheAccountTiesToTheLedgerAndTrialBalance() {
        posting.post(new PostingRequest(JournalDocType.JV, D("2027-05-10"), "Manual", Dimensions.ofProperty(propertyId),
                JournalSourceType.MANUAL, null, null,
                List.of(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("750")), cr(AccountRole.CASH, new BigDecimal("750")))));

        ReportGridDTO g = json(filter(D("2027-05-01"), TO));
        assertThat(bands(g)).last().isEqualTo("PARTY|Tenant Name : No tenant");
        BigDecimal closing = new BigDecimal(subtotal(g, 1).get("balance"));          // Tenant A 20,400 + No tenant 750
        assertThat(closing).isEqualByComparingTo("21150.00");
        assertThat(closing).isEqualByComparingTo(read(() -> ledger.accountLedger(rr.getId(),
                new LedgerFilter(D("2027-05-01"), TO, null, null, null, null)).closingBalance()));
        assertThat(closing).isEqualByComparingTo(read(() -> ledger.trialBalance(TO, null)).stream()
                .filter(t -> t.accountId().equals(rr.getId())).findFirst().orElseThrow().balance());
    }

    @Test
    void aLeaseWithAReceivableOverrideFormsItsOwnAccountBand() {
        Account override = accountService.createLeaf("Rent Receivable B - " + fx.propertyName(),
                accountService.getAccountByCode("A-02-01"), propertyId);
        Renter tenantC = fx.createRenter("Tenant C");
        Unit unitC = fx.createUnit(fx.property(), "103");
        UUID leaseC = fx.draftLease(unitC, tenantC, CONTRACT, START, END, List.of(line("RENT", "12000")));
        fx.generateGrid(leaseC, 2, START);
        new TransactionTemplate(txManager).executeWithoutResult(s -> {
            Lease l = leaseRepo.findById(leaseC).orElseThrow();
            l.setReceivableAccountId(override.getId());
            leaseRepo.save(l);
        });
        leasePosting.post(leaseC);

        assertThat(bands(json(filter(FROM, TO)))).filteredOn(b -> b.startsWith("ACCOUNT|"))
                .containsExactlyInAnyOrder("ACCOUNT|" + rrName, "ACCOUNT|" + override.getName());
        assertThat(service.receivableAccounts(propertyId)).extracting(ReceivableAccountDTO::id)
                .containsExactlyInAnyOrder(rr.getId(), override.getId());
    }

    @Test
    void hideReversedDropsBothLegsAcrossTheWindowEdge() {
        UUID jv = posting.post(new PostingRequest(JournalDocType.JV, D("2027-04-20"), "Manual",
                new Dimensions(propertyId, fx.unit().getId(), leaseA, tenantA.getId(), null), JournalSourceType.MANUAL, null, null,
                List.of(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("1000")), cr(AccountRole.CASH, new BigDecimal("1000"))))).getId();
        posting.reverse(jv, D("2027-05-05"), "test");

        ReportGridDTO shown = json(filter(D("2027-05-01"), TO));
        ReportGridDTO hidden = json(filterHideReversed(D("2027-05-01"), TO));
        assertThat(openings(shown).get(0)).isEqualTo("11200.00");
        assertThat(openings(hidden).get(0)).isEqualTo("10200.00");
        assertThat(rows(hidden)).hasSize(rows(shown).size() - 1);
        assertThat(subtotal(hidden, 2).get("balance")).isEqualTo(subtotal(shown, 2).get("balance"));
        assertThat(hidden.meta().lines()).anyMatch(l -> l.contains("Reversed pairs hidden: Yes"));
    }

    @Test
    void runningBalancesMatchTheAccountLedgerRowForRow() {
        // Same-day ties: the TCO and all seven PDRs are on 2026-09-16.
        List<String> ours = rows(json(filterRenter(tenantA, FROM, TO))).stream().map(c -> c.get("balance")).toList();
        List<String> gl = read(() -> ledger.accountLedger(rr.getId(), new LedgerFilter(FROM, TO, null, null, null, tenantA.getId())))
                .rows().stream().map(LedgerRowDTO::balance).map(b -> b.setScale(2).toPlainString()).toList();
        assertThat(ours).hasSize(12).isEqualTo(gl);
    }

    @Test
    void pagesSplitAGroupAndCarryTheBalanceForward() {
        addTenantB();
        TenantLedgerFilter whole = filter(FROM, TO);
        // A has 12 rows, B 4: pages A1-5, A6-10, A11-12, B1-4.
        List<ReportGridDTO> pages = IntStream.range(0, 4)
                .mapToObj(p -> TenantLedgerGrid.json(service.page(whole, p, 5, "it"), labels)).toList();
        ReportGridDTO all = json(whole);

        assertThat(pages.get(0).page().pageCount()).isEqualTo(4);
        assertThat(pages).extracting(p -> total(p)).allSatisfy(t -> assertThat(t).isEqualTo(total(all)));
        ReportGridDTO p1 = pages.get(1);
        assertThat(p1.items().get(0)).satisfies(i -> {
            assertThat(i.continued()).isTrue();
            assertThat(i.band()).isEqualTo("ACCOUNT");
        });
        assertThat(p1.items().get(1).label()).isEqualTo("Tenant Name : Tenant A (continued)");
        assertThat(p1.items().get(2).carriedForward()).isTrue();
        assertThat(p1.items().get(2).cells().get("balance")).isEqualTo(rows(pages.get(0)).get(4).get("balance"));
        assertThat(pages.stream().flatMap(p -> rows(p).stream()).map(c -> c.get("docNo") + c.get("balance")).toList())
                .isEqualTo(rows(all).stream().map(c -> c.get("docNo") + c.get("balance")).toList());
        assertThat(pages.get(1).items()).noneMatch(i -> i.type().equals("subtotal"));
        assertThat(pages.get(2).items()).anyMatch(i -> i.type().equals("subtotal") && i.level() == 2);
        assertThat(pages.get(3).items()).anyMatch(i -> i.type().equals("subtotal") && i.level() == 1);
        assertThat(pages.get(1).page().continuesOnNextPage()).isTrue();
        assertThat(pages.get(2).page().continuesOnNextPage()).isTrue();
        assertThat(pages.get(3).page().continuesOnNextPage()).isFalse();
        assertThat(pages).extracting(p -> p.page().page()).containsExactly(0, 1, 2, 3);

        // Page 3 opens Tenant B under the continued account band, with B's own brought-forward row.
        ReportGridDTO p3 = pages.get(3);
        assertThat(p3.items().get(0)).satisfies(i -> assertThat(i.continued()).isTrue());
        assertThat(p3.items().get(1).label()).isEqualTo("Tenant Name : Tenant B");
        assertThat(p3.items().get(2).carriedForward()).isFalse();

        // A page past the end reads as the last page.
        assertThat(TenantLedgerGrid.json(service.page(whole, 99, 5, "it"), labels).page().page()).isEqualTo(3);
    }

    @Test
    void pageTwoOfALongGroupReadsOnlyItsRowsAndCarriesTheBalance() {
        // Tenant A's 12 rows at five a page: page 1 (0-based) is rows 6-10, and only those leave the database.
        List<LineRow> fromSql = new ArrayList<>();
        TenantLedgerQueries recording = new TenantLedgerQueries(namedJdbc) {
            @Override
            public List<LineRow> lines(Scope s, Collection<GroupKey> only, PageBounds bounds) {
                List<LineRow> rows = super.lines(s, only, bounds);
                fromSql.addAll(rows);
                return rows;
            }
        };
        TenantLedgerReportService paged = transactional(new TenantLedgerReportService(recording, propertyScope, labels, caps, clock));

        TenantLedgerResult second = paged.page(filter(FROM, TO), 1, 5, "it");
        assertThat(fromSql).extracting(LineRow::rn).containsExactly(6, 7, 8, 9, 10);
        assertThat(second.lines()).extracting(TenantLedgerResult.Line::rn).containsExactly(6, 7, 8, 9, 10);

        List<Map<String, String>> whole = rows(json(filter(FROM, TO)));
        ReportGridDTO g = TenantLedgerGrid.json(second, labels);
        ReportGridDTO.Item carried = g.items().stream().filter(i -> i.type().equals("opening")).findFirst().orElseThrow();
        assertThat(carried.carriedForward()).isTrue();
        assertThat(carried.cells().get("balance")).isEqualTo(whole.get(4).get("balance")).isEqualTo("51000.00");
        assertThat(rows(g)).extracting(c -> c.get("docNo") + c.get("balance"))
                .isEqualTo(whole.subList(5, 10).stream().map(c -> c.get("docNo") + c.get("balance")).toList());
    }

    @Test
    void arabicUsesArabicNamesAndBands() {
        tenantA.setNameAr("المستأجر أ");
        renterRepo.save(tenantA);

        ReportGridDTO g = json(new TenantLedgerFilter(FROM, TO, null, List.of(), null, null, false, false, "ar"));
        assertThat(g.meta().lang()).isEqualTo("ar");
        assertThat(g.meta().title()).isEqualTo("دفتر أستاذ المستأجر");
        assertThat(bands(g)).containsExactly("ACCOUNT|" + arOrEn(rr), "PARTY|اسم المستأجر : المستأجر أ");
        assertThat(rows(g).get(0).get("particular")).isEqualTo(arOrEn(resolver.resolve(AccountRole.SECURITY_DEPOSIT, propertyId)));
        assertThat(rows(g).get(3).get("particular")).isEqualTo(arOrEn(resolver.resolve(AccountRole.PDC_RECEIVABLE, propertyId)));
        assertThat(total(g)).containsEntry("balance", "20400.00");
        assertThat(TenantLedgerGrid.filename(service.export(new TenantLedgerFilter(FROM, TO, null, List.of(), null, null,
                false, false, "ar"), ExportFormat.PDF, "it"), ExportFormat.PDF)).isEqualTo("tenant-ledger-2026-09-01-2027-09-30-ar.pdf");

        // Tenants follow the name the report prints: in English A before B, in Arabic B (ب) before A (ي).
        // "No tenant" stays last in both.
        tenantA.setNameAr("المستأجر ي");
        renterRepo.save(tenantA);
        Renter tenantB = addTenantB();
        tenantB.setNameAr("المستأجر ب");
        renterRepo.save(tenantB);
        posting.post(new PostingRequest(JournalDocType.JV, D("2027-05-10"), "Manual", Dimensions.ofProperty(propertyId),
                JournalSourceType.MANUAL, null, null,
                List.of(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("750")), cr(AccountRole.CASH, new BigDecimal("750")))));
        assertThat(bands(json(new TenantLedgerFilter(FROM, TO, null, List.of(), null, null, false, false, "ar"))))
                .containsExactly("ACCOUNT|" + arOrEn(rr), "PARTY|اسم المستأجر : المستأجر ب",
                        "PARTY|اسم المستأجر : المستأجر ي", "PARTY|اسم المستأجر : بدون مستأجر");
        assertThat(bands(json(filter(FROM, TO)))).containsExactly("ACCOUNT|" + rrName, "PARTY|Tenant Name : Tenant A",
                "PARTY|Tenant Name : Tenant B", "PARTY|Tenant Name : No tenant");
    }

    @Test
    void everyFormatRendersTheSameReport() {
        TenantLedgerResult r = service.export(filter(FROM, TO), ExportFormat.XLSX, "it");
        for (ExportFormat f : ExportFormat.values()) {
            byte[] bytes = TenantLedgerGrid.file(r, f, labels, 50_000);
            assertThat(bytes).as(f.name()).isNotEmpty();
        }
        assertThat(new String(TenantLedgerGrid.file(r, ExportFormat.CSV, labels, 50_000), java.nio.charset.StandardCharsets.UTF_8))
                .contains("Tenant Name : Tenant A").contains("000201");
        assertThat(TenantLedgerGrid.filename(r, ExportFormat.XLSX)).isEqualTo("tenant-ledger-2026-09-01-2027-09-30.xlsx");
    }

    @Test
    void anEmptySelectionIsAValidReportWithZeroTotals() {
        // A property with no receivable lines: no groups at all, yet the report still ends with a zero REPORT TOTAL.
        Property empty = fx.createProperty("EMP");
        ReportGridDTO g = json(filterProperty(empty.getId()));
        assertThat(g.items()).singleElement().satisfies(i -> {
            assertThat(i.type()).isEqualTo("total");
            assertThat(i.cells()).containsEntry("debit", "0.00").containsEntry("credit", "0.00").containsEntry("balance", "0.00");
        });
        assertThat(g.page().totalGroups()).isZero();
        assertThat(TenantLedgerGrid.json(service.page(filterProperty(empty.getId()), 0, 200, "it"), labels).items())
                .singleElement().satisfies(i -> assertThat(i.type()).isEqualTo("total"));
    }

    @Test
    void anotherOrganisationWithIdenticalNamesNeverLeaks() {
        UUID orgA = fx.tenantId();
        int groupsBeforeOrgB = service.export(filter(FROM, TO), ExportFormat.XLSX, "it").totalGroups();

        // Org B: a property with the same English name, a renter "Tenant A", the same contract shape, posted.
        LeaseTestFixtures fxB = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .withLeaseServices(leaseService, grid, leasePosting);
        UUID orgB = fxB.newTenant();
        fxB.asTenantAdmin();
        fxB.seedAccounting();
        Property p = new Property();
        p.setNameEn(fx.propertyName());
        p.setEmirate(Emirate.DUBAI);
        p.setCode("ORGB_" + UUID.randomUUID().toString().substring(0, 4));
        p.setTenantId(orgB);
        Property orgBProperty = propertyService.createProperty(p);
        Unit orgBUnit = fxB.createUnit(orgBProperty, "101");
        Renter orgBRenter = fxB.createRenter("Tenant A");
        UUID orgBLease = fxB.postedLease(orgBUnit, orgBRenter, CONTRACT, START, END,
                List.of(line("SECURITY_DEPOSIT", "3000"), line("RENT", "60000"), line("ADMIN_FEE", "500")), 4, null).lease().getId();
        UUID orgBRr = resolver.resolve(AccountRole.RENT_RECEIVABLE, orgBProperty.getId()).getId();
        assertThat(orgB).isNotEqualTo(orgA);

        TenantContextHolder.setTenantId(orgA);
        fx.asTenantAdmin();
        TenantLedgerResult r = service.export(filter(FROM, TO), ExportFormat.XLSX, "it");
        Set<UUID> orgAEntries = Set.copyOf(jdbc.queryForList("select id from journal_entries where tenant_id = ?", UUID.class, orgA));
        assertThat(r.lines()).isNotEmpty().allSatisfy(l -> assertThat(orgAEntries).contains(l.entryId()));
        assertThat(r.totalGroups()).isEqualTo(groupsBeforeOrgB);
        assertThat(r.accounts()).extracting(TenantLedgerResult.AccountGroup::accountId).doesNotContain(orgBRr);
        assertThat(service.receivableAccounts(null)).extracting(ReceivableAccountDTO::id).doesNotContain(orgBRr);

        for (TenantLedgerFilter foreign : List.of(filterProperty(orgBProperty.getId()), filterRenter(orgBRenter.getId()),
                filterLease(orgBLease), filterAccounts(orgBRr))) {
            assertThatThrownBy(() -> service.export(foreign, ExportFormat.XLSX, "it")).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> service.page(foreign, 0, 200, "it")).isInstanceOf(NotFoundException.class);
        }
        assertThatThrownBy(() -> service.receivableAccounts(orgBProperty.getId())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aPostingCommittedBetweenTheGroupsAndTheLinesReadIsNotSeen() {
        // The groups (SQL totals, row counts) and the lines are two statements. A posting to Tenant A's receivable
        // that commits between them must stay out of the lines, or the writer's totals check fails (a 500) and a
        // page's span no longer lines up with the rows read: both reads have to see one snapshot.
        TransactionTemplate elsewhere = new TransactionTemplate(txManager);
        elsewhere.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        AtomicBoolean committed = new AtomicBoolean();
        TenantLedgerQueries racing = new TenantLedgerQueries(namedJdbc) {
            @Override
            public List<GroupRow> groups(Scope s) {
                List<GroupRow> groups = super.groups(s);
                if (committed.compareAndSet(false, true)) {
                    elsewhere.executeWithoutResult(st -> posting.post(new PostingRequest(JournalDocType.JV, D("2027-05-10"),
                            "Manual", new Dimensions(propertyId, fx.unit().getId(), leaseA, tenantA.getId(), null),
                            JournalSourceType.MANUAL, null, null, List.of(dr(AccountRole.RENT_RECEIVABLE, new BigDecimal("750")),
                            cr(AccountRole.CASH, new BigDecimal("750"))))));
                }
                return groups;
            }
        };
        TenantLedgerReportService racy = transactional(new TenantLedgerReportService(racing, propertyScope, labels, caps, clock));

        TenantLedgerResult r = racy.export(filter(FROM, TO), ExportFormat.XLSX, "it");
        assertThat(committed).isTrue();
        assertThat(r.lines()).hasSize(12);
        ReportGridDTO g = TenantLedgerGrid.json(r, labels);          // the writer checks the lines against the SQL totals
        assertThat(total(g)).containsEntry("debit", "83900.00").containsEntry("balance", "20400.00");
        assertThat(total(json(filter(FROM, TO)))).containsEntry("debit", "84650.00");   // the posting did commit
    }

    // ------------------------------------------------------------------

    private LeaseTestFixtures fixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, grid, leasePosting);
    }

    /** Tenant B on unit "102": rent 12,000 in two cheques from 2026-10-02; the first banked, cleared and bounced that day. */
    private Renter addTenantB() {
        Renter tenantB = fx.createRenter("Tenant B");
        Unit unitB = fx.createUnit(fx.property(), "102");
        UUID leaseB = fx.draftLease(unitB, tenantB, CONTRACT, START, END, List.of(line("RENT", "12000")));
        fx.generateGrid(leaseB, 2, START);
        leasePosting.post(leaseB);
        ChequeDTO first = grid.list(leaseB).get(0);
        cheques.deposit(first.id(), ChequeActionRequest.on(first.chequeDate()));
        cheques.clear(first.id(), ChequeActionRequest.on(first.chequeDate()));
        cheques.bounce(first.id(), new ChequeActionRequest(first.chequeDate(), null, ChequeFailureReason.BOUNCE, null));
        return tenantB;
    }

    private static ChequeRowInput pdc(int seq, String number, String chequeDate, String amount, String narration) {
        return new ChequeRowInput(null, seq, CONTRACT, number, D(chequeDate), "Emirates NBD", null, null,
                new BigDecimal(amount), narration, ChequeMode.PDC);
    }

    private <T> T read(java.util.function.Supplier<T> q) {
        TransactionTemplate t = new TransactionTemplate(txManager);
        t.setReadOnly(true);
        return t.execute(s -> q.get());
    }

    /** {@code target} behind a transaction proxy built from its own {@code @Transactional}, as Spring builds the bean's. */
    private TenantLedgerReportService transactional(TenantLedgerReportService target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor((TransactionManager) txManager, new AnnotationTransactionAttributeSource()));
        return (TenantLedgerReportService) factory.getProxy();
    }

    private ReportGridDTO json(TenantLedgerFilter f) {
        return TenantLedgerGrid.json(service.export(f, ExportFormat.XLSX, "it"), labels);
    }

    static LocalDate D(String s) {
        return LocalDate.parse(s);
    }

    static TenantLedgerFilter filter(LocalDate from, LocalDate to) {
        return new TenantLedgerFilter(from, to, null, List.of(), null, null, false, false, "en");
    }

    static TenantLedgerFilter filterHideReversed(LocalDate from, LocalDate to) {
        return new TenantLedgerFilter(from, to, null, List.of(), null, null, false, true, "en");
    }

    static TenantLedgerFilter filterRenter(Renter renter, LocalDate from, LocalDate to) {
        return new TenantLedgerFilter(from, to, null, List.of(), renter.getId(), null, false, false, "en");
    }

    static TenantLedgerFilter filterRenter(UUID renterId) {
        return new TenantLedgerFilter(FROM, TO, null, List.of(), renterId, null, false, false, "en");
    }

    static TenantLedgerFilter filterProperty(UUID propertyId) {
        return new TenantLedgerFilter(FROM, TO, propertyId, List.of(), null, null, false, false, "en");
    }

    static TenantLedgerFilter filterLease(UUID leaseId) {
        return new TenantLedgerFilter(FROM, TO, null, List.of(), null, leaseId, false, false, "en");
    }

    static TenantLedgerFilter filterAccounts(UUID accountId) {
        return new TenantLedgerFilter(FROM, TO, null, List.of(accountId), null, null, false, false, "en");
    }

    static List<String> bands(ReportGridDTO g) {
        return g.items().stream().filter(i -> i.type().equals("band")).map(i -> i.band() + "|" + i.label()).toList();
    }

    static List<String> openings(ReportGridDTO g) {
        return g.items().stream().filter(i -> i.type().equals("opening")).map(i -> i.cells().get("balance")).toList();
    }

    static Map<String, String> subtotal(ReportGridDTO g, int level) {
        return firstSubtotal(g, level).cells();
    }

    static String subtotalLabel(ReportGridDTO g, int level) {
        return firstSubtotal(g, level).label();
    }

    static ReportGridDTO.Item firstSubtotal(ReportGridDTO g, int level) {
        return g.items().stream().filter(i -> i.type().equals("subtotal") && i.level() == level).findFirst().orElseThrow();
    }

    static Map<String, String> total(ReportGridDTO g) {
        return g.items().get(g.items().size() - 1).cells();
    }

    static List<Map<String, String>> rows(ReportGridDTO g) {
        return g.items().stream().filter(i -> i.type().equals("row")).map(ReportGridDTO.Item::cells).toList();
    }

    static String arOrEn(Account a) {
        return a.getNameAr() == null || a.getNameAr().isBlank() ? a.getName() : a.getNameAr();
    }
}

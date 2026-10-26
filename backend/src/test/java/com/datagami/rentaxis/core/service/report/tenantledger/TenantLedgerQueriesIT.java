package com.datagami.rentaxis.core.service.report.tenantledger;

import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.dto.report.ReceivableAccountDTO;
import com.datagami.rentaxis.core.security.PropertyScope;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.CounterRow;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.GroupKey;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.GroupRow;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.LineRow;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.NamedRef;
import com.datagami.rentaxis.core.service.report.tenantledger.TenantLedgerQueries.Scope;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.cr;
import static com.datagami.rentaxis.core.service.ledger.PostingRequest.dr;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenant ledger's read model (reports spec §2): per (account, renter) openings, window sums and row counts
 * (the authoritative totals), the lines in the one ledger ordering with a running window movement and the
 * cheque columns, the counter-account names of unpaired lines, and every lookup bound to the caller's tenant.
 *
 * <p>Synthetic data only: one contract (deposit 3,000, rent 60,000 in four instalments, admin fee 500) for
 * "Tenant A", contract-dated 2026-09-16, so its TCO and its PDRs all sit on that one day and net to zero.</p>
 */
@SpringBootTest
class TenantLedgerQueriesIT extends AbstractPostgresIT {

    static final LocalDate CONTRACT = LocalDate.of(2026, 9, 16);
    static final LocalDate START = LocalDate.of(2026, 10, 2);
    static final LocalDate END = LocalDate.of(2027, 10, 1);
    static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    static final LocalDate TO = LocalDate.of(2027, 9, 30);
    static final LocalDate OCT_5 = LocalDate.of(2026, 10, 5);
    static final LocalDate OCT_6 = LocalDate.of(2026, 10, 6);
    static final PropertyScope.Selection ALL = new PropertyScope.Selection(true, List.of(), false);

    @Autowired TenantLedgerQueries queries;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeasePostingService leasePosting;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired AccountResolver resolver;
    @Autowired PostingService posting;
    @Autowired PlatformTransactionManager txManager;

    LeaseTestFixtures fx;
    Renter tenantA;
    PostLeaseResponse contract;
    UUID propertyId;
    UUID rr;
    TransactionTemplate readOnly;

    @BeforeEach
    void setUp() {
        readOnly = new TransactionTemplate(txManager);
        readOnly.setReadOnly(true);
        fx = fixtures();
        tenantA = fx.createRenter("Tenant A");
        contract = postContract(fx, tenantA);
        propertyId = fx.property().getId();
        rr = resolver.resolve(AccountRole.RENT_RECEIVABLE, propertyId).getId();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void groupsCarryOpeningsWindowSumsAndRowCounts() {
        List<GroupRow> whole = read(() -> queries.groups(scope().build()));
        assertThat(whole).singleElement().satisfies(g -> {
            assertThat(g.key()).isEqualTo(new GroupKey(rr, tenantA.getId()));
            assertThat(g.renterName()).isEqualTo("Tenant A");
            assertThat(g.accountCode()).isNotBlank();
            assertThat(g.accountName()).isNotBlank();
            assertThat(g.opening()).isEqualByComparingTo("0");
            assertThat(g.debit()).isEqualByComparingTo("63500");
            assertThat(g.credit()).isEqualByComparingTo("63500");
            assertThat(g.rowCount()).isEqualTo(3 + contract.cheques().size());
        });

        // From the day after the contract: the contract nets to zero before the window and nothing moves inside it.
        assertThat(read(() -> queries.groups(scope().from(CONTRACT.plusDays(1)).build()))).isEmpty();

        // The property selection: naming the property keeps the group, another property or "assigned nothing" drops it.
        assertThat(read(() -> queries.groups(scope().properties(new PropertyScope.Selection(false, List.of(propertyId), false)).build())))
                .hasSize(1);
        assertThat(read(() -> queries.groups(scope().properties(new PropertyScope.Selection(false, List.of(UUID.randomUUID()), false)).build())))
                .isEmpty();
        assertThat(read(() -> queries.groups(scope().properties(new PropertyScope.Selection(false, List.of(), true)).build())))
                .isEmpty();
        // No accounts at all is an empty answer, never an empty IN list.
        assertThat(read(() -> queries.groups(scope().accounts(List.of()).build()))).isEmpty();
    }

    @Test
    void aRenterlessReceivableLineIsItsOwnGroupListedLast() {
        manualJv(Dimensions.ofProperty(propertyId), dr(AccountRole.RENT_RECEIVABLE, bd("750")), cr(AccountRole.CASH, bd("750")));

        List<GroupRow> groups = read(() -> queries.groups(scope().build()));
        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).renterId()).isEqualTo(tenantA.getId());
        GroupRow noTenant = groups.get(1);
        assertThat(noTenant.renterId()).isNull();
        assertThat(noTenant.renterName()).isNull();
        assertThat(noTenant.key()).isEqualTo(new GroupKey(rr, null));
        assertThat(noTenant.debit()).isEqualByComparingTo("750");
        assertThat(noTenant.credit()).isEqualByComparingTo("0");
        assertThat(noTenant.rowCount()).isEqualTo(1);
    }

    @Test
    void linesFollowTheLedgerOrderWithAWindowMovementAndChequeColumns() {
        List<LineRow> lines = read(() -> queries.lines(scope().build(), null));
        int n = 3 + contract.cheques().size();
        assertThat(lines).hasSize(n);
        assertThat(lines).extracting(LineRow::rn).containsExactlyElementsOf(IntStream.rangeClosed(1, n).boxed().toList());
        assertThat(lines).extracting(LineRow::key).containsOnly(new GroupKey(rr, tenantA.getId()));
        assertThat(lines.get(2).movement()).isEqualByComparingTo("63500");
        assertThat(lines.get(n - 1).movement()).isEqualByComparingTo("0");

        UUID leaseId = contract.lease().getId();
        assertThat(lines.subList(0, 3)).allSatisfy(l -> {
            assertThat(l.docType()).isEqualTo("TCO");
            assertThat(l.entryId()).isEqualTo(contract.tcoJournalId());
            assertThat(l.entryDate()).isEqualTo(CONTRACT);
            assertThat(l.debit()).isPositive();
            assertThat(l.leaseId()).isEqualTo(leaseId);
            assertThat(l.contraName()).isNotBlank();
            assertThat(l.chequeId()).isNull();
            assertThat(l.chequeNumber()).isNull();
            assertThat(l.payeeBank()).isNull();
            assertThat(l.chequeDate()).isNull();
            assertThat(l.pdrJournalId()).isNull();
            assertThat(l.cbrJournalId()).isNull();
        });

        Map<UUID, ChequeDTO> cheques = contract.cheques().stream().collect(Collectors.toMap(ChequeDTO::id, Function.identity()));
        List<LineRow> pdrs = lines.subList(3, n);
        assertThat(pdrs).allSatisfy(l -> {
            ChequeDTO c = cheques.get(l.chequeId());
            assertThat(c).as("PDR row carries its cheque").isNotNull();
            assertThat(l.docType()).isEqualTo("PDR");
            assertThat(l.credit()).isEqualByComparingTo(c.amount());
            assertThat(l.chequeNumber()).isEqualTo(c.chequeNumber());
            assertThat(l.chequeMode()).isEqualTo(c.mode().name());
            assertThat(l.payeeBank()).isEqualTo("Emirates NBD");
            assertThat(l.chequeDate()).isEqualTo(c.chequeDate());
            assertThat(l.pdrJournalId()).isEqualTo(l.entryId());
            assertThat(l.cbrJournalId()).isNull();
            assertThat(l.bouncedAt()).isNull();
        });
        assertThat(pdrs).extracting(LineRow::chequeNumber)
                .containsExactlyInAnyOrderElementsOf(contract.cheques().stream().map(ChequeDTO::chequeNumber).toList());

        // The contract filter: this lease keeps every row, any other lease none.
        assertThat(read(() -> queries.lines(scope().lease(leaseId).build(), null))).hasSize(n);
        assertThat(read(() -> queries.lines(scope().lease(UUID.randomUUID()).build(), null))).isEmpty();
    }

    @Test
    void linesForChosenGroupsAreExactlyThoseGroups() {
        Renter tenantB = fx.createRenter("Tenant B");
        manualJv(new Dimensions(propertyId, null, null, tenantB.getId(), null),
                dr(AccountRole.RENT_RECEIVABLE, bd("400")), cr(AccountRole.CASH, bd("400")));
        manualJv(Dimensions.ofProperty(propertyId), dr(AccountRole.RENT_RECEIVABLE, bd("750")), cr(AccountRole.CASH, bd("750")));

        List<LineRow> all = read(() -> queries.lines(scope().build(), null));
        assertThat(all).extracting(LineRow::key).containsOnly(
                new GroupKey(rr, tenantA.getId()), new GroupKey(rr, tenantB.getId()), new GroupKey(rr, null));

        List<LineRow> b = read(() -> queries.lines(scope().build(), List.of(new GroupKey(rr, tenantB.getId()))));
        assertThat(b).singleElement().satisfies(l -> {
            assertThat(l.renterId()).isEqualTo(tenantB.getId());
            assertThat(l.rn()).isEqualTo(1);
            assertThat(l.movement()).isEqualByComparingTo("400");
        });
        List<LineRow> noTenant = read(() -> queries.lines(scope().build(), List.of(new GroupKey(rr, null))));
        assertThat(noTenant).singleElement().satisfies(l -> assertThat(l.renterId()).isNull());
        assertThat(read(() -> queries.lines(scope().build(), List.of()))).isEmpty();

        // Two accounts and two renters named crosswise: the SQL's IN lists also match (rr, Tenant A) and
        // (rr B, Tenant B), which are not on the page and must not come back.
        Account group = accountService.getAccountByCode("A-02-01");
        UUID rrB = accountService.createLeaf("Rent Receivable B - " + fx.propertyName(), group, propertyId).getId();
        manualJv(new Dimensions(propertyId, null, null, tenantA.getId(), null), dr(rrB, bd("250")), cr(AccountRole.CASH, bd("250")));
        manualJv(new Dimensions(propertyId, null, null, tenantB.getId(), null), dr(rrB, bd("125")), cr(AccountRole.CASH, bd("125")));
        List<GroupKey> crosswise = List.of(new GroupKey(rr, tenantB.getId()), new GroupKey(rrB, tenantA.getId()));
        List<LineRow> page = read(() -> queries.lines(scope().accounts(List.of(rr, rrB)).build(), crosswise));
        assertThat(page).extracting(LineRow::key).containsExactlyInAnyOrderElementsOf(crosswise);
        assertThat(page).filteredOn(l -> l.accountId().equals(rrB)).singleElement()
                .satisfies(l -> assertThat(l.debit()).isEqualByComparingTo("250"));
    }

    @Test
    void hideReversedDropsBothLegs() {
        UUID jv = manualJv(new Dimensions(propertyId, null, null, tenantA.getId(), null),
                dr(AccountRole.RENT_RECEIVABLE, bd("1000")), cr(AccountRole.CASH, bd("1000")));
        UUID mirror = posting.reverse(jv, OCT_6, "test").getId();

        List<LineRow> shown = read(() -> queries.lines(scope().build(), null));
        List<LineRow> hidden = read(() -> queries.lines(scope().hideReversed(true).build(), null));
        assertThat(shown).hasSize(hidden.size() + 2);
        assertThat(shown).extracting(LineRow::entryId).contains(jv, mirror);
        assertThat(hidden).extracting(LineRow::entryId).doesNotContain(jv, mirror);

        GroupRow withPairs = read(() -> queries.groups(scope().build())).get(0);
        GroupRow without = read(() -> queries.groups(scope().hideReversed(true).build())).get(0);
        assertThat(withPairs.debit()).isEqualByComparingTo("64500");
        assertThat(without.debit()).isEqualByComparingTo("63500");
        assertThat(withPairs.rowCount()).isEqualTo(without.rowCount() + 2);
        assertThat(closing(withPairs)).isEqualByComparingTo(closing(without));

        // A pair straddling the window's start: the original's 1,000 is in the opening, the mirror in the
        // window. Hidden, both leave, and with nothing left the group is dropped.
        GroupRow straddling = read(() -> queries.groups(scope().from(OCT_6).build())).get(0);
        assertThat(straddling.opening()).isEqualByComparingTo("1000");
        assertThat(straddling.credit()).isEqualByComparingTo("1000");
        assertThat(straddling.rowCount()).isEqualTo(1);
        assertThat(read(() -> queries.groups(scope().from(OCT_6).hideReversed(true).build()))).isEmpty();
    }

    @Test
    void onlyWithBalanceKeepsOnlyNonZeroClosings() {
        manualJv(Dimensions.ofProperty(propertyId), dr(AccountRole.RENT_RECEIVABLE, bd("750")), cr(AccountRole.CASH, bd("750")));

        assertThat(read(() -> queries.groups(scope().build()))).hasSize(2);
        List<GroupRow> withBalance = read(() -> queries.groups(scope().onlyWithBalance(true).build()));
        assertThat(withBalance).singleElement().satisfies(g -> {
            assertThat(g.renterId()).isNull();
            assertThat(g.debit()).isEqualByComparingTo("750");
        });
        List<LineRow> lines = read(() -> queries.lines(scope().onlyWithBalance(true).build(), null));
        assertThat(lines).singleElement().satisfies(l -> {
            assertThat(l.renterId()).isNull();
            assertThat(l.debit()).isEqualByComparingTo("750");
        });
    }

    @Test
    void counterNamesNameTheOtherAccountsInBothLanguages() {
        Account cash = resolver.resolve(AccountRole.CASH, propertyId);
        Account other = resolver.resolve(AccountRole.OTHER_INCOME, propertyId);
        UUID jv = manualJv(Dimensions.ofProperty(propertyId), dr(AccountRole.RENT_RECEIVABLE, bd("300")),
                cr(AccountRole.CASH, bd("100")), cr(AccountRole.OTHER_INCOME, bd("200")));

        // The JV's receivable line faces two accounts, so it carries no contra of its own.
        assertThat(read(() -> queries.lines(scope().build(), List.of(new GroupKey(rr, null)))))
                .singleElement().satisfies(l -> assertThat(l.contraName()).isNull());

        // 1,500 unrelated ids first, so the lookup spans more than one chunk of 1,000.
        List<UUID> entryIds = new ArrayList<>(Stream.generate(UUID::randomUUID).limit(1_500).toList());
        entryIds.add(jv);
        List<CounterRow> rows = read(() -> queries.counterNames(fx.tenantId(), entryIds, List.of(rr)));
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.entryId()).isEqualTo(jv);
            assertThat(r.accountId()).isEqualTo(rr);
            assertThat(r.names()).isEqualTo(Stream.of(cash.getName(), other.getName()).sorted().collect(Collectors.joining(" / ")));
            assertThat(Arrays.asList(r.namesAr().split(" / "))).containsExactlyInAnyOrder(arOrEn(cash), arOrEn(other));
        });
        assertThat(read(() -> queries.counterNames(fx.tenantId(), List.of(), List.of(rr)))).isEmpty();
    }

    @Test
    void foreignIdsMatchNothing() {
        UUID orgA = fx.tenantId();
        LeaseTestFixtures fxB = fixtures();
        Renter tenantAInB = fxB.createRenter("Tenant A");
        PostLeaseResponse contractB = postContract(fxB, tenantAInB);
        UUID propertyB = fxB.property().getId();
        UUID rrB = resolver.resolve(AccountRole.RENT_RECEIVABLE, propertyB).getId();
        UUID orgB = fxB.tenantId();
        TenantContextHolder.setTenantId(orgA);

        // Org A's own ids resolve, so the empties below are the tenant binding and not a broken lookup.
        assertThat(read(() -> queries.renter(orgA, tenantA.getId()))).map(NamedRef::name).contains("Tenant A");
        assertThat(read(() -> queries.property(orgA, propertyId))).map(NamedRef::name).contains(fx.propertyName());
        assertThat(read(() -> queries.lease(orgA, contract.lease().getId()))).isPresent();
        assertThat(read(() -> queries.accounts(orgA, List.of(rr)))).containsOnlyKeys(rr);
        assertThat(read(() -> queries.orgName(orgA))).isPresent();
        assertThat(read(() -> queries.groups(scope().build()))).hasSize(1);

        // Org B's ids, asked in org A.
        assertThat(read(() -> queries.renter(orgA, tenantAInB.getId()))).isEmpty();
        assertThat(read(() -> queries.property(orgA, propertyB))).isEmpty();
        assertThat(read(() -> queries.lease(orgA, contractB.lease().getId()))).isEmpty();
        assertThat(read(() -> queries.accounts(orgA, List.of(rrB)))).isEmpty();
        assertThat(read(() -> queries.receivableLeaves(orgA, propertyB))).isEmpty();
        assertThat(read(() -> queries.receivableLeafIds(orgA))).doesNotContain(rrB);
        assertThat(read(() -> queries.groups(scope().renter(tenantAInB.getId()).build()))).isEmpty();
        assertThat(read(() -> queries.groups(scope().accounts(List.of(rrB)).build()))).isEmpty();
        assertThat(read(() -> queries.lines(scope().accounts(List.of(rrB)).build(), null))).isEmpty();
        assertThat(read(() -> queries.groups(scope().lease(contractB.lease().getId()).build()))).isEmpty();
        assertThat(read(() -> queries.groups(scope().properties(new PropertyScope.Selection(false, List.of(propertyB), false)).build())))
                .isEmpty();
        assertThat(read(() -> queries.counterNames(orgA, List.of(contractB.tcoJournalId()), List.of(rrB)))).isEmpty();

        // And org B sees its own.
        assertThat(read(() -> queries.groups(new Scope(orgB, FROM, TO, List.of(rrB), null, null, ALL, false, false))))
                .singleElement().satisfies(g -> assertThat(g.renterId()).isEqualTo(tenantAInB.getId()));
    }

    @Test
    void receivableLeavesAreLeavesOfTheReceivableSubtype() {
        UUID t = fx.tenantId();
        Account rrAccount = resolver.resolve(AccountRole.RENT_RECEIVABLE, propertyId);
        Account group = accountService.getAccountByCode("A-02-01");

        List<ReceivableAccountDTO> leaves = read(() -> queries.receivableLeaves(t, propertyId));
        assertThat(leaves).singleElement().satisfies(a -> {
            assertThat(a.id()).isEqualTo(rr);
            assertThat(a.code()).isEqualTo(rrAccount.getCode());
            assertThat(a.name()).isEqualTo(rrAccount.getName());
            assertThat(a.propertyId()).isEqualTo(propertyId);
            assertThat(a.propertyName()).isEqualTo(fx.propertyName());
        });
        assertThat(read(() -> queries.receivableLeafIds(t))).contains(rr).doesNotContain(group.getId());

        Account second = accountService.createLeaf("Rent Receivable B - " + fx.propertyName(), group, propertyId);
        List<ReceivableAccountDTO> both = read(() -> queries.receivableLeaves(t, propertyId));
        assertThat(both).extracting(ReceivableAccountDTO::id).containsExactlyInAnyOrder(rr, second.getId());
        assertThat(both).extracting(ReceivableAccountDTO::code).isSorted();
        assertThat(read(() -> queries.receivableLeaves(t, null))).extracting(ReceivableAccountDTO::id).contains(rr, second.getId());
        assertThat(read(() -> queries.receivableLeafIds(t))).contains(rr, second.getId()).doesNotContain(group.getId());
    }

    // ------------------------------------------------------------------

    private LeaseTestFixtures fixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, leasePosting);
    }

    private static PostLeaseResponse postContract(LeaseTestFixtures f, Renter renter) {
        return f.postedLease(f.unit(), renter, CONTRACT, START, END,
                List.of(line("SECURITY_DEPOSIT", "3000"), line("RENT", "60000"), line("ADMIN_FEE", "500")), 4, null);
    }

    private UUID manualJv(Dimensions dims, PostingRequest.Line... lines) {
        return posting.post(new PostingRequest(JournalDocType.JV, OCT_5, "Manual", dims, JournalSourceType.MANUAL,
                null, null, List.of(lines))).getId();
    }

    private <T> T read(Supplier<T> query) {
        return readOnly.execute(status -> query.get());
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static BigDecimal closing(GroupRow g) {
        return g.opening().add(g.debit()).subtract(g.credit());
    }

    private static String arOrEn(Account a) {
        return a.getNameAr() == null || a.getNameAr().isEmpty() ? a.getName() : a.getNameAr();
    }

    private ScopeBuilder scope() {
        return new ScopeBuilder(fx.tenantId(), List.of(rr));
    }

    /** The scope every test starts from (org A, the whole window, the property's receivable) with one thing changed. */
    private static final class ScopeBuilder {
        private final UUID tenantId;
        private LocalDate from = FROM;
        private List<UUID> accounts;
        private UUID renter;
        private UUID lease;
        private PropertyScope.Selection properties = ALL;
        private boolean onlyWithBalance;
        private boolean hideReversed;

        ScopeBuilder(UUID tenantId, List<UUID> accounts) {
            this.tenantId = tenantId;
            this.accounts = accounts;
        }

        ScopeBuilder from(LocalDate d) { from = d; return this; }
        ScopeBuilder accounts(List<UUID> ids) { accounts = ids; return this; }
        ScopeBuilder renter(UUID id) { renter = id; return this; }
        ScopeBuilder lease(UUID id) { lease = id; return this; }
        ScopeBuilder properties(PropertyScope.Selection s) { properties = s; return this; }
        ScopeBuilder onlyWithBalance(boolean b) { onlyWithBalance = b; return this; }
        ScopeBuilder hideReversed(boolean b) { hideReversed = b; return this; }

        Scope build() {
            return new Scope(tenantId, from, TO, accounts, renter, lease, properties, onlyWithBalance, hideReversed);
        }
    }
}

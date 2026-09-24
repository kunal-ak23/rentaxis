package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.lease.AddChargeRequest;
import com.datagami.rentaxis.api.dto.lease.AddendumResponse;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseAddendumDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService.LedgerFilter;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseAddendum;
import com.datagami.rentaxis.domain.entity.RentSegment;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.entity.enums.SegmentStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseAddendumRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RentSegmentRepository;
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

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.chequeRow;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A charge added to a posted lease mid-term, against a real database (findings
 * #15/#17).
 *
 * <p>Almost everything worth asserting here is about what the ledger, the
 * register and the recognition schedule look like <em>afterwards</em> — that the
 * original {@code TCO} and a cleared cheque are untouched, that the contract
 * value rose by exactly the addendum, that the new rent is recognised over the
 * addendum's window and not over the whole term again. None of that is
 * observable through a mock.</p>
 *
 * <p><b>Transactions.</b> {@code TenantAspect} only enables the Hibernate tenant
 * filter inside one, so every read-back goes through {@link #tx}.</p>
 *
 * <p><b>Shared database.</b> The suite runs every class against one Postgres
 * container and nothing truncates between them, so every assertion here names
 * its own tenant, its own lease or its own ids. There is no assertion about a
 * whole table.</p>
 */
@SpringBootTest
class LeaseVariationServiceIT extends AbstractPostgresIT {

    @Autowired LeaseVariationService variations;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService cheques;
    @Autowired ChequeService chequeService;
    @Autowired LeaseService leaseService;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired LeaseRepository leaseRepo;
    @Autowired LeaseLineRepository lineRepo;
    @Autowired LeaseAddendumRepository addenda;
    @Autowired RentSegmentRepository segmentRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired JournalEntryRepository entries;
    @Autowired JournalLineRepository journalLines;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    /** Year one: contract dated before the tenancy starts, as PACT's are. */
    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);
    /** February, mid-way through the tenancy — the simulation's parking-bay month. */
    private static final LocalDate ADDENDUM_DATE = LocalDate.of(2027, 2, 10);
    private static final LocalDate EFFECTIVE = LocalDate.of(2027, 2, 15);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, cheques, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    /** Year one on the books: 51,000 of rent plus a 2,000 admin fee, no deposit. */
    private UUID postedWithFee() {
        return fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")), 4, null)
                .lease().getId();
    }

    private AddChargeRequest parking(String fee, String cheque) {
        return new AddChargeRequest(EFFECTIVE, ADDENDUM_DATE, null, "Parking bay P-12",
                List.of(line("PARKING_FEE", fee)),
                List.of(chequeRow(cheque, LocalDate.of(2027, 3, 1))));
    }

    /** Deposit then clear the lease's first cheque, so money has settled against the contract. */
    private Cheque clearFirstCheque(UUID leaseId) {
        Cheque first = registerOf(leaseId).get(0);
        fixtures.asTenantAdmin();
        chequeService.deposit(first.getId(), ChequeActionRequest.on(first.getChequeDate()));
        chequeService.clear(first.getId(), ChequeActionRequest.on(first.getChequeDate()));
        return tx.execute(s -> chequeRepo.findById(first.getId()).orElseThrow());
    }

    private String addNumber(int n) {
        return "ADD-" + String.format(java.util.Locale.ROOT, "%02d",
                tx.execute(s -> fiscal.fiscalYearOf(ADDENDUM_DATE)) % 100) + "/" + n;
    }

    private Account leaf(AccountRole role) {
        return tx.execute(s -> resolver.resolve(role, fixtures.property().getId()));
    }

    private Lease reread(UUID leaseId) {
        return tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow());
    }

    private List<JournalLine> linesOf(UUID entryId) {
        return tx.execute(s -> journalLines.findByEntry_IdOrderByLineNoAsc(entryId));
    }

    private List<Cheque> registerOf(UUID leaseId) {
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    private List<LeaseLineDTO> leaseLines(UUID leaseId) {
        return tx.execute(s -> leaseService.getLines(leaseId));
    }

    private BigDecimal balanceOf(Account account, UUID leaseId) {
        return tx.execute(s -> ledger.accountLedger(account.getId(),
                new LedgerFilter(null, null, null, null, leaseId, null)).closingBalance());
    }

    private long journalEntryRows() {
        return jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?",
                Long.class, fixtures.tenantId());
    }

    /** Every TCO raised against this lease, oldest first. */
    private List<JournalEntry> tcosOf(UUID leaseId) {
        return tx.execute(s -> entries.findAll().stream()
                .filter(e -> e.getDocType() == JournalDocType.TCO
                        && e.getSourceType() == JournalSourceType.LEASE
                        && leaseId.equals(e.getSourceId()))
                .sorted(java.util.Comparator.comparing(JournalEntry::getEntryNumber))
                .toList());
    }

    private List<RentSegment> segmentsOf(UUID leaseId) {
        return tx.execute(s -> segmentRepo.findByLease_IdOrderByFromDateAsc(leaseId));
    }

    private List<LeaseAddendum> addendaOf(UUID leaseId) {
        return tx.execute(s -> addenda.findByLease_IdOrderByCreatedAtAsc(leaseId));
    }

    // ------------------------------------------------------------------
    // addCharge
    // ------------------------------------------------------------------

    /** #15: a cleared cheque does not stop an addition — nothing settled is disturbed. */
    @Test
    void anAddendumPostsOnALeaseWithAClearedCheque() {
        UUID leaseId = postedWithFee();
        UUID originalTcoId = reread(leaseId).getPostingJournalId();
        Cheque cleared = clearFirstCheque(leaseId);
        assertThat(cleared.getStatus()).isEqualTo(ChequeStatus.CLEARED);

        AddendumResponse r = variations.addCharge(leaseId, parking("6000", "6000"));

        assertThat(r.addendum().addendumNumber()).isEqualTo(addNumber(1));
        JournalEntry tco = tx.execute(s -> entries.findById(r.posting().tcoJournalId()).orElseThrow());
        assertThat(tco.getDocType()).isEqualTo(JournalDocType.TCO);
        assertThat(tco.getEntryDate()).isEqualTo(ADDENDUM_DATE);
        assertThat(tco.getNarration()).isEqualTo("Addendum " + addNumber(1) + ": Parking bay P-12");
        assertThat(linesOf(tco.getId())).extracting(JournalLine::getAccountId)
                .containsExactly(leaf(AccountRole.RENT_RECEIVABLE).getId(), leaf(AccountRole.PARKING_INCOME).getId());

        // Nothing that money settled against moved.
        assertThat(tx.execute(s -> entries.findById(originalTcoId).orElseThrow()).getStatus())
                .isEqualTo(JournalStatus.POSTED);
        assertThat(reread(leaseId).getPostingJournalId()).isEqualTo(originalTcoId);
        assertThat(tx.execute(s -> chequeRepo.findById(cleared.getId()).orElseThrow()).getStatus())
                .isEqualTo(ChequeStatus.CLEARED);
        assertThat(tcosOf(leaseId)).extracting(JournalEntry::getId).containsExactly(originalTcoId, tco.getId());

        // The new instrument is registered with its PDR.
        List<Cheque> register = registerOf(leaseId);
        assertThat(register).hasSize(6);
        assertThat(register.get(5).getAmount()).isEqualByComparingTo("6000");
        assertThat(register.get(5).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
    }

    /** #17: the contract value rises by exactly the addendum, and the renter's receivable nets to zero. */
    @Test
    void theContractValueRisesByExactlyTheAddendum() {
        UUID leaseId = postedWithFee();
        BigDecimal before = leaseLines(leaseId).stream().map(LeaseLineDTO::netAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        variations.addCharge(leaseId, parking("6000", "6000"));

        BigDecimal after = leaseLines(leaseId).stream().map(LeaseLineDTO::netAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(after.subtract(before)).isEqualByComparingTo("6000");
        assertThat(balanceOf(leaf(AccountRole.RENT_RECEIVABLE), leaseId)).isEqualByComparingTo("0");
        assertThat(balanceOf(leaf(AccountRole.PARKING_INCOME), leaseId)).isEqualByComparingTo("-6000");
        assertThat(balanceOf(leaf(AccountRole.PDC_RECEIVABLE), leaseId)).isEqualByComparingTo("59000");
        assertThat(reread(leaseId).getEndDate()).isEqualTo(END);
    }

    /** A RENT addendum is recognised over its own window only; the original term is not recognised twice. */
    @Test
    void aRentAddendumGetsItsOwnSegmentOverTheAddendumWindow() {
        UUID leaseId = postedWithFee();
        List<RentSegment> before = segmentsOf(leaseId);

        AddendumResponse r = variations.addCharge(leaseId, new AddChargeRequest(EFFECTIVE, ADDENDUM_DATE, null,
                "Storage room", List.of(line("RENT", "4000")), List.of(chequeRow("4000", LocalDate.of(2027, 3, 1)))));

        List<RentSegment> segs = segmentsOf(leaseId);
        assertThat(segs).hasSize(before.size() + 1);
        RentSegment added = segs.stream().filter(s -> s.getFromDate().equals(EFFECTIVE)).findFirst().orElseThrow();
        assertThat(added.getToDate()).isEqualTo(END);
        assertThat(added.getAmount()).isEqualByComparingTo("4000");
        assertThat(added.getDays()).isEqualTo(
                (int) java.time.temporal.ChronoUnit.DAYS.between(EFFECTIVE, END) + 1);

        // The original term is recognised once, over the term it always had. Had the
        // new line fallen back to the lease's default window it would have been cut
        // from START, and every month of year one would be earned a second time.
        RentSegment original = segs.stream().filter(s -> s.getFromDate().equals(START)).findFirst().orElseThrow();
        assertThat(original.getToDate()).isEqualTo(END);
        assertThat(original.getStatus()).isEqualTo(SegmentStatus.ACTIVE);
        assertThat(segs).filteredOn(s -> s.getFromDate().equals(START)).hasSize(1);

        // The new line is tied to its addendum.
        UUID addendumId = r.addendum().id();
        assertThat(tx.execute(s -> lineRepo.findByLease_IdOrderBySeqNoAsc(leaseId)).getLast().getAddendumId())
                .isEqualTo(addendumId);
    }

    /**
     * #54 review M-2: on a lease whose header says rent carries VAT, an addendum's
     * RENT line sent with no VAT flag of its own follows the header — in the figure
     * the cheques must cover and on the written line the TCO posts Output VAT from.
     */
    @Test
    void anAddendumsFlaglessRentLineFollowsTheLeasesRentVatFlag() {
        UUID leaseId = postedWithFee();
        // A commercial lease, ticked after posting so the fixture's own grid stays
        // VAT-free: the subject here is the addendum alone.
        tx.executeWithoutResult(s -> {
            Lease lease = leaseRepo.findById(leaseId).orElseThrow();
            lease.setRentVatApplicable(true);
            leaseRepo.save(lease);
        });
        LeaseLineInput storage = line("RENT", "4000");
        assertThat(storage.vatApplicable()).as("the line sends no flag of its own").isNull();

        assertThatThrownBy(() -> variations.addCharge(leaseId, new AddChargeRequest(EFFECTIVE, ADDENDUM_DATE, null,
                "Storage room", List.of(storage), List.of(chequeRow("4000", LocalDate.of(2027, 3, 1))))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cheque rows total 4,000.00 but the addendum charges 4,200.00");
        assertThat(leaseLines(leaseId)).hasSize(2);

        AddendumResponse r = variations.addCharge(leaseId, new AddChargeRequest(EFFECTIVE, ADDENDUM_DATE, null,
                "Storage room", List.of(storage), List.of(chequeRow("4200", LocalDate.of(2027, 3, 1)))));

        assertThat(leaseLines(leaseId).getLast().vatApplicable()).isTrue();
        // The addendum's TCO parks its VAT until the new row's tax point, and the new
        // row carries exactly the new lines' VAT (spec 2026-09-24 §1).
        UUID outputVat = leaf(AccountRole.OUTPUT_VAT_DEFERRED).getId();
        assertThat(r.posting().cheques()).filteredOn(c -> c.chequeDate().equals(LocalDate.of(2027, 3, 1)))
                .singleElement().satisfies(c -> assertThat(c.vatAmount()).isEqualByComparingTo("200.00"));
        assertThat(linesOf(r.posting().tcoJournalId()))
                .filteredOn(l -> outputVat.equals(l.getAccountId()))
                .singleElement()
                .satisfies(l -> assertThat(l.getCredit()).isEqualByComparingTo("200"));
    }

    @Test
    void aChequeMismatchChangesNothing() {
        UUID leaseId = postedWithFee();
        long journalsBefore = journalEntryRows();

        assertThatThrownBy(() -> variations.addCharge(leaseId, parking("6000", "5000")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cheque rows total 5,000.00 but the addendum charges 6,000.00");

        assertThat(leaseLines(leaseId)).hasSize(2);
        assertThat(registerOf(leaseId)).hasSize(5);
        assertThat(journalEntryRows()).isEqualTo(journalsBefore);
        assertThat(addendaOf(leaseId)).isEmpty();
    }

    /**
     * #80 on the addendum door (PR #344 review I4): a post-dated cheque added with
     * the addendum needs its number like one on the original grid, and the
     * addendum is refused whole — no TCO, no rows, no addendum.
     */
    @Test
    void anUnnumberedPdcRefusesTheAddendumWhole() {
        UUID leaseId = postedWithFee();
        long journalsBefore = journalEntryRows();
        ChequeRowInput unnumbered = new ChequeRowInput(null, null, null, null, LocalDate.of(2027, 3, 1),
                "Emirates NBD", null, null, new BigDecimal("6000"), null, null);

        assertThatThrownBy(() -> variations.addCharge(leaseId, new AddChargeRequest(EFFECTIVE, ADDENDUM_DATE, null,
                "Parking bay P-12", List.of(line("PARKING_FEE", "6000")), List.of(unnumbered))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("has no number; a post-dated cheque needs its number");

        assertThat(journalEntryRows()).isEqualTo(journalsBefore);
        assertThat(addendaOf(leaseId)).isEmpty();
        assertThat(registerOf(leaseId)).hasSize(5);
    }

    @Test
    void aDepositLineIsRefused() {
        UUID leaseId = postedWithFee();
        assertThatThrownBy(() -> variations.addCharge(leaseId, new AddChargeRequest(EFFECTIVE, ADDENDUM_DATE, null, null,
                List.of(line("PARKING_DEPOSIT", "1000")), List.of(chequeRow("1000", LocalDate.of(2027, 3, 1))))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("an addendum cannot charge a deposit");
        assertThat(leaseLines(leaseId)).hasSize(2);
    }

    @Test
    void aLockedPeriodRefusesTheAddendumWhole() {
        UUID leaseId = postedWithFee();
        long journalsBefore = journalEntryRows();
        fiscal.lockThrough(LocalDate.of(2027, 2, 28));

        assertThatThrownBy(() -> variations.addCharge(leaseId, parking("6000", "6000")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cannot post on 2027-02-10: books are locked through 2027-02-28");
        assertThat(journalEntryRows()).isEqualTo(journalsBefore);
        assertThat(registerOf(leaseId)).hasSize(5);
    }

    /**
     * Stage 2's own lock check (review point at addCharge's stage-2 comment,
     * {@code LeaseVariationService.java:166}): the addendum's entry date can be
     * open while one of its cheque rows posts into a month that is locked. Stage
     * 1 only checks the entry date against the lock ({@code periodLockErrors(entryDate,
     * List.of())}), so this path is refused only if stage 2 asks the same
     * question of the rows' own posting dates — which is exactly the line this
     * test is here to keep honest.
     */
    @Test
    void aChequeRowDatedInALockedMonthRefusesTheAddendumWhole() {
        UUID leaseId = postedWithFee();
        long journalsBefore = journalEntryRows();
        // January is locked; the addendum's own entry date (ADDENDUM_DATE,
        // 2027-02-10) is after it and so is open by itself.
        fiscal.lockThrough(LocalDate.of(2027, 1, 31));
        LocalDate lockedChequeDate = LocalDate.of(2027, 1, 20);
        ChequeRowInput rowInLockedMonth = new ChequeRowInput(null, null, lockedChequeDate, null,
                LocalDate.of(2027, 3, 1), "Emirates NBD", null, null, new BigDecimal("6000"), null, null);

        // The specific "Cheque row N cannot post on ..." phrasing is what
        // periodLockErrors(entryDate, newRows) produces for a row's own date; a
        // generic "Cannot post on ..." from a lower-level check catching the same
        // date later would not name the cheque at all. Asserting the row-specific
        // wording is what makes this test fail (rather than pass for the wrong
        // reason) if that stage-2 line is ever deleted.
        assertThatThrownBy(() -> variations.addCharge(leaseId, new AddChargeRequest(EFFECTIVE, ADDENDUM_DATE, null,
                "Parking bay P-12", List.of(line("PARKING_FEE", "6000")), List.of(rowInLockedMonth))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Cheque row")
                .hasMessageContaining("cannot post on 2027-01-20: books are locked through 2027-01-31");

        // Nothing written: not the TCO, not the addendum row, not the register.
        assertThat(journalEntryRows()).isEqualTo(journalsBefore);
        assertThat(addendaOf(leaseId)).isEmpty();
        assertThat(registerOf(leaseId)).hasSize(5);
    }

    @Test
    void anEffectiveDateOutsideTheTenancyIsRefused() {
        UUID leaseId = postedWithFee();
        assertThatThrownBy(() -> variations.addCharge(leaseId, new AddChargeRequest(END.plusDays(1), ADDENDUM_DATE,
                null, null, List.of(line("PARKING_FEE", "6000")), List.of(chequeRow("6000", LocalDate.of(2027, 3, 1))))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("must take effect within the tenancy");
    }

    @Test
    void aDraftLeaseCannotTakeAnAddendum() {
        UUID draft = fixtures.draftLease(CONTRACT_DATE, START, END, List.of(line("RENT", "51000")));
        assertThatThrownBy(() -> variations.addCharge(draft, parking("6000", "6000")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only an ACTIVE lease can take an addendum");
    }

    @Test
    void twoAddendaNumberInSequenceAndEjariCanBeRecordedLater() {
        UUID leaseId = postedWithFee();
        AddendumResponse first = variations.addCharge(leaseId, parking("6000", "6000"));
        AddendumResponse second = variations.addCharge(leaseId, parking("1200", "1200"));

        assertThat(first.addendum().addendumNumber()).isEqualTo(addNumber(1));
        assertThat(second.addendum().addendumNumber()).isEqualTo(addNumber(2));
        assertThat(first.addendum().ejariPending()).isTrue();

        LeaseAddendumDTO recorded = variations.recordEjari(leaseId, first.addendum().id(), "EJ-2027-00042");
        assertThat(recorded.ejariNumber()).isEqualTo("EJ-2027-00042");
        assertThat(recorded.ejariPending()).isFalse();
        assertThat(variations.list(leaseId)).extracting(LeaseAddendumDTO::ejariPending).containsExactly(false, true);
        // F14-33: with the later addendum not yet registered, the first one's Ejari is the latest.
        assertThat(headerEjari(leaseId)).isEqualTo("EJ-2027-00042");

        // F14-33: the latest addendum's Ejari is the lease's; a corrected number replaces it.
        variations.recordEjari(leaseId, second.addendum().id(), "EJ-2027-00050");
        variations.recordEjari(leaseId, second.addendum().id(), "EJ-2027-00051");
        assertThat(headerEjari(leaseId)).isEqualTo("EJ-2027-00051");
        // Correcting an earlier addendum's number leaves the latest one on the header.
        variations.recordEjari(leaseId, first.addendum().id(), "EJ-2027-00043");
        assertThat(headerEjari(leaseId)).isEqualTo("EJ-2027-00051");

        // Changeset 127 backfills a header an older build left behind, and is idempotent.
        jdbc.update("update leases set ejari_number = null where id = ?", leaseId);
        String backfill = changeset127Sql();
        assertThat(jdbc.update(backfill)).isGreaterThanOrEqualTo(1);
        assertThat(headerEjari(leaseId)).isEqualTo("EJ-2027-00051");
        assertThat(jdbc.update(backfill)).isZero();
    }

    private String headerEjari(UUID leaseId) {
        return tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow().getEjariNumber());
    }

    /** The UPDATE of changeset 127, as Liquibase runs it. */
    private static String changeset127Sql() {
        try (var in = LeaseVariationServiceIT.class.getResourceAsStream(
                "/db/changelog/changesets/127-lease-ejari-from-latest-addendum.yaml")) {
            String yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            String body = yaml.substring(yaml.indexOf("sql: |") + "sql: |".length(), yaml.indexOf("      rollback:"));
            return body.lines().map(String::strip).filter(l -> !l.isEmpty())
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // amend after an addendum (review I-2)
    // ------------------------------------------------------------------

    /** Every current line, re-sent exactly as it stands — period included. */
    private static LeaseLineInput resend(LeaseLineDTO l) {
        return new LeaseLineInput(l.chargeTypeId(), null, l.grossAmount(), l.discountAmount(),
                l.narration(), l.vatApplicable(), l.creditAccountId(), l.periodStart(), l.periodEnd());
    }

    /**
     * Amending re-inserts every line, and a RENT line that arrives without a period
     * defaults to the whole term. The addendum's rent has to come back with its own
     * window, or the rebuild recognises it from the lease start.
     */
    @Test
    void anAmendAfterARentAddendumKeepsTheAddendumsWindow() {
        UUID leaseId = postedWithFee();
        variations.addCharge(leaseId, new AddChargeRequest(EFFECTIVE, ADDENDUM_DATE, null,
                "Storage room", List.of(line("RENT", "4000")), List.of(chequeRow("4000", LocalDate.of(2027, 3, 1)))));

        List<LeaseLineInput> same = leaseLines(leaseId).stream().map(LeaseVariationServiceIT::resend).toList();
        fixtures.asTenantAdmin();
        posting.amendLines(leaseId, same, "Narration correction");

        LeaseLineDTO storage = leaseLines(leaseId).stream()
                .filter(l -> "RENT".equals(l.chargeTypeCode()) && l.grossAmount().compareTo(new BigDecimal("4000")) == 0)
                .findFirst().orElseThrow();
        assertThat(storage.periodStart()).isEqualTo(EFFECTIVE);
        assertThat(storage.periodEnd()).isEqualTo(END);

        List<RentSegment> active = segmentsOf(leaseId).stream()
                .filter(s -> s.getStatus() == SegmentStatus.ACTIVE).toList();
        RentSegment added = active.stream()
                .filter(s -> s.getAmount().compareTo(new BigDecimal("4000")) == 0).findFirst().orElseThrow();
        assertThat(added.getFromDate()).isEqualTo(EFFECTIVE);
        assertThat(added.getToDate()).isEqualTo(END);
        // The original term is still recognised once, over its own term.
        assertThat(active).filteredOn(s -> s.getFromDate().equals(START)).hasSize(1)
                .allSatisfy(s -> assertThat(s.getAmount()).isEqualByComparingTo("51000"));
    }

    /**
     * amendLines reverses every POSTED TCO on the lease to rebuild it from the
     * fresh set of lines — including an addendum's own TCO, which it has no
     * reason to spare. The addendum row is never touched by an amend, so
     * without a derived flag the panel would go on showing that addendum's TCO
     * entry number as if it were still live.
     */
    @Test
    void anAmendAfterAnAddendumMarksTheAddendumSuperseded() {
        UUID leaseId = postedWithFee();
        AddendumResponse added = variations.addCharge(leaseId, parking("6000", "6000"));
        assertThat(variations.list(leaseId)).extracting(LeaseAddendumDTO::superseded).containsExactly(false);

        List<LeaseLineInput> same = leaseLines(leaseId).stream().map(LeaseVariationServiceIT::resend).toList();
        fixtures.asTenantAdmin();
        posting.amendLines(leaseId, same, "Narration correction");

        List<LeaseAddendumDTO> after = variations.list(leaseId);
        assertThat(after).singleElement().satisfies(a -> {
            assertThat(a.id()).isEqualTo(added.addendum().id());
            assertThat(a.superseded()).isTrue();
            // The addendum row itself keeps naming the (now reversed) TCO — the
            // amend does not rewrite it, only the derived flag changes.
            assertThat(a.tcoJournalId()).isEqualTo(added.addendum().tcoJournalId());
        });
        assertThat(tx.execute(s -> entries.findById(added.addendum().tcoJournalId()).orElseThrow()).getStatus())
                .isEqualTo(JournalStatus.REVERSED);
    }

    // ------------------------------------------------------------------
    // recordEjari isolation (review T3/T4)
    // ------------------------------------------------------------------

    @Test
    void anEjariCannotBeRecordedAgainstAnotherLeasesAddendum() {
        UUID leaseA = postedWithFee();
        UUID leaseB = fixtures.postedLease(fixtures.createUnit(fixtures.property(), "102"),
                fixtures.createRenter("Second Renter"), CONTRACT_DATE, START, END,
                List.of(line("RENT", "24000")), 2, null).lease().getId();
        AddendumResponse onA = variations.addCharge(leaseA, parking("6000", "6000"));

        assertThatThrownBy(() -> variations.recordEjari(leaseB, onA.addendum().id(), "X"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Addendum not found");

        assertThat(variations.list(leaseA)).singleElement()
                .satisfies(a -> {
                    assertThat(a.ejariPending()).isTrue();
                    assertThat(a.ejariNumber()).isNull();
                });
    }

    @Test
    void anEjariCannotBeRecordedAcrossTenants() {
        UUID leaseId = postedWithFee();
        UUID tenantOne = fixtures.tenantId();
        AddendumResponse r = variations.addCharge(leaseId, parking("6000", "6000"));

        // A second landlord, made current.
        new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService).bootstrap();
        assertThat(TenantContextHolder.getTenantId()).isNotEqualTo(tenantOne);

        assertThatThrownBy(() -> variations.recordEjari(leaseId, r.addendum().id(), "X"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Lease not found");

        TenantContextHolder.setTenantId(tenantOne);
        fixtures.asTenantAdmin();
        assertThat(variations.list(leaseId)).singleElement()
                .satisfies(a -> {
                    assertThat(a.ejariPending()).isTrue();
                    assertThat(a.ejariNumber()).isNull();
                });
        assertThat(jdbc.queryForObject("select ejari_number from lease_addenda where id = ?",
                String.class, r.addendum().id())).isNull();
    }

    @Test
    void aBlankEjariNumberIsRefused() {
        UUID leaseId = postedWithFee();
        AddendumResponse r = variations.addCharge(leaseId, parking("6000", "6000"));

        assertThatThrownBy(() -> variations.recordEjari(leaseId, r.addendum().id(), "   "))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("An Ejari number is required");
        assertThat(variations.list(leaseId)).singleElement()
                .satisfies(a -> assertThat(a.ejariPending()).isTrue());
    }
}

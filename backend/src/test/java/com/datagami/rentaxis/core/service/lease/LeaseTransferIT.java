package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.lease.PostLeaseDryRunResponse;
import com.datagami.rentaxis.api.dto.lease.TransferLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TransferPreviewDTO;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeFailureReason;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UnitStatus;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spec 2026-09-24 §2, the worked example: A-102, 60,000 for 01/01–31/12/2026 (365
 * days, 164.383562 a day), deposit 3,000, four rent cheques of 15,000 on 1 Jan, 1 Apr,
 * 1 Jul and 1 Oct; January and April cleared. The renter moves on T = 15/05/2026
 * (135 days earned, 22,191.78) to a unit for 16/05–31/12 (230 days) at 41,589.04.
 * Unearned 37,808.22; C = 30,000 − 37,808.22 = −7,808.22 (prepaid); July and October
 * carried; a new row of 3,780.82 closes the gap.
 */
@SpringBootTest
class LeaseTransferIT extends AbstractPostgresIT {

    @Autowired LeaseTransferService transfers;
    @Autowired LeaseTerminationService termination;
    @Autowired com.datagami.rentaxis.core.service.SettlementService settlements;
    @Autowired LeaseDepositLedger depositLedger;
    @Autowired LeasePostingService posting;
    @Autowired LeaseRenewalService renewal;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired ChequeService chequeService;
    @Autowired RecognitionService recognition;
    @Autowired LeaseService leaseService;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired ChequeRepository chequeRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired JournalEntryRepository journals;
    @Autowired JournalLineRepository journalLines;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT = LocalDate.of(2025, 12, 20);
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 12, 31);
    private static final LocalDate JAN = LocalDate.of(2026, 1, 1);
    private static final LocalDate APR = LocalDate.of(2026, 4, 1);
    private static final LocalDate JUL = LocalDate.of(2026, 7, 1);
    private static final LocalDate OCT = LocalDate.of(2026, 10, 1);
    private static final LocalDate T = LocalDate.of(2026, 5, 15);
    private static final LocalDate B_START = LocalDate.of(2026, 5, 16);

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

    @Test
    void theWorkedExampleOnTheSameProperty() {
        UUID a = leaseA(true);
        Unit target = tx.execute(s -> fixtures.createUnit(fixtures.property(), "A-201"));

        TransferPreviewDTO preview = transfers.preview(a, T, target.getId(), null);
        assertThat(preview.problems()).isEmpty();
        assertThat(preview.earnedThrough()).isEqualByComparingTo("22191.78");
        assertThat(preview.unearned()).isEqualByComparingTo("37808.22");
        assertThat(preview.balanceCarried()).isEqualByComparingTo("-7808.22");
        assertThat(preview.depositCarried()).isEqualByComparingTo("3000");
        assertThat(preview.newDays()).isEqualTo(230);
        assertThat(preview.suggestedRent()).isEqualByComparingTo("37808.22");   // A's day rate × 230
        assertThat(preview.cheques()).extracting(TransferPreviewDTO.Row::chequeDate, TransferPreviewDTO.Row::disposition)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(JUL, "CARRY"), org.assertj.core.groups.Tuple.tuple(OCT, "CARRY"));

        UUID b = draftB(a, target, "41589.04");
        assertThat(lease(b).getStatus()).isEqualTo(LeaseStatus.DRAFT);
        assertThat(journalCount(JournalDocType.TCR)).as("a draft writes nothing").isZero();
        assertThat(posting.dryRun(b).ok()).isTrue();

        posting.post(b);

        // A: ended at T with no fee, carried rows gone, nothing left — closed.
        Lease left = lease(a);
        assertThat(left.getStatus()).isEqualTo(LeaseStatus.CLOSED);
        assertThat(left.getTerminatedOn()).isEqualTo(T);
        JournalEntry tcr = journal(left.getTerminationJournalId());
        assertThat(tcr.getEntryDate()).isEqualTo(T);
        assertThat(debitOn(tcr, AccountRole.ADVANCE_RENT, fixtures.property())).isEqualByComparingTo("37808.22");
        assertThat(chequeOn(a, JUL).getStatus()).isEqualTo(ChequeStatus.TRANSFERRED);
        assertThat(chequeOn(a, OCT).getStatus()).isEqualTo(ChequeStatus.TRANSFERRED);

        // C carried by one JV on T + 1.
        List<JournalEntry> jvs = tx.execute(s -> journals.findAll()).stream()
                .filter(j -> j.getDocType() == JournalDocType.JV && b.equals(j.getSourceId())
                        && j.getNarration().startsWith("Balance carried")).toList();
        assertThat(jvs).singleElement().satisfies(j -> {
            assertThat(j.getEntryDate()).isEqualTo(B_START);
            assertThat(linesOf(j.getId())).extracting(JournalLine::getDebit).contains(new BigDecimal("7808.22"));
        });

        // B: the carried instruments registered on B, the gap row, and nothing owed.
        Lease moved = lease(b);
        assertThat(moved.getStatus()).isEqualTo(LeaseStatus.ACTIVE);
        List<Cheque> bRows = register(b);
        assertThat(bRows).allSatisfy(c -> assertThat(c.getStatus()).isEqualTo(ChequeStatus.REGISTERED));
        assertThat(bRows).filteredOn(c -> c.getTransferredFromId() != null).hasSize(2)
                .allSatisfy(c -> assertThat(c.getAmount()).isEqualByComparingTo("15000"));
        assertThat(chequeOn(a, JUL).getTransferredToId()).isIn(bRows.stream().map(Cheque::getId).toList());
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, a, fixtures.property())).isZero();
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, b, fixtures.property())).isZero();
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, a, fixtures.property())).isZero();
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, a, fixtures.property())).isZero();
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, b, fixtures.property())).isEqualByComparingTo("-3000");
        assertThat(moved.getChainId()).isEqualTo(left.getChainId());

        // Units flipped.
        assertThat(unit(fixtures.unit().getId()).getStatus()).isEqualTo(UnitStatus.VACANT);
        assertThat(unit(target.getId()).getStatus()).isEqualTo(UnitStatus.OCCUPIED);
        // PR #359 R1 P2-2: a carried cheque is live once, on B — the dashboard's expected
        // collection for July and the owner statement's instalments count it once.
        List<Object[]> july = tx.execute(s -> chequeRepo.aggregateMonthly(JUL, JUL.plusMonths(1), true, List.of(UUID.randomUUID())));
        assertThat(july).singleElement().satisfies(r -> assertThat((BigDecimal) r[1]).isEqualByComparingTo("15000"));
        var instalments = tx.execute(s -> new com.datagami.rentaxis.core.service.report.statement.StandardStatementSections
                .InstalmentsDue(chequeRepo).build(new com.datagami.rentaxis.core.service.report.statement.StatementContext(
                        fixtures.tenantId(), fixtures.property(), JUL, OCT)));
        assertThat(instalments.figures()).filteredOn(f -> f.key().equals("gross")).singleElement()
                .satisfies(f -> assertThat(f.amount()).isEqualByComparingTo("30000"));
        LeaseDTO aDto = tx.execute(s -> leaseService.getLeaseById(a));
        assertThat(aDto.getTransferredToLeaseId()).isEqualTo(b);
        assertTrialBalanceBalances();

        // Recognition to year end: A earned 22,191.78, B its 41,589.04.
        recognition.runTo(END, false);
        assertThat(balanceOf(AccountRole.ADVANCE_RENT, a, fixtures.property())).isZero();
        assertThat(balanceOf(AccountRole.ADVANCE_RENT, b, fixtures.property())).isZero();
    }

    @Test
    void acrossPropertiesTheDepositAndPdcsMoveBetweenTheLeaves() {
        UUID a = leaseA(true);
        Property other = tx.execute(s -> fixtures.createProperty("OTH"));
        Unit target = tx.execute(s -> fixtures.createUnit(other, "B-301"));
        UUID b = draftB(a, target, "41589.04");
        posting.post(b);
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, b, other)).isEqualByComparingTo("-3000");
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, a, fixtures.property())).isZero();
        assertThat(leaf(AccountRole.SECURITY_DEPOSIT, other)).isNotEqualTo(leaf(AccountRole.SECURITY_DEPOSIT, fixtures.property()));
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, b, other)).isEqualByComparingTo("33780.82");
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, b, other)).isZero();
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, a, fixtures.property())).isZero();
        assertTrialBalanceBalances();
    }

    /**
     * PR #359 R1 P1-1, the spec's missing test: the renter moves across properties,
     * then leaves the new unit. The deposit ledger follows the carried deposit to B's
     * property's leaf, the settlement refunds it once, and the liability is nil on
     * both properties. Same-property below.
     */
    @Test
    void theCarriedDepositIsRefundedOnceWhenTheNewLeaseEnds_acrossProperties() {
        UUID a = leaseA(true);
        Property other = tx.execute(s -> fixtures.createProperty("OTH"));
        Unit target = tx.execute(s -> fixtures.createUnit(other, "B-302"));
        UUID b = draftB(a, target, "41589.04");
        posting.post(b);
        endAndSettle(b, fixtures.property(), other);
    }

    @Test
    void theCarriedDepositIsRefundedOnceWhenTheNewLeaseEnds_sameProperty() {
        UUID a = leaseA(true);
        Unit target = tx.execute(s -> fixtures.createUnit(fixtures.property(), "A-206"));
        UUID b = draftB(a, target, "41589.04");
        posting.post(b);
        endAndSettle(b, fixtures.property(), fixtures.property());
    }

    private void endAndSettle(UUID b, Property from, Property to) {
        BigDecimal held = tx.execute(s -> depositLedger.depositHeld(leaseRepo.findById(b).orElseThrow()));
        assertThat(held)
                .as("the ledger sees the carried deposit on B").isEqualByComparingTo("3000");
        LocalDate end = LocalDate.of(2026, 6, 30);
        termination.terminate(b, new com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest(end, null, null, null), null);
        var settled = settlements.finalizeSettlement(b,
                new com.datagami.rentaxis.api.dto.settlement.FinalizeSettlementRequest(end, null, true), null);
        assertThat(settled.getDepositsHeld()).isEqualByComparingTo("3000");
        UUID sdFrom = leaf(AccountRole.SECURITY_DEPOSIT, from);
        UUID sdTo = leaf(AccountRole.SECURITY_DEPOSIT, to);
        assertThat(jdbc.queryForObject("select coalesce(sum(debit - credit), 0) from journal_lines where account_id = ?",
                BigDecimal.class, sdFrom)).as("deposit liability on the old property").isZero();
        assertThat(jdbc.queryForObject("select coalesce(sum(debit - credit), 0) from journal_lines where account_id = ?",
                BigDecimal.class, sdTo)).as("deposit liability on the new property").isZero();
        assertThat(jdbc.queryForObject("select coalesce(sum(l.debit), 0) from journal_lines l join journal_entries e"
                + " on e.id = l.journal_entry_id where e.doc_type = 'STL' and l.account_id = ?", BigDecimal.class, sdTo))
                .as("released once").isEqualByComparingTo("3000");
        assertTrialBalanceBalances();
    }

    @Test
    void aBouncedRowStaysOnTheLeaseLeftBehindWhichIsNotClosed() {
        UUID a = leaseA(false);   // January cleared, April bounced
        Cheque april = chequeOn(a, APR);
        chequeService.deposit(april.getId(), ChequeActionRequest.on(APR));
        chequeService.bounce(april.getId(), new ChequeActionRequest(APR, null, ChequeFailureReason.BOUNCE, null));
        Unit target = tx.execute(s -> fixtures.createUnit(fixtures.property(), "A-202"));
        // C excludes the bounced 15,000: the gap is the same as the worked example's.
        UUID b = draftB(a, target, "41589.04");
        posting.post(b);
        assertThat(lease(a).getStatus()).isEqualTo(LeaseStatus.TERMINATED);
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, a, fixtures.property())).isEqualByComparingTo("15000");
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, b, fixtures.property())).isZero();
        assertTrialBalanceBalances();
    }

    /**
     * A grid that does not cover the contract plus C less the carried rows is refused
     * with the exact gap, and the whole transfer rolls back. A row kept on A stays
     * there for collection and its money reaches B through C.
     */
    @Test
    void aShortGridIsRefusedWithTheGapAndAKeptRowStaysOnTheOldLease() {
        UUID a = leaseA(true);
        Unit target = tx.execute(s -> fixtures.createUnit(fixtures.property(), "A-203"));
        LeaseDTO draft = transfers.draft(a, request(T, target.getId(), List.of(line("RENT", "41589.04"))), posting);
        UUID b = draft.getId();
        chequeGeneration.saveRows(b, List.of(row("620009", B_START, B_START, "3000")));
        PostLeaseDryRunResponse dry = posting.dryRun(b);
        assertThat(dry.ok()).isFalse();
        assertThat(String.join(" ", dry.errors())).contains("780.82 still to collect");
        assertThatThrownBy(() -> posting.post(b)).isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("780.82 still to collect");
        assertThat(lease(a).getStatus()).as("rolled back").isEqualTo(LeaseStatus.ACTIVE);
        assertThat(chequeOn(a, OCT).getStatus()).isEqualTo(ChequeStatus.REGISTERED);

        // Re-drafted with July kept on A (in the drawer until its date): its money
        // reaches B through C, so October carried plus 3,780.82 still adds up.
        tx.executeWithoutResult(st -> leaseService.deleteDraftLease(b));
        UUID b2 = transfers.draft(a, new TransferLeaseRequest(T, target.getId(), null, null,
                List.of(line("RENT", "41589.04")), List.of(new TransferLeaseRequest.ChequeDisposition(
                        chequeOn(a, JUL).getId(), "KEEP"))), posting).getId();
        chequeGeneration.saveRows(b2, List.of(row("620010", B_START, B_START, "3780.82")));
        posting.post(b2);
        assertThat(chequeOn(a, JUL).getStatus()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(register(b2)).filteredOn(c -> c.getTransferredFromId() != null).singleElement()
                .satisfies(c -> assertThat(c.getChequeDate()).isEqualTo(OCT));
        assertThat(lease(a).getStatus()).as("July is still to collect on A").isEqualTo(LeaseStatus.TERMINATED);
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, a, fixtures.property())).isZero();
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, b2, fixtures.property())).isZero();
        Cheque july = chequeOn(a, JUL);
        chequeService.deposit(july.getId(), ChequeActionRequest.on(JUL));
        chequeService.clear(july.getId(), ChequeActionRequest.on(JUL));
        assertThat(balanceOf(AccountRole.PDC_RECEIVABLE, a, fixtures.property())).isZero();
        assertTrialBalanceBalances();
    }

    /** No lines sent: A's recurring lines at A's day rate, the typed rent in place of the suggestion, no deposit line. */
    @Test
    void theTypedRentReplacesTheSuggestionAndTheDepositIsNotChargedAgain() {
        UUID a = leaseA(true);
        Unit target = tx.execute(s -> fixtures.createUnit(fixtures.property(), "A-205"));
        LeaseDTO b = transfers.draft(a, new TransferLeaseRequest(T, target.getId(), null, null, null, null,
                new BigDecimal("41589.04")), posting);
        List<com.datagami.rentaxis.api.dto.lease.LeaseLineDTO> lines = tx.execute(s -> leaseService.getLines(b.getId()));
        assertThat(lines).extracting(com.datagami.rentaxis.api.dto.lease.LeaseLineDTO::chargeTypeCode,
                        com.datagami.rentaxis.api.dto.lease.LeaseLineDTO::grossAmount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("RENT", new BigDecimal("41589.04")));
        assertThat(b.getTransferredFromLeaseId()).isEqualTo(a);
        assertThat(b.getTransferMoveDate()).isEqualTo(T);
    }

    /**
     * PR #359 R2: a lease posted under the at-posting rule (every lease before #358,
     * and every cut-over) charged its annual parking fee for the whole term at posting;
     * that charge reaches B through C. B's default lines leave the fee out, and a fee
     * line added to B by hand is refused at post (refused rather than warned: posting
     * it would bill the renter twice).
     */
    @Test
    void aFeeChargedAtPostingIsNotChargedAgainOnTheNewLease() {
        UUID a = fixtures.draftLease(CONTRACT, START, END,
                List.of(line("RENT", "60000"), line("SECURITY_DEPOSIT", "3000"), line("PARKING_FEE", "3650")));
        jdbc.update("update leases set fee_timing = 'AT_POSTING' where id = ?", a);
        chequeGeneration.saveRows(a, List.of(
                row("630040", CONTRACT, CONTRACT, "3000"),
                row("630041", CONTRACT, JAN, "18650"),
                row("630042", CONTRACT, APR, "15000"),
                row("630043", CONTRACT, JUL, "15000"),
                row("630044", CONTRACT, OCT, "15000")));
        posting.post(a);
        for (LocalDate d : List.of(CONTRACT, JAN, APR)) {
            Cheque c = chequeOn(a, d);
            chequeService.deposit(c.getId(), ChequeActionRequest.on(d));
            chequeService.clear(c.getId(), ChequeActionRequest.on(d));
        }
        Unit target = tx.execute(s -> fixtures.createUnit(fixtures.property(), "A-207"));
        LeaseDTO byDefault = transfers.draft(a, request(T, target.getId(), null), posting);
        List<com.datagami.rentaxis.api.dto.lease.LeaseLineDTO> lines = tx.execute(s -> leaseService.getLines(byDefault.getId()));
        assertThat(lines).extracting(com.datagami.rentaxis.api.dto.lease.LeaseLineDTO::chargeTypeCode).containsExactly("RENT");
        tx.executeWithoutResult(st -> leaseService.deleteDraftLease(byDefault.getId()));

        LeaseDTO byHand = transfers.draft(a, request(T, target.getId(),
                List.of(line("RENT", "41589.04"), line("PARKING_FEE", "2304.66"))), posting);
        chequeGeneration.saveRows(byHand.getId(), List.of(row("630050", B_START, B_START, "6085.48")));
        assertThatThrownBy(() -> posting.post(byHand.getId()))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.transferFeeChargedTwice");
        assertThat(lease(a).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
    }

    /**
     * PR #359 R2: a parking deposit carried across properties twice stays a parking
     * deposit, and the third lease's ledger still sees it.
     */
    @Test
    void aParkingDepositStaysAParkingDepositOverTwoCrossPropertyTransfers() {
        UUID a = fixtures.draftLease(CONTRACT, START, END, List.of(line("RENT", "60000"), line("PARKING_DEPOSIT", "500")));
        chequeGeneration.saveRows(a, List.of(
                row("640040", CONTRACT, CONTRACT, "500"),
                row("640041", CONTRACT, JAN, "15000"),
                row("640042", CONTRACT, APR, "15000"),
                row("640043", CONTRACT, JUL, "15000"),
                row("640044", CONTRACT, OCT, "15000")));
        posting.post(a);
        for (LocalDate d : List.of(CONTRACT, JAN, APR)) {
            Cheque c = chequeOn(a, d);
            chequeService.deposit(c.getId(), ChequeActionRequest.on(d));
            chequeService.clear(c.getId(), ChequeActionRequest.on(d));
        }
        Property p2 = tx.execute(s -> fixtures.createProperty("P2"));
        UUID b = draftB(a, tx.execute(s -> fixtures.createUnit(p2, "P2-1")), "41589.04");
        posting.post(b);
        Property p3 = tx.execute(s -> fixtures.createProperty("P3"));
        LocalDate t2 = LocalDate.of(2026, 6, 30);
        LeaseDTO c = transfers.draft(b, new TransferLeaseRequest(t2, tx.execute(s -> fixtures.createUnit(p3, "P3-1")).getId(),
                null, null, List.of(line("RENT", "60000")), null), posting);
        UUID cId = c.getId();
        var est = tx.execute(s -> transfers.estimateForDryRun(leaseRepo.findById(cId).orElseThrow()));
        BigDecimal ownRows = new BigDecimal("60000").add(est.carriedBalance()).subtract(est.carriedTotal());
        chequeGeneration.saveRows(cId, List.of(row("640050", t2.plusDays(1), t2.plusDays(1), ownRows.toPlainString())));
        posting.post(cId);
        assertThat(balanceOf(AccountRole.PARKING_DEPOSIT, cId, p3)).isEqualByComparingTo("-500");
        assertThat(balanceOf(AccountRole.SECURITY_DEPOSIT, cId, p3)).isZero();
        BigDecimal held = tx.execute(s -> depositLedger.depositHeld(leaseRepo.findById(cId).orElseThrow()));
        assertThat(held).isEqualByComparingTo("500");
        assertTrialBalanceBalances();
    }

    @Test
    void refusals() {
        UUID a = leaseA(true);
        Unit target = tx.execute(s -> fixtures.createUnit(fixtures.property(), "A-204"));
        assertThatThrownBy(() -> transfers.draft(a, request(END, target.getId(), null), posting))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.transferOnLastDay");
        assertThatThrownBy(() -> transfers.draft(a, request(T, fixtures.unit().getId(), null), posting))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.transferSameUnit");
        UUID b = draftB(a, target, "41589.04");
        assertThatThrownBy(() -> transfers.draft(a, request(T, target.getId(), null), posting))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.transferExists");
        assertThatThrownBy(() -> renewal.renew(a, new com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest(
                null, LocalDate.of(2027, 1, 1), LocalDate.of(2027, 12, 31), null, true)))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.renewHasTransfer");
        // B moved off the day after the move date is refused at post.
        jdbc.update("update leases set start_date = ? where id = ?", LocalDate.of(2026, 5, 20), b);
        assertThatThrownBy(() -> posting.post(b)).hasMessageContaining("must start the day after the move date");
        // Only paper still in the drawer can be carried (a banked row is simulated here).
        tx.executeWithoutResult(st -> leaseService.deleteDraftLease(b));
        jdbc.update("update cheques set status = 'DEPOSITED' where id = ?", chequeOn(a, JUL).getId());
        assertThatThrownBy(() -> transfers.draft(a, new TransferLeaseRequest(T, target.getId(), null, null,
                List.of(line("RENT", "41589.04")), List.of(new TransferLeaseRequest.ChequeDisposition(
                        chequeOn(a, JUL).getId(), "CARRY"))), posting))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.transferCarryNotRegistered");
    }

    // ------------------------------------------------------------------

    private UUID leaseA(boolean aprilCleared) {
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END, List.of(line("RENT", "60000"), line("SECURITY_DEPOSIT", "3000")));
        chequeGeneration.saveRows(leaseId, List.of(
                row("610040", CONTRACT, CONTRACT, "3000"),
                row("610041", CONTRACT, JAN, "15000"),
                row("610042", CONTRACT, APR, "15000"),
                row("610043", CONTRACT, JUL, "15000"),
                row("610044", CONTRACT, OCT, "15000")));
        posting.post(leaseId);
        for (LocalDate d : aprilCleared ? List.of(CONTRACT, JAN, APR) : List.of(CONTRACT, JAN)) {
            Cheque c = chequeOn(leaseId, d);
            chequeService.deposit(c.getId(), ChequeActionRequest.on(d));
            chequeService.clear(c.getId(), ChequeActionRequest.on(d));
        }
        return leaseId;
    }

    private UUID draftB(UUID a, Unit target, String rent) {
        LeaseDTO b = transfers.draft(a, request(T, target.getId(), List.of(line("RENT", rent))), posting);
        chequeGeneration.saveRows(b.getId(), List.of(row("620001", B_START, B_START, "3780.82")));
        return b.getId();
    }

    private static TransferLeaseRequest request(LocalDate t, UUID unit, List<LeaseLineInput> lines) {
        return new TransferLeaseRequest(t, unit, null, null, lines, null);
    }

    private static ChequeRowInput row(String number, LocalDate postingDate, LocalDate chequeDate, String amount) {
        return new ChequeRowInput(null, null, postingDate, number, chequeDate, "Emirates NBD",
                null, null, new BigDecimal(amount), null, null);
    }

    private Lease lease(UUID id) {
        return tx.execute(s -> leaseRepo.findById(id).orElseThrow());
    }

    private Unit unit(UUID id) {
        return tx.execute(s -> unitRepo.findById(id).orElseThrow());
    }

    private List<Cheque> register(UUID leaseId) {
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    private Cheque chequeOn(UUID leaseId, LocalDate chequeDate) {
        return register(leaseId).stream().filter(c -> chequeDate.equals(c.getChequeDate())).findFirst().orElseThrow();
    }

    private JournalEntry journal(UUID id) {
        return tx.execute(s -> journals.findById(id).orElseThrow());
    }

    private List<JournalLine> linesOf(UUID entryId) {
        return tx.execute(s -> journalLines.findByEntry_IdOrderByLineNoAsc(entryId));
    }

    private UUID leaf(AccountRole role, Property property) {
        return tx.execute(s -> resolver.resolve(role, property.getId())).getId();
    }

    private BigDecimal debitOn(JournalEntry entry, AccountRole role, Property property) {
        UUID id = leaf(role, property);
        return linesOf(entry.getId()).stream().filter(l -> id.equals(l.getAccountId()))
                .map(JournalLine::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal balanceOf(AccountRole role, UUID leaseId, Property property) {
        UUID id = leaf(role, property);
        return tx.execute(s -> ledger.accountLedger(id,
                new LedgerQueryService.LedgerFilter(null, null, null, null, leaseId, null)).closingBalance());
    }

    private long journalCount(JournalDocType docType) {
        return jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ? and doc_type = ?",
                Long.class, fixtures.tenantId(), docType.name());
    }

    private void assertTrialBalanceBalances() {
        List<TrialBalanceRowDTO> rows = tx.execute(s -> ledger.trialBalance(LocalDate.of(2030, 1, 1), null));
        BigDecimal debit = rows.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = rows.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(debit).as("trial balance").isEqualByComparingTo(credit);
    }
}

package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.cheque.ChequeActionRequest;
import com.datagami.rentaxis.api.dto.lease.AddendumResponse;
import com.datagami.rentaxis.api.dto.lease.ChequeRowInput;
import com.datagami.rentaxis.api.dto.lease.LeaseAddendumDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.api.dto.lease.ReduceLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.ReductionPreviewDTO;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.cheque.ChequeService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.service.vat.VatTaxPointService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.entity.enums.VatTiming;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F14-32: a mid-term reduction as a numbered credit addendum.
 *
 * <p>The fixture is the termination suite's: 51,000 of rent for 24/09/2026 →
 * 23/09/2027 (365 days, 139.726027 a day), a 2,000 admin fee and four quarterly
 * rent cheques of 12,750. From 01/03/2027 the rent drops to 39,000 a year: 158 days
 * are earned (22,076.71), 28,923.29 is left to earn, and the 207 days left at the new
 * rate are worth 22,117.81 — a credit of 6,805.48 (= 12,000 × 207 ÷ 365).</p>
 */
@SpringBootTest
class LeaseReductionIT extends AbstractPostgresIT {

    @Autowired LeaseReductionService reductions;
    @Autowired LeaseRenewalService renewal;
    @Autowired LeaseTransferService transfers;
    @Autowired LeaseVariationService variations;
    @Autowired LeaseTerminationService termination;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired ChequeService chequeService;
    @Autowired RecognitionService recognition;
    @Autowired VatTaxPointService vatTaxPoints;
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

    private static final LocalDate CONTRACT_DATE = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 9, 24);
    private static final LocalDate END = LocalDate.of(2027, 9, 23);
    private static final LocalDate ADMIN_CHEQUE = LocalDate.of(2026, 9, 11);
    private static final LocalDate RENT_1 = LocalDate.of(2026, 10, 2);
    private static final LocalDate RENT_2 = LocalDate.of(2027, 1, 2);
    private static final LocalDate RENT_3 = LocalDate.of(2027, 4, 2);
    private static final LocalDate RENT_4 = LocalDate.of(2027, 7, 2);
    private static final LocalDate E = LocalDate.of(2027, 3, 1);
    private static final LocalDate SIGNED = LocalDate.of(2027, 2, 20);

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

    @Test
    void aRentCutIsCreditedDayByDayAndLeftOnTheRentersAccount() {
        UUID leaseId = galahWithTwoCleared();
        UUID rentLine = lineId(leaseId, "RENT");

        ReductionPreviewDTO preview = reductions.preview(leaseId, credit(rentLine, "39000"));
        assertThat(preview.problems()).isEmpty();
        assertThat(preview.lines()).singleElement().satisfies(l -> {
            assertThat(l.remainingDays()).isEqualTo(207);
            assertThat(l.remainingBefore()).isEqualByComparingTo("28923.29");
            assertThat(l.remainingAfter()).isEqualByComparingTo("22117.81");
            assertThat(l.credit()).isEqualByComparingTo("6805.48");
            assertThat(l.vat()).isEqualByComparingTo("0");
        });
        assertThat(preview.creditTotal()).isEqualByComparingTo("6805.48");
        assertThat(preview.returnable()).extracting(ReductionPreviewDTO.ReturnableCheque::chequeDate)
                .containsExactly(RENT_3, RENT_4);

        AddendumResponse done = reductions.reduce(leaseId, credit(rentLine, "39000"));
        LeaseAddendumDTO addendum = done.addendum();
        assertThat(addendum.kind()).isEqualTo("CREDIT");
        assertThat(addendum.excess()).isEqualTo("CREDIT");
        assertThat(addendum.addendumNumber()).startsWith("ADD-");
        assertThat(addendum.value()).isEqualByComparingTo("-6805.48");
        assertThat(addendum.credits()).singleElement().satisfies(c -> {
            assertThat(c.chargeTypeCode()).isEqualTo("RENT");
            assertThat(c.creditAmount()).isEqualByComparingTo("6805.48");
        });

        JournalEntry tcc = journal(addendum.tcoJournalId());
        assertThat(tcc.getDocType()).isEqualTo(JournalDocType.TCC);
        assertThat(tcc.getEntryDate()).isEqualTo(SIGNED);
        assertThat(linesOf(tcc.getId())).hasSize(2);
        assertThat(debitOn(tcc, AccountRole.ADVANCE_RENT)).isEqualByComparingTo("6805.48");
        assertThat(creditOn(tcc, AccountRole.RENT_RECEIVABLE)).isEqualByComparingTo("6805.48");
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).as("a credit on the renter's account")
                .isEqualByComparingTo("-6805.48");

        // The schedule: February re-cut to close the old rate's earned total exactly
        // (22,076.71 through 28/02; the last kept month absorbs the rounding, 3,912.32
        // against 3,912.33 planned), March at the new day rate, and the whole
        // term worth exactly what was earned plus the rest at the new rate.
        recognition.runTo(END, false);
        List<RecognitionEntryDTO> posted = recognition.scheduleFor(leaseId).stream()
                .filter(r -> r.status() == RecognitionStatus.POSTED).toList();
        assertThat(posted.stream().map(RecognitionEntryDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("44194.52");
        assertThat(posted).filteredOn(r -> r.periodStart().equals(LocalDate.of(2027, 2, 1)))
                .extracting(RecognitionEntryDTO::amount).containsExactly(new BigDecimal("3912.32"));
        assertThat(posted).filteredOn(r -> r.periodStart().equals(E))
                .extracting(RecognitionEntryDTO::amount).containsExactly(new BigDecimal("3312.33"));
        assertThat(balanceOf(AccountRole.ADVANCE_RENT, leaseId)).as("nothing left unearned at the end")
                .isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from lease_events where lease_id = ? and notes like 'Credit addendum %'",
                Long.class, leaseId)).isEqualTo(1);
        assertTrialBalanceBalances();
    }

    @Test
    void handingBackAChequeAndReplacingItFundsTheCredit() {
        UUID leaseId = galahWithTwoCleared();
        UUID rentLine = lineId(leaseId, "RENT");
        Cheque july = chequeOn(leaseId, RENT_4);

        ReduceLeaseRequest wrong = cheques(rentLine, "39000", july.getId(), "6000.00");
        assertThat(reductions.preview(leaseId, wrong).gap()).isEqualByComparingTo("55.48");
        assertThatThrownBy(() -> reductions.reduce(leaseId, wrong))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("55.48 short")
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.reductionChequeGap");

        reductions.reduce(leaseId, cheques(rentLine, "39000", july.getId(), "5944.52"));
        assertThat(chequeOn(leaseId, RENT_4).getStatus()).isEqualTo(ChequeStatus.RETURNED);
        Cheque replacement = register(leaseId).stream()
                .filter(c -> c.getAmount().compareTo(new BigDecimal("5944.52")) == 0).findFirst().orElseThrow();
        assertThat(replacement.getStatus()).isEqualTo(ChequeStatus.REGISTERED);
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).as("the instruments now match what is owed")
                .isEqualByComparingTo("0");
        assertTrialBalanceBalances();
    }

    /**
     * VAT per instalment: the credit's VAT (340.27) comes off what is still to be
     * declared. The July instalment handed back carried 637.50 of it; the 297.23 the
     * credit does not remove moves onto the replacement row.
     */
    @Test
    void onAnInstalmentVatLeaseTheCreditsVatComesOffThePlannedTaxPoints() {
        UUID leaseId = commercialGalah();
        UUID rentLine = lineId(leaseId, "RENT");
        Cheque july = chequeOn(leaseId, RENT_4);

        ReductionPreviewDTO preview = reductions.preview(leaseId, cheques(rentLine, "39000", july.getId(), "6241.75"));
        assertThat(preview.vatFromDeferred()).isEqualByComparingTo("340.27");
        assertThat(preview.vatCreditNote()).isEqualByComparingTo("0");
        assertThat(preview.creditTotal()).isEqualByComparingTo("7145.75");
        assertThat(preview.gap()).isEqualByComparingTo("0");

        AddendumResponse done = reductions.reduce(leaseId, cheques(rentLine, "39000", july.getId(), "6241.75"));
        JournalEntry tcc = journal(done.addendum().tcoJournalId());
        assertThat(debitOn(tcc, AccountRole.ADVANCE_RENT)).isEqualByComparingTo("6805.48");
        assertThat(debitOn(tcc, AccountRole.OUTPUT_VAT_DEFERRED)).isEqualByComparingTo("340.27");
        assertThat(creditOn(tcc, AccountRole.RENT_RECEIVABLE)).isEqualByComparingTo("7145.75");

        assertThat(chequeOn(leaseId, RENT_4).getVatAmount()).isEqualByComparingTo("0");
        Cheque replacement = register(leaseId).stream()
                .filter(c -> c.getAmount().compareTo(new BigDecimal("6241.75")) == 0).findFirst().orElseThrow();
        assertThat(replacement.getVatAmount()).isEqualByComparingTo("297.23");
        assertThat(jdbc.queryForObject("select coalesce(sum(vat_amount), 0) from vat_tax_points where lease_id = ?"
                + " and status = 'PLANNED'", BigDecimal.class, leaseId)).isEqualByComparingTo("2209.73");
        assertThat(balanceOf(AccountRole.OUTPUT_VAT_DEFERRED, leaseId)).isEqualByComparingTo("-2209.73");
        assertThat(balanceOf(AccountRole.RENT_RECEIVABLE, leaseId)).isEqualByComparingTo("0");
        assertTrialBalanceBalances();
    }

    /** Every instalment's VAT already declared: the credit's VAT is credited back with a tax credit note. */
    @Test
    void vatAlreadyDeclaredIsCreditedBackWithACreditNote() {
        UUID leaseId = commercialGalah();
        UUID rentLine = lineId(leaseId, "RENT");
        vatTaxPoints.runTo(RENT_4, false);
        assertThat(jdbc.queryForObject("select count(*) from vat_tax_points where lease_id = ? and status = 'PLANNED'",
                Long.class, leaseId)).isZero();

        AddendumResponse done = reductions.reduce(leaseId, credit(rentLine, "39000"));
        JournalEntry tcc = journal(done.addendum().tcoJournalId());
        assertThat(debitOn(tcc, AccountRole.OUTPUT_VAT)).isEqualByComparingTo("340.27");
        assertThat(debitOn(tcc, AccountRole.OUTPUT_VAT_DEFERRED)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from tax_invoices where lease_id = ? and kind = 'CREDIT_NOTE'"
                + " and vat_amount = 340.27 and invoice_number like 'TCN-%'", Long.class, leaseId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from vat_tax_points where lease_id = ? and kind = 'REDUCTION'"
                + " and status = 'POSTED' and vat_amount = -340.27", Long.class, leaseId)).isEqualTo(1);
        assertTrialBalanceBalances();
    }

    /** A termination after the reduction hands back only what the new rate left unearned. */
    @Test
    void aTerminationAfterAReductionRefundsOnlyTheNewRate() {
        UUID leaseId = galahWithTwoCleared();
        reductions.reduce(leaseId, credit(lineId(leaseId, "RENT"), "39000"));
        LocalDate t = LocalDate.of(2027, 6, 15);
        termination.terminate(leaseId, new TerminateLeaseRequest(t, null, null, null), null);
        JournalEntry tcr = journal(tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow()).getTerminationJournalId());
        assertThat(debitOn(tcr, AccountRole.ADVANCE_RENT)).isEqualByComparingTo("10684.93");
        recognition.runTo(t, false);
        assertThat(balanceOf(AccountRole.ADVANCE_RENT, leaseId)).isEqualByComparingTo("0");
        assertTrialBalanceBalances();
    }

    /**
     * A reduction from 01/06/2027 (credit 3,780.82), then a termination on 15/04/2027,
     * before it took effect: the unearned rent is the old rate's 16/04 → 31/05
     * (6,427.40) plus the whole of the new rate's rest of term (12,287.67).
     */
    @Test
    void aTerminationBeforeTheReductionTakesEffectStillCutsTheOldRate() {
        UUID leaseId = galahWithTwoCleared();
        LocalDate june = LocalDate.of(2027, 6, 1);
        AddendumResponse done = reductions.reduce(leaseId, new ReduceLeaseRequest(june, SIGNED, null, null,
                List.of(new ReduceLeaseRequest.LineReduction(lineId(leaseId, "RENT"), new BigDecimal("39000"))),
                "CREDIT", List.of(), List.of()));
        assertThat(done.addendum().value()).isEqualByComparingTo("-3780.82");
        LocalDate t = LocalDate.of(2027, 4, 15);
        termination.terminate(leaseId, new TerminateLeaseRequest(t, null, null, null), null);
        JournalEntry tcr = journal(tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow()).getTerminationJournalId());
        assertThat(debitOn(tcr, AccountRole.ADVANCE_RENT)).isEqualByComparingTo("18715.07");
        recognition.runTo(t, false);
        assertThat(balanceOf(AccountRole.ADVANCE_RENT, leaseId)).isEqualByComparingTo("0");
        assertTrialBalanceBalances();
    }

    /**
     * PR #359 R1 P2-1: after 51,000 → 39,000, everything that reads the lease's current
     * terms reads 39,000 — a "no change" renewal keeps 39,000, +5% gives 40,950, the
     * header's current rent is 39,000 (the contract rent stays 51,000) — and a
     * transfer does not bring back a charge the addendum removed.
     */
    @Test
    void theReducedTermsAreWhatRenewalTransferAndTheHeaderRead() {
        UUID leaseId = galahWithTwoCleared();
        reductions.reduce(leaseId, credit(lineId(leaseId, "RENT"), "39000"));
        LocalDate from = LocalDate.of(2027, 9, 24), to = LocalDate.of(2028, 9, 23);
        var same = renewal.planFor(leaseId, new com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest(null, from, to,
                null, true, null, null, null));
        assertThat(same.baseRent()).isEqualByComparingTo("39000");
        assertThat(same.newRent()).isEqualByComparingTo("39000");
        var up = renewal.planFor(leaseId, new com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest(null, from, to,
                null, true, new com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest.RentChange(
                        com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest.RentChange.Mode.PERCENT, new BigDecimal("5"), null),
                null, null));
        assertThat(up.newRent()).isEqualByComparingTo("40950");
        var dto = tx.execute(s -> leaseService.getLeaseById(leaseId));
        assertThat(dto.getRentAmount()).isEqualByComparingTo("51000");
        // PR #359 R2: the cut takes effect on 01/03/2027 — until then the rent charged is the contract's.
        assertThat(dto.getCurrentRentAmount()).isEqualByComparingTo(LocalDate.now().isBefore(E) ? "51000" : "39000");
        assertThat(dto.getLines()).filteredOn(l -> "RENT".equals(l.chargeTypeCode())).singleElement()
                .satisfies(l -> assertThat(l.currentAmount()).isEqualByComparingTo("39000"));
    }

    @Test
    void aTransferDoesNotBringBackARemovedFee() {
        UUID leaseId = fixtures.postedLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("PARKING_FEE", "3650")), 4, null).lease().getId();
        reductions.reduce(leaseId, credit(lineId(leaseId, "PARKING_FEE"), "0"));
        var plan = renewal.planFor(leaseId, new com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest(null,
                LocalDate.of(2027, 9, 24), LocalDate.of(2028, 9, 23), null, true));
        assertThat(plan.lines()).as("nor does a renewal").hasSize(1);
        var unit = tx.execute(s -> fixtures.createUnit(fixtures.property(), "R-2"));
        var b = transfers.draft(leaseId, new com.datagami.rentaxis.api.dto.lease.TransferLeaseRequest(
                LocalDate.of(2027, 3, 15), unit.getId(), null, null, null, null), posting);
        List<LeaseLineDTO> bLines = tx.execute(s -> leaseService.getLines(b.getId()));
        assertThat(bLines).extracting(LeaseLineDTO::chargeTypeCode).containsExactly("RENT");
    }

    @Test
    void refusals() {
        UUID leaseId = galah();
        UUID rentLine = lineId(leaseId, "RENT");
        assertThatThrownBy(() -> reductions.reduce(leaseId, credit(rentLine, "-1")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.reductionNegative");
        assertThatThrownBy(() -> reductions.reduce(leaseId, credit(rentLine, "60000")))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.reductionNotLower");
        assertThatThrownBy(() -> reductions.reduce(leaseId, credit(lineId(leaseId, "ADMIN_FEE"), "0")))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.reductionNotScheduled");
        assertThatThrownBy(() -> reductions.reduce(leaseId, new ReduceLeaseRequest(E, SIGNED, null, null,
                List.of(new ReduceLeaseRequest.LineReduction(rentLine, new BigDecimal("39000"))), "CREDIT",
                List.of(chequeOn(leaseId, RENT_4).getId()), List.of())))
                .hasMessageContaining("hands back no instalments");
        assertThat(journalCount(JournalDocType.TCC)).isZero();

        // Removing the charge altogether is a reduction to zero.
        reductions.reduce(leaseId, credit(rentLine, "0"));
        assertThat(recognition.scheduleFor(leaseId).stream()
                .filter(r -> r.status() == RecognitionStatus.PLANNED && !r.periodStart().isBefore(E))).isEmpty();
        // An amendment would repost the lines without the credit.
        List<com.datagami.rentaxis.api.dto.lease.LeaseLineInput> same = List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000"));
        assertThatThrownBy(() -> posting.amendLines(leaseId, same, "x"))
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("lease.amendAfterCredit");
    }

    @Test
    void anUnassignedPropertyManagerCannotReduce() {
        UUID leaseId = galah();
        UUID rentLine = lineId(leaseId, "RENT");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(UUID.randomUUID().toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_PROPERTY_MANAGER"))));
        assertThatThrownBy(() -> reductions.reduce(leaseId, credit(rentLine, "39000")))
                .isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private UUID galah() {
        UUID leaseId = fixtures.draftLease(CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("ADMIN_FEE", "2000")));
        chequeGeneration.saveRows(leaseId, List.of(
                row("300040", ADMIN_CHEQUE, ADMIN_CHEQUE, "2000"),
                row("300041", CONTRACT_DATE, RENT_1, "12750"),
                row("300042", CONTRACT_DATE, RENT_2, "12750"),
                row("300043", CONTRACT_DATE, RENT_3, "12750"),
                row("300044", CONTRACT_DATE, RENT_4, "12750")));
        posting.post(leaseId);
        return leaseId;
    }

    private UUID galahWithTwoCleared() {
        UUID leaseId = galah();
        clearOnItsOwnDate(chequeOn(leaseId, ADMIN_CHEQUE));
        clearOnItsOwnDate(chequeOn(leaseId, RENT_1));
        clearOnItsOwnDate(chequeOn(leaseId, RENT_2));
        return leaseId;
    }

    private UUID commercialGalah() {
        UUID leaseId = fixtures.draftLease(CONTRACT_DATE, START, END,
                List.of(LeaseTestFixtures.vatLine("RENT", "51000")));
        jdbc.update("update leases set vat_timing = ? where id = ?", VatTiming.INSTALMENT.name(), leaseId);
        chequeGeneration.saveRows(leaseId, List.of(
                row("400041", CONTRACT_DATE, RENT_1, "13387.50"),
                row("400042", CONTRACT_DATE, RENT_2, "13387.50"),
                row("400043", CONTRACT_DATE, RENT_3, "13387.50"),
                row("400044", CONTRACT_DATE, RENT_4, "13387.50")));
        posting.post(leaseId);
        return leaseId;
    }

    private static ReduceLeaseRequest credit(UUID lineId, String newAmount) {
        return new ReduceLeaseRequest(E, SIGNED, "Rent renegotiated", null,
                List.of(new ReduceLeaseRequest.LineReduction(lineId, new BigDecimal(newAmount))), "CREDIT",
                List.of(), List.of());
    }

    private static ReduceLeaseRequest cheques(UUID lineId, String newAmount, UUID returned, String replacement) {
        return new ReduceLeaseRequest(E, SIGNED, "Rent renegotiated", null,
                List.of(new ReduceLeaseRequest.LineReduction(lineId, new BigDecimal(newAmount))), "CHEQUES",
                List.of(returned), List.of(new ChequeRowInput(null, null, null, LeaseTestFixtures.nextChequeNumber(),
                        RENT_4, "Emirates NBD", null, null, new BigDecimal(replacement), null, null)));
    }

    private static ChequeRowInput row(String number, LocalDate postingDate, LocalDate chequeDate, String amount) {
        return new ChequeRowInput(null, null, postingDate, number, chequeDate, "Emirates NBD",
                null, null, new BigDecimal(amount), null, null);
    }

    private void clearOnItsOwnDate(Cheque cheque) {
        chequeService.deposit(cheque.getId(), ChequeActionRequest.on(cheque.getChequeDate()));
        chequeService.clear(cheque.getId(), ChequeActionRequest.on(cheque.getChequeDate()));
    }

    private UUID lineId(UUID leaseId, String code) {
        return tx.execute(s -> leaseService.getLines(leaseId)).stream()
                .filter(l -> code.equals(l.chargeTypeCode())).map(LeaseLineDTO::id).findFirst().orElseThrow();
    }

    private List<Cheque> register(UUID leaseId) {
        return tx.execute(s -> chequeRepo.findByLease_IdOrderBySeqNoAsc(leaseId));
    }

    private Cheque chequeOn(UUID leaseId, LocalDate chequeDate) {
        return register(leaseId).stream().filter(c -> chequeDate.equals(c.getChequeDate())
                        && c.getStatus() != ChequeStatus.DRAFT && c.getSeqNo() <= 5)
                .findFirst().orElseThrow();
    }

    private JournalEntry journal(UUID id) {
        return tx.execute(s -> journals.findById(id).orElseThrow());
    }

    private List<JournalLine> linesOf(UUID entryId) {
        return tx.execute(s -> journalLines.findByEntry_IdOrderByLineNoAsc(entryId));
    }

    private UUID leaf(AccountRole role) {
        return tx.execute(s -> resolver.resolve(role, fixtures.property().getId())).getId();
    }

    private BigDecimal debitOn(JournalEntry entry, AccountRole role) {
        UUID id = leaf(role);
        return linesOf(entry.getId()).stream().filter(l -> id.equals(l.getAccountId()))
                .map(JournalLine::getDebit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal creditOn(JournalEntry entry, AccountRole role) {
        UUID id = leaf(role);
        return linesOf(entry.getId()).stream().filter(l -> id.equals(l.getAccountId()))
                .map(JournalLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal balanceOf(AccountRole role, UUID leaseId) {
        UUID id = leaf(role);
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

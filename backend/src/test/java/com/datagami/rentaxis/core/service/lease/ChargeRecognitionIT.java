package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TerminationPreviewDTO;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService.LedgerFilter;
import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.RentSegment;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.FeeTiming;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.JournalLineRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.RentSegmentRepository;
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
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F14-18 (round-15 ruling): a charge type decides how a fee is earned.
 *
 * <p>The fixture is a 365-day term, 24/09/2026 → 23/09/2027, with 51,000 of rent,
 * a 3,650 annual parking fee (10.00 a day, so every month is days × 10 exactly),
 * a 1,000 admin fee and 650 of DEWA recovered at cost.</p>
 */
@SpringBootTest
class ChargeRecognitionIT extends AbstractPostgresIT {

    @Autowired LeasePostingService posting;
    @Autowired LeaseTerminationService termination;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired RecognitionService recognition;
    @Autowired LeaseService leaseService;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired AccountRepository accounts;
    @Autowired RentSegmentRepository segments;
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
    private static final LocalDate T = LocalDate.of(2027, 2, 15);

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

    private static final List<com.datagami.rentaxis.api.dto.lease.LeaseLineInput> LINES = List.of(
            line("RENT", "51000"), line("PARKING_FEE", "3650"), line("ADMIN_FEE", "1000"), line("UTILITIES", "650"));

    private UUID posted() {
        return fixtures.postedLease(CONTRACT_DATE, START, END, LINES, 4, null).lease().getId();
    }

    /** A lease posted before round 15: fees are income at posting. */
    private UUID postedTheOldWay() {
        UUID leaseId = fixtures.draftLease(CONTRACT_DATE, START, END, LINES);
        jdbc.update("update leases set fee_timing = 'AT_POSTING' where id = ?", leaseId);
        fixtures.generateGrid(leaseId, 4, START);
        posting.post(leaseId);
        return leaseId;
    }

    @Test
    void aPeriodicFeeIsDeferredAtPostingAndEarnedMonthByMonth() {
        UUID leaseId = posted();
        FeeTiming timing = tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow().getFeeTiming());
        assertThat(timing).isEqualTo(FeeTiming.OVER_TERM);

        // The TCO: parking and the DEWA recovery are parked in Unearned charges; the
        // admin fee is income now. DEWA is released to the Utilities expense leaf as it
        // is recovered (PR #358 R1) — never to income.
        UUID tcoId = tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow().getPostingJournalId());
        List<JournalLine> tco = tx.execute(s -> journalLines.findByEntry_IdOrderByLineNoAsc(tcoId));
        assertThat(credit(tco, leaf(AccountRole.UNEARNED_CHARGES).getId())).isEqualByComparingTo("4300");
        assertThat(credit(tco, leaf(AccountRole.PARKING_INCOME).getId())).isEqualByComparingTo("0");
        assertThat(credit(tco, leaf(AccountRole.ADMIN_FEE).getId())).isEqualByComparingTo("1000");
        Account utilities = utilitiesLeaf();
        assertThat(utilities.getAccountType()).isEqualTo(com.datagami.rentaxis.domain.entity.enums.AccountType.EXPENSE);
        assertThat(credit(tco, utilities.getId())).isEqualByComparingTo("0");

        // Three segments: rent, parking and the utility recovery, each fee with its own two leaves.
        List<RentSegment> segs = tx.execute(s -> segments.findByLease_IdOrderByFromDateAsc(leaseId));
        assertThat(segs).hasSize(3);
        RentSegment dewa = segs.stream().filter(sg -> utilities.getId().equals(sg.getIncomeAccountId())).findFirst().orElseThrow();
        assertThat(dewa.getAmount()).isEqualByComparingTo("650");
        RentSegment parking = segs.stream().filter(sg -> leaf(AccountRole.PARKING_INCOME).getId().equals(sg.getIncomeAccountId()))
                .findFirst().orElseThrow();
        assertThat(parking.getAmount()).isEqualByComparingTo("3650");
        assertThat(parking.getFromDate()).isEqualTo(START);
        assertThat(parking.getToDate()).isEqualTo(END);
        assertThat(parking.getDeferralAccountId()).isEqualTo(leaf(AccountRole.UNEARNED_CHARGES).getId());
        assertThat(parking.getIncomeAccountId()).isEqualTo(leaf(AccountRole.PARKING_INCOME).getId());

        // The schedule labels the parking rows; per-day: 10.00 × days.
        List<RecognitionEntryDTO> parkingRows = recognition.scheduleFor(leaseId).stream()
                .filter(r -> "PARKING_FEE".equals(r.chargeCode())).toList();
        assertThat(parkingRows).hasSize(13);
        assertThat(parkingRows.get(0).amount()).isEqualByComparingTo("70.00");   // 24–30 Sep
        assertThat(parkingRows.get(1).amount()).isEqualByComparingTo("310.00");  // Oct
        assertThat(parkingRows.stream().map(RecognitionEntryDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("3650");

        // The month-end run releases Sep + Oct to parking income.
        recognition.runTo(LocalDate.of(2026, 10, 31), false);
        assertThat(balanceOf(leaf(AccountRole.PARKING_INCOME).getId(), leaseId)).isEqualByComparingTo("-380");
        BigDecimal dewaRecovered = posted(leaseId, "UTILITIES");
        assertThat(dewaRecovered).isPositive().isLessThan(new BigDecimal("80"));
        assertThat(balanceOf(utilities.getId(), leaseId)).isEqualByComparingTo(dewaRecovered.negate());
        assertThat(balanceOf(leaf(AccountRole.UNEARNED_CHARGES).getId(), leaseId))
                .isEqualByComparingTo(new BigDecimal("-3920").add(dewaRecovered));
    }

    /** F14-37's deferred piece: an early exit hands the unearned part of the fee back. */
    @Test
    void anEarlyTerminationReversesTheUnearnedPartOfThePeriodicFee() {
        UUID leaseId = posted();
        recognition.runTo(LocalDate.of(2027, 1, 31), false);

        TerminationPreviewDTO preview = termination.preview(leaseId, T);
        // Rent unearned 30,739.73 (LeaseTerminationServiceIT's figure) plus parking
        // (earned 24/09 → 15/02 = 145 days = 1,450, unearned 2,200) plus the DEWA
        // recovery not yet due: 650 − 650 × 145 ÷ 365 = 391.78.
        assertThat(preview.unearnedRent()).isEqualByComparingTo("33331.51");

        termination.terminate(leaseId, new TerminateLeaseRequest(T, null, null, "Early exit"), null);
        recognition.runTo(LocalDate.of(2027, 2, 28), false);

        assertThat(balanceOf(leaf(AccountRole.PARKING_INCOME).getId(), leaseId)).isEqualByComparingTo("-1450");
        assertThat(balanceOf(leaf(AccountRole.UNEARNED_CHARGES).getId(), leaseId)).isEqualByComparingTo("0");
        assertThat(posted(leaseId, "UTILITIES")).isEqualByComparingTo("258.22");
        // The one-off admin fee stays earned.
        assertThat(balanceOf(leaf(AccountRole.ADMIN_FEE).getId(), leaseId)).isEqualByComparingTo("-1000");
        assertThat(recognition.scheduleFor(leaseId).stream()
                .filter(r -> "PARKING_FEE".equals(r.chargeCode()) && r.status() == RecognitionStatus.POSTED)
                .map(RecognitionEntryDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("1450");
    }

    /** Leases already on the books are not restated: every fee is income at posting, no fee schedule. */
    @Test
    void aLeasePostedUnderTheOldRuleKeepsFeesAsIncomeAtPosting() {
        UUID leaseId = postedTheOldWay();
        assertThat(balanceOf(leaf(AccountRole.PARKING_INCOME).getId(), leaseId)).isEqualByComparingTo("-3650");
        assertThat(balanceOf(leaf(AccountRole.UNEARNED_CHARGES).getId(), leaseId)).isEqualByComparingTo("0");
        List<RentSegment> segs = tx.execute(s -> segments.findByLease_IdOrderByFromDateAsc(leaseId));
        assertThat(segs).hasSize(1);
        assertThat(recognition.scheduleFor(leaseId)).allSatisfy(r -> assertThat(r.rent()).isTrue());
    }

    /** A pass-through can never be pointed at an income account. */
    @Test
    void aPassThroughLineRefusesAnIncomeAccount() {
        UUID income = leaf(AccountRole.OTHER_INCOME).getId();
        assertThatThrownBy(() -> fixtures.draftLease(CONTRACT_DATE, START, END, List.of(line("RENT", "51000"),
                LeaseTestFixtures.lineCreditedTo("UTILITIES", "650", income))))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("recovered at cost, not income");
    }

    /** The draft line tells the operator which recognition applies. */
    @Test
    void theLinesCarryTheirRecognition() {
        UUID leaseId = fixtures.draftLease(CONTRACT_DATE, START, END, LINES);
        List<LeaseLineDTO> lines = tx.execute(s -> leaseService.getLines(leaseId));
        assertThat(lines).extracting(LeaseLineDTO::chargeTypeCode, LeaseLineDTO::recognition).containsExactly(
                org.assertj.core.groups.Tuple.tuple("RENT", "RENT_LIKE"),
                org.assertj.core.groups.Tuple.tuple("PARKING_FEE", "RENT_LIKE"),
                org.assertj.core.groups.Tuple.tuple("ADMIN_FEE", "ONE_OFF"),
                org.assertj.core.groups.Tuple.tuple("UTILITIES", "PASS_THROUGH"));
        LeaseDTO lease = tx.execute(s -> leaseService.getLeaseById(leaseId));
        assertThat(lease.getSkippedOneOffLines()).isNull();
    }

    /**
     * #99: a charge type a posted lease uses keeps its behaviour and recognition,
     * and each posted line is labelled with what posting did with it (F15-06).
     */
    @Test
    void aChargeTypeInUseKeepsItsRuleAndLinesKeepTheirSnapshot() {
        UUID draftOnly = fixtures.draftLease(CONTRACT_DATE, START, END, List.of(line("RENT", "51000"), line("COOLING", "1200")));
        UUID leaseId = posted();
        assertThat(jdbc.queryForObject("select count(*) from lease_lines where lease_id = ? and posted_recognition is not null",
                Long.class, draftOnly)).as("a draft's lines are not stamped").isZero();
        assertThat(lineDtos(leaseId)).extracting(LeaseLineDTO::chargeTypeCode,
                LeaseLineDTO::recognition, LeaseLineDTO::postedRecognition).containsExactly(
                org.assertj.core.groups.Tuple.tuple("RENT", "RENT_LIKE", "RENT_LIKE"),
                org.assertj.core.groups.Tuple.tuple("PARKING_FEE", "RENT_LIKE", "RENT_LIKE"),
                org.assertj.core.groups.Tuple.tuple("ADMIN_FEE", "ONE_OFF", "ONE_OFF"),
                org.assertj.core.groups.Tuple.tuple("UTILITIES", "PASS_THROUGH", "PASS_THROUGH"));

        var parking = tx.execute(s -> chargeTypeService.list(false)).stream()
                .filter(t -> t.code().equals("PARKING_FEE")).findFirst().orElseThrow();
        var toOneOff = new com.datagami.rentaxis.api.dto.lease.ChargeTypeDTO(parking.id(), "PARKING_FEE", parking.nameEn(),
                parking.nameAr(), parking.role(), parking.behaviour(), parking.vatApplicableDefault(), true,
                parking.displayOrder(), com.datagami.rentaxis.domain.entity.enums.ChargeRecognition.ONE_OFF);
        assertThatThrownBy(() -> tx.execute(s -> chargeTypeService.update(parking.id(), toOneOff)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("PARKING_FEE is on 1 lease(s) already past draft")
                .extracting(e -> ((BusinessRuleViolationException) e).getCode()).isEqualTo("chargeType.ruleInUse");
        var admin = tx.execute(s -> chargeTypeService.list(false)).stream()
                .filter(t -> t.code().equals("ADMIN_FEE")).findFirst().orElseThrow();
        var toDeposit = new com.datagami.rentaxis.api.dto.lease.ChargeTypeDTO(admin.id(), "ADMIN_FEE", admin.nameEn(),
                admin.nameAr(), AccountRole.SECURITY_DEPOSIT, com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour.DEPOSIT,
                false, true, admin.displayOrder(), com.datagami.rentaxis.domain.entity.enums.ChargeRecognition.RENT_LIKE);
        assertThatThrownBy(() -> tx.execute(s -> chargeTypeService.update(admin.id(), toDeposit)))
                .hasMessageContaining("ADMIN_FEE is on 1 lease(s)");
        // A rename is not a rule change; a type only a draft uses may change its rule.
        var renamed = new com.datagami.rentaxis.api.dto.lease.ChargeTypeDTO(parking.id(), "PARKING_FEE", "Covered parking",
                parking.nameAr(), parking.role(), parking.behaviour(), parking.vatApplicableDefault(), true,
                parking.displayOrder(), parking.recognition());
        assertThat(tx.execute(s -> chargeTypeService.update(parking.id(), renamed)).nameEn()).isEqualTo("Covered parking");
        var cooling = tx.execute(s -> chargeTypeService.list(false)).stream()
                .filter(t -> t.code().equals("COOLING")).findFirst().orElseThrow();
        var coolingPassThrough = new com.datagami.rentaxis.api.dto.lease.ChargeTypeDTO(cooling.id(), "COOLING",
                cooling.nameEn(), cooling.nameAr(), cooling.role(), cooling.behaviour(), false, true,
                cooling.displayOrder(), com.datagami.rentaxis.domain.entity.enums.ChargeRecognition.PASS_THROUGH);
        assertThat(tx.execute(s -> chargeTypeService.update(cooling.id(), coolingPassThrough)).recognition())
                .isEqualTo(com.datagami.rentaxis.domain.entity.enums.ChargeRecognition.PASS_THROUGH);
        // NONE is a posted line's word for "not income", never a type's rule.
        var toNone = new com.datagami.rentaxis.api.dto.lease.ChargeTypeDTO(cooling.id(), "COOLING", cooling.nameEn(),
                cooling.nameAr(), cooling.role(), cooling.behaviour(), false, true, cooling.displayOrder(),
                com.datagami.rentaxis.domain.entity.enums.ChargeRecognition.NONE);
        assertThatThrownBy(() -> tx.execute(s -> chargeTypeService.update(cooling.id(), toNone)))
                .hasMessageContaining("NONE describes a posted deposit line");
    }

    /**
     * F15-06: a periodic fee on a lease posted under the old at-posting rule was
     * income at posting, and a deposit is never income — at posting and in the
     * changeset's backfill of lines posted before it.
     */
    @Test
    void theSnapshotSaysWhatThePostingDidOldRuleFeesAndDeposits() {
        UUID oldWay = postedTheOldWay();
        assertThat(lineDtos(oldWay)).extracting(LeaseLineDTO::chargeTypeCode,
                LeaseLineDTO::postedRecognition).contains(
                org.assertj.core.groups.Tuple.tuple("PARKING_FEE", "ONE_OFF"),
                org.assertj.core.groups.Tuple.tuple("UTILITIES", "PASS_THROUGH"));
        var unit = tx.execute(s -> fixtures.createUnit(fixtures.property(), "DEP-1"));
        UUID withDeposit = fixtures.postedLease(unit, fixtures.renter(), CONTRACT_DATE, START, END,
                List.of(line("RENT", "51000"), line("SECURITY_DEPOSIT", "5000"), line("PARKING_FEE", "3650")),
                4, null).lease().getId();
        assertThat(lineDtos(withDeposit)).extracting(LeaseLineDTO::chargeTypeCode,
                LeaseLineDTO::postedRecognition).containsExactly(
                org.assertj.core.groups.Tuple.tuple("RENT", "RENT_LIKE"),
                org.assertj.core.groups.Tuple.tuple("SECURITY_DEPOSIT", "NONE"),
                org.assertj.core.groups.Tuple.tuple("PARKING_FEE", "RENT_LIKE"));

        // The backfill: wipe the stamps, run changeset 132's own UPDATE, get the same answers.
        jdbc.update("update lease_lines set posted_recognition = null where lease_id in (?, ?)", oldWay, withDeposit);
        jdbc.execute(backfillSql());
        assertThat(jdbc.queryForList("select c.code || '=' || l.posted_recognition from lease_lines l"
                + " join charge_types c on c.id = l.charge_type_id where l.lease_id = ? order by l.seq_no", String.class,
                oldWay)).containsExactly("RENT=RENT_LIKE", "PARKING_FEE=ONE_OFF", "ADMIN_FEE=ONE_OFF", "UTILITIES=PASS_THROUGH");
        assertThat(jdbc.queryForList("select c.code || '=' || l.posted_recognition from lease_lines l"
                + " join charge_types c on c.id = l.charge_type_id where l.lease_id = ? order by l.seq_no", String.class,
                withDeposit)).containsExactly("RENT=RENT_LIKE", "SECURITY_DEPOSIT=NONE", "PARKING_FEE=RENT_LIKE");

        // The label comes from the snapshot, not the catalogue.
        jdbc.update("update lease_lines l set posted_recognition = 'ONE_OFF' from charge_types c "
                + "where c.id = l.charge_type_id and c.code = 'PARKING_FEE' and l.lease_id = ?", withDeposit);
        assertThat(lineDtos(withDeposit)).filteredOn(l -> "PARKING_FEE".equals(l.chargeTypeCode()))
                .extracting(LeaseLineDTO::recognition, LeaseLineDTO::postedRecognition)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("RENT_LIKE", "ONE_OFF"));
    }

    private List<LeaseLineDTO> lineDtos(UUID leaseId) {
        return tx.execute(s -> leaseService.getLines(leaseId));
    }

    @SuppressWarnings("unchecked")
    private static String backfillSql() {
        try (var in = ChargeRecognitionIT.class.getResourceAsStream(
                "/db/changelog/changesets/132-lease-line-posted-recognition.yaml")) {
            Map<String, Object> doc = new org.yaml.snakeyaml.Yaml().load(in);
            var changeSet = (Map<String, Object>) ((List<Map<String, Object>>) doc.get("databaseChangeLog")).get(0).get("changeSet");
            for (Map<String, Object> change : (List<Map<String, Object>>) changeSet.get("changes")) {
                if (change.containsKey("sql")) return (String) ((Map<String, Object>) change.get("sql")).get("sql");
            }
            throw new IllegalStateException("no sql change in 132");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------

    private Account leaf(AccountRole role) {
        return tx.execute(s -> resolver.resolve(role, fixtures.property().getId()));
    }

    private BigDecimal posted(UUID leaseId, String code) {
        return recognition.scheduleFor(leaseId).stream()
                .filter(r -> code.equals(r.chargeCode()) && r.status() == RecognitionStatus.POSTED)
                .map(RecognitionEntryDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Account utilitiesLeaf() {
        return tx.execute(s -> accounts.findUtilitiesLeaves(fixtures.property().getId()).get(0));
    }

    private static BigDecimal credit(List<JournalLine> lines, UUID accountId) {
        return lines.stream().filter(l -> accountId.equals(l.getAccountId()))
                .map(JournalLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal balanceOf(UUID accountId, UUID leaseId) {
        return tx.execute(s -> ledger.accountLedger(accountId,
                new LedgerFilter(null, null, null, null, leaseId, null)).closingBalance());
    }
}

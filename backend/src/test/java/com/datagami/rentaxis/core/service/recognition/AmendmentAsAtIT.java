package com.datagami.rentaxis.core.service.recognition;

import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.ledger.TrialBalanceRowDTO;
import com.datagami.rentaxis.api.dto.vat.VatReturnDTO;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.LedgerQueryService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.vat.VatReturnService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.vatLine;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * #371 review, amendment-date design (P2): an amendment is dated the day it is made.
 * The contract is re-posted that day, the months already recognised are kept, the
 * elapsed period's difference is one catch-up dated that day, and the rest of the
 * term is planned per day from it. So every as-at report before the amendment date
 * reads exactly as it did — Advance Rent never goes negative, income is not counted
 * twice — for a lease of ours and a cut-over lease alike, whether the rent rises or
 * falls, and with a locked month before the amendment (which no longer blocks it).
 *
 * <p>A lease of 365 days (10/01/2026 – 09/01/2027) at 36,500 + VAT on CONTRACT timing,
 * 100 a day; recognised through 31/08; books locked through 30/06; amended on
 * 27/09/2026 (the fixed clock) to 43,800 (120 a day) or 29,200 (80 a day), a one-off
 * fee absorbing the change so the cheques still add up. Elapsed through 26/09: 260
 * days.</p>
 */
@SpringBootTest
@Import(AmendmentAsAtIT.FixedClockConfig.class)
class AmendmentAsAtIT extends AbstractPostgresIT {

    static final LocalDate TODAY = LocalDate.of(2026, 9, 27);
    static final ZoneId DUBAI = ZoneId.of("Asia/Dubai");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(TODAY.atTime(10, 0).atZone(DUBAI).toInstant(), DUBAI);
        }
    }

    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeasePostingService posting;
    @Autowired RecognitionService recognition;
    @Autowired com.datagami.rentaxis.core.service.lease.LeaseTerminationService termination;
    @Autowired com.datagami.rentaxis.core.service.lease.LeaseReductionService reductions;
    @Autowired VatReturnService vatReturns;
    @Autowired LedgerQueryService ledger;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService fiscal;

    private LeaseTestFixtures fixtures;
    private com.datagami.rentaxis.api.dto.lease.PostLeaseResponse lastResponse;

    static final LocalDate CONTRACT = LocalDate.of(2026, 1, 5);
    static final LocalDate START = LocalDate.of(2026, 1, 10);
    static final LocalDate END = LocalDate.of(2027, 1, 9);
    static final LocalDate JUN_30 = LocalDate.of(2026, 6, 30);
    static final LocalDate AUG_31 = LocalDate.of(2026, 8, 31);

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

    // ------------------------------------------------------------------ the four cases

    @Test
    void ownLeaseRentRaised() {
        run(false, "43800", "2335");
    }

    @Test
    void ownLeaseRentCut() {
        run(false, "29200", "17665");
    }

    @Test
    void cutOverLeaseRentRaised() {
        run(true, "43800", "2335");
    }

    @Test
    void cutOverLeaseRentCut() {
        run(true, "29200", "17665");
    }

    /** With no lock at all: the as-at history is what is under test, not the lock. */
    @Test
    void ownLeaseRentRaisedNoLock() {
        run(false, "43800", "2335", false);
    }

    @Test
    void cutOverLeaseRentCutNoLock() {
        run(true, "29200", "17665", false);
    }

    /**
     * @param newRent the amended rent (+5% VAT)
     * @param newFee  the one-off fee that keeps the contract's gross (and so the
     *                cheques, 48,325.00) unchanged
     */
    private void run(boolean cutOver, String newRent, String newFee) {
        run(cutOver, newRent, newFee, true);
    }

    private void run(boolean cutOver, String newRent, String newFee, boolean lock) {
        run(cutOver, newRent, newFee, lock, AUG_31);
    }

    /**
     * A month recognised by nobody before the lock closed it (June, here) will never be
     * posted by the runner; the amendment's catch-up takes it instead of leaving it
     * stranded in Advance Rent.
     */
    @Test
    void anUnpostedLockedMonthIsTakenByTheCatchUp() {
        run(false, "43800", "2335", true, LocalDate.of(2026, 5, 31));
        // #372 review P3-1: said on the amendment and on the catch-up's own journal.
        assertThat(lastResponse.notices()).singleElement().asString()
                .contains("1 month(s) of recognition inside the locked period (3,000.00)");
        assertThat(jdbc.queryForObject("select narration from journal_entries where tenant_id = ? and doc_type = 'CIL'"
                + " and narration like 'Recognition catch-up%'", String.class, fixtures.tenantId()))
                .contains("includes 1 locked month(s) never recognised (3000.00)");
    }

    /**
     * S16-15: after an amendment the catch-up and the re-planned rent rows are still rent
     * (chargeCode null), so the Recognition tab and the settlement statement count them:
     * the live rent rows add up to the amended rent and none carries the RENT code.
     */
    @Test
    void anAmendedLeasesRentRowsStayRent() {
        UUID leaseId = amendedLease();
        List<com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO> rows =
                tx.execute(s -> recognition.scheduleFor(leaseId));
        assertThat(rows).extracting(com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO::chargeCode)
                .containsOnlyNulls();
        BigDecimal rent = rows.stream()
                .filter(r -> r.status() != com.datagami.rentaxis.domain.entity.enums.RecognitionStatus.CANCELLED && r.rent())
                .map(com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(rent).isEqualByComparingTo("43800.00");
        // The catch-up is stamped as rent: no accounts of its own, like every rent segment.
        String catchUps = "select count(*) from rent_segments where lease_id = ? and status = 'TRUNCATED'"
                + " and day_rate = 0 and income_account_id is not null";
        assertThat(jdbc.queryForObject(catchUps.replace("is not null", "is null"), Integer.class, leaseId)).isEqualTo(1);

        // A catch-up written before the fix named the rent's accounts like a fee's (prod,
        // since #372): the read side still calls it rent.
        jdbc.update("update rent_segments set income_account_id = ?, deferral_account_id = ?"
                + " where lease_id = ? and status = 'TRUNCATED' and day_rate = 0",
                leaf(AccountRole.RENTAL_INCOME), leaf(AccountRole.ADVANCE_RENT), leaseId);
        assertThat(jdbc.queryForObject(catchUps, Integer.class, leaseId)).isEqualTo(1);
        List<com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO> after =
                tx.execute(s -> recognition.scheduleFor(leaseId));
        assertThat(after)
                .extracting(com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO::chargeCode)
                .containsOnlyNulls();
    }

    /**
     * S16-15: a periodic fee's catch-up is still the fee's — it names its accounts and
     * shows the fee's code — while the rent's stays rent. Rent cut to 29,200, parking
     * raised from 3,650 to 7,300, the one-off fee absorbing the difference.
     */
    @Test
    void anAmendedFeesCatchUpStaysTheFees() {
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END,
                List.of(vatLine("RENT", "36500"), line("PARKING_FEE", "3650"), line("ADMIN_FEE", "10000")));
        fixtures.generateGrid(leaseId, 4, START);
        posting.post(leaseId);
        recognition.runTo(AUG_31, false);
        List<LeaseLineInput> amended = new ArrayList<>();
        for (LeaseLineDTO l : tx.execute(s -> leaseService.getLines(leaseId))) {
            String gross = switch (l.chargeTypeCode()) {
                case "RENT" -> "29200";
                case "PARKING_FEE" -> "7300";
                default -> "14015";
            };
            amended.add(new LeaseLineInput(l.chargeTypeId(), null, new BigDecimal(gross), l.discountAmount(),
                    l.narration(), l.vatApplicable(), l.creditAccountId(), l.periodStart(), l.periodEnd(), l.addendumId()));
        }
        LeaseTestFixtures.authenticateAsTenantAdmin();
        posting.amendLines(leaseId, amended, "Rent and parking corrected");

        List<com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO> rows =
                tx.execute(s -> recognition.scheduleFor(leaseId));
        Map<String, BigDecimal> byCode = new java.util.HashMap<>();
        for (var r : rows) {
            if (r.status() == com.datagami.rentaxis.domain.entity.enums.RecognitionStatus.CANCELLED) continue;
            byCode.merge(String.valueOf(r.chargeCode()), r.amount(), BigDecimal::add);
        }
        assertThat(byCode).containsOnlyKeys("null", "PARKING_FEE");
        assertThat(byCode.get("null")).isEqualByComparingTo("29200.00");
        assertThat(byCode.get("PARKING_FEE")).isEqualByComparingTo("7300.00");
        assertThat(jdbc.queryForObject("select count(*) from rent_segments where lease_id = ? and status = 'TRUNCATED'"
                + " and day_rate = 0 and income_account_id is not null", Integer.class, leaseId)).isEqualTo(1);
    }

    /** Amends the rent to 43,800 on 27/09 (the fixed clock) after recognising through 31/08. */
    private UUID amendedLease() {
        UUID leaseId = lease(false);
        recognition.runTo(AUG_31, false);
        List<LeaseLineInput> amended = new ArrayList<>();
        for (LeaseLineDTO l : tx.execute(s -> leaseService.getLines(leaseId))) {
            amended.add(new LeaseLineInput(l.chargeTypeId(), null,
                    new BigDecimal("RENT".equals(l.chargeTypeCode()) ? "43800" : "2335"), l.discountAmount(),
                    l.narration(), l.vatApplicable(), l.creditAccountId(), l.periodStart(), l.periodEnd(), l.addendumId()));
        }
        LeaseTestFixtures.authenticateAsTenantAdmin();
        posting.amendLines(leaseId, amended, "Rent corrected");
        return leaseId;
    }

    /**
     * #372 review P2-1 (probe E): the tenant left on 15/09, the rent correction was entered
     * on 27/09. Ending the lease before the amendment would leave its catch-up counting
     * days the tenancy never ran — refused, naming the date, in the preview's problems and
     * by the termination itself; a credit addendum likewise. On or after the date it works.
     */
    @Test
    void aTerminationOrReductionBeforeTheLatestAmendmentIsRefused() {
        UUID leaseId = amendedLease();
        LocalDate before = LocalDate.of(2026, 9, 15);
        assertThat(tx.execute(s -> termination.preview(leaseId, before)).problems())
                .anySatisfy(p -> assertThat(p).contains("amended on 27/09/2026"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> termination.terminate(leaseId,
                        new com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest(before, null, null, null), null))
                .isInstanceOf(com.datagami.rentaxis.api.exception.BusinessRuleViolationException.class)
                .hasMessageContaining("amended on 27/09/2026").hasMessageContaining("on or after 27/09/2026");

        UUID rentLine = tx.execute(s -> leaseService.getLines(leaseId)).stream()
                .filter(l -> "RENT".equals(l.chargeTypeCode())).map(LeaseLineDTO::id).findFirst().orElseThrow();
        com.datagami.rentaxis.api.dto.lease.ReduceLeaseRequest cut = new com.datagami.rentaxis.api.dto.lease.ReduceLeaseRequest(
                before, before, "Rent renegotiated", null,
                List.of(new com.datagami.rentaxis.api.dto.lease.ReduceLeaseRequest.LineReduction(rentLine, new BigDecimal("40000"))),
                "CREDIT", List.of(), List.of());
        assertThat(tx.execute(s -> reductions.preview(leaseId, cut)).problems())
                .anySatisfy(p -> assertThat(p.message()).contains("amended on 27/09/2026"));

        termination.terminate(leaseId, new com.datagami.rentaxis.api.dto.lease.TerminateLeaseRequest(
                LocalDate.of(2026, 9, 30), null, null, null), null);
    }

    /**
     * #372 review P3-2: a CONTRACT lease of ours posted before contracts were invoiced has
     * no invoice to cite; the amendment's credit note names the contract instead.
     */
    @Test
    void aPreRuleLeasesAmendmentCreditNoteNamesTheContract() {
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END,
                List.of(vatLine("RENT", "36500"), line("ADMIN_FEE", "10000")));
        fixtures.generateGrid(leaseId, 4, START);
        posting.post(leaseId);   // INSTALMENT at posting: no contract invoice
        jdbc.update("update leases set vat_timing = 'CONTRACT' where id = ?", leaseId);   // changeset 108's pre-rule shape
        List<LeaseLineInput> amended = new ArrayList<>();
        for (LeaseLineDTO l : tx.execute(s -> leaseService.getLines(leaseId))) {
            amended.add(new LeaseLineInput(l.chargeTypeId(), null,
                    new BigDecimal("RENT".equals(l.chargeTypeCode()) ? "29200" : "17665"), l.discountAmount(),
                    l.narration(), l.vatApplicable(), l.creditAccountId(), l.periodStart(), l.periodEnd(), l.addendumId()));
        }
        LeaseTestFixtures.authenticateAsTenantAdmin();
        posting.amendLines(leaseId, amended, "Rent corrected");
        assertThat(jdbc.queryForObject("select reference_note from tax_invoices where lease_id = ? and kind = 'CREDIT_NOTE'",
                String.class, leaseId)).startsWith("Tenancy contract").contains("dated 05/01/2026")
                .contains("no tax invoice issued in this system");
    }

    private void run(boolean cutOver, String newRent, String newFee, boolean lock, LocalDate recognisedThrough) {
        UUID leaseId = lease(cutOver);
        recognition.runTo(recognisedThrough, false);
        if (lock) tx.executeWithoutResult(s -> fiscal.lockThrough(JUN_30));

        Map<String, BigDecimal> jun = asAt(leaseId, JUN_30);
        Map<String, BigDecimal> aug = asAt(leaseId, AUG_31);
        if (recognisedThrough.equals(AUG_31)) {
            assertThat(aug.get("advance")).isEqualByComparingTo("-13100.00");   // 36,500 − 234 × 100, a credit
            assertThat(aug.get("income")).isEqualByComparingTo("-23400.00");
        }

        List<LeaseLineInput> amended = new ArrayList<>();
        for (LeaseLineDTO l : tx.execute(s -> leaseService.getLines(leaseId))) {
            String gross = "RENT".equals(l.chargeTypeCode()) ? newRent : newFee;
            amended.add(new LeaseLineInput(l.chargeTypeId(), null, new BigDecimal(gross), l.discountAmount(),
                    l.narration(), l.vatApplicable(), l.creditAccountId(), l.periodStart(), l.periodEnd(), l.addendumId()));
        }
        LeaseTestFixtures.authenticateAsTenantAdmin();
        lastResponse = posting.amendLines(leaseId, amended, "Rent corrected");
        // The runner catches up everything due to date (the month holding 27/09 was re-cut at 26/09).
        recognition.runTo(TODAY.minusDays(1), false);

        // Nothing before the amendment date moved.
        assertThat(asAt(leaseId, JUN_30)).as("as at 30/06 (locked)").isEqualTo(jun);
        if (recognisedThrough.equals(AUG_31)) {
            assertThat(asAt(leaseId, AUG_31)).as("as at 31/08").isEqualTo(aug);
        } else {
            // July and August were open and still planned: the runner posted them at their
            // own month-ends on the old contract, as it would have without the amendment.
            assertThat(asAt(leaseId, AUG_31).get("income")).isEqualByComparingTo("-20400.00");
        }
        assertThat(asAt(leaseId, TODAY.minusDays(1)).get("advance")).as("Advance Rent the day before").isNegative();

        // On the amendment date: the new contract, 260 days of it recognised at the new rate.
        BigDecimal rate = new BigDecimal(newRent).divide(new BigDecimal("365"));
        BigDecimal earned = rate.multiply(new BigDecimal("260"));
        Map<String, BigDecimal> onD = asAt(leaseId, TODAY);
        assertThat(onD.get("income")).as("income as at 27/09").isEqualByComparingTo(earned.negate());
        assertThat(onD.get("advance")).as("Advance Rent as at 27/09")
                .isEqualByComparingTo(new BigDecimal(newRent).subtract(earned).negate());
        BigDecimal newVat = new BigDecimal(newRent).multiply(new BigDecimal("0.05"));
        assertThat(onD.get("vat")).as("Output VAT as at 27/09").isEqualByComparingTo(newVat.negate());
        // The VAT difference is documented on the amendment date, so the quarter ties.
        VatReturnDTO.OutputCheck q3 = tx.execute(s -> vatReturns.get(LocalDate.of(2026, 7, 1))).outputCheck();
        assertThat(q3.ok()).as("Q3 output check " + q3).isTrue();
        assertThat(q3.ledger()).isEqualByComparingTo(newVat.subtract(new BigDecimal("1825.00")));

        // The remaining term earns the rest per day: at the end nothing is deferred.
        recognition.runTo(END, false);
        Map<String, BigDecimal> end = asAt(leaseId, END);
        assertThat(end.get("advance")).isEqualByComparingTo("0");
        assertThat(end.get("income")).isEqualByComparingTo(new BigDecimal(newRent).negate());
        assertPropertyTrialBalances();
    }

    // ------------------------------------------------------------------ fixture

    /** 36,500 rent + 5% VAT and a 10,000 one-off fee on CONTRACT timing: 48,325.00 in four cheques. */
    private UUID lease(boolean cutOver) {
        UUID leaseId = fixtures.draftLease(CONTRACT, START, END,
                List.of(vatLine("RENT", "36500"), line("ADMIN_FEE", "10000")));
        jdbc.update("update leases set vat_timing = 'CONTRACT' where id = ?", leaseId);
        fixtures.generateGrid(leaseId, 4, START);
        if (cutOver) {
            UUID batch = UUID.randomUUID();
            jdbc.update("insert into import_batches (id, tenant_id, status) values (?, ?, 'POSTED')", batch, fixtures.tenantId());
            posting.post(leaseId, batch);
        } else {
            posting.post(leaseId);
        }
        return leaseId;
    }

    private UUID leaf(AccountRole role) {
        return tx.execute(s -> resolver.resolve(role, fixtures.property().getId())).getId();
    }

    /** The lease's Advance Rent, rental income and Output VAT (debit − credit) as at a date. */
    private Map<String, BigDecimal> asAt(UUID leaseId, LocalDate date) {
        String q = """
                select coalesce(sum(l.debit - l.credit), 0) from journal_lines l
                  join journal_entries e on e.id = l.journal_entry_id
                 where l.lease_id = ? and l.account_id = ? and e.entry_date <= ?""";
        return Map.of(
                "advance", jdbc.queryForObject(q, BigDecimal.class, leaseId, leaf(AccountRole.ADVANCE_RENT), date).setScale(2),
                "income", jdbc.queryForObject(q, BigDecimal.class, leaseId, leaf(AccountRole.RENTAL_INCOME), date).setScale(2),
                "vat", jdbc.queryForObject(q, BigDecimal.class, leaseId, leaf(AccountRole.OUTPUT_VAT), date).setScale(2));
    }

    private void assertPropertyTrialBalances() {
        for (LocalDate d : List.of(JUN_30, AUG_31, TODAY, END)) {
            List<TrialBalanceRowDTO> rows = tx.execute(s -> ledger.trialBalance(d, fixtures.property().getId()));
            BigDecimal debit = rows.stream().map(TrialBalanceRowDTO::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal credit = rows.stream().map(TrialBalanceRowDTO::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(debit).as("property trial balance as at " + d).isEqualByComparingTo(credit);
        }
    }
}

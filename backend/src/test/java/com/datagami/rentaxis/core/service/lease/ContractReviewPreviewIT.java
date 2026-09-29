package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.recognition.ProrationEngine;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;

/**
 * Owner request (2026-09-29): the New Contract wizard's Review step shows the monthly
 * rent the contract will recognise and, when no cheques were added, the schedule
 * "Generate cheques" would create. Both come from the backend's own rules — never a
 * re-implementation — and both read only.
 */
@SpringBootTest
class ContractReviewPreviewIT extends AbstractPostgresIT {

    @Autowired RecognitionService recognition;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService cheques;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate START = LocalDate.of(2026, 9, 9);
    private static final LocalDate END = LocalDate.of(2027, 9, 8);

    private LeaseTestFixtures fixtures;

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

    private long rows(String table, UUID leaseId) {
        return jdbc.queryForObject("select count(*) from " + table + " where lease_id = ?", Long.class, leaseId);
    }

    @Test
    void theMonthlyRentIsTheLedgersOwnScheduleAndWritesNothing() {
        UUID id = fixtures.draftLease(START, START, END, List.of(line("RENT", "50000")));

        List<RecognitionService.RentMonth> months = recognition.previewRentSchedule(id);

        List<ProrationEngine.Slice> ledger = ProrationEngine.slice(new BigDecimal("50000"), START, END);
        assertThat(months).hasSize(ledger.size()).hasSize(13);
        for (int i = 0; i < ledger.size(); i++) {
            assertThat(months.get(i).periodStart()).isEqualTo(ledger.get(i).periodStart());
            assertThat(months.get(i).days()).isEqualTo(ledger.get(i).days());
            assertThat(months.get(i).amount()).isEqualByComparingTo(ledger.get(i).amount());
        }
        assertThat(months.get(0).days()).isEqualTo(22);              // 9–30 Sep
        assertThat(months.get(12).days()).isEqualTo(8);              // 1–8 Sep
        assertThat(months.stream().map(RecognitionService.RentMonth::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("50000.00");
        // Day rate × days for every month but the last; the last absorbs the rounding.
        BigDecimal rate = ProrationEngine.dayRate(new BigDecimal("50000"), START, END);
        assertThat(months.get(1).amount()).isEqualByComparingTo(
                rate.multiply(BigDecimal.valueOf(31)).setScale(2, java.math.RoundingMode.HALF_UP));

        assertThat(rows("recognition_entries", id)).isZero();
        assertThat(rows("rent_segments", id)).isZero();
    }

    @Test
    void theSuggestedScheduleIsWhatGenerateWritesAndPreviewWritesNothing() {
        UUID id = fixtures.draftLease(START, START, END, List.of(line("RENT", "60000")));
        GenerateChequesRequest req = new GenerateChequesRequest(6, START, InstallmentDistribution.LAST_LARGER,
                null, null, true, null);

        List<ChequeGenerationService.PreviewRow> suggested = cheques.preview(id, req);
        assertThat(rows("cheques", id)).as("a preview writes nothing").isZero();

        var generated = cheques.generate(id, req);
        assertThat(suggested).hasSize(generated.size()).hasSize(6);
        for (int i = 0; i < suggested.size(); i++) {
            assertThat(suggested.get(i).chequeDate()).isEqualTo(generated.get(i).chequeDate());
            assertThat(suggested.get(i).amount()).isEqualByComparingTo(generated.get(i).amount());
        }
        // Six cheques on twelve months: every two months from the first due date.
        assertThat(suggested).extracting(ChequeGenerationService.PreviewRow::chequeDate).containsExactly(
                START, START.plusMonths(2), START.plusMonths(4), START.plusMonths(6), START.plusMonths(8), START.plusMonths(10));
        assertThat(suggested.stream().map(ChequeGenerationService.PreviewRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("60000");
    }

    /**
     * Owner request (2026-09-29): any count from 1 to one a month re-spaces the due
     * dates evenly — cheque i on firstDue + floor(i × months ÷ count) months, the
     * generator's rule — and re-splits the rent (LAST_LARGER takes the rounding to tens).
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"12,1", "6,2", "4,3", "3,4", "1,12"})
    void anyCountOnATwelveMonthTermIsEvenlySpacedAndAddsUpToTheRent(int count, int everyMonths) {
        UUID id = fixtures.draftLease(START, START, END, List.of(line("RENT", "50000")));
        List<ChequeGenerationService.PreviewRow> rows = cheques.preview(id, new GenerateChequesRequest(count, START,
                InstallmentDistribution.LAST_LARGER, null, null, true, null));
        assertThat(rows).hasSize(count);
        for (int i = 0; i < count; i++) {
            assertThat(rows.get(i).chequeDate()).isEqualTo(START.plusMonths((long) i * everyMonths));
        }
        assertThat(rows.stream().map(ChequeGenerationService.PreviewRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("50000");
        // LAST_LARGER: every cheque but the last is the same round amount.
        assertThat(rows.subList(0, count - 1)).extracting(ChequeGenerationService.PreviewRow::amount)
                .allSatisfy(a -> assertThat(a).isEqualByComparingTo(rows.get(0).amount()));
    }

    /** Thirteen months over six cheques: floor(i × 13 ÷ 6) → months 0, 2, 4, 6, 8, 10. */
    @Test
    void sixChequesOnAThirteenMonthTerm() {
        LocalDate end13 = START.plusMonths(13).minusDays(1);
        UUID id = fixtures.draftLease(START, START, end13, List.of(line("RENT", "65000")));
        List<ChequeGenerationService.PreviewRow> rows = cheques.preview(id, new GenerateChequesRequest(6, START,
                InstallmentDistribution.LAST_LARGER, null, null, true, null));
        assertThat(rows).extracting(ChequeGenerationService.PreviewRow::chequeDate).containsExactly(
                START, START.plusMonths(2), START.plusMonths(4), START.plusMonths(6), START.plusMonths(8), START.plusMonths(10));
        assertThat(rows.stream().map(ChequeGenerationService.PreviewRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("65000");
    }

    @Test
    void anotherOrganisationCannotPreviewThisContract() {
        UUID id = fixtures.draftLease(START, START, END, List.of(line("RENT", "60000")));
        fixtures.newTenant();
        assertThatThrownBy(() -> recognition.previewRentSchedule(id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> cheques.preview(id, null)).isInstanceOf(NotFoundException.class);
    }
}

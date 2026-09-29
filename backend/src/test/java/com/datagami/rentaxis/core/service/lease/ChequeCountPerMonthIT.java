package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
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

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Review of R4-B, I1: at most one cheque per month. The generator spaces cheque i at
 * firstDue + floor(i × months ÷ n) months, with months the whole months from the first
 * due date to the end (rounded down); more cheques than that put two on one date. The
 * plain path now refuses such a count (the rent-free path already did), coded.
 */
@SpringBootTest
class ChequeCountPerMonthIT extends AbstractPostgresIT {

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

    private static final LocalDate START = LocalDate.of(2026, 9, 9);

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

    private static GenerateChequesRequest req(Integer n, LocalDate firstDue) {
        return new GenerateChequesRequest(n, firstDue, InstallmentDistribution.LAST_LARGER, null, null, true, null);
    }

    private static void assertDistinctDates(List<ChequeGenerationService.PreviewRow> rows) {
        var dates = rows.stream().map(ChequeGenerationService.PreviewRow::chequeDate).toList();
        assertThat(new HashSet<>(dates)).as("one cheque per date: %s", dates).hasSameSizeAs(dates);
    }

    @Test
    void aTermWithAPartMonthLeftOverTakesOneChequePerWholeMonth() {
        // 09/09/2026 → 20/03/2027: six whole months and twelve days.
        UUID id = fixtures.draftLease(START, START, LocalDate.of(2027, 3, 20), List.of(line("RENT", "60000")));

        assertThatThrownBy(() -> cheques.preview(id, req(7, START)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("cheque.tooManyInstalments");
                    assertThat(e.getArgs()).containsEntry("months", 6).containsEntry("count", 7);
                });
        assertThatThrownBy(() -> cheques.generate(id, req(7, START)))
                .isInstanceOf(BusinessRuleViolationException.class);

        List<ChequeGenerationService.PreviewRow> six = cheques.preview(id, req(6, START));
        assertThat(six).hasSize(6);
        assertDistinctDates(six);
    }

    @Test
    void aFirstDueDateAfterTheStartCountsFromTheFirstDueDate() {
        LocalDate end = START.plusMonths(12).minusDays(1);
        UUID id = fixtures.draftLease(START, START, end, List.of(line("RENT", "60000")));
        LocalDate firstDue = LocalDate.of(2026, 9, 15);

        assertThatThrownBy(() -> cheques.preview(id, req(12, firstDue)))
                .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("cheque.tooManyInstalments");
                    assertThat(e.getArgs()).containsEntry("months", 11);
                });
        List<ChequeGenerationService.PreviewRow> eleven = cheques.preview(id, req(11, firstDue));
        assertThat(eleven).hasSize(11);
        assertDistinctDates(eleven);
    }

    @Test
    void aPlainTwelveMonthTermStillTakesTwelve() {
        UUID id = fixtures.draftLease(START, START, START.plusMonths(12).minusDays(1), List.of(line("RENT", "60000")));
        List<ChequeGenerationService.PreviewRow> twelve = cheques.preview(id, req(12, START));
        assertThat(twelve).hasSize(12);
        assertDistinctDates(twelve);
    }
}

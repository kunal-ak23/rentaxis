package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.cheque.ChequeDTO;
import com.datagami.rentaxis.api.dto.lease.GenerateChequesRequest;
import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.lease.RentFreePeriodDTO;
import com.datagami.rentaxis.api.dto.recognition.RecognitionEntryDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.ContractGenerationService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.recognition.RecognitionService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Rent-free periods (spec 2026-09-24 §4b, #50) and F14-26's straight-line rule.
 *
 * <p>The finding's own contract: 01/06/2026 → 31/05/2027 (365 days), June free,
 * 66,000 payable. The operator enters a headline of 72,000 and the exact
 * concession 6,000. Straight-line: 66,000 ÷ 365 = 180.8219/day from 01/06, so
 * June 5,424.66 and July 5,605.48 — the free month earns like any other.</p>
 */
@SpringBootTest
class RentFreeIT extends AbstractPostgresIT {

    @Autowired RentFreeService rentFree;
    @Autowired LeaseRenewalService renewal;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired RecognitionService recognition;
    @Autowired LeaseService leaseService;
    @Autowired ContractGenerationService contracts;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired TransactionTemplate tx;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT = LocalDate.of(2026, 5, 20);
    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2027, 5, 31);
    private static final RentFreePeriodDTO JUNE_EXACT =
            new RentFreePeriodDTO(null, START, LocalDate.of(2026, 6, 30), new BigDecimal("6000"), "First month free", null, null);

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

    private UUID draft() {
        return fixtures.draftLease(CONTRACT, START, END, List.of(line("RENT", "72000"), line("SECURITY_DEPOSIT", "5000")));
    }

    private LeaseLineDTO rentLine(UUID leaseId) {
        return tx.execute(s -> leaseService.getLines(leaseId)).stream()
                .filter(l -> "RENT".equals(l.chargeTypeCode())).findFirst().orElseThrow();
    }

    @Test
    void theFreeMonthLowersWhatIsPaidAndIsRecognisedStraightLine() {
        UUID leaseId = draft();
        LeaseDTO lease = rentFree.replace(leaseId, List.of(JUNE_EXACT));

        assertThat(lease.getRentFreePeriods()).singleElement().satisfies(p -> {
            assertThat(p.days()).isEqualTo(30);
            assertThat(p.concession()).isEqualByComparingTo("6000");
        });
        LeaseLineDTO rent = rentLine(leaseId);
        assertThat(rent.grossAmount()).isEqualByComparingTo("72000");
        assertThat(rent.rentFreeAmount()).isEqualByComparingTo("6000");
        assertThat(rent.netAmount()).isEqualByComparingTo("66000");
        assertThat(lease.getRentAmount()).isEqualByComparingTo("66000");

        // The grid collects the payable rent, starting on the first charged day.
        List<ChequeDTO> grid = chequeGeneration.generate(leaseId,
                new GenerateChequesRequest(4, null, null, "Emirates NBD", null, false, null));
        List<ChequeDTO> rentRows = grid.stream().filter(c -> c.amount().compareTo(new BigDecimal("5000")) != 0).toList();
        assertThat(rentRows.get(0).chequeDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(rentRows.stream().map(ChequeDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("66000");
        fixtures.numberGrid(leaseId, LeaseTestFixtures.nextChequeBook());
        posting.post(leaseId);

        // F14-26: straight-line over the whole term, the free June included.
        List<RecognitionEntryDTO> schedule = recognition.scheduleFor(leaseId);
        assertThat(schedule.get(0).periodStart()).isEqualTo(START);
        assertThat(schedule.get(0).amount()).isEqualByComparingTo("5424.66");
        assertThat(schedule.get(1).amount()).isEqualByComparingTo("5605.48");
        assertThat(schedule.stream().map(RecognitionEntryDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("66000");

        // The contract names the window and the concession, in both languages.
        String rows = tx.execute(s -> contracts.buildSection3Rows(leaseRepo.findById(leaseId).orElseThrow()));
        assertThat(rows).contains("Rent-free period 01/06/2026 – 30/06/2026 (30 days): AED 6,000.00")
                .contains("فترة إعفاء من الإيجار").contains("66,000.00");
    }

    @Test
    void withoutAnOverrideTheConcessionIsHeadlineTimesFreeDaysOverTermDays() {
        UUID leaseId = draft();
        rentFree.replace(leaseId, List.of(new RentFreePeriodDTO(null, START, LocalDate.of(2026, 6, 30), null, null, null, null)));
        // 72,000 × 30 ÷ 365 = 5,917.808… → 5,917.81
        assertThat(rentLine(leaseId).rentFreeAmount()).isEqualByComparingTo("5917.81");
        assertThat(rentLine(leaseId).netAmount()).isEqualByComparingTo("66082.19");
    }

    @Test
    void reSendingTheDraftsLinesKeepsTheConcession() {
        UUID leaseId = draft();
        rentFree.replace(leaseId, List.of(JUNE_EXACT));
        var dto = fixtures.draftDto(START, END, List.of(line("RENT", "72000"), line("SECURITY_DEPOSIT", "5000")));
        dto.setContractDate(CONTRACT);
        dto.setFirstDueDate(START);
        leaseService.updateDraftLease(leaseId, dto);
        assertThat(rentLine(leaseId).netAmount()).isEqualByComparingTo("66000");
        // Clearing the periods clears the concession.
        rentFree.replace(leaseId, List.of());
        assertThat(rentLine(leaseId).netAmount()).isEqualByComparingTo("72000");
    }

    @Test
    void periodsMustLieInsideTheTermNotOverlapAndLeaveADayCharged() {
        UUID leaseId = draft();
        assertThatThrownBy(() -> rentFree.replace(leaseId, List.of(
                new RentFreePeriodDTO(null, START.minusDays(1), START.plusDays(5), null, null, null, null))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("outside the lease term");
        assertThatThrownBy(() -> rentFree.replace(leaseId, List.of(JUNE_EXACT,
                new RentFreePeriodDTO(null, LocalDate.of(2026, 6, 15), LocalDate.of(2026, 7, 5), null, null, null, null))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("cannot overlap");
        assertThatThrownBy(() -> rentFree.replace(leaseId, List.of(
                new RentFreePeriodDTO(null, START, END, null, null, null, null))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("whole term");
        assertThatThrownBy(() -> rentFree.replace(leaseId, List.of(
                new RentFreePeriodDTO(null, START, LocalDate.of(2026, 6, 30), new BigDecimal("80000"), null, null, null))))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("more than the rent");
        assertThat(rentLine(leaseId).netAmount()).isEqualByComparingTo("72000");
    }

    /**
     * PR #358 R1 P2-2: the concession is frozen at posting. An extension adds its own
     * term with no free period, and a later amend — even one that changes the rent —
     * re-applies the contract's concession instead of re-deriving it.
     */
    @Test
    void theConcessionIsFrozenAtPostingThroughAnExtensionAndAnAmend() {
        UUID leaseId = draft();
        rentFree.replace(leaseId, List.of(new RentFreePeriodDTO(null, START, LocalDate.of(2026, 6, 30), null, null, null, null)));
        fixtures.generateGrid(leaseId, 4, START);
        posting.post(leaseId);
        assertThat(rentLine(leaseId).rentFreeAmount()).isEqualByComparingTo("5917.81");

        renewal.extend(leaseId, new com.datagami.rentaxis.api.dto.lease.ExtendLeaseRequest(LocalDate.of(2027, 8, 31),
                LocalDate.of(2027, 5, 1), List.of(line("RENT", "18000")),
                List.of(LeaseTestFixtures.chequeRow("18000", LocalDate.of(2027, 6, 1)))));
        List<LeaseLineDTO> afterExtension = tx.execute(s -> leaseService.getLines(leaseId));
        assertThat(afterExtension.stream().filter(l -> "RENT".equals(l.chargeTypeCode())).map(LeaseLineDTO::rentFreeAmount).toList())
                .extracting(BigDecimal::toPlainString).containsExactly("5917.81", "0.00");

        // Amend: the contract rent raised by 1,000 and the deposit lowered by 1,000,
        // so the cheques still cover the contract only if the concession is unchanged.
        List<LeaseLineInput> resent = afterExtension.stream().map(l -> new LeaseLineInput(l.chargeTypeId(), null,
                l.seqNo() == 1 ? l.grossAmount().add(new BigDecimal("1000"))
                        : "SECURITY_DEPOSIT".equals(l.chargeTypeCode()) ? l.grossAmount().subtract(new BigDecimal("1000"))
                        : l.grossAmount(), l.discountAmount(),
                l.narration(), l.vatApplicable(), l.creditAccountId(), l.periodStart(), l.periodEnd(), l.addendumId()))
                .toList();
        posting.amendLines(leaseId, resent, "Rent corrected");
        LeaseLineDTO rent = rentLine(leaseId);
        assertThat(rent.grossAmount()).isEqualByComparingTo("73000");
        assertThat(rent.rentFreeAmount()).isEqualByComparingTo("5917.81");
        assertThat(rent.netAmount()).isEqualByComparingTo("67082.19");
    }

    /** PR #358 R1: a monthly lease with a free first month gets one cheque per charged month, never two on a date. */
    @Test
    void aMonthlyGridSkipsTheFreeMonthWithOneChequePerChargedMonth() {
        UUID leaseId = draft();
        rentFree.replace(leaseId, List.of(JUNE_EXACT));
        jdbc.update("update leases set payment_terms = 12 where id = ?", leaseId);
        List<ChequeDTO> grid = chequeGeneration.generate(leaseId,
                new GenerateChequesRequest(null, null, null, "Emirates NBD", null, false, null));
        List<LocalDate> rentDates = grid.stream().filter(c -> c.amount().compareTo(new BigDecimal("5000")) != 0)
                .map(ChequeDTO::chequeDate).toList();
        assertThat(rentDates).hasSize(11).doesNotHaveDuplicates().startsWith(LocalDate.of(2026, 7, 1))
                .endsWith(LocalDate.of(2027, 5, 1));
        assertThat(grid.stream().filter(c -> c.amount().compareTo(new BigDecimal("5000")) != 0)
                .map(ChequeDTO::amount).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("66000");
        // An explicit count above the charged months is refused rather than doubled up.
        assertThatThrownBy(() -> chequeGeneration.generate(leaseId,
                new GenerateChequesRequest(12, null, null, "Emirates NBD", null, false, null)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("11 charged month");
    }

    @Test
    void aPostedLeasesPeriodsCannotBeChangedHere() {
        UUID leaseId = fixtures.postedLease(CONTRACT, START, END, List.of(line("RENT", "72000")), 4, null).lease().getId();
        assertThatThrownBy(() -> rentFree.replace(leaseId, List.of(JUNE_EXACT)))
                .isInstanceOf(BusinessRuleViolationException.class).hasMessageContaining("draft lease only");
    }
}

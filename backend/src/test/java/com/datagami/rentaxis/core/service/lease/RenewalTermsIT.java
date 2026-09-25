package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.RentCollectionSettingsDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.dto.lease.PostLeaseResponse;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest.RentChange;
import com.datagami.rentaxis.api.dto.lease.RenewalPreviewDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.ContractGenerationService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.RentCollectionSettingsService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.JournalLine;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
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
 * Renewal terms (spec 2026-09-24 §4a escalation, §4c one-off skip, §4d renewal
 * charges). Ahmed's lease: 85,000 of rent (a 1,000 discount) and a 1,500 admin
 * fee over 02/10/2026 → 01/10/2027, renewed for the next 12 months.
 */
@SpringBootTest
class RenewalTermsIT extends AbstractPostgresIT {

    @Autowired LeaseRenewalService renewal;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired ContractGenerationService contracts;
    @Autowired RentCollectionSettingsService rentSettings;
    @Autowired AccountResolver resolver;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired JournalLineRepository journalLines;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private LeaseTestFixtures fixtures;

    private static final LocalDate CONTRACT = LocalDate.of(2026, 9, 16);
    private static final LocalDate START = LocalDate.of(2026, 10, 2);
    private static final LocalDate END = LocalDate.of(2027, 10, 1);
    private static final LocalDate R_CONTRACT = LocalDate.of(2027, 9, 16);
    private static final LocalDate R_START = LocalDate.of(2027, 10, 2);
    private static final LocalDate R_END = LocalDate.of(2028, 10, 1);

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

    private UUID ahmed() {
        return fixtures.postedLease(CONTRACT, START, END,
                List.of(line("RENT", "85000", "1000"), line("ADMIN_FEE", "1500")), 4, null).lease().getId();
    }

    private static RenewLeaseRequest request(RentChange change, String ejari, List<LeaseLineInput> extra) {
        return new RenewLeaseRequest(R_CONTRACT, R_START, R_END, null, false, change, ejari, extra);
    }

    private static RentChange percent(String p) {
        return new RentChange(RentChange.Mode.PERCENT, new BigDecimal(p), null);
    }

    private List<LeaseLineDTO> lines(UUID leaseId) {
        return tx.execute(s -> leaseService.getLines(leaseId));
    }

    @Test
    void eightPercentOn85000Is91800AndTheDiscountDoesNotRenew() {
        UUID first = ahmed();
        LeaseDTO successor = renewal.renew(first, request(percent("8"), null, null));

        List<LeaseLineDTO> copied = lines(successor.getId());
        assertThat(copied).extracting(LeaseLineDTO::chargeTypeCode).containsExactly("RENT");
        assertThat(copied.get(0).grossAmount()).isEqualByComparingTo("91800");
        assertThat(copied.get(0).discountAmount()).isEqualByComparingTo("0");
        assertThat(copied.get(0).netAmount()).isEqualByComparingTo("91800");
        assertThat(successor.getRenewalPreviousRent()).isEqualByComparingTo("85000");
        assertThat(successor.getRenewalChangePercent()).isEqualByComparingTo("8.000");
        assertThat(successor.getSkippedOneOffLines()).extracting(LeaseLineDTO::chargeTypeCode).containsExactly("ADMIN_FEE");

        // Read back from the database, and printed on the contract in both languages.
        LeaseDTO reread = tx.execute(s -> leaseService.getLeaseById(successor.getId()));
        assertThat(reread.getRenewalPreviousRent()).isEqualByComparingTo("85000");
        String rows = tx.execute(s -> contracts.buildSection3Rows(leaseRepo.findById(successor.getId()).orElseThrow()));
        assertThat(rows).contains("Rent revised from AED 85,000.00 to AED 91,800.00 (+8.00%)")
                .contains("تم تعديل الإيجار");
    }

    @Test
    void aPercentageRoundsToWholeDirhams() {
        UUID first = ahmed();
        // 85,000 × 1.0333 = 87,830.50 → 87,831
        LeaseDTO successor = renewal.renew(first, request(percent("3.33"), null, null));
        assertThat(lines(successor.getId()).get(0).grossAmount()).isEqualByComparingTo("87831");
    }

    @Test
    void aPercentageIsRefusedWhenTheTermLengthChanges() {
        UUID first = ahmed();
        assertThatThrownBy(() -> renewal.renew(first,
                new RenewLeaseRequest(R_CONTRACT, R_START, R_END.plusMonths(6), null, false, percent("8"), null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("term length changed");
        // …but an exact amount is fine.
        LeaseDTO successor = renewal.renew(first, new RenewLeaseRequest(R_CONTRACT, R_START, R_END.plusMonths(6), null,
                false, new RentChange(RentChange.Mode.AMOUNT, null, new BigDecimal("130000")), null, null));
        assertThat(lines(successor.getId()).get(0).grossAmount()).isEqualByComparingTo("130000");
        assertThat(successor.getRenewalChangePercent()).isEqualByComparingTo("52.941");
    }

    @Test
    void aRenewalFeeIsChargedOnTheSuccessorAndNotCopiedByTheNextRenewal() {
        UUID first = ahmed();
        LeaseDTO successor = renewal.renew(first, request(null, "EJ-2027-777",
                List.of(line("RENEWAL_FEE", "1050"), line("SECURITY_DEPOSIT", "5000"))));
        assertThat(lines(successor.getId())).extracting(LeaseLineDTO::chargeTypeCode)
                .containsExactly("RENT", "RENEWAL_FEE", "SECURITY_DEPOSIT");
        assertThat(successor.getEjariNumber()).isEqualTo("EJ-2027-777");
        // Mode NONE: no revision recorded.
        assertThat(successor.getRenewalPreviousRent()).isNull();

        fixtures.generateGrid(successor.getId(), 4, R_START);
        PostLeaseResponse posted = posting.post(successor.getId());
        List<JournalLine> tco = tx.execute(s -> journalLines.findByEntry_IdOrderByLineNoAsc(posted.tcoJournalId()));
        UUID adminFeeLeaf = tx.execute(s -> resolver.resolve(AccountRole.ADMIN_FEE, fixtures.property().getId()).getId());
        assertThat(tco.stream().filter(l -> adminFeeLeaf.equals(l.getAccountId()))
                .map(JournalLine::getCredit).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("1050");

        LeaseDTO third = renewal.renew(successor.getId(), new RenewLeaseRequest(LocalDate.of(2028, 9, 16),
                LocalDate.of(2028, 10, 2), LocalDate.of(2029, 10, 1), null, true));
        assertThat(third.getSkippedOneOffLines()).extracting(LeaseLineDTO::chargeTypeCode).containsExactly("RENEWAL_FEE");
    }

    @Test
    void aBlankEjariLeavesTheFollowUpOnTheDraftsHistory() {
        UUID first = ahmed();
        LeaseDTO successor = renewal.renew(first, request(null, "  ", null));
        assertThat(successor.getEjariNumber()).isNull();
        assertThat(jdbc.queryForList("select notes from lease_events where lease_id = ?", String.class, successor.getId()))
                .anyMatch(n -> n != null && n.contains("Ejari registration pending"));
    }

    @Test
    void thePreviewShowsTheChangeTheSkippedOneOffsAndTheNotice() {
        UUID first = ahmed();
        RentCollectionSettingsDTO settings = new RentCollectionSettingsDTO();
        settings.setRenewalIncreaseWarnPercent(new BigDecimal("5"));
        rentSettings.saveSettings(fixtures.property().getId(), settings);

        RenewalPreviewDTO preview = renewal.preview(first, request(percent("8"), null, null));
        assertThat(preview.baseRent()).isEqualByComparingTo("85000");
        assertThat(preview.newRent()).isEqualByComparingTo("91800");
        assertThat(preview.changePercent()).isEqualByComparingTo("8");
        assertThat(preview.warnPercent()).isEqualByComparingTo("5");
        assertThat(preview.exceedsWarn()).isTrue();
        assertThat(preview.copiedLines()).extracting(RenewalPreviewDTO.Line::chargeTypeCode).containsExactly("RENT");
        assertThat(preview.skippedOneOffLines()).extracting(LeaseLineDTO::chargeTypeCode).containsExactly("ADMIN_FEE");
        // Nothing was written.
        assertThat(leaseRepo.findByRenewedFromLeaseId(first)).isEmpty();

        assertThat(renewal.preview(first, request(percent("4"), null, null)).exceedsWarn()).isFalse();
    }

    @Test
    void linesAndARentChangeCannotBothBeSent() {
        UUID first = ahmed();
        assertThatThrownBy(() -> renewal.renew(first, new RenewLeaseRequest(R_CONTRACT, R_START, R_END,
                List.of(line("RENT", "90000")), false, percent("8"), null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("either the renewal's lines or a rent change");
    }
}

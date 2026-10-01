package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Break-it R4 money4 F2: a contract date typed as 2099 (for 2026) was accepted on
 * Renew and in the wizard, and the contract then posted in 2099 — its cheques could
 * not be banked until then and income was recognised against an advance rent that
 * did not exist yet. The contract date is the TCO's journal date, a user-typed
 * posting date, so it follows the one posting-date policy's PLANNED rule
 * ({@code PostingDatePath.LEASE_POST}): not more than a year after today.
 */
@SpringBootTest
class ContractDatePolicyIT extends AbstractPostgresIT {

    @Autowired LeaseRenewalService renewal;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService cheques;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired LeaseRepository leaseRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("Asia/Dubai"));
    private static final LocalDate START = TODAY.minusMonths(2).withDayOfMonth(1);
    private static final LocalDate END = START.plusYears(1).minusDays(1);
    private static final LocalDate YEAR_2099 = LocalDate.of(2099, 12, 31);

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

    @Test
    void aNewContractDated2099IsRefused() {
        CreateLeaseDTO dto = dto(YEAR_2099);
        assertThatThrownBy(() -> leaseService.createDraftLease(dto))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("more than a year ahead")
                .hasMessageContaining("31/12/2099")
                .satisfies(e -> assertThat(((BusinessRuleViolationException) e).getCode())
                        .isEqualTo("posting.dateTooFarAhead"));
    }

    @Test
    void exactlyAYearAheadIsAllowedAndOneDayMoreIsNot() {
        LeaseDTO ok = leaseService.createDraftLease(dto(TODAY.plusYears(1)));
        assertThat(ok.getContractDate()).isEqualTo(TODAY.plusYears(1));

        CreateLeaseDTO oneDayMore = dto(TODAY.plusYears(1).plusDays(1));
        oneDayMore.setUnitId(fixtures.createUnit(fixtures.property(), "CD2").getId());
        assertThatThrownBy(() -> leaseService.createDraftLease(oneDayMore))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("more than a year ahead");
    }

    @Test
    void editingADraftTo2099IsRefusedAndTheDraftKeepsItsDate() {
        UUID id = leaseService.createDraftLease(dto(TODAY)).getId();

        assertThatThrownBy(() -> leaseService.updateDraftLease(id, dto(YEAR_2099)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("more than a year ahead");
        assertThat(reread(id).getContractDate()).isEqualTo(TODAY);
    }

    @Test
    void aRenewalDated2099IsRefused() {
        UUID firstId = fixtures.postedLease(TODAY, START, END,
                List.of(line("RENT", "51000")), 4, "400010").lease().getId();

        assertThatThrownBy(() -> renewal.renew(firstId,
                new RenewLeaseRequest(YEAR_2099, END.plusDays(1), END.plusYears(1), null, false)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("more than a year ahead");
    }

    /** A draft saved before the rule (or written around it) still cannot be posted in 2099. */
    @Test
    void aDraftAlreadyDated2099CannotBePosted() {
        UUID id = fixtures.draftLease(TODAY, START, END, List.of(line("RENT", "51000")));
        fixtures.generateGrid(id, 4, START);
        fixtures.numberGrid(id, "400110");
        jdbc.update("update leases set contract_date = ? where id = ?", YEAR_2099, id);

        assertThatThrownBy(() -> posting.post(id))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("more than a year ahead");
        assertThat(jdbc.queryForObject("select count(*) from journal_entries where tenant_id = ?",
                Long.class, fixtures.tenantId())).as("nothing posted").isZero();
    }

    private CreateLeaseDTO dto(LocalDate contractDate) {
        CreateLeaseDTO dto = fixtures.draftDto(START, END, List.of(line("RENT", "51000")));
        dto.setContractDate(contractDate);
        return dto;
    }

    private Lease reread(UUID leaseId) {
        return tx.execute(s -> leaseRepo.findById(leaseId).orElseThrow());
    }
}

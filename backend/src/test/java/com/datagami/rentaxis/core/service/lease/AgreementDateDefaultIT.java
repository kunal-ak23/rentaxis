package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
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
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Owner ruling 2026-09-29: the contract wizard no longer asks for an agreement
 * date — only the contract date. The column stays (the generated contract prints
 * it, the DTO and the portfolio import carry it), so the server fills it in: an
 * omitted agreement date is the contract date, and it keeps following the contract
 * date while it still holds the value it defaulted to. One given explicitly is kept.
 */
@SpringBootTest
class AgreementDateDefaultIT extends AbstractPostgresIT {

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

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Dubai"));
    private static final LocalDate START = TODAY.plusMonths(1).withDayOfMonth(1);
    private static final LocalDate END = START.plusYears(1).minusDays(1);

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

    private CreateLeaseDTO dto(LocalDate contractDate, LocalDate agreementDate) {
        CreateLeaseDTO dto = fixtures.draftDto(START, END, List.of(line("RENT", "51000")));
        dto.setContractDate(contractDate);
        dto.setAgreementDate(agreementDate);
        return dto;
    }

    private Lease reread(UUID id) {
        return tx.execute(s -> leaseRepo.findById(id).orElseThrow());
    }

    @Test
    void anOmittedAgreementDateIsTheContractDate() {
        LocalDate contract = TODAY.minusDays(3);
        LeaseDTO created = leaseService.createDraftLease(dto(contract, null));

        assertThat(created.getAgreementDate()).isEqualTo(contract);
        assertThat(reread(created.getId()).getAgreementDate()).isEqualTo(contract);
    }

    @Test
    void theDefaultFollowsTheContractDateWhenTheDraftIsEdited() {
        UUID id = leaseService.createDraftLease(dto(TODAY.minusDays(3), null)).getId();

        leaseService.updateDraftLease(id, dto(TODAY.minusDays(1), null));

        Lease lease = reread(id);
        assertThat(lease.getContractDate()).isEqualTo(TODAY.minusDays(1));
        assertThat(lease.getAgreementDate()).isEqualTo(TODAY.minusDays(1));
    }

    @Test
    void anExplicitAgreementDateIsKeptEvenWhenLaterEditsOmitIt() {
        LocalDate signed = TODAY.minusDays(10);
        UUID id = leaseService.createDraftLease(dto(TODAY.minusDays(3), signed)).getId();
        assertThat(reread(id).getAgreementDate()).isEqualTo(signed);

        leaseService.updateDraftLease(id, dto(TODAY.minusDays(1), null));

        assertThat(reread(id).getAgreementDate()).isEqualTo(signed);
    }

    @Test
    void noContractDateAtAllStillLeavesBothDatesSet() {
        LeaseDTO created = leaseService.createDraftLease(dto(null, null));

        assertThat(created.getContractDate()).isNotNull();
        assertThat(created.getAgreementDate()).isEqualTo(created.getContractDate());
    }
}

package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseEventDTO;
import com.datagami.rentaxis.api.dto.lease.GiveNoticeRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.NoticeParty;
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
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #27: giving notice records the date, the party and the move-out date it
 * names, on the lease and in its event trail — and a caller that sends only
 * notes (or nothing) still works, as a renter's notice dated today.
 */
@SpringBootTest
class LeaseNoticeDetailsIT extends AbstractPostgresIT {

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LeaseService leaseService;
    @Autowired ChequeGenerationService generation;
    @Autowired LeasePostingService posting;

    private UUID leaseId;

    @BeforeEach
    void setUp() {
        LeaseTestFixtures f = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService,
                accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, generation, posting);
        LocalDate start = LocalDate.now().minusMonths(3).withDayOfMonth(1);
        leaseId = f.postedLease(start.minusDays(10), start, start.plusYears(1).minusDays(1),
                List.of(line("RENT", "60000")), 4, "300100").lease().getId();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private String lastEventNotes() {
        List<LeaseEventDTO> events = leaseService.getLeaseEvents(leaseId);
        return events.stream().filter(e -> e.getNewState() == LeaseStatus.NOTICE_GIVEN)
                .map(LeaseEventDTO::getNotes).findFirst().orElseThrow();
    }

    @Test
    void theOldNotesOnlyCallIsARentersNoticeDatedToday() {
        LeaseDTO after = leaseService.giveNotice(leaseId, "Relocating to Abu Dhabi", null);

        assertThat(after.getStatus()).isEqualTo(LeaseStatus.NOTICE_GIVEN);
        assertThat(after.getNoticeDate()).isEqualTo(LocalDate.now());
        assertThat(after.getNoticeGivenBy()).isEqualTo(NoticeParty.RENTER);
        assertThat(after.getIntendedMoveOutDate()).isNull();
        assertThat(lastEventNotes())
                .isEqualTo("Notice given by renter on " + LocalDate.now() + ": Relocating to Abu Dhabi");
    }

    @Test
    void aLandlordsNoticeKeepsItsDateAndMoveOut() {
        LocalDate served = LocalDate.now().minusDays(2);
        LocalDate moveOut = LocalDate.now().plusMonths(12);

        LeaseDTO after = leaseService.giveNotice(leaseId,
                new GiveNoticeRequest("Owner moving in", served, NoticeParty.LANDLORD, moveOut), null);

        assertThat(after.getNoticeDate()).isEqualTo(served);
        assertThat(after.getNoticeGivenBy()).isEqualTo(NoticeParty.LANDLORD);
        assertThat(after.getIntendedMoveOutDate()).isEqualTo(moveOut);
        LeaseDTO reread = leaseService.getLeaseById(leaseId);
        assertThat(reread.getNoticeGivenBy()).isEqualTo(NoticeParty.LANDLORD);
        assertThat(reread.getNoticeDate()).isEqualTo(served);
        assertThat(lastEventNotes()).contains("landlord on " + served)
                .contains("intended move-out " + moveOut).contains("Owner moving in");
    }

    @Test
    void aFutureNoticeDateIsRefusedAndTheLeaseStaysActive() {
        assertThatThrownBy(() -> leaseService.giveNotice(leaseId,
                new GiveNoticeRequest(null, LocalDate.now().plusDays(1), NoticeParty.RENTER, null), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("future");
        assertThat(leaseService.getLeaseById(leaseId).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
    }

    @Test
    void aMoveOutBeforeTheNoticeIsRefused() {
        assertThatThrownBy(() -> leaseService.giveNotice(leaseId,
                new GiveNoticeRequest(null, LocalDate.now(), NoticeParty.RENTER, LocalDate.now().minusDays(1)), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("move-out");
        assertThat(leaseService.getLeaseById(leaseId).getStatus()).isEqualTo(LeaseStatus.ACTIVE);
    }
}

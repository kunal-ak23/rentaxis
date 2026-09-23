package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateTicketDTO;
import com.datagami.rentaxis.api.dto.MaintenanceTicketDTO;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Renter;
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
 * Web review I3: {@code GET /tickets?renterId=} narrows the caller's list to one
 * renter's record server-side — logged for them, raised on their contract, or
 * reported from their account — inside the caller's tenant and role scope.
 */
@SpringBootTest
class TicketRenterFilterIT extends AbstractPostgresIT {

    @Autowired MaintenanceTicketService tickets;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired com.datagami.rentaxis.core.service.ledger.PropertyAccountService propertyAccountService;
    @Autowired com.datagami.rentaxis.core.service.lease.ChargeTypeService chargeTypeService;
    @Autowired LeaseService leaseService;
    @Autowired com.datagami.rentaxis.core.service.lease.ChequeGenerationService chequeGeneration;
    @Autowired LeasePostingService leasePosting;

    private LeaseTestFixtures fx;
    private Renter other;
    private UUID loggedFor;
    private UUID reportedByRenter;
    private UUID onTheirLease;
    private UUID someoneElses;

    @BeforeEach
    void setUp() {
        fx = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService,
                accountService, propertyAccountService, chargeTypeService)
                .withLeaseServices(leaseService, chequeGeneration, leasePosting)
                .bootstrap();
        other = fx.createRenter("Other Renter");
        LocalDate start = LocalDate.now().plusMonths(1);
        UUID leaseId = fx.draftLease(start.minusDays(7), start, start.plusYears(1).minusDays(1),
                List.of(line("RENT", "48000")));

        UUID staff = UUID.randomUUID();
        loggedFor = create(staff, t -> t.setOnBehalfOfRenterId(fx.renter().getId()));
        reportedByRenter = create(fx.renter().getUserId(), t -> { });
        onTheirLease = create(staff, t -> t.setLeaseId(leaseId));
        someoneElses = create(staff, t -> t.setOnBehalfOfRenterId(other.getId()));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private UUID create(UUID reporter, java.util.function.Consumer<CreateTicketDTO> tweak) {
        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(fx.property().getId());
        dto.setTitle("Ticket " + UUID.randomUUID());
        tweak.accept(dto);
        return tickets.createTicket(dto, reporter).getId();
    }

    private List<UUID> ids(List<MaintenanceTicketDTO> list) {
        return list.stream().map(MaintenanceTicketDTO::getId).toList();
    }

    @Test
    void anAdminGetsExactlyThisRentersTickets() {
        List<UUID> got = ids(tickets.getTickets(UUID.randomUUID(), "TENANT_ADMIN", null, fx.renter().getId()));

        assertThat(got).containsExactlyInAnyOrder(loggedFor, reportedByRenter, onTheirLease);
        assertThat(ids(tickets.getTickets(UUID.randomUUID(), "TENANT_ADMIN", null, other.getId())))
                .containsExactly(someoneElses);
    }

    @Test
    void theFilterNarrowsARoleScopeItNeverWidensIt() {
        // A PM with no assigned buildings sees nothing, renter filter or not.
        assertThat(tickets.getTickets(UUID.randomUUID(), "PROPERTY_MANAGER", null, fx.renter().getId())).isEmpty();
        // A renter asking for another renter's record gets none of it.
        assertThat(ids(tickets.getTickets(other.getUserId(), "RENTER", null, fx.renter().getId())))
                .doesNotContain(loggedFor, reportedByRenter, onTheirLease);
    }

    @Test
    void anotherLandlordsRenterIsNotFound() {
        UUID ours = fx.tenantId();
        fx.newTenant();
        Renter foreign = fx.createRenter("Foreign");
        TenantContextHolder.setTenantId(ours);

        assertThatThrownBy(() -> tickets.getTickets(UUID.randomUUID(), "TENANT_ADMIN", null, foreign.getId()))
                .isInstanceOf(NotFoundException.class);
    }
}

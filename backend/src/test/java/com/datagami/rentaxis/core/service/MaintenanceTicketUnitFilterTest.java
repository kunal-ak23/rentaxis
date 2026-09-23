package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.TicketStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.TicketAttachmentRepository;
import com.datagami.rentaxis.domain.repository.TicketHistoryRepository;
import com.datagami.rentaxis.domain.repository.TicketReplyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Narrowing the ticket list to one unit in the database.
 *
 * <p>The lease detail page needs the tickets for a single unit. It used to GET
 * the whole tenant's list — every property, every unit, all time — and keep the
 * rows matching one unitId, so opening a lease transferred the landlord's
 * entire maintenance history on every page view.</p>
 *
 * <p>What these tests are really pinning is that the unit filter <em>narrows</em>
 * the role scope rather than replacing it. A renter who passes someone else's
 * unitId must still see only tickets they reported — a unit-wide query for a
 * renter would be a tenant-data leak dressed up as an optimisation.</p>
 */
class MaintenanceTicketUnitFilterTest {

    private MaintenanceTicketRepository ticketRepository;
    private UserPropertyAssignmentRepository propertyAssignmentRepository;
    private MaintenanceTicketService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID unitId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ticketRepository = mock(MaintenanceTicketRepository.class);
        propertyAssignmentRepository = mock(UserPropertyAssignmentRepository.class);
        UserRepository userRepository = mock(UserRepository.class);

        service = new MaintenanceTicketService(
                ticketRepository,
                mock(TicketReplyRepository.class),
                mock(TicketAttachmentRepository.class),
                mock(PropertyRepository.class),
                mock(UnitRepository.class),
                mock(LeaseRepository.class),
                userRepository,
                propertyAssignmentRepository,
                mock(TicketHistoryRepository.class),
                mock(LandlordOrgRepository.class),
                mock(NotificationService.class),
                mock(ApplicationEventPublisher.class),
                mock(com.datagami.rentaxis.core.service.ledger.EntryNumberService.class));

        when(userRepository.findDisplayNameById(any())).thenReturn(Optional.empty());
        when(ticketRepository.findByUnitId(any())).thenReturn(List.of(ticket()));
        when(ticketRepository.findByReportedByAndUnitId(any(), any())).thenReturn(List.of(ticket()));
        when(ticketRepository.findByPropertyIdInAndUnitId(any(), any())).thenReturn(List.of(ticket()));
        when(ticketRepository.findAll()).thenReturn(List.of(ticket()));
        when(ticketRepository.findByReportedBy(any())).thenReturn(List.of(ticket()));
        when(ticketRepository.findByPropertyIdIn(any())).thenReturn(List.of(ticket()));
    }

    private MaintenanceTicket ticket() {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        MaintenanceTicket t = new MaintenanceTicket();
        t.setId(UUID.randomUUID());
        t.setProperty(property);
        t.setReportedBy(userId);
        t.setTitle("Leaky tap");
        t.setStatus(TicketStatus.OPEN);
        return t;
    }

    @Test
    void adminWithAUnitQueriesThatUnitRatherThanEverything() {
        service.getTickets(userId, "TENANT_ADMIN", unitId);

        verify(ticketRepository).findByUnitId(unitId);
        verify(ticketRepository, never()).findAll();
    }

    @Test
    void adminWithNoUnitStillSeesEverything() {
        service.getTickets(userId, "TENANT_ADMIN", null);

        verify(ticketRepository).findAll();
        verify(ticketRepository, never()).findByUnitId(any());
    }

    /**
     * The important one. A unit-wide query for a renter would hand them every
     * ticket on that unit, including ones raised by a previous tenant.
     */
    @Test
    void renterWithAUnitIsStillScopedToTicketsTheyReported() {
        service.getTickets(userId, "RENTER", unitId);

        verify(ticketRepository).findByReportedByAndUnitId(userId, unitId);
        verify(ticketRepository, never()).findByUnitId(any());
        verify(ticketRepository, never()).findAll();
    }

    @Test
    void tenantUserWithAUnitIsScopedTheSameWay() {
        service.getTickets(userId, "TENANT_USER", unitId);

        verify(ticketRepository).findByReportedByAndUnitId(userId, unitId);
        verify(ticketRepository, never()).findByUnitId(any());
    }

    @Test
    void propertyManagerWithAUnitIsStillScopedToAssignedProperties() {
        UUID propertyId = UUID.randomUUID();
        UserPropertyAssignment assignment = new UserPropertyAssignment();
        assignment.setUserId(userId);
        assignment.setPropertyId(propertyId);
        when(propertyAssignmentRepository.findByUserId(userId)).thenReturn(List.of(assignment));

        service.getTickets(userId, "PROPERTY_MANAGER", unitId);

        verify(ticketRepository).findByPropertyIdInAndUnitId(List.of(propertyId), unitId);
        verify(ticketRepository, never()).findByUnitId(any());
        verify(ticketRepository, never()).findAll();
    }

    /** A manager with no assignments sees nothing, unit filter or not. */
    @Test
    void propertyManagerWithNoAssignmentsQueriesNothing() {
        when(propertyAssignmentRepository.findByUserId(userId)).thenReturn(List.of());

        assertThat(service.getTickets(userId, "PROPERTY_MANAGER", unitId)).isEmpty();

        verify(ticketRepository, never()).findByPropertyIdInAndUnitId(any(), any());
        verify(ticketRepository, never()).findByUnitId(any());
    }

    /** The two-argument overload must keep behaving exactly as before. */
    @Test
    void theUnfilteredOverloadIsUnchanged() {
        service.getTickets(userId, "TENANT_ADMIN");

        verify(ticketRepository).findAll();
        verify(ticketRepository, never()).findByUnitId(any());
    }
}

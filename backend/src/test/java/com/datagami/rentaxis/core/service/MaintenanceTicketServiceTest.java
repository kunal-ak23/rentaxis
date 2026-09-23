package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateTicketDTO;
import com.datagami.rentaxis.api.dto.MaintenanceTicketDTO;
import com.datagami.rentaxis.api.dto.TicketAttachmentDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.MaintenanceTicket;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.TicketAttachment;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MaintenanceTicketServiceTest {

    private MaintenanceTicketRepository ticketRepository;
    private TicketAttachmentRepository attachmentRepository;
    private PropertyRepository propertyRepository;
    private UserRepository userRepository;
    private MaintenanceTicketService service;

    @BeforeEach
    void setUp() {
        ticketRepository = mock(MaintenanceTicketRepository.class);
        TicketReplyRepository replyRepository = mock(TicketReplyRepository.class);
        attachmentRepository = mock(TicketAttachmentRepository.class);
        propertyRepository = mock(PropertyRepository.class);
        UnitRepository unitRepository = mock(UnitRepository.class);
        LeaseRepository leaseRepository = mock(LeaseRepository.class);
        userRepository = mock(UserRepository.class);
        UserPropertyAssignmentRepository propertyAssignmentRepository =
                mock(UserPropertyAssignmentRepository.class);
        TicketHistoryRepository historyRepository = mock(TicketHistoryRepository.class);
        LandlordOrgRepository landlordOrgRepository = mock(LandlordOrgRepository.class);
        NotificationService notificationService = mock(NotificationService.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

        service = new MaintenanceTicketService(
                ticketRepository, replyRepository, attachmentRepository,
                propertyRepository, unitRepository, leaseRepository,
                userRepository, propertyAssignmentRepository, historyRepository,
                landlordOrgRepository, notificationService, events,
                mock(com.datagami.rentaxis.core.service.ledger.EntryNumberService.class),
                mock(com.datagami.rentaxis.domain.repository.RenterRepository.class),
                new com.datagami.rentaxis.core.security.PropertyScope(
                        new com.datagami.rentaxis.core.security.LeaseAccessPolicy(propertyAssignmentRepository,
                                mock(com.datagami.rentaxis.domain.repository.RenterRepository.class))));

        when(userRepository.findDisplayNameById(any())).thenReturn(Optional.empty());
        // The ticket service resolves the caller's reach (round 5): act as a tenant admin.
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        java.util.UUID.randomUUID().toString(), null,
                        java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuth() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private MaintenanceTicket ticket(UUID reportedBy, String closureOtp) {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        MaintenanceTicket ticket = new MaintenanceTicket();
        ticket.setId(UUID.randomUUID());
        ticket.setProperty(property);
        ticket.setReportedBy(reportedBy);
        ticket.setTitle("Leaky tap");
        ticket.setStatus(TicketStatus.RESOLVED);
        ticket.setClosureOtp(closureOtp);
        return ticket;
    }

    // ---- createTicket: null propertyId must be a 400, not findById(null) ----

    @Test
    void createTicket_nullPropertyId_throwsBusinessRuleViolation() {
        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setTitle("No property");

        assertThatThrownBy(() -> service.createTicket(dto, UUID.randomUUID()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("propertyId");

        verify(propertyRepository, never()).findById(any());
        verify(ticketRepository, never()).save(any());
    }

    // ---- reported date: a complaint logged late keeps the day it was raised ----

    @Test
    void createTicket_usesTheGivenReportedDate() {
        UUID propertyId = UUID.randomUUID();
        Property property = new Property();
        property.setId(propertyId);
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(ticketRepository.save(any(MaintenanceTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(propertyId);
        dto.setTitle("AC reported by phone last week");
        dto.setReportedDate(java.time.LocalDate.now().minusDays(6));

        MaintenanceTicketDTO created = service.createTicket(dto, UUID.randomUUID());

        assertThat(created.getReportedDate()).isEqualTo(java.time.LocalDate.now().minusDays(6));
    }

    @Test
    void createTicket_defaultsReportedDateToToday() {
        UUID propertyId = UUID.randomUUID();
        Property property = new Property();
        property.setId(propertyId);
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));
        when(ticketRepository.save(any(MaintenanceTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(propertyId);
        dto.setTitle("Reported now");

        MaintenanceTicketDTO created = service.createTicket(dto, UUID.randomUUID());

        assertThat(created.getReportedDate()).isEqualTo(java.time.LocalDate.now());
    }

    @Test
    void createTicket_refusesAFutureReportedDate() {
        UUID propertyId = UUID.randomUUID();
        Property property = new Property();
        property.setId(propertyId);
        when(propertyRepository.findById(propertyId)).thenReturn(Optional.of(property));

        CreateTicketDTO dto = new CreateTicketDTO();
        dto.setPropertyId(propertyId);
        dto.setTitle("From the future");
        dto.setReportedDate(java.time.LocalDate.now().plusDays(1));

        assertThatThrownBy(() -> service.createTicket(dto, UUID.randomUUID()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("future");
        verify(ticketRepository, never()).save(any());
    }

    // ---- closureOtp redaction ----

    @Test
    void getTicket_asReporter_includesClosureOtp() {
        UUID reporter = UUID.randomUUID();
        MaintenanceTicket t = ticket(reporter, "123456");
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));

        MaintenanceTicketDTO dto = service.getTicket(t.getId(), reporter);

        assertThat(dto.getClosureOtp()).isEqualTo("123456");
    }

    @Test
    void getTicket_asOtherUser_redactsClosureOtp() {
        MaintenanceTicket t = ticket(UUID.randomUUID(), "123456");
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));

        MaintenanceTicketDTO dto = service.getTicket(t.getId(), UUID.randomUUID());

        assertThat(dto.getClosureOtp()).isNull();
    }

    @Test
    void getTicket_withoutRequester_redactsClosureOtp() {
        MaintenanceTicket t = ticket(UUID.randomUUID(), "123456");
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));

        MaintenanceTicketDTO dto = service.getTicket(t.getId(), null);

        assertThat(dto.getClosureOtp()).isNull();
    }

    @Test
    void getTickets_redactsClosureOtpForNonReporter() {
        UUID manager = UUID.randomUUID();
        MaintenanceTicket t = ticket(UUID.randomUUID(), "654321");
        when(ticketRepository.findAll()).thenReturn(List.of(t));

        List<MaintenanceTicketDTO> dtos = service.getTickets(manager, "TENANT_ADMIN");

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).getClosureOtp()).isNull();
    }

    @Test
    void getTickets_includesClosureOtpForReporter() {
        UUID renter = UUID.randomUUID();
        MaintenanceTicket t = ticket(renter, "654321");
        when(ticketRepository.findByReportedBy(renter)).thenReturn(List.of(t));

        List<MaintenanceTicketDTO> dtos = service.getTickets(renter, "RENTER");

        assertThat(dtos).hasSize(1);
        assertThat(dtos.get(0).getClosureOtp()).isEqualTo("654321");
    }

    // ---- attachments are mapped to DTOs, not raw entities ----

    @Test
    void getAttachments_mapsEntityToDTO() {
        UUID ticketId = UUID.randomUUID();
        MaintenanceTicket t = ticket(UUID.randomUUID(), null);
        t.setId(ticketId);

        TicketAttachment attachment = new TicketAttachment();
        attachment.setId(UUID.randomUUID());
        attachment.setTicket(t);
        attachment.setFileUrl("/api/v1/assets/serve/ticket-attachments/abc123.png");
        attachment.setFileType("image/png");
        attachment.setFileSize(2048L);
        attachment.setUploadedAt(Instant.parse("2026-08-01T10:00:00Z"));

        when(attachmentRepository.findByTicketId(ticketId)).thenReturn(List.of(attachment));
        // Every role now resolves the ticket first: the reach check is not renter-only.
        when(ticketRepository.findById(ticketId)).thenReturn(Optional.of(t));

        List<TicketAttachmentDTO> dtos = service.getAttachments(ticketId);

        assertThat(dtos).hasSize(1);
        TicketAttachmentDTO dto = dtos.get(0);
        assertThat(dto.getId()).isEqualTo(attachment.getId());
        assertThat(dto.getTicketId()).isEqualTo(ticketId);
        assertThat(dto.getFileUrl()).isEqualTo("/api/v1/assets/serve/ticket-attachments/abc123.png");
        assertThat(dto.getFileType()).isEqualTo("image/png");
        assertThat(dto.getFileSize()).isEqualTo(2048L);
        assertThat(dto.getUploadedAt()).isEqualTo(Instant.parse("2026-08-01T10:00:00Z"));
    }
}

package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.event.EmailEvent;
import com.datagami.rentaxis.core.email.event.payload.TicketPayload;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.TicketCategory;
import com.datagami.rentaxis.domain.entity.enums.TicketPriority;
import com.datagami.rentaxis.domain.entity.enums.TicketStatus;
import com.datagami.rentaxis.domain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class MaintenanceTicketService {

    private final MaintenanceTicketRepository ticketRepository;
    private final TicketReplyRepository replyRepository;
    private final TicketAttachmentRepository attachmentRepository;
    private final PropertyRepository propertyRepository;
    private final UnitRepository unitRepository;
    private final LeaseRepository leaseRepository;
    private final UserRepository userRepository;
    private final UserPropertyAssignmentRepository propertyAssignmentRepository;
    private final TicketHistoryRepository historyRepository;
    private final LandlordOrgRepository landlordOrgRepository;
    private final NotificationService notificationService;
    private final ApplicationEventPublisher events;
    private final com.datagami.rentaxis.core.service.ledger.EntryNumberService entryNumberService;
    private final com.datagami.rentaxis.domain.repository.RenterRepository renterRepository;

    @Value("${AZURE_STORAGE_CONNECTION_STRING:}")
    private String azureConnectionString;

    @Value("${AZURE_STORAGE_CONTAINER_PREFIX:tenant-}")
    private String containerPrefix;

    @Value("${rentaxis.assets.storage-path:./data/assets}")
    private String localStoragePath;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static boolean callerIsRenter() {
        return callerHasRole("RENTER");
    }

    private static boolean callerHasRole(String role) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> ("ROLE_" + role).equals(a.getAuthority()));
    }

    /**
     * The calling renter's user id, from the verified security principal (not
     * the X-User-Id header), or {@code null} when the caller is not a renter.
     */
    private static UUID callingRenterUserId() {
        if (!callerIsRenter()) return null;
        String name = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getName();
        try {
            return UUID.fromString(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            // A renter we cannot identify owns nothing.
            throw new NotFoundException("Ticket not found");
        }
    }

    /**
     * Renter isolation: a renter may reach a ticket only when they reported it,
     * it was logged on their behalf (#19) or it was raised on their contract.
     * Anything else is the same 404 as a
     * ticket that does not exist, so its existence is not revealed. Staff pass
     * through; their tenant scope is the Hibernate tenant filter.
     */
    private void requireRenterOwns(MaintenanceTicket ticket) {
        UUID caller = callingRenterUserId();
        if (caller == null) return;
        if (caller.equals(ticket.getReportedBy())) return;
        UUID callerRenterId = renterRepository.findByUserId(caller).map(Renter::getId).orElse(null);
        if (callerRenterId != null) {
            if (callerRenterId.equals(ticket.getOnBehalfOfRenterId())) return;
            Renter leaseRenter = leaseRenter(ticket);
            if (leaseRenter != null && callerRenterId.equals(leaseRenter.getId())) return;
        }
        throw new NotFoundException("Ticket not found");
    }

    /** The ticket, when it exists in the tenant and the caller may reach it. */
    private MaintenanceTicket visibleTicket(UUID ticketId) {
        MaintenanceTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));
        requireRenterOwns(ticket);
        return ticket;
    }

    /** For the sub-resource reads: a renter must own the ticket first. */
    private void requireRenterOwns(UUID ticketId) {
        if (callerIsRenter()) visibleTicket(ticketId);
    }

    /** The journal_entry_sequences series for ticket references (#20). */
    static final String TICKET_SERIES = "TKT";

    // ---- Ticket CRUD ----

    @Transactional
    public MaintenanceTicketDTO createTicket(CreateTicketDTO dto, UUID reportedBy) {
        // findById(null) would throw an opaque InvalidDataAccessApiUsageException
        // (surfaced as a 500); reject the missing field explicitly instead.
        if (dto.getPropertyId() == null) {
            throw new BusinessRuleViolationException("propertyId is required");
        }
        Property property = propertyRepository.findById(dto.getPropertyId())
                .orElseThrow(() -> new NotFoundException("Property not found"));

        MaintenanceTicket ticket = new MaintenanceTicket();
        ticket.setProperty(property);
        ticket.setReportedBy(reportedBy);
        ticket.setTitle(dto.getTitle());
        ticket.setDescription(dto.getDescription());
        ticket.setStatus(TicketStatus.OPEN);

        if (dto.getUnitId() != null) {
            Unit unit = unitRepository.findById(dto.getUnitId())
                    .orElseThrow(() -> new NotFoundException("Unit not found"));
            ticket.setUnit(unit);
        }

        if (dto.getLeaseId() != null) {
            Lease lease = leaseRepository.findById(dto.getLeaseId())
                    .orElseThrow(() -> new NotFoundException("Lease not found"));
            // A lease's renter holds its tickets' closure OTP, so a renter may
            // only raise a ticket on their own contract.
            if (callerIsRenter() && (lease.getRenter() == null
                    || !reportedBy.equals(lease.getRenter().getUserId()))) {
                throw new NotFoundException("Lease not found");
            }
            ticket.setLease(lease);
        }

        if (dto.getCategory() != null) {
            ticket.setCategory(TicketCategory.valueOf(dto.getCategory()));
        }

        if (dto.getPriority() != null) {
            ticket.setPriority(TicketPriority.valueOf(dto.getPriority()));
        } else {
            ticket.setPriority(TicketPriority.MEDIUM);
        }

        ticket.setOnBehalfOf(dto.getOnBehalfOf());
        if (dto.getOnBehalfOfRenterId() != null) {
            // #19: a renter picked from the org's list. Staff only — a renter
            // reports for themselves — and resolved in the ticket's tenant, so a
            // foreign renter id is the same 404 as a missing one.
            if (callerIsRenter()) {
                throw new BusinessRuleViolationException("Only staff can log a ticket on a renter's behalf");
            }
            com.datagami.rentaxis.domain.entity.Renter onBehalf = renterRepository.findById(dto.getOnBehalfOfRenterId())
                    .filter(r -> r.getTenantId() != null && r.getTenantId().equals(property.getTenantId()))
                    .orElseThrow(() -> new NotFoundException("Renter not found"));
            ticket.setOnBehalfOfRenterId(onBehalf.getId());
            // The legacy text column still carries the name, for readers that only
            // know it (the manager app, older exports).
            ticket.setOnBehalfOf(onBehalf.getNameEn());
        }

        // The day the tenant reported it — for a complaint logged after the fact,
        // the operator sets an earlier date. A future date is refused: a ticket
        // cannot have been reported tomorrow. Absent, it is today.
        LocalDate reportedDate = dto.getReportedDate() != null ? dto.getReportedDate() : LocalDate.now();
        if (reportedDate.isAfter(LocalDate.now())) {
            throw new BusinessRuleViolationException("A ticket cannot be reported in the future");
        }
        ticket.setReportedDate(reportedDate);

        // #20: a reference people can read out, "TKT-26/14" — per tenant (the
        // property's) and calendar year of entry, from the same locked counter
        // the journal numbers use, so two concurrent tickets never share one.
        ticket.setReference(entryNumberService.nextNumberForYear(
                property.getTenantId(), TICKET_SERIES, LocalDate.now().getYear()));

        MaintenanceTicket saved = ticketRepository.save(ticket);
        log.info("Created maintenance ticket {} for property {}", saved.getId(), property.getId());

        // Emit TICKET_CREATED event
        try {
            events.publishEvent(new EmailEvent(this,
                    EmailEventType.TICKET_CREATED,
                    saved.getTenantId(),
                    new TicketPayload(
                            saved.getId(),
                            saved.getReportedBy(),
                            saved.getAssignedTo(),
                            saved.getTitle(),
                            saved.getCategory() != null ? saved.getCategory().name() : null,
                            saved.getPriority() != null ? saved.getPriority().name() : null,
                            saved.getStatus().name(),
                            null),
                    "TICKET_CREATED:" + saved.getId()));
        } catch (Exception e) {
            log.warn("Failed to publish TICKET_CREATED event for ticket {}", saved.getId(), e);
        }

        return mapToDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceTicketDTO> getTickets(UUID userId, String role) {
        return getTickets(userId, role, null);
    }

    /**
     * @param unitId optional. When given, the query is narrowed to that unit in
     *        the database rather than by the caller after the fact — the lease
     *        detail page wants one unit's tickets, and used to pull the tenant's
     *        entire maintenance history to filter client-side.
     *
     *        <p>It narrows the role scope, it does not bypass it: a renter
     *        passing someone else's unitId still sees only tickets they
     *        reported.</p>
     *
     * <p><b>{@code @Transactional} is load-bearing here (P0).</b> Nothing in this
     * method names a tenant: the TENANT_ADMIN branch asks for {@code findAll()} and
     * means "every ticket of <em>this</em> landlord", which is true only while the
     * Hibernate tenant filter is on — and {@code TenantAspect} turns it on for the
     * duration of a transaction. Without the annotation each repository call ran in
     * its own short transaction with no filter enabled, and
     * {@code GET /maintenance-tickets} answered a tenant admin with every
     * landlord's tickets. The two-argument overload above was annotated and
     * therefore safe; this one, which the controller actually calls, was not.
     * {@code CrossTenantReadGuardIT#aTenantAdminSeesOnlyTheirOwnTenantsTickets}
     * pins it.</p>
     */
    @Transactional(readOnly = true)
    public List<MaintenanceTicketDTO> getTickets(UUID userId, String role, UUID unitId) {
        return getTickets(userId, role, unitId, null);
    }

    /**
     * @param renterId optional (web review I3). Narrows the caller's list to one
     *        renter's record — tickets logged on their behalf, raised on one of
     *        their contracts, or reported from their portal account — so the
     *        renter page no longer downloads the tenant's whole maintenance
     *        history to filter it in the browser. The renter is resolved in the
     *        caller's tenant (a foreign or unknown id is a 404), and the role
     *        scope below still applies: it narrows, it never widens.
     */
    @Transactional(readOnly = true)
    public List<MaintenanceTicketDTO> getTickets(UUID userId, String role, UUID unitId, UUID renterId) {
        List<MaintenanceTicket> tickets = scopedTickets(userId, role, unitId);
        if (renterId != null) {
            Renter renter = renterRepository.findById(renterId)
                    .filter(r -> TenantContextHolder.getTenantId() == null
                            || TenantContextHolder.getTenantId().equals(r.getTenantId()))
                    .orElseThrow(() -> new NotFoundException("Renter not found"));
            Set<UUID> ofRenter = (renter.getUserId() != null
                    ? ticketRepository.findForRenterRecord(renter.getId(), renter.getUserId())
                    : ticketRepository.findForRenterRecordWithoutAccount(renter.getId()))
                    .stream().map(MaintenanceTicket::getId).collect(Collectors.toSet());
            tickets = tickets.stream().filter(t -> ofRenter.contains(t.getId())).toList();
        }
        return tickets.stream()
                .map(t -> mapToDTO(t, userId))
                .collect(Collectors.toList());
    }

    /** The tickets the caller's role may see, optionally narrowed to one unit. */
    private List<MaintenanceTicket> scopedTickets(UUID userId, String role, UUID unitId) {
        List<MaintenanceTicket> tickets;

        if ("RENTER".equals(role) || "TENANT_USER".equals(role)) {
            // Renters/tenant users see the tickets they reported, and a renter
            // also sees the ones staff logged on their behalf (#19).
            UUID renterId = "RENTER".equals(role)
                    ? renterRepository.findByUserId(userId).map(Renter::getId).orElse(null)
                    : null;
            if (renterId != null) {
                tickets = unitId != null
                        ? ticketRepository.findForRenterAndUnitId(userId, renterId, unitId)
                        : ticketRepository.findForRenter(userId, renterId);
            } else {
                tickets = unitId != null
                        ? ticketRepository.findByReportedByAndUnitId(userId, unitId)
                        : ticketRepository.findByReportedBy(userId);
            }
        } else if ("PROPERTY_MANAGER".equals(role)) {
            // Property managers see tickets for their assigned properties
            List<UserPropertyAssignment> assignments = propertyAssignmentRepository.findByUserId(userId);
            List<UUID> propertyIds = assignments.stream()
                    .map(UserPropertyAssignment::getPropertyId)
                    .collect(Collectors.toList());
            if (propertyIds.isEmpty()) {
                tickets = List.of();
            } else {
                tickets = unitId != null
                        ? ticketRepository.findByPropertyIdInAndUnitId(propertyIds, unitId)
                        : ticketRepository.findByPropertyIdIn(propertyIds);
            }
        } else {
            // TENANT_ADMIN and SUPER_ADMIN see all tickets for the tenant
            tickets = unitId != null
                    ? ticketRepository.findByUnitId(unitId)
                    : ticketRepository.findAll();
        }
        return tickets;
    }

    @Transactional(readOnly = true)
    public MaintenanceTicketDTO getTicket(UUID ticketId, UUID requesterId) {
        MaintenanceTicket ticket = visibleTicket(ticketId);
        return mapToDTO(ticket, requesterId);
    }

    @Transactional
    public MaintenanceTicketDTO assignTicket(UUID ticketId, UUID assignTo, UUID performedBy) {
        MaintenanceTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));

        UUID previousAssignee = ticket.getAssignedTo();
        String previousStatus = ticket.getStatus() != null ? ticket.getStatus().name() : null;
        ticket.setAssignedTo(assignTo);
        if (ticket.getStatus() == TicketStatus.OPEN || ticket.getStatus() == TicketStatus.REOPENED) {
            ticket.setStatus(TicketStatus.ASSIGNED);
        }

        // Resolve names for descriptive history
        String assigneeName = userRepository.findDisplayNameById(assignTo).orElse("Unknown");
        String action = previousAssignee == null ? "ASSIGNED" : "REASSIGNED";
        String notes = previousAssignee == null
                ? "Ticket assigned to " + assigneeName
                : "Ticket reassigned to " + assigneeName;

        MaintenanceTicket saved = ticketRepository.save(ticket);
        recordHistory(saved, action, previousStatus, saved.getStatus().name(),
                previousAssignee, assignTo, performedBy != null ? performedBy : assignTo, notes);
        log.info("Assigned ticket {} to user {}", ticketId, assignTo);

        // Notify assignee
        try {
            notificationService.notify(ticket.getTenantId(), assignTo,
                    "TICKET_ASSIGNED", "Ticket Assigned to You",
                    "Ticket: " + ticket.getTitle(),
                    "TICKET", ticket.getId());
        } catch (Exception e) {
            log.warn("Failed to send ticket assignment notification for ticket {}", ticketId, e);
        }

        return mapToDTO(saved);
    }

    @Transactional
    public MaintenanceTicketDTO updateStatus(UUID ticketId, String newStatus, UUID performedBy) {
        MaintenanceTicket ticket = visibleTicket(ticketId);

        String fromStatus = ticket.getStatus().name();
        TicketStatus targetStatus = TicketStatus.valueOf(newStatus);
        validateStatusTransition(ticket.getStatus(), targetStatus);

        // A resolved ticket is closed by its renter's OTP. The status route may
        // close it only when nobody could hand one over (or the tenant does not
        // ask for OTPs); otherwise it would let staff skip the renter.
        String closeNote = null;
        if (ticket.getStatus() == TicketStatus.RESOLVED && targetStatus == TicketStatus.CLOSED
                && otpRequired(ticket)) {
            if (otpClosureLocked(ticket)) {
                // Too many wrong OTPs: only an admin may close it, on the record.
                if (!callerHasRole("TENANT_ADMIN") && !callerHasRole("SUPER_ADMIN")) {
                    throw new AccessDeniedException(
                            "OTP closure is locked for this ticket. Only a tenant admin can close it.");
                }
                closeNote = "Ticket closed without OTP (OTP closure locked after "
                        + MAX_TOTAL_OTP_FAILURES + " wrong OTPs)";
            } else if (hasRenterToConfirm(ticket)) {
                throw new BusinessRuleViolationException(
                        "A resolved ticket is closed with the renter's OTP. Use OTP closure (PUT /tickets/{id}/close).");
            } else {
                closeNote = "Ticket closed without OTP (no renter to confirm)";
            }
        }

        ticket.setStatus(targetStatus);
        if (targetStatus == TicketStatus.CLOSED) {
            ticket.setClosedAt(Instant.now());
            ticket.setClosureOtp(null);
        }

        if (targetStatus == TicketStatus.RESOLVED) {
            ticket.setResolvedAt(Instant.now());
            if (!otpClosureLocked(ticket)) issueClosureOtp(ticket);
            log.info("Ticket {} resolved. Closure OTP generated.", ticketId);
        }

        if (targetStatus == TicketStatus.IN_PROGRESS && ticket.getAssignedTo() == null) {
            throw new BusinessRuleViolationException("Ticket must be assigned before moving to IN_PROGRESS");
        }

        MaintenanceTicket saved = ticketRepository.save(ticket);
        recordHistory(saved, "STATUS_CHANGED", fromStatus, targetStatus.name(),
                null, null, performedBy != null ? performedBy : ticket.getReportedBy(),
                closeNote != null ? closeNote : "Status changed: " + fromStatus + " → " + targetStatus.name());

        // Notify the OTP holder when the ticket is resolved: the renter it was
        // logged for, never the staff member who logged it (PR #342 review I2).
        if (targetStatus == TicketStatus.RESOLVED && !otpClosureLocked(ticket)) {
            notifyOtpHolder(ticket, "TICKET_RESOLVED", "Ticket Resolved",
                    "Your ticket '" + ticket.getTitle() + "' has been resolved. Please share the OTP to close.");
        }

        // Emit TICKET_REOPENED event when transitioning to REOPENED status
        if (targetStatus == TicketStatus.REOPENED) {
            try {
                events.publishEvent(new EmailEvent(this,
                        EmailEventType.TICKET_REOPENED,
                        saved.getTenantId(),
                        new TicketPayload(
                                saved.getId(),
                                saved.getReportedBy(),
                                saved.getAssignedTo(),
                                saved.getTitle(),
                                saved.getCategory() != null ? saved.getCategory().name() : null,
                                saved.getPriority() != null ? saved.getPriority().name() : null,
                                saved.getStatus().name(),
                                null),
                        "TICKET_REOPENED:" + saved.getId()));
            } catch (Exception e) {
                log.warn("Failed to publish TICKET_REOPENED event for ticket {}", saved.getId(), e);
            }
        }

        return mapToDTO(saved);
    }

    /** Wrong closure OTPs allowed before the code is discarded (PR #342 review I2). */
    static final int MAX_OTP_ATTEMPTS = 5;

    /**
     * Wrong closure OTPs a ticket may receive in its lifetime, across every
     * re-issued code; at this many OTP closure is locked for good (PR #342
     * re-review I2). Without it, re-issuing reset the per-code counter and
     * guessing was unbounded.
     */
    static final int MAX_TOTAL_OTP_FAILURES = 10;

    /** Closure OTP re-issues allowed per ticket in any 24 hours. */
    static final int MAX_OTP_REISSUES_PER_DAY = 3;

    private static boolean otpClosureLocked(MaintenanceTicket ticket) {
        return ticket.getClosureOtpTotalFailedAttempts() >= MAX_TOTAL_OTP_FAILURES;
    }

    /**
     * Who holds a ticket's closure OTP: the renter it was logged for, when staff
     * logged it on a renter's behalf (#19), and otherwise whoever reported it.
     *
     * <p>The OTP is the renter's confirmation that the work is done. Keyed on the
     * reporter alone, an on-behalf ticket put it in the hands of the staff member
     * who logged it, who could then resolve and close it without the renter. A
     * renter with no portal account holds it too, which means nobody can read it:
     * the ticket is then closed by the tenant's non-OTP route, not by staff
     * quoting a code to themselves.
     */
    private UUID otpHolder(MaintenanceTicket ticket) {
        if (ticket.getOnBehalfOfRenterId() != null) {
            return renterRepository.findById(ticket.getOnBehalfOfRenterId())
                    .map(Renter::getUserId)
                    .orElse(null);
        }
        // A ticket raised on a contract belongs to that contract's renter, even
        // when staff logged it (PR #342 re-review I1).
        Renter leaseRenter = leaseRenter(ticket);
        if (leaseRenter != null) {
            return leaseRenter.getUserId();
        }
        return ticket.getReportedBy();
    }

    private static Renter leaseRenter(MaintenanceTicket ticket) {
        return ticket.getLease() != null ? ticket.getLease().getRenter() : null;
    }

    /**
     * Whether someone who can act holds the closure OTP: a renter with a portal
     * account the ticket was logged for or whose contract it was raised on, or a
     * renter who reported it. An
     * on-behalf renter with no account, or a ticket with no renter behind it
     * (staff-originated, legacy free-text on-behalf), has nobody to confirm.
     */
    private boolean hasRenterToConfirm(MaintenanceTicket ticket) {
        UUID holder = otpHolder(ticket);
        if (holder == null) return false;
        if (ticket.getOnBehalfOfRenterId() != null || leaseRenter(ticket) != null) return true;
        return renterRepository.findByUserId(holder).isPresent()
                || userRepository.findById(holder)
                        .map(u -> u.getRole() == com.datagami.rentaxis.domain.entity.enums.UserRole.RENTER)
                        .orElse(false);
    }

    /** The tenant's ticketOtpRequired setting; on unless switched off. */
    private boolean otpRequired(MaintenanceTicket ticket) {
        UUID tenantId = ticket.getTenantId() != null ? ticket.getTenantId() : TenantContextHolder.getTenantId();
        if (tenantId == null) return true;
        return landlordOrgRepository.findById(tenantId)
                .map(org -> org.getTicketOtpRequired() != null ? org.getTicketOtpRequired() : true)
                .orElse(true);
    }

    private void issueClosureOtp(MaintenanceTicket ticket) {
        ticket.setClosureOtp(String.format("%06d", SECURE_RANDOM.nextInt(1_000_000)));
        ticket.setClosureOtpFailedAttempts(0);
    }

    private void notifyOtpHolder(MaintenanceTicket ticket, String type, String title, String message) {
        UUID holder = otpHolder(ticket);
        if (holder == null) return;
        try {
            notificationService.notify(ticket.getTenantId(), holder,
                    type, title, message, "TICKET", ticket.getId());
        } catch (Exception e) {
            log.warn("Failed to send closure OTP notification for ticket {}", ticket.getId(), e);
        }
    }

    /**
     * Issues a fresh closure OTP for a resolved ticket and sends it to the holder,
     * for when the renter lost it or the old one was locked after
     * {@link #MAX_OTP_ATTEMPTS} wrong tries. Staff trigger it; they still never
     * see the code.
     */
    @Transactional
    public MaintenanceTicketDTO reissueClosureOtp(UUID ticketId, UUID performedBy) {
        MaintenanceTicket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));
        requirePropertyManagerAssigned(ticket);
        if (ticket.getStatus() != TicketStatus.RESOLVED) {
            throw new BusinessRuleViolationException("A closure OTP can only be issued for a resolved ticket");
        }
        if (otpClosureLocked(ticket)) {
            throw new BusinessRuleViolationException(
                    "OTP closure is locked for this ticket after too many wrong OTPs. A tenant admin can close it"
                            + " through the status route (PUT /tickets/{id}/status).");
        }
        if (!hasRenterToConfirm(ticket)) {
            throw new BusinessRuleViolationException(
                    "No renter can receive a closure OTP for this ticket. Close it through the status route"
                            + " (PUT /tickets/{id}/status).");
        }
        long recent = historyRepository.countByTicketIdAndActionAndCreatedAtAfter(
                ticket.getId(), "OTP_REISSUED", Instant.now().minus(24, ChronoUnit.HOURS));
        if (recent >= MAX_OTP_REISSUES_PER_DAY) {
            throw new BusinessRuleViolationException(
                    "A closure OTP can be re-issued at most " + MAX_OTP_REISSUES_PER_DAY
                            + " times in 24 hours. Try again later.");
        }
        issueClosureOtp(ticket);
        MaintenanceTicket saved = ticketRepository.save(ticket);
        recordHistory(saved, "OTP_REISSUED", null, null, null, null, performedBy,
                "A new closure OTP was sent to the renter");
        notifyOtpHolder(saved, "TICKET_OTP_REISSUED", "New closure OTP",
                "A new OTP was issued for your ticket '" + saved.getTitle() + "'. Share it to close the ticket.");
        return mapToDTO(saved, performedBy);
    }

    /**
     * A property manager acts only on tickets of the properties assigned to them,
     * as their list does; anything else is "not found". Other roles pass.
     */
    private void requirePropertyManagerAssigned(MaintenanceTicket ticket) {
        if (!callerHasRole("PROPERTY_MANAGER")) return;
        UUID caller;
        try {
            caller = UUID.fromString(org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new NotFoundException("Ticket not found");
        }
        UUID propertyId = ticket.getProperty() != null ? ticket.getProperty().getId() : null;
        boolean assigned = propertyAssignmentRepository.findByUserId(caller).stream()
                .anyMatch(a -> a.getPropertyId().equals(propertyId));
        if (!assigned) throw new NotFoundException("Ticket not found");
    }

    /**
     * {@code noRollbackFor}: a wrong OTP is answered with a 400 and must still
     * count, or the attempt limit would roll back with every failed try.
     */
    @Transactional(noRollbackFor = BusinessRuleViolationException.class)
    public MaintenanceTicketDTO closeWithOtp(UUID ticketId, String otp, UUID performedBy) {
        // Locked for the read-check-increment below, so parallel guesses cannot
        // each read the same count and exceed the limit.
        MaintenanceTicket ticket = ticketRepository.findByIdForUpdate(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));
        requireRenterOwns(ticket);

        if (ticket.getStatus() != TicketStatus.RESOLVED) {
            throw new BusinessRuleViolationException("Ticket must be in RESOLVED status to close with OTP");
        }

        if (otpRequired(ticket)) {
            if (otpClosureLocked(ticket)) {
                throw new BusinessRuleViolationException(
                        "OTP closure is locked for this ticket after too many wrong OTPs. A tenant admin can close it"
                                + " through the status route (PUT /tickets/{id}/status).");
            }
            if (ticket.getClosureOtp() == null) {
                throw new BusinessRuleViolationException(
                        "This ticket's closure OTP is no longer valid. Issue a new one to the renter.");
            }
            if (!ticket.getClosureOtp().equals(otp)) {
                int attempts = ticket.getClosureOtpFailedAttempts() + 1;
                ticket.setClosureOtpFailedAttempts(attempts);
                int total = ticket.getClosureOtpTotalFailedAttempts() + 1;
                ticket.setClosureOtpTotalFailedAttempts(total);
                if (total >= MAX_TOTAL_OTP_FAILURES) {
                    ticket.setClosureOtp(null);
                    ticketRepository.save(ticket);
                    recordHistory(ticket, "OTP_LOCKED", null, null, null, null, performedBy,
                            "OTP closure locked after " + MAX_TOTAL_OTP_FAILURES + " wrong OTPs");
                    throw new BusinessRuleViolationException(
                            "Too many wrong OTPs. OTP closure is now locked for this ticket; a tenant admin can"
                                    + " close it through the status route.");
                }
                if (attempts >= MAX_OTP_ATTEMPTS) {
                    // Discard the code: six digits cannot survive unlimited guesses.
                    ticket.setClosureOtp(null);
                    ticketRepository.save(ticket);
                    throw new BusinessRuleViolationException(
                            "Too many wrong OTPs. Issue a new one to the renter to close this ticket.");
                }
                ticketRepository.save(ticket);
                throw new BusinessRuleViolationException("Invalid OTP");
            }
        }

        ticket.setStatus(TicketStatus.CLOSED);
        ticket.setClosedAt(Instant.now());
        ticket.setClosureOtp(null);

        MaintenanceTicket saved = ticketRepository.save(ticket);
        recordHistory(saved, "STATUS_CHANGED", "RESOLVED", "CLOSED",
                null, null, performedBy != null ? performedBy : ticket.getAssignedTo(),
                "Ticket closed with OTP verification");
        log.info("Ticket {} closed with OTP verification", ticketId);
        return mapToDTO(saved);
    }

    @Transactional
    public MaintenanceTicketDTO setEstimate(UUID ticketId, Integer hours) {
        MaintenanceTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));

        ticket.setEstimatedResolutionHours(hours);
        MaintenanceTicket saved = ticketRepository.save(ticket);
        return mapToDTO(saved);
    }

    @Transactional
    public MaintenanceTicketDTO rateTicket(UUID ticketId, int rating, String comment) {
        MaintenanceTicket ticket = visibleTicket(ticketId);

        if (ticket.getStatus() != TicketStatus.CLOSED && ticket.getStatus() != TicketStatus.RESOLVED) {
            throw new BusinessRuleViolationException("Can only rate resolved or closed tickets");
        }

        if (rating < 1 || rating > 5) {
            throw new BusinessRuleViolationException("Rating must be between 1 and 5");
        }

        ticket.setSatisfactionRating(rating);
        ticket.setSatisfactionComment(comment);

        MaintenanceTicket saved = ticketRepository.save(ticket);
        log.info("Ticket {} rated: {}/5", ticketId, rating);
        return mapToDTO(saved);
    }

    // ---- Replies ----

    @Transactional
    public TicketReplyDTO addReply(UUID ticketId, UUID userId, String message) {
        MaintenanceTicket ticket = visibleTicket(ticketId);

        // Resolve user name from ID (filter-bypassing: superadmins have
        // tenant_id = NULL and are invisible to the tenant-filtered findById)
        String resolvedName = userRepository.findDisplayNameById(userId).orElse("Unknown");

        TicketReply reply = new TicketReply();
        reply.setTicket(ticket);
        reply.setUserId(userId);
        reply.setUserName(resolvedName);
        reply.setMessage(message);

        TicketReply saved = replyRepository.save(reply);

        // Notify the other party about the new reply
        try {
            UUID notifyUser = userId.equals(ticket.getReportedBy()) ? ticket.getAssignedTo() : ticket.getReportedBy();
            if (notifyUser != null) {
                notificationService.notify(ticket.getTenantId(), notifyUser,
                        "TICKET_REPLY", "New Reply on Ticket",
                        "New reply on: " + ticket.getTitle(),
                        "TICKET", ticket.getId());
            }
        } catch (Exception e) {
            log.warn("Failed to send ticket reply notification for ticket {}", ticketId, e);
        }

        return mapReplyToDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<TicketReplyDTO> getReplies(UUID ticketId) {
        requireRenterOwns(ticketId);
        return replyRepository.findByTicketIdOrderByCreatedAtAsc(ticketId).stream()
                .map(this::mapReplyToDTO)
                .collect(Collectors.toList());
    }

    // ---- Attachments ----

    @Transactional(readOnly = true)
    public java.util.List<TicketAttachmentDTO> getAttachments(UUID ticketId) {
        requireRenterOwns(ticketId);
        return attachmentRepository.findByTicketId(ticketId).stream()
                .map(this::mapAttachmentToDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public void deleteAttachment(UUID attachmentId) {
        TicketAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found"));
        requireRenterOwnsAttachment(attachment);
        attachmentRepository.delete(attachment);
    }

    @Transactional(readOnly = true)
    public byte[] downloadAttachment(UUID attachmentId) {
        TicketAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found"));
        requireRenterOwnsAttachment(attachment);
        String url = attachment.getFileUrl();
        if (url.startsWith("https://") && url.contains(".blob.core.windows.net")) {
            String marker = ".blob.core.windows.net/";
            int idx = url.indexOf(marker);
            String path = url.substring(idx + marker.length());
            int slash = path.indexOf('/');
            String container = path.substring(0, slash);
            String blobPath = path.substring(slash + 1);
            com.azure.storage.blob.BlobClient blobClient = new com.azure.storage.blob.BlobServiceClientBuilder()
                    .connectionString(azureConnectionString)
                    .buildClient()
                    .getBlobContainerClient(container)
                    .getBlobClient(blobPath);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            blobClient.downloadStream(baos);
            return baos.toByteArray();
        }
        try {
            return java.nio.file.Files.readAllBytes(java.nio.file.Path.of(localStoragePath).resolve(url.replace("/api/v1/assets/serve/", "")));
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to read attachment", e);
        }
    }

    @Transactional
    public TicketAttachmentDTO uploadAttachment(UUID ticketId, MultipartFile file) throws IOException {
        MaintenanceTicket ticket = visibleTicket(ticketId);

        byte[] bytes = file.getBytes();
        String ext = getExtension(file.getOriginalFilename());
        String fileName = UUID.randomUUID().toString().substring(0, 8) + ext;

        String fileUrl;
        if (azureConnectionString != null && !azureConnectionString.isBlank()) {
            fileUrl = uploadToAzure(fileName, bytes, file.getContentType());
        } else {
            fileUrl = saveToLocal(fileName, bytes);
        }

        TicketAttachment attachment = new TicketAttachment();
        attachment.setTicket(ticket);
        attachment.setFileUrl(fileUrl);
        attachment.setFileType(file.getContentType());
        attachment.setFileSize(file.getSize());
        attachment.setUploadedAt(Instant.now());

        return mapAttachmentToDTO(attachmentRepository.save(attachment));
    }

    /** An attachment on a ticket the renter does not own is "not found" to them. */
    private void requireRenterOwnsAttachment(TicketAttachment attachment) {
        if (!callerIsRenter()) return;
        try {
            requireRenterOwns(attachment.getTicket());
        } catch (NotFoundException e) {
            throw new NotFoundException("Attachment not found");
        }
    }

    /**
     * Attachments were previously serialized as raw JPA entities, dragging the
     * whole lazy ticket graph into the payload; the DTO pins the contract to
     * the fields clients actually read.
     */
    private TicketAttachmentDTO mapAttachmentToDTO(TicketAttachment attachment) {
        TicketAttachmentDTO dto = new TicketAttachmentDTO();
        dto.setId(attachment.getId());
        dto.setTicketId(attachment.getTicket() != null ? attachment.getTicket().getId() : null);
        dto.setFileUrl(attachment.getFileUrl());
        dto.setFileType(attachment.getFileType());
        dto.setFileSize(attachment.getFileSize());
        dto.setUploadedAt(attachment.getUploadedAt());
        return dto;
    }

    // ---- Reports ----

    @Transactional(readOnly = true)
    public TicketReportDTO getReport(UUID propertyId, java.time.LocalDate startDate, java.time.LocalDate endDate) {
        List<MaintenanceTicket> allTickets = ticketRepository.findAll();

        // Apply filters
        if (propertyId != null) {
            allTickets = allTickets.stream()
                    .filter(t -> t.getProperty() != null && propertyId.equals(t.getProperty().getId()))
                    .collect(Collectors.toList());
        }
        if (startDate != null) {
            Instant startInstant = startDate.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
            allTickets = allTickets.stream()
                    .filter(t -> t.getCreatedAt() != null && !t.getCreatedAt().isBefore(startInstant))
                    .collect(Collectors.toList());
        }
        if (endDate != null) {
            Instant endInstant = endDate.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant();
            allTickets = allTickets.stream()
                    .filter(t -> t.getCreatedAt() != null && t.getCreatedAt().isBefore(endInstant))
                    .collect(Collectors.toList());
        }

        TicketReportDTO report = new TicketReportDTO();
        report.setTotalTickets(allTickets.size());
        report.setOpenCount(allTickets.stream()
                .filter(t -> t.getStatus() == TicketStatus.OPEN || t.getStatus() == TicketStatus.ASSIGNED
                        || t.getStatus() == TicketStatus.IN_PROGRESS || t.getStatus() == TicketStatus.REOPENED)
                .count());
        report.setResolvedCount(allTickets.stream()
                .filter(t -> t.getStatus() == TicketStatus.RESOLVED)
                .count());
        report.setClosedCount(allTickets.stream()
                .filter(t -> t.getStatus() == TicketStatus.CLOSED)
                .count());

        // Average resolution hours (for resolved/closed tickets that have resolvedAt)
        OptionalDouble avgHours = allTickets.stream()
                .filter(t -> t.getResolvedAt() != null)
                .mapToDouble(t -> ChronoUnit.HOURS.between(t.getCreatedAt(), t.getResolvedAt()))
                .average();
        report.setAvgResolutionHours(avgHours.orElse(0.0));

        // Average satisfaction
        OptionalDouble avgSat = allTickets.stream()
                .filter(t -> t.getSatisfactionRating() != null)
                .mapToInt(MaintenanceTicket::getSatisfactionRating)
                .average();
        report.setAvgSatisfaction(avgSat.orElse(0.0));

        // Overdue: tickets with estimated_resolution_hours set, still open, and past the estimate
        long overdueCount = allTickets.stream()
                .filter(t -> t.getEstimatedResolutionHours() != null)
                .filter(t -> t.getStatus() != TicketStatus.CLOSED && t.getStatus() != TicketStatus.RESOLVED)
                .filter(t -> {
                    Instant deadline = t.getCreatedAt().plus(t.getEstimatedResolutionHours(), ChronoUnit.HOURS);
                    return Instant.now().isAfter(deadline);
                })
                .count();
        report.setOverdueCount(overdueCount);

        // By category
        Map<String, Long> byCategory = allTickets.stream()
                .filter(t -> t.getCategory() != null)
                .collect(Collectors.groupingBy(t -> t.getCategory().name(), Collectors.counting()));
        report.setTicketsByCategory(byCategory);

        // By priority
        Map<String, Long> byPriority = allTickets.stream()
                .filter(t -> t.getPriority() != null)
                .collect(Collectors.groupingBy(t -> t.getPriority().name(), Collectors.counting()));
        report.setTicketsByPriority(byPriority);

        return report;
    }

    // ---- Status transition validation ----

    private void validateStatusTransition(TicketStatus current, TicketStatus target) {
        Set<TicketStatus> allowed = switch (current) {
            case OPEN -> Set.of(TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, TicketStatus.CLOSED);
            case ASSIGNED -> Set.of(TicketStatus.IN_PROGRESS, TicketStatus.OPEN, TicketStatus.CLOSED);
            case IN_PROGRESS -> Set.of(TicketStatus.RESOLVED, TicketStatus.ASSIGNED, TicketStatus.CLOSED);
            case RESOLVED -> Set.of(TicketStatus.CLOSED, TicketStatus.REOPENED);
            case CLOSED -> Set.of(TicketStatus.REOPENED);
            case REOPENED -> Set.of(TicketStatus.ASSIGNED, TicketStatus.IN_PROGRESS, TicketStatus.CLOSED);
        };

        if (!allowed.contains(target)) {
            throw new BusinessRuleViolationException(
                    String.format("Cannot transition from %s to %s", current, target));
        }
    }

    // ---- Mapping helpers ----

    private MaintenanceTicketDTO mapToDTO(MaintenanceTicket ticket) {
        return mapToDTO(ticket, null);
    }

    /**
     * Maps a ticket to its DTO. The closure OTP is a secret shared with its
     * holder only ({@link #otpHolder}: the renter hands it over to confirm
     * closure); exposing it to managers would let them close tickets without
     * renter confirmation, so it is redacted for everyone else.
     */
    private MaintenanceTicketDTO mapToDTO(MaintenanceTicket ticket, UUID requesterId) {
        MaintenanceTicketDTO dto = new MaintenanceTicketDTO();
        dto.setId(ticket.getId());
        dto.setReference(ticket.getReference());
        dto.setTenantId(ticket.getTenantId());
        dto.setPropertyId(ticket.getProperty().getId());
        dto.setUnitId(ticket.getUnit() != null ? ticket.getUnit().getId() : null);
        dto.setLeaseId(ticket.getLease() != null ? ticket.getLease().getId() : null);
        dto.setReportedBy(ticket.getReportedBy());
        dto.setAssignedTo(ticket.getAssignedTo());
        dto.setTitle(ticket.getTitle());
        dto.setDescription(ticket.getDescription());
        dto.setCategory(ticket.getCategory() != null ? ticket.getCategory().name() : null);
        dto.setPriority(ticket.getPriority() != null ? ticket.getPriority().name() : null);
        dto.setStatus(ticket.getStatus().name());
        dto.setEstimatedResolutionHours(ticket.getEstimatedResolutionHours());
        dto.setResolvedAt(ticket.getResolvedAt());
        dto.setClosedAt(ticket.getClosedAt());
        boolean isOtpHolder = requesterId != null && ticket.getClosureOtp() != null
                && requesterId.equals(otpHolder(ticket));
        dto.setClosureOtp(isOtpHolder ? ticket.getClosureOtp() : null);
        dto.setSatisfactionRating(ticket.getSatisfactionRating());
        dto.setSatisfactionComment(ticket.getSatisfactionComment());
        dto.setOnBehalfOf(ticket.getOnBehalfOf());
        dto.setOnBehalfOfRenterId(ticket.getOnBehalfOfRenterId());
        dto.setReportedDate(ticket.getReportedDate());
        dto.setCreatedAt(ticket.getCreatedAt());
        dto.setUpdatedAt(ticket.getUpdatedAt());

        // Enriched fields
        try {
            dto.setPropertyName(ticket.getProperty().getNameEn());
        } catch (Exception e) {
            // Lazy loading issue — skip
        }

        try {
            if (ticket.getUnit() != null) {
                dto.setUnitNumber(ticket.getUnit().getUnitNumber());
            }
        } catch (Exception e) {
            // Lazy loading issue — skip
        }

        // Reporter / assignee names — filter-bypassing lookup so superadmin
        // actors (tenant_id = NULL) don't render as blank/Unknown.
        userRepository.findDisplayNameById(ticket.getReportedBy())
                .ifPresent(dto::setReporterName);

        if (ticket.getAssignedTo() != null) {
            userRepository.findDisplayNameById(ticket.getAssignedTo())
                    .ifPresent(dto::setAssigneeName);
        }

        dto.setReplyCount(replyRepository.countByTicketId(ticket.getId()));
        dto.setAttachmentCount(attachmentRepository.countByTicketId(ticket.getId()));

        return dto;
    }

    private TicketReplyDTO mapReplyToDTO(TicketReply reply) {
        TicketReplyDTO dto = new TicketReplyDTO();
        dto.setId(reply.getId());
        dto.setTicketId(reply.getTicket().getId());
        dto.setUserId(reply.getUserId());
        dto.setUserName(reply.getUserName());
        dto.setMessage(reply.getMessage());
        dto.setCreatedAt(reply.getCreatedAt());
        return dto;
    }

    // ---- File storage helpers (same pattern as LeaseAttachmentService) ----

    private String uploadToAzure(String fileName, byte[] bytes, String contentType) {
        UUID tenantId = TenantContextHolder.getTenantId();
        String containerName = tenantId != null ? containerPrefix + tenantId : "shared";

        BlobServiceClient blobServiceClient = new BlobServiceClientBuilder()
                .connectionString(azureConnectionString)
                .buildClient();

        BlobContainerClient containerClient = blobServiceClient.getBlobContainerClient(containerName);
        if (!containerClient.exists()) {
            containerClient.create();
        }

        String blobPath = "ticket-attachments/" + fileName;
        BlobClient blobClient = containerClient.getBlobClient(blobPath);
        blobClient.upload(new ByteArrayInputStream(bytes), bytes.length, true);

        return blobServiceClient.getAccountUrl() + "/" + containerName + "/" + blobPath;
    }

    private String saveToLocal(String fileName, byte[] bytes) throws IOException {
        Path dirPath = Path.of(localStoragePath, "ticket-attachments");
        Files.createDirectories(dirPath);
        Path filePath = dirPath.resolve(fileName);
        Files.write(filePath, bytes);
        return "/api/v1/assets/serve/ticket-attachments/" + fileName;
    }

    private String getExtension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot >= 0 ? filename.substring(dot) : "";
    }

    // ---- History ----

    private void recordHistory(MaintenanceTicket ticket, String action, String fromStatus, String toStatus,
                               UUID assignedFrom, UUID assignedTo, UUID performedBy, String notes) {
        TicketHistory h = new TicketHistory();
        h.setTicket(ticket);
        h.setAction(action);
        h.setFromStatus(fromStatus);
        h.setToStatus(toStatus);
        h.setAssignedFrom(assignedFrom);
        h.setAssignedTo(assignedTo);
        h.setPerformedBy(performedBy);
        // findDisplayNameById, not the tenant-filtered findById: a SUPER_ADMIN
        // acting inside a pivoted tenant has tenant_id = NULL, so the filtered
        // lookup can't see them and the name is lost permanently — it's stored
        // on this history row at write time, so a blank here shows "System" in
        // the UI forever.
        userRepository.findDisplayNameById(performedBy).ifPresent(h::setPerformedByName);
        h.setNotes(notes);
        h.setCreatedAt(java.time.Instant.now());
        historyRepository.save(h);
    }

    @Transactional(readOnly = true)
    public java.util.List<TicketHistoryDTO> getHistory(UUID ticketId) {
        requireRenterOwns(ticketId);
        return historyRepository.findByTicketIdOrderByCreatedAtAsc(ticketId).stream()
                .map(this::mapHistoryToDTO)
                .collect(java.util.stream.Collectors.toList());
    }

    private TicketHistoryDTO mapHistoryToDTO(TicketHistory h) {
        TicketHistoryDTO dto = new TicketHistoryDTO();
        dto.setId(h.getId());
        dto.setTicketId(h.getTicket().getId());
        dto.setAction(h.getAction());
        dto.setFromStatus(h.getFromStatus());
        dto.setToStatus(h.getToStatus());
        dto.setAssignedFrom(h.getAssignedFrom());
        dto.setAssignedTo(h.getAssignedTo());
        dto.setPerformedBy(h.getPerformedBy());
        dto.setPerformedByName(h.getPerformedByName());
        dto.setNotes(h.getNotes());
        dto.setCreatedAt(h.getCreatedAt());
        return dto;
    }
}

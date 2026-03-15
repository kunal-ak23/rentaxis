package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.TicketCategory;
import com.datagami.rentaxis.domain.entity.enums.TicketPriority;
import com.datagami.rentaxis.domain.entity.enums.TicketStatus;
import com.datagami.rentaxis.domain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${AZURE_STORAGE_CONNECTION_STRING:}")
    private String azureConnectionString;

    @Value("${AZURE_STORAGE_CONTAINER_PREFIX:tenant-}")
    private String containerPrefix;

    @Value("${rentaxis.assets.storage-path:./data/assets}")
    private String localStoragePath;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    // ---- Ticket CRUD ----

    @Transactional
    public MaintenanceTicketDTO createTicket(CreateTicketDTO dto, UUID reportedBy) {
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

        MaintenanceTicket saved = ticketRepository.save(ticket);
        log.info("Created maintenance ticket {} for property {}", saved.getId(), property.getId());
        return mapToDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceTicketDTO> getTickets(UUID userId, String role) {
        List<MaintenanceTicket> tickets;

        if ("RENTER".equals(role) || "TENANT_USER".equals(role)) {
            // Renters/tenant users see only tickets they reported
            tickets = ticketRepository.findByReportedBy(userId);
        } else if ("PROPERTY_MANAGER".equals(role)) {
            // Property managers see tickets for their assigned properties
            List<UserPropertyAssignment> assignments = propertyAssignmentRepository.findByUserId(userId);
            List<UUID> propertyIds = assignments.stream()
                    .map(UserPropertyAssignment::getPropertyId)
                    .collect(Collectors.toList());
            if (propertyIds.isEmpty()) {
                tickets = List.of();
            } else {
                tickets = ticketRepository.findByPropertyIdIn(propertyIds);
            }
        } else {
            // TENANT_ADMIN and SUPER_ADMIN see all tickets for the tenant
            tickets = ticketRepository.findAll();
        }

        return tickets.stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public MaintenanceTicketDTO getTicket(UUID ticketId) {
        MaintenanceTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));
        return mapToDTO(ticket);
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
        String assigneeName = userRepository.findById(assignTo).map(User::getName).orElse("Unknown");
        String action = previousAssignee == null ? "ASSIGNED" : "REASSIGNED";
        String notes = previousAssignee == null
                ? "Ticket assigned to " + assigneeName
                : "Ticket reassigned to " + assigneeName;

        MaintenanceTicket saved = ticketRepository.save(ticket);
        recordHistory(saved, action, previousStatus, saved.getStatus().name(),
                previousAssignee, assignTo, performedBy != null ? performedBy : assignTo, notes);
        log.info("Assigned ticket {} to user {}", ticketId, assignTo);
        return mapToDTO(saved);
    }

    @Transactional
    public MaintenanceTicketDTO updateStatus(UUID ticketId, String newStatus, UUID performedBy) {
        MaintenanceTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));

        String fromStatus = ticket.getStatus().name();
        TicketStatus targetStatus = TicketStatus.valueOf(newStatus);
        validateStatusTransition(ticket.getStatus(), targetStatus);

        ticket.setStatus(targetStatus);

        if (targetStatus == TicketStatus.RESOLVED) {
            ticket.setResolvedAt(Instant.now());
            String otp = String.format("%06d", SECURE_RANDOM.nextInt(999999));
            ticket.setClosureOtp(otp);
            log.info("Ticket {} resolved. Closure OTP generated.", ticketId);
        }

        if (targetStatus == TicketStatus.IN_PROGRESS && ticket.getAssignedTo() == null) {
            throw new BusinessRuleViolationException("Ticket must be assigned before moving to IN_PROGRESS");
        }

        MaintenanceTicket saved = ticketRepository.save(ticket);
        recordHistory(saved, "STATUS_CHANGED", fromStatus, targetStatus.name(),
                null, null, performedBy != null ? performedBy : ticket.getReportedBy(),
                "Status changed: " + fromStatus + " → " + targetStatus.name());
        return mapToDTO(saved);
    }

    @Transactional
    public MaintenanceTicketDTO closeWithOtp(UUID ticketId, String otp, UUID performedBy) {
        MaintenanceTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));

        if (ticket.getStatus() != TicketStatus.RESOLVED) {
            throw new BusinessRuleViolationException("Ticket must be in RESOLVED status to close with OTP");
        }

        // Check tenant OTP setting
        UUID tenantId = TenantContextHolder.getTenantId();
        boolean otpRequired = true;
        if (tenantId != null) {
            otpRequired = landlordOrgRepository.findById(tenantId)
                    .map(org -> org.getTicketOtpRequired() != null ? org.getTicketOtpRequired() : true)
                    .orElse(true);
        }

        if (otpRequired) {
            if (ticket.getClosureOtp() == null || !ticket.getClosureOtp().equals(otp)) {
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
        MaintenanceTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));

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
        MaintenanceTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));

        // Resolve user name from ID
        String resolvedName = userRepository.findById(userId).map(User::getName).orElse("Unknown");

        TicketReply reply = new TicketReply();
        reply.setTicket(ticket);
        reply.setUserId(userId);
        reply.setUserName(resolvedName);
        reply.setMessage(message);

        TicketReply saved = replyRepository.save(reply);
        return mapReplyToDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<TicketReplyDTO> getReplies(UUID ticketId) {
        return replyRepository.findByTicketIdOrderByCreatedAtAsc(ticketId).stream()
                .map(this::mapReplyToDTO)
                .collect(Collectors.toList());
    }

    // ---- Attachments ----

    @Transactional(readOnly = true)
    public java.util.List<TicketAttachment> getAttachments(UUID ticketId) {
        return attachmentRepository.findByTicketId(ticketId);
    }

    @Transactional
    public void deleteAttachment(UUID attachmentId) {
        TicketAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found"));
        attachmentRepository.delete(attachment);
    }

    @Transactional(readOnly = true)
    public byte[] downloadAttachment(UUID attachmentId) {
        TicketAttachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found"));
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
    public TicketAttachment uploadAttachment(UUID ticketId, MultipartFile file) throws IOException {
        MaintenanceTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));

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

        return attachmentRepository.save(attachment);
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
        MaintenanceTicketDTO dto = new MaintenanceTicketDTO();
        dto.setId(ticket.getId());
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
        dto.setClosureOtp(ticket.getClosureOtp());
        dto.setSatisfactionRating(ticket.getSatisfactionRating());
        dto.setSatisfactionComment(ticket.getSatisfactionComment());
        dto.setOnBehalfOf(ticket.getOnBehalfOf());
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

        // Reporter name
        userRepository.findById(ticket.getReportedBy())
                .ifPresent(user -> dto.setReporterName(user.getName()));

        // Assignee name
        if (ticket.getAssignedTo() != null) {
            userRepository.findById(ticket.getAssignedTo())
                    .ifPresent(user -> dto.setAssigneeName(user.getName()));
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
        // Resolve performer name
        userRepository.findById(performedBy).ifPresent(u -> h.setPerformedByName(u.getName()));
        h.setNotes(notes);
        h.setCreatedAt(java.time.Instant.now());
        historyRepository.save(h);
    }

    @Transactional(readOnly = true)
    public java.util.List<TicketHistoryDTO> getHistory(UUID ticketId) {
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

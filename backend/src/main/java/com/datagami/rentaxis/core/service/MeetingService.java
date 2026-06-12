package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateMeetingDTO;
import com.datagami.rentaxis.api.dto.MeetingDTO;
import com.datagami.rentaxis.api.dto.MeetingDetailDTO;
import com.datagami.rentaxis.api.dto.SlotDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.SlotConflictException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Meeting;
import com.datagami.rentaxis.domain.entity.MeetingDetail;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.MeetingPurpose;
import com.datagami.rentaxis.domain.entity.enums.MeetingStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class MeetingService {

    private static final ZoneId UAE_ZONE = ZoneId.of("Asia/Dubai");
    private static final int SLOT_DURATION_MINUTES = 30;
    private static final int DAY_START_HOUR = 9;
    private static final int DAY_END_HOUR = 21;
    private static final int MAX_SCAN_DAYS = 14;

    private static final List<MeetingStatus> INACTIVE_STATUSES =
            List.of(MeetingStatus.CANCELLED, MeetingStatus.NO_SHOW);

    private final MeetingRepository meetingRepository;
    private final MeetingDetailRepository meetingDetailRepository;
    private final UserRepository userRepository;
    private final LeaseRepository leaseRepository;
    private final PropertyRepository propertyRepository;
    private final UnitRepository unitRepository;
    private final NotificationService notificationService;

    // ---- Create ----

    @Transactional
    public MeetingDTO createMeeting(CreateMeetingDTO dto, UUID requesterUserId) {
        // Validate slot is in the future
        if (dto.getSlotStart().isBefore(Instant.now())) {
            throw new BusinessRuleViolationException("Cannot book a slot in the past");
        }

        // Validate slot is on a 30-minute boundary within 9AM-9PM UAE time
        ZonedDateTime slotZoned = dto.getSlotStart().atZone(ZoneId.of("Asia/Dubai"));
        int hour = slotZoned.getHour();
        int minute = slotZoned.getMinute();
        if (hour < DAY_START_HOUR || hour >= DAY_END_HOUR || (minute != 0 && minute != 30)) {
            throw new BusinessRuleViolationException("Slot must be on a 30-minute boundary between 9:00 AM and 9:00 PM UAE time");
        }

        // Validate host exists
        userRepository.findById(dto.getHostUserId())
                .orElseThrow(() -> new NotFoundException("Host user not found"));

        // Check for conflicts
        List<Meeting> conflicts = meetingRepository.findConflicts(
                dto.getHostUserId(), TenantContextHolder.getTenantId(), dto.getSlotStart(), INACTIVE_STATUSES);
        if (!conflicts.isEmpty()) {
            Instant nextSlot = findNextAvailableSlot(dto.getHostUserId(), dto.getSlotStart());
            throw new SlotConflictException(
                    "Slot is already taken for this host. Next available: " + nextSlot, nextSlot);
        }

        Meeting meeting = new Meeting();
        meeting.setType(dto.getType());
        meeting.setPurpose(dto.getPurpose());
        meeting.setTitle(dto.getTitle());
        meeting.setNotes(dto.getNotes());
        meeting.setSlotStart(dto.getSlotStart());
        meeting.setSlotEnd(dto.getSlotStart().plus(SLOT_DURATION_MINUTES, ChronoUnit.MINUTES));
        meeting.setHostUserId(dto.getHostUserId());
        meeting.setRequesterUserId(requesterUserId);
        meeting.setStatus(MeetingStatus.REQUESTED);

        if (dto.getLeaseId() != null) {
            meeting.setLease(leaseRepository.findById(dto.getLeaseId())
                    .orElseThrow(() -> new NotFoundException("Lease not found")));
        }
        if (dto.getPropertyId() != null) {
            meeting.setProperty(propertyRepository.findById(dto.getPropertyId())
                    .orElseThrow(() -> new NotFoundException("Property not found")));
        }
        if (dto.getUnitId() != null) {
            meeting.setUnit(unitRepository.findById(dto.getUnitId())
                    .orElseThrow(() -> new NotFoundException("Unit not found")));
        }

        Meeting saved = meetingRepository.save(meeting);

        // Save detail for specific purposes
        if (dto.getPurpose() == MeetingPurpose.CHEQUE_REPLACEMENT
                || dto.getPurpose() == MeetingPurpose.LEASE_RENEWAL) {
            MeetingDetail detail = new MeetingDetail();
            detail.setMeeting(saved);
            detail.setDetailType(dto.getPurpose().name());
            detail.setPaymentScheduleIds(dto.getPaymentScheduleIds());
            detail.setProposedStartDate(dto.getProposedStartDate());
            detail.setProposedEndDate(dto.getProposedEndDate());
            detail.setProposedRentAmount(dto.getProposedRentAmount());
            detail.setNotes(dto.getDetailNotes());
            meetingDetailRepository.save(detail);
        }

        // Notify host PM
        try {
            notificationService.notify(
                    saved.getTenantId(), saved.getHostUserId(),
                    "MEETING_REQUESTED", "New Meeting Requested",
                    "A meeting has been requested for " + formatSlotForDisplay(saved.getSlotStart()),
                    "MEETING", saved.getId());
        } catch (Exception e) {
            log.warn("Failed to send MEETING_REQUESTED notification for meeting {}", saved.getId(), e);
        }

        // Notify requester (confirmation)
        if (!saved.getRequesterUserId().equals(saved.getHostUserId())) {
            try {
                notificationService.notify(
                        saved.getTenantId(), saved.getRequesterUserId(),
                        "MEETING_REQUESTED", "Meeting Request Submitted",
                        "Your meeting request for " + formatSlotForDisplay(saved.getSlotStart()) + " has been submitted and is awaiting approval.",
                        "MEETING", saved.getId());
            } catch (Exception e) {
                log.warn("Failed to send MEETING_REQUESTED confirmation for meeting {} to requester", saved.getId(), e);
            }
        }

        log.info("Created meeting {} for host {}", saved.getId(), dto.getHostUserId());
        return mapToDTO(saved);
    }

    // ---- Approve ----

    @Transactional
    public MeetingDTO approveMeeting(UUID meetingId, UUID approverUserId) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new NotFoundException("Meeting not found"));

        if (meeting.getStatus() != MeetingStatus.REQUESTED) {
            throw new BusinessRuleViolationException(
                    "Cannot approve meeting in status: " + meeting.getStatus());
        }

        meeting.setStatus(MeetingStatus.APPROVED);
        Meeting saved = meetingRepository.save(meeting);

        // Notify requester
        try {
            notificationService.notify(
                    saved.getTenantId(), saved.getRequesterUserId(),
                    "MEETING_APPROVED", "Meeting Approved",
                    "Your meeting request has been approved for " + formatSlotForDisplay(saved.getSlotStart()),
                    "MEETING", saved.getId());
        } catch (Exception e) {
            log.warn("Failed to send MEETING_APPROVED notification for meeting {}", meetingId, e);
        }

        log.info("Approved meeting {} by user {}", meetingId, approverUserId);
        return mapToDTO(saved);
    }

    // ---- Cancel ----

    @Transactional
    public MeetingDTO cancelMeeting(UUID meetingId, UUID cancelledByUserId, String role) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new NotFoundException("Meeting not found"));

        boolean isManager = "PROPERTY_MANAGER".equals(role) || "TENANT_ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
        boolean isInvolved = meeting.getRequesterUserId().equals(cancelledByUserId) || meeting.getHostUserId().equals(cancelledByUserId);
        if (!isManager && !isInvolved) {
            throw new NotFoundException("Meeting not found");
        }

        if (meeting.getStatus() != MeetingStatus.REQUESTED
                && meeting.getStatus() != MeetingStatus.APPROVED) {
            throw new BusinessRuleViolationException(
                    "Cannot cancel meeting in status: " + meeting.getStatus());
        }

        meeting.setStatus(MeetingStatus.CANCELLED);
        Meeting saved = meetingRepository.save(meeting);

        // Notify both parties
        try {
            String cancellerName = userRepository.findDisplayNameById(cancelledByUserId).orElse("Someone");
            String msg = cancellerName + " cancelled the meeting: " + (saved.getTitle() != null ? saved.getTitle() : saved.getPurpose().name());
            notificationService.notify(saved.getTenantId(), saved.getHostUserId(),
                    "MEETING_CANCELLED", "Meeting Cancelled", msg, "MEETING", saved.getId());
            notificationService.notify(saved.getTenantId(), saved.getRequesterUserId(),
                    "MEETING_CANCELLED", "Meeting Cancelled", msg, "MEETING", saved.getId());
        } catch (Exception e) {
            log.warn("Failed to send MEETING_CANCELLED notification for meeting {}", meetingId, e);
        }

        log.info("Cancelled meeting {} by user {}", meetingId, cancelledByUserId);
        return mapToDTO(saved);
    }

    // ---- Complete ----

    @Transactional
    public MeetingDTO completeMeeting(UUID meetingId, UUID performedByUserId) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new NotFoundException("Meeting not found"));

        if (meeting.getStatus() != MeetingStatus.APPROVED) {
            throw new BusinessRuleViolationException(
                    "Cannot complete meeting in status: " + meeting.getStatus());
        }

        meeting.setStatus(MeetingStatus.COMPLETED);
        Meeting saved = meetingRepository.save(meeting);

        // Notify requester
        try {
            notificationService.notify(
                    saved.getTenantId(), saved.getRequesterUserId(),
                    "MEETING_COMPLETED", "Meeting Completed",
                    "Your meeting on " + formatSlotForDisplay(saved.getSlotStart()) + " has been marked as completed.",
                    "MEETING", saved.getId());
        } catch (Exception e) {
            log.warn("Failed to send MEETING_COMPLETED notification for meeting {}", meetingId, e);
        }

        log.info("Completed meeting {} by user {}", meetingId, performedByUserId);
        return mapToDTO(saved);
    }

    // ---- No-show ----

    @Transactional
    public MeetingDTO noShowMeeting(UUID meetingId, UUID performedByUserId) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new NotFoundException("Meeting not found"));

        if (meeting.getStatus() != MeetingStatus.APPROVED) {
            throw new BusinessRuleViolationException(
                    "Cannot mark no-show for meeting in status: " + meeting.getStatus());
        }

        meeting.setStatus(MeetingStatus.NO_SHOW);
        Meeting saved = meetingRepository.save(meeting);

        // Notify requester
        try {
            notificationService.notify(
                    saved.getTenantId(), saved.getRequesterUserId(),
                    "MEETING_NO_SHOW", "Meeting Marked No-Show",
                    "Your meeting on " + formatSlotForDisplay(saved.getSlotStart()) + " was marked as no-show.",
                    "MEETING", saved.getId());
        } catch (Exception e) {
            log.warn("Failed to send MEETING_NO_SHOW notification for meeting {}", meetingId, e);
        }

        log.info("Marked no-show for meeting {} by user {}", meetingId, performedByUserId);
        return mapToDTO(saved);
    }

    // ---- Read operations ----

    @Transactional(readOnly = true)
    public MeetingDTO getMeeting(UUID meetingId, UUID userId, String role) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new NotFoundException("Meeting not found"));
        boolean isManager = role.equals("PROPERTY_MANAGER") || role.equals("TENANT_ADMIN") || role.equals("SUPER_ADMIN");
        boolean isInvolved = meeting.getRequesterUserId().equals(userId) || meeting.getHostUserId().equals(userId);
        if (!isManager && !isInvolved) {
            throw new NotFoundException("Meeting not found");
        }
        return mapToDTO(meeting);
    }

    @Transactional(readOnly = true)
    public Page<MeetingDTO> listMeetings(Pageable pageable) {
        return meetingRepository.findByTenantId(TenantContextHolder.getTenantId(), pageable).map(this::mapToDTO);
    }

    @Transactional(readOnly = true)
    public Page<MeetingDTO> listMyMeetings(UUID userId, String perspective, Pageable pageable) {
        if ("host".equalsIgnoreCase(perspective)) {
            return meetingRepository.findByHostUserId(userId, pageable).map(this::mapToDTO);
        }
        return meetingRepository.findByRequesterUserId(userId, pageable).map(this::mapToDTO);
    }

    @Transactional(readOnly = true)
    public Page<MeetingDTO> getCalendarMeetings(Instant rangeStart, Instant rangeEnd, Pageable pageable) {
        return meetingRepository.findByTenantIdAndDateRange(TenantContextHolder.getTenantId(), rangeStart, rangeEnd, pageable).map(this::mapToDTO);
    }

    // ---- Default host (for renters who cannot derive a PM) ----

    @Transactional(readOnly = true)
    public UUID getDefaultHostId() {
        UUID tenantId = TenantContextHolder.getTenantId();
        // Prefer TENANT_ADMIN, fall back to any user with PROPERTY_MANAGER role
        List<User> admins = userRepository.findByTenantIdAndRole(tenantId, UserRole.TENANT_ADMIN);
        if (!admins.isEmpty()) {
            return admins.get(0).getId();
        }
        List<User> managers = userRepository.findByTenantIdAndRole(tenantId, UserRole.PROPERTY_MANAGER);
        if (!managers.isEmpty()) {
            return managers.get(0).getId();
        }
        throw new NotFoundException("No host user found for this tenant");
    }

    // ---- Slot availability ----

    @Transactional(readOnly = true)
    public List<SlotDTO> getAvailableSlots(UUID hostUserId, java.time.LocalDate date) {
        ZonedDateTime dayStartZdt = date.atTime(DAY_START_HOUR, 0).atZone(UAE_ZONE);
        ZonedDateTime dayEndZdt = date.atTime(DAY_END_HOUR, 0).atZone(UAE_ZONE);

        Instant dayStart = dayStartZdt.toInstant();
        Instant dayEnd = dayEndZdt.toInstant();

        List<Meeting> bookedMeetings = meetingRepository.findActiveByHostAndDay(
                hostUserId, TenantContextHolder.getTenantId(), dayStart, dayEnd, INACTIVE_STATUSES);

        List<SlotDTO> slots = new ArrayList<>();
        ZonedDateTime cursor = dayStartZdt;
        while (cursor.toInstant().isBefore(dayEnd)) {
            Instant slotStart = cursor.toInstant();
            Instant slotEnd = cursor.plusMinutes(SLOT_DURATION_MINUTES).toInstant();
            boolean taken = bookedMeetings.stream()
                    .anyMatch(m -> m.getSlotStart().equals(slotStart));
            slots.add(new SlotDTO(slotStart, slotEnd, !taken));
            cursor = cursor.plusMinutes(SLOT_DURATION_MINUTES);
        }
        return slots;
    }

    // ---- Find next available slot ----

    @Transactional(readOnly = true)
    public Instant findNextAvailableSlot(UUID hostUserId, Instant fromInstant) {
        ZonedDateTime from = fromInstant.atZone(UAE_ZONE);
        java.time.LocalDate date = from.toLocalDate();

        for (int dayOffset = 0; dayOffset <= 14; dayOffset++) {
            java.time.LocalDate checkDate = date.plusDays(dayOffset);
            List<SlotDTO> slots = getAvailableSlots(hostUserId, checkDate);
            for (SlotDTO slot : slots) {
                if (slot.isAvailable()) {
                    // For same day, only suggest future slots; for future days, any slot works
                    if (dayOffset == 0 && !slot.getStart().isAfter(from.toInstant())) continue;
                    return slot.getStart();
                }
            }
        }

        return null; // No slots available within 14 days
    }

    private String formatSlotForDisplay(Instant slotStart) {
        return slotStart.atZone(ZoneId.of("Asia/Dubai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a"));
    }

    // ---- Mapping ----

    private MeetingDTO mapToDTO(Meeting meeting) {
        MeetingDTO dto = new MeetingDTO();
        dto.setId(meeting.getId());
        dto.setType(meeting.getType() != null ? meeting.getType().name() : null);
        dto.setStatus(meeting.getStatus() != null ? meeting.getStatus().name() : null);
        dto.setPurpose(meeting.getPurpose() != null ? meeting.getPurpose().name() : null);
        dto.setTitle(meeting.getTitle());
        dto.setNotes(meeting.getNotes());
        dto.setSlotStart(meeting.getSlotStart());
        dto.setSlotEnd(meeting.getSlotEnd());
        dto.setHostUserId(meeting.getHostUserId());
        dto.setRequesterUserId(meeting.getRequesterUserId());
        dto.setCreatedAt(meeting.getCreatedAt());
        dto.setUpdatedAt(meeting.getUpdatedAt());

        // Enrich host / requester names. Filter-bypassing lookup: superadmin
        // actors have tenant_id = NULL and are invisible to the tenant-
        // filtered findById, which degraded these names to blank.
        try {
            userRepository.findDisplayNameById(meeting.getHostUserId())
                    .ifPresent(dto::setHostName);
        } catch (Exception e) {
            log.debug("Could not enrich host name for meeting {}", meeting.getId());
        }

        try {
            userRepository.findDisplayNameById(meeting.getRequesterUserId())
                    .ifPresent(dto::setRequesterName);
        } catch (Exception e) {
            log.debug("Could not enrich requester name for meeting {}", meeting.getId());
        }

        // Enrich lease
        try {
            if (meeting.getLease() != null) {
                dto.setLeaseId(meeting.getLease().getId());
                // Build lease label: "Unit {unitNumber} - {renterName}"
                String unitNumber = meeting.getLease().getUnit() != null
                        ? meeting.getLease().getUnit().getUnitNumber() : null;
                String renterName = meeting.getLease().getRenter() != null
                        ? meeting.getLease().getRenter().getNameEn() : null;
                if (unitNumber != null && renterName != null) {
                    dto.setLeaseLabel("Unit " + unitNumber + " - " + renterName);
                } else if (unitNumber != null) {
                    dto.setLeaseLabel("Unit " + unitNumber);
                }
            }
        } catch (Exception e) {
            log.debug("Could not enrich lease for meeting {}", meeting.getId());
        }

        // Enrich property
        try {
            if (meeting.getProperty() != null) {
                dto.setPropertyId(meeting.getProperty().getId());
                dto.setPropertyName(meeting.getProperty().getNameEn());
            }
        } catch (Exception e) {
            log.debug("Could not enrich property for meeting {}", meeting.getId());
        }

        // Enrich unit
        try {
            if (meeting.getUnit() != null) {
                dto.setUnitId(meeting.getUnit().getId());
                dto.setUnitNumber(meeting.getUnit().getUnitNumber());
            }
        } catch (Exception e) {
            log.debug("Could not enrich unit for meeting {}", meeting.getId());
        }

        // Enrich meeting details
        try {
            meetingDetailRepository.findByMeetingId(meeting.getId())
                    .ifPresent(detail -> dto.setDetails(mapDetailToDTO(detail)));
        } catch (Exception e) {
            log.debug("Could not enrich meeting details for meeting {}", meeting.getId());
        }

        return dto;
    }

    private MeetingDetailDTO mapDetailToDTO(MeetingDetail detail) {
        MeetingDetailDTO dto = new MeetingDetailDTO();
        dto.setDetailType(detail.getDetailType());
        dto.setPaymentScheduleIds(detail.getPaymentScheduleIds());
        dto.setProposedStartDate(detail.getProposedStartDate());
        dto.setProposedEndDate(detail.getProposedEndDate());
        dto.setProposedRentAmount(detail.getProposedRentAmount());
        dto.setNotes(detail.getNotes());
        return dto;
    }
}

package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateMeetingDTO;
import com.datagami.rentaxis.api.dto.MeetingDTO;
import com.datagami.rentaxis.api.dto.MeetingDetailDTO;
import com.datagami.rentaxis.api.dto.SlotDTO;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.SlotConflictException;
import com.datagami.rentaxis.domain.entity.Meeting;
import com.datagami.rentaxis.domain.entity.MeetingDetail;
import com.datagami.rentaxis.domain.entity.enums.MeetingPurpose;
import com.datagami.rentaxis.domain.entity.enums.MeetingStatus;
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
        // Validate host exists
        userRepository.findById(dto.getHostUserId())
                .orElseThrow(() -> new NotFoundException("Host user not found"));

        // Check for conflicts
        List<Meeting> conflicts = meetingRepository.findConflicts(
                dto.getHostUserId(), dto.getSlotStart(), INACTIVE_STATUSES);
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
                    "A meeting has been requested for " + saved.getSlotStart(),
                    "MEETING", saved.getId());
        } catch (Exception e) {
            log.warn("Failed to send MEETING_REQUESTED notification for meeting {}", saved.getId(), e);
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
                    "Your meeting request has been approved for " + saved.getSlotStart(),
                    "MEETING", saved.getId());
        } catch (Exception e) {
            log.warn("Failed to send MEETING_APPROVED notification for meeting {}", meetingId, e);
        }

        log.info("Approved meeting {} by user {}", meetingId, approverUserId);
        return mapToDTO(saved);
    }

    // ---- Cancel ----

    @Transactional
    public MeetingDTO cancelMeeting(UUID meetingId, UUID cancelledByUserId) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new NotFoundException("Meeting not found"));

        if (meeting.getStatus() != MeetingStatus.REQUESTED
                && meeting.getStatus() != MeetingStatus.APPROVED) {
            throw new BusinessRuleViolationException(
                    "Cannot cancel meeting in status: " + meeting.getStatus());
        }

        meeting.setStatus(MeetingStatus.CANCELLED);
        Meeting saved = meetingRepository.save(meeting);

        // Notify the other party (whoever didn't cancel)
        try {
            UUID notifyUser = cancelledByUserId.equals(saved.getHostUserId())
                    ? saved.getRequesterUserId()
                    : saved.getHostUserId();
            notificationService.notify(
                    saved.getTenantId(), notifyUser,
                    "MEETING_CANCELLED", "Meeting Cancelled",
                    "A meeting scheduled for " + saved.getSlotStart() + " has been cancelled.",
                    "MEETING", saved.getId());
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
                    "Your meeting on " + saved.getSlotStart() + " has been marked as completed.",
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
                    "Your meeting on " + saved.getSlotStart() + " was marked as no-show.",
                    "MEETING", saved.getId());
        } catch (Exception e) {
            log.warn("Failed to send MEETING_NO_SHOW notification for meeting {}", meetingId, e);
        }

        log.info("Marked no-show for meeting {} by user {}", meetingId, performedByUserId);
        return mapToDTO(saved);
    }

    // ---- Read operations ----

    @Transactional(readOnly = true)
    public MeetingDTO getMeeting(UUID meetingId) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new NotFoundException("Meeting not found"));
        return mapToDTO(meeting);
    }

    @Transactional(readOnly = true)
    public Page<MeetingDTO> listMeetings(Pageable pageable) {
        return meetingRepository.findAll(pageable).map(this::mapToDTO);
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
        return meetingRepository.findByDateRange(rangeStart, rangeEnd, pageable).map(this::mapToDTO);
    }

    // ---- Slot availability ----

    @Transactional(readOnly = true)
    public List<SlotDTO> getAvailableSlots(UUID hostUserId, java.time.LocalDate date) {
        ZonedDateTime dayStartZdt = date.atTime(DAY_START_HOUR, 0).atZone(UAE_ZONE);
        ZonedDateTime dayEndZdt = date.atTime(DAY_END_HOUR, 0).atZone(UAE_ZONE);

        Instant dayStart = dayStartZdt.toInstant();
        Instant dayEnd = dayEndZdt.toInstant();

        List<Meeting> bookedMeetings = meetingRepository.findActiveByHostAndDay(
                hostUserId, dayStart, dayEnd, INACTIVE_STATUSES);

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

    public Instant findNextAvailableSlot(UUID hostUserId, Instant fromInstant) {
        ZonedDateTime from = fromInstant.atZone(UAE_ZONE);
        ZonedDateTime scanStart = from.plusMinutes(SLOT_DURATION_MINUTES);

        // Try slots within the same day first, then scan up to MAX_SCAN_DAYS
        Instant rangeEnd = scanStart.toInstant().plus(MAX_SCAN_DAYS, ChronoUnit.DAYS);
        List<Meeting> booked = meetingRepository.findByHostAndRange(
                hostUserId, scanStart.toInstant(), rangeEnd, INACTIVE_STATUSES);

        ZonedDateTime cursor = scanStart;
        for (int day = 0; day < MAX_SCAN_DAYS; day++) {
            java.time.LocalDate checkDate = cursor.toLocalDate();
            ZonedDateTime dayStart = checkDate.atTime(DAY_START_HOUR, 0).atZone(UAE_ZONE);
            ZonedDateTime dayEnd = checkDate.atTime(DAY_END_HOUR, 0).atZone(UAE_ZONE);

            // Adjust cursor to at least dayStart
            if (cursor.isBefore(dayStart)) {
                cursor = dayStart;
            }

            while (cursor.toInstant().isBefore(dayEnd.toInstant())) {
                Instant slotCandidate = cursor.toInstant();
                boolean taken = booked.stream()
                        .anyMatch(m -> m.getSlotStart().equals(slotCandidate));
                if (!taken) {
                    return slotCandidate;
                }
                cursor = cursor.plusMinutes(SLOT_DURATION_MINUTES);
            }

            // Move to next day's start
            cursor = checkDate.plusDays(1).atTime(DAY_START_HOUR, 0).atZone(UAE_ZONE);
        }

        // Fallback: return start of first available day after scan window
        return scanStart.toInstant().plus(MAX_SCAN_DAYS, ChronoUnit.DAYS);
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

        // Enrich host name
        try {
            userRepository.findById(meeting.getHostUserId())
                    .ifPresent(u -> dto.setHostName(u.getName()));
        } catch (Exception e) {
            log.debug("Could not enrich host name for meeting {}", meeting.getId());
        }

        // Enrich requester name
        try {
            userRepository.findById(meeting.getRequesterUserId())
                    .ifPresent(u -> dto.setRequesterName(u.getName()));
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

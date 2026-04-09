# Meetings Module Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a meetings/scheduling module that lets renters and property managers book office visits (cheque replacement, lease renewal, custom) and property visits, with conflict detection, approval workflow, calendar UI, and notifications.

**Architecture:** Single `meetings` table with type discriminator + `meeting_details` child table. Fixed 30-min slots (9AM–9PM). Conflict check against host PM's schedule. Approval flow: REQUESTED → APPROVED → COMPLETED/CANCELLED/NO_SHOW. Leverages existing NotificationService for in-app + email alerts.

**Tech Stack:** Java 21 + Spring Boot + JPA + Liquibase (backend), Next.js + TypeScript + Tailwind + @fullcalendar/react (frontend)

**Design Doc:** `docs/plans/2026-04-09-meetings-design.md`

---

## Task 1: Database Migration

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/41-meetings.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (add include at end, line ~90)

**Step 1: Create the migration file**

Create `41-meetings.yaml` with two tables:

```yaml
databaseChangeLog:
  - changeSet:
      id: 41-meetings
      author: system
      comment: "Meetings and scheduling module"
      changes:
        - createTable:
            tableName: meetings
            columns:
              - column:
                  name: id
                  type: uuid
                  defaultValueComputed: gen_random_uuid()
                  constraints:
                    primaryKey: true
              - column:
                  name: tenant_id
                  type: uuid
                  constraints:
                    nullable: false
              - column:
                  name: type
                  type: varchar(30)
                  constraints:
                    nullable: false
              - column:
                  name: status
                  type: varchar(30)
                  defaultValue: REQUESTED
                  constraints:
                    nullable: false
              - column:
                  name: purpose
                  type: varchar(30)
                  constraints:
                    nullable: false
              - column:
                  name: title
                  type: varchar(255)
              - column:
                  name: notes
                  type: text
              - column:
                  name: slot_start
                  type: timestamp with time zone
                  constraints:
                    nullable: false
              - column:
                  name: slot_end
                  type: timestamp with time zone
                  constraints:
                    nullable: false
              - column:
                  name: host_user_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_meeting_host_user
                    references: users(id)
              - column:
                  name: requester_user_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_meeting_requester_user
                    references: users(id)
              - column:
                  name: lease_id
                  type: uuid
                  constraints:
                    foreignKeyName: fk_meeting_lease
                    references: leases(id)
              - column:
                  name: property_id
                  type: uuid
                  constraints:
                    foreignKeyName: fk_meeting_property
                    references: properties(id)
              - column:
                  name: unit_id
                  type: uuid
                  constraints:
                    foreignKeyName: fk_meeting_unit
                    references: units(id)
              - column:
                  name: version
                  type: bigint
                  defaultValueNumeric: 0
              - column:
                  name: created_at
                  type: timestamp with time zone
                  defaultValueComputed: now()
              - column:
                  name: updated_at
                  type: timestamp with time zone
                  defaultValueComputed: now()

        - createTable:
            tableName: meeting_details
            columns:
              - column:
                  name: id
                  type: uuid
                  defaultValueComputed: gen_random_uuid()
                  constraints:
                    primaryKey: true
              - column:
                  name: meeting_id
                  type: uuid
                  constraints:
                    nullable: false
                    foreignKeyName: fk_detail_meeting
                    references: meetings(id)
              - column:
                  name: detail_type
                  type: varchar(30)
                  constraints:
                    nullable: false
              - column:
                  name: payment_schedule_ids
                  type: uuid[]
              - column:
                  name: proposed_start_date
                  type: date
              - column:
                  name: proposed_end_date
                  type: date
              - column:
                  name: proposed_rent_amount
                  type: decimal(15,2)
              - column:
                  name: notes
                  type: text

        - createIndex:
            tableName: meetings
            indexName: idx_meetings_tenant_id
            columns:
              - column:
                  name: tenant_id
        - createIndex:
            tableName: meetings
            indexName: idx_meetings_host_slot
            columns:
              - column:
                  name: host_user_id
              - column:
                  name: slot_start
        - createIndex:
            tableName: meetings
            indexName: idx_meetings_status
            columns:
              - column:
                  name: status
        - createIndex:
            tableName: meetings
            indexName: idx_meetings_requester
            columns:
              - column:
                  name: requester_user_id
        - createIndex:
            tableName: meetings
            indexName: idx_meetings_lease
            columns:
              - column:
                  name: lease_id
        - createIndex:
            tableName: meetings
            indexName: idx_meetings_property
            columns:
              - column:
                  name: property_id
        - createIndex:
            tableName: meeting_details
            indexName: idx_meeting_details_meeting
            unique: true
            columns:
              - column:
                  name: meeting_id
```

**Step 2: Add to master changelog**

Add at end of `db.changelog-master.yaml` (after line 89):
```yaml
  - include:
      file: db/changelog/changesets/41-meetings.yaml
```

**Step 3: Run the backend to verify migration**

Run: `cd backend && ./gradlew bootRun`
Expected: Application starts, tables `meetings` and `meeting_details` created.

**Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/changesets/41-meetings.yaml backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat(meetings): add database migration for meetings and meeting_details tables"
```

---

## Task 2: Backend Enums

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/MeetingType.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/MeetingStatus.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/MeetingPurpose.java`

**Step 1: Create enums**

Follow pattern from existing enums at `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/TicketStatus.java`.

```java
// MeetingType.java
package com.datagami.rentaxis.domain.entity.enums;
public enum MeetingType {
    OFFICE_VISIT,
    PROPERTY_VISIT
}

// MeetingStatus.java
package com.datagami.rentaxis.domain.entity.enums;
public enum MeetingStatus {
    REQUESTED,
    APPROVED,
    COMPLETED,
    CANCELLED,
    NO_SHOW
}

// MeetingPurpose.java
package com.datagami.rentaxis.domain.entity.enums;
public enum MeetingPurpose {
    CHEQUE_REPLACEMENT,
    LEASE_RENEWAL,
    PROPERTY_VIEWING,
    OTHER
}
```

**Step 2: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/Meeting*.java
git commit -m "feat(meetings): add MeetingType, MeetingStatus, MeetingPurpose enums"
```

---

## Task 3: Backend Entities

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/Meeting.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/MeetingDetail.java`

**Step 1: Create Meeting entity**

Follow pattern from `MaintenanceTicket.java` (extends `BaseTenantEntity`, UUID PK, `@GeneratedValue(strategy = GenerationType.UUID)`, `@ManyToOne(fetch = FetchType.LAZY)`, `@Enumerated(EnumType.STRING)`, Instant timestamps, `@PreUpdate`, `@Version`).

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.MeetingType;
import com.datagami.rentaxis.domain.entity.enums.MeetingStatus;
import com.datagami.rentaxis.domain.entity.enums.MeetingPurpose;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meetings")
@Getter
@Setter
public class Meeting extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private MeetingType type;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private MeetingStatus status = MeetingStatus.REQUESTED;

    @Enumerated(EnumType.STRING)
    @Column(length = 30, nullable = false)
    private MeetingPurpose purpose;

    @Column(length = 255)
    private String title;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "slot_start", nullable = false)
    private Instant slotStart;

    @Column(name = "slot_end", nullable = false)
    private Instant slotEnd;

    @Column(name = "host_user_id", nullable = false)
    private UUID hostUserId;

    @Column(name = "requester_user_id", nullable = false)
    private UUID requesterUserId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id")
    private Lease lease;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id")
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id")
    private Unit unit;

    @Version
    private Long version;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
```

**Step 2: Create MeetingDetail entity**

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "meeting_details")
@Getter
@Setter
public class MeetingDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false, unique = true)
    private Meeting meeting;

    @Column(name = "detail_type", length = 30, nullable = false)
    private String detailType;

    @Column(name = "payment_schedule_ids", columnDefinition = "uuid[]")
    private UUID[] paymentScheduleIds;

    @Column(name = "proposed_start_date")
    private LocalDate proposedStartDate;

    @Column(name = "proposed_end_date")
    private LocalDate proposedEndDate;

    @Column(name = "proposed_rent_amount", precision = 15, scale = 2)
    private BigDecimal proposedRentAmount;

    @Column(columnDefinition = "text")
    private String notes;
}
```

**Step 3: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/Meeting.java backend/src/main/java/com/datagami/rentaxis/domain/entity/MeetingDetail.java
git commit -m "feat(meetings): add Meeting and MeetingDetail JPA entities"
```

---

## Task 4: Backend Repositories

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/MeetingRepository.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/MeetingDetailRepository.java`

**Step 1: Create repositories**

Follow pattern from `MaintenanceTicketRepository.java`.

```java
// MeetingRepository.java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Meeting;
import com.datagami.rentaxis.domain.entity.enums.MeetingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

    Page<Meeting> findByHostUserId(UUID hostUserId, Pageable pageable);

    Page<Meeting> findByRequesterUserId(UUID requesterUserId, Pageable pageable);

    Page<Meeting> findByStatus(MeetingStatus status, Pageable pageable);

    @Query("SELECT m FROM Meeting m WHERE m.hostUserId = :hostUserId " +
           "AND m.slotStart >= :dayStart AND m.slotStart < :dayEnd " +
           "AND m.status NOT IN ('CANCELLED', 'NO_SHOW')")
    List<Meeting> findActiveByHostAndDay(@Param("hostUserId") UUID hostUserId,
                                         @Param("dayStart") Instant dayStart,
                                         @Param("dayEnd") Instant dayEnd);

    @Query("SELECT m FROM Meeting m WHERE m.hostUserId = :hostUserId " +
           "AND m.slotStart = :slotStart " +
           "AND m.status NOT IN ('CANCELLED', 'NO_SHOW')")
    List<Meeting> findConflicts(@Param("hostUserId") UUID hostUserId,
                                @Param("slotStart") Instant slotStart);

    @Query("SELECT m FROM Meeting m WHERE m.hostUserId = :hostUserId " +
           "AND m.slotStart >= :rangeStart AND m.slotStart < :rangeEnd " +
           "AND m.status NOT IN ('CANCELLED', 'NO_SHOW')")
    List<Meeting> findByHostAndRange(@Param("hostUserId") UUID hostUserId,
                                     @Param("rangeStart") Instant rangeStart,
                                     @Param("rangeEnd") Instant rangeEnd);

    @Query("SELECT m FROM Meeting m WHERE m.slotStart >= :rangeStart AND m.slotStart < :rangeEnd")
    Page<Meeting> findByDateRange(@Param("rangeStart") Instant rangeStart,
                                   @Param("rangeEnd") Instant rangeEnd,
                                   Pageable pageable);
}

// MeetingDetailRepository.java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.MeetingDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface MeetingDetailRepository extends JpaRepository<MeetingDetail, UUID> {
    Optional<MeetingDetail> findByMeetingId(UUID meetingId);
}
```

**Step 2: Verify compilation**

Run: `cd backend && ./gradlew compileJava`

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/repository/MeetingRepository.java backend/src/main/java/com/datagami/rentaxis/domain/repository/MeetingDetailRepository.java
git commit -m "feat(meetings): add MeetingRepository and MeetingDetailRepository"
```

---

## Task 5: Backend DTOs

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/CreateMeetingDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/MeetingDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/MeetingDetailDTO.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/SlotDTO.java`

**Step 1: Create DTOs**

Follow pattern from `CreateTicketDTO.java` and `MaintenanceTicketDTO.java`.

```java
// CreateMeetingDTO.java
package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class CreateMeetingDTO {
    @NotBlank
    private String type;        // OFFICE_VISIT, PROPERTY_VISIT
    @NotBlank
    private String purpose;     // CHEQUE_REPLACEMENT, LEASE_RENEWAL, PROPERTY_VIEWING, OTHER
    private String title;
    private String notes;
    @NotNull
    private Instant slotStart;
    @NotNull
    private UUID hostUserId;
    private UUID leaseId;       // for office visits
    private UUID propertyId;    // for property visits
    private UUID unitId;        // for property visits

    // Cheque replacement details
    private UUID[] paymentScheduleIds;

    // Lease renewal details
    private LocalDate proposedStartDate;
    private LocalDate proposedEndDate;
    private BigDecimal proposedRentAmount;
    private String detailNotes;
}

// MeetingDTO.java
package com.datagami.rentaxis.api.dto;

import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
public class MeetingDTO {
    private UUID id;
    private String type;
    private String status;
    private String purpose;
    private String title;
    private String notes;
    private Instant slotStart;
    private Instant slotEnd;
    private UUID hostUserId;
    private String hostName;
    private UUID requesterUserId;
    private String requesterName;
    private UUID leaseId;
    private String leaseLabel;      // e.g. "Unit 101 - John Doe"
    private UUID propertyId;
    private String propertyName;
    private UUID unitId;
    private String unitNumber;
    private MeetingDetailDTO details;
    private Instant createdAt;
    private Instant updatedAt;
}

// MeetingDetailDTO.java
package com.datagami.rentaxis.api.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class MeetingDetailDTO {
    private String detailType;
    private UUID[] paymentScheduleIds;
    private LocalDate proposedStartDate;
    private LocalDate proposedEndDate;
    private BigDecimal proposedRentAmount;
    private String notes;
}

// SlotDTO.java
package com.datagami.rentaxis.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.time.Instant;

@Data
@AllArgsConstructor
public class SlotDTO {
    private Instant start;
    private Instant end;
    private boolean available;
}
```

**Step 2: Verify compilation**

Run: `cd backend && ./gradlew compileJava`

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/CreateMeetingDTO.java backend/src/main/java/com/datagami/rentaxis/api/dto/MeetingDTO.java backend/src/main/java/com/datagami/rentaxis/api/dto/MeetingDetailDTO.java backend/src/main/java/com/datagami/rentaxis/api/dto/SlotDTO.java
git commit -m "feat(meetings): add meeting DTOs"
```

---

## Task 6: Backend Service — MeetingService

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/MeetingService.java`

Follow pattern from `MaintenanceTicketService.java` — constructor injection, `@Transactional`, tenant context, notification integration, DTO mapping.

**Step 1: Create MeetingService**

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class MeetingService {

    private final MeetingRepository meetingRepository;
    private final MeetingDetailRepository meetingDetailRepository;
    private final UserRepository userRepository;
    private final LeaseRepository leaseRepository;
    private final PropertyRepository propertyRepository;
    private final UnitRepository unitRepository;
    private final NotificationService notificationService;

    private static final int SLOT_MINUTES = 30;
    private static final int DAY_START_HOUR = 9;
    private static final int DAY_END_HOUR = 21;

    // --- Create ---

    @Transactional
    public MeetingDTO createMeeting(CreateMeetingDTO dto, UUID requesterUserId) {
        // Validate slot availability
        List<Meeting> conflicts = meetingRepository.findConflicts(dto.getHostUserId(), dto.getSlotStart());
        if (!conflicts.isEmpty()) {
            throw new SlotConflictException("Slot is already booked", findNextAvailableSlot(dto.getHostUserId(), dto.getSlotStart()));
        }

        Meeting meeting = new Meeting();
        meeting.setType(MeetingType.valueOf(dto.getType()));
        meeting.setStatus(MeetingStatus.REQUESTED);
        meeting.setPurpose(MeetingPurpose.valueOf(dto.getPurpose()));
        meeting.setTitle(dto.getTitle());
        meeting.setNotes(dto.getNotes());
        meeting.setSlotStart(dto.getSlotStart());
        meeting.setSlotEnd(dto.getSlotStart().plus(Duration.ofMinutes(SLOT_MINUTES)));
        meeting.setHostUserId(dto.getHostUserId());
        meeting.setRequesterUserId(requesterUserId);

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

        meeting = meetingRepository.save(meeting);

        // Save meeting details if applicable
        if (dto.getPurpose().equals("CHEQUE_REPLACEMENT") || dto.getPurpose().equals("LEASE_RENEWAL")) {
            MeetingDetail detail = new MeetingDetail();
            detail.setMeeting(meeting);
            detail.setDetailType(dto.getPurpose());
            detail.setPaymentScheduleIds(dto.getPaymentScheduleIds());
            detail.setProposedStartDate(dto.getProposedStartDate());
            detail.setProposedEndDate(dto.getProposedEndDate());
            detail.setProposedRentAmount(dto.getProposedRentAmount());
            detail.setNotes(dto.getDetailNotes());
            meetingDetailRepository.save(detail);
        }

        // Notify host PM
        UUID tenantId = TenantContextHolder.getTenantId();
        String requesterName = userRepository.findById(requesterUserId)
                .map(User::getName).orElse("Unknown");
        notificationService.notify(tenantId, dto.getHostUserId(),
                "MEETING_REQUESTED", "New Meeting Request",
                requesterName + " has requested a meeting: " + (dto.getTitle() != null ? dto.getTitle() : dto.getPurpose()),
                "MEETING", meeting.getId());

        return mapToDTO(meeting);
    }

    // --- Status transitions ---

    @Transactional
    public MeetingDTO approveMeeting(UUID meetingId, UUID approverId) {
        Meeting meeting = findById(meetingId);
        validateStatusTransition(meeting.getStatus(), MeetingStatus.APPROVED);
        meeting.setStatus(MeetingStatus.APPROVED);
        meeting = meetingRepository.save(meeting);

        notificationService.notify(meeting.getTenantId(), meeting.getRequesterUserId(),
                "MEETING_APPROVED", "Meeting Approved",
                "Your meeting has been approved for " + meeting.getSlotStart(),
                "MEETING", meeting.getId());

        return mapToDTO(meeting);
    }

    @Transactional
    public MeetingDTO cancelMeeting(UUID meetingId, UUID userId) {
        Meeting meeting = findById(meetingId);
        validateStatusTransition(meeting.getStatus(), MeetingStatus.CANCELLED);
        meeting.setStatus(MeetingStatus.CANCELLED);
        meeting = meetingRepository.save(meeting);

        // Notify both parties
        UUID tenantId = meeting.getTenantId();
        String cancellerName = userRepository.findById(userId).map(User::getName).orElse("Unknown");
        String msg = cancellerName + " cancelled the meeting: " + (meeting.getTitle() != null ? meeting.getTitle() : meeting.getPurpose().name());

        if (!meeting.getHostUserId().equals(userId)) {
            notificationService.notify(tenantId, meeting.getHostUserId(),
                    "MEETING_CANCELLED", "Meeting Cancelled", msg, "MEETING", meeting.getId());
        }
        if (!meeting.getRequesterUserId().equals(userId)) {
            notificationService.notify(tenantId, meeting.getRequesterUserId(),
                    "MEETING_CANCELLED", "Meeting Cancelled", msg, "MEETING", meeting.getId());
        }

        return mapToDTO(meeting);
    }

    @Transactional
    public MeetingDTO completeMeeting(UUID meetingId) {
        Meeting meeting = findById(meetingId);
        validateStatusTransition(meeting.getStatus(), MeetingStatus.COMPLETED);
        meeting.setStatus(MeetingStatus.COMPLETED);
        meeting = meetingRepository.save(meeting);

        notificationService.notify(meeting.getTenantId(), meeting.getRequesterUserId(),
                "MEETING_COMPLETED", "Meeting Completed",
                "Your meeting has been marked as completed.",
                "MEETING", meeting.getId());

        return mapToDTO(meeting);
    }

    @Transactional
    public MeetingDTO noShowMeeting(UUID meetingId) {
        Meeting meeting = findById(meetingId);
        validateStatusTransition(meeting.getStatus(), MeetingStatus.NO_SHOW);
        meeting.setStatus(MeetingStatus.NO_SHOW);
        meeting = meetingRepository.save(meeting);

        notificationService.notify(meeting.getTenantId(), meeting.getRequesterUserId(),
                "MEETING_NO_SHOW", "Meeting No-Show",
                "You were marked as a no-show for your scheduled meeting.",
                "MEETING", meeting.getId());

        return mapToDTO(meeting);
    }

    // --- Queries ---

    @Transactional(readOnly = true)
    public MeetingDTO getMeeting(UUID meetingId) {
        return mapToDTO(findById(meetingId));
    }

    @Transactional(readOnly = true)
    public Page<MeetingDTO> listMeetings(Pageable pageable) {
        return meetingRepository.findAll(pageable).map(this::mapToDTO);
    }

    @Transactional(readOnly = true)
    public Page<MeetingDTO> listMyMeetings(UUID userId, Pageable pageable) {
        return meetingRepository.findByRequesterUserId(userId, pageable).map(this::mapToDTO);
    }

    @Transactional(readOnly = true)
    public Page<MeetingDTO> getCalendarMeetings(Instant rangeStart, Instant rangeEnd, Pageable pageable) {
        return meetingRepository.findByDateRange(rangeStart, rangeEnd, pageable).map(this::mapToDTO);
    }

    // --- Slot availability ---

    @Transactional(readOnly = true)
    public List<SlotDTO> getAvailableSlots(UUID hostUserId, LocalDate date) {
        ZoneId zone = ZoneId.of("Asia/Dubai"); // UAE timezone
        Instant dayStart = date.atTime(DAY_START_HOUR, 0).atZone(zone).toInstant();
        Instant dayEnd = date.atTime(DAY_END_HOUR, 0).atZone(zone).toInstant();

        List<Meeting> booked = meetingRepository.findActiveByHostAndDay(hostUserId, dayStart, dayEnd);
        Set<Instant> bookedStarts = new HashSet<>();
        for (Meeting m : booked) {
            bookedStarts.add(m.getSlotStart());
        }

        List<SlotDTO> slots = new ArrayList<>();
        Instant cursor = dayStart;
        while (cursor.isBefore(dayEnd)) {
            Instant slotEnd = cursor.plus(Duration.ofMinutes(SLOT_MINUTES));
            boolean available = !bookedStarts.contains(cursor);
            slots.add(new SlotDTO(cursor, slotEnd, available));
            cursor = slotEnd;
        }
        return slots;
    }

    public Instant findNextAvailableSlot(UUID hostUserId, Instant from) {
        ZoneId zone = ZoneId.of("Asia/Dubai");
        LocalDate date = from.atZone(zone).toLocalDate();

        // Check remaining slots on the same day, then next 14 days
        for (int dayOffset = 0; dayOffset <= 14; dayOffset++) {
            LocalDate checkDate = date.plusDays(dayOffset);
            List<SlotDTO> slots = getAvailableSlots(hostUserId, checkDate);
            for (SlotDTO slot : slots) {
                if (slot.isAvailable() && slot.getStart().isAfter(from)) {
                    return slot.getStart();
                }
            }
        }
        return null; // No slots available in next 14 days
    }

    // --- Helpers ---

    private Meeting findById(UUID id) {
        return meetingRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Meeting not found"));
    }

    private void validateStatusTransition(MeetingStatus current, MeetingStatus target) {
        boolean valid = switch (target) {
            case APPROVED -> current == MeetingStatus.REQUESTED;
            case COMPLETED -> current == MeetingStatus.APPROVED;
            case CANCELLED -> current == MeetingStatus.REQUESTED || current == MeetingStatus.APPROVED;
            case NO_SHOW -> current == MeetingStatus.APPROVED;
            default -> false;
        };
        if (!valid) {
            throw new BusinessRuleViolationException(
                    "Cannot transition from " + current + " to " + target);
        }
    }

    private MeetingDTO mapToDTO(Meeting m) {
        MeetingDTO dto = new MeetingDTO();
        dto.setId(m.getId());
        dto.setType(m.getType().name());
        dto.setStatus(m.getStatus().name());
        dto.setPurpose(m.getPurpose().name());
        dto.setTitle(m.getTitle());
        dto.setNotes(m.getNotes());
        dto.setSlotStart(m.getSlotStart());
        dto.setSlotEnd(m.getSlotEnd());
        dto.setHostUserId(m.getHostUserId());
        dto.setRequesterUserId(m.getRequesterUserId());
        dto.setCreatedAt(m.getCreatedAt());
        dto.setUpdatedAt(m.getUpdatedAt());

        // Enrich with names
        userRepository.findById(m.getHostUserId()).ifPresent(u -> dto.setHostName(u.getName()));
        userRepository.findById(m.getRequesterUserId()).ifPresent(u -> dto.setRequesterName(u.getName()));

        // Enrich lease/property/unit
        if (m.getLease() != null) {
            dto.setLeaseId(m.getLease().getId());
        }
        if (m.getProperty() != null) {
            dto.setPropertyId(m.getProperty().getId());
            dto.setPropertyName(m.getProperty().getNameEn());
        }
        if (m.getUnit() != null) {
            dto.setUnitId(m.getUnit().getId());
            dto.setUnitNumber(m.getUnit().getUnitNumber());
        }

        // Enrich meeting details
        meetingDetailRepository.findByMeetingId(m.getId()).ifPresent(d -> {
            MeetingDetailDTO detailDTO = new MeetingDetailDTO();
            detailDTO.setDetailType(d.getDetailType());
            detailDTO.setPaymentScheduleIds(d.getPaymentScheduleIds());
            detailDTO.setProposedStartDate(d.getProposedStartDate());
            detailDTO.setProposedEndDate(d.getProposedEndDate());
            detailDTO.setProposedRentAmount(d.getProposedRentAmount());
            detailDTO.setNotes(d.getNotes());
            dto.setDetails(detailDTO);
        });

        return dto;
    }
}
```

**Step 2: Create SlotConflictException**

Check if a generic exception exists. If not, create:

```java
// backend/src/main/java/com/datagami/rentaxis/core/service/SlotConflictException.java
package com.datagami.rentaxis.core.service;

import java.time.Instant;

public class SlotConflictException extends RuntimeException {
    private final Instant nextAvailableSlot;

    public SlotConflictException(String message, Instant nextAvailableSlot) {
        super(message);
        this.nextAvailableSlot = nextAvailableSlot;
    }

    public Instant getNextAvailableSlot() {
        return nextAvailableSlot;
    }
}
```

**Step 3: Verify compilation**

Run: `cd backend && ./gradlew compileJava`

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/MeetingService.java backend/src/main/java/com/datagami/rentaxis/core/service/SlotConflictException.java
git commit -m "feat(meetings): add MeetingService with CRUD, slot availability, conflict detection, and notifications"
```

---

## Task 7: Backend Controller — MeetingController

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/MeetingController.java`

Follow pattern from `MaintenanceTicketController.java` — `@RestController`, `@RequestMapping`, `@PreAuthorize`, `@RequestHeader` for userId/role.

**Step 1: Create MeetingController**

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.core.service.MeetingService;
import com.datagami.rentaxis.core.service.SlotConflictException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingService meetingService;

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> createMeeting(@Valid @RequestBody CreateMeetingDTO dto,
                                            @RequestHeader("X-User-Id") UUID userId) {
        try {
            MeetingDTO meeting = meetingService.createMeeting(dto, userId);
            return ResponseEntity.status(HttpStatus.CREATED).body(meeting);
        } catch (SlotConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", e.getMessage(),
                    "nextAvailableSlot", e.getNextAvailableSlot()
            ));
        }
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Page<MeetingDTO>> listMeetings(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(meetingService.listMeetings(
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "slotStart"))));
    }

    @GetMapping("/my")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Page<MeetingDTO>> listMyMeetings(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(meetingService.listMyMeetings(userId,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "slotStart"))));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MeetingDTO> getMeeting(@PathVariable UUID id) {
        return ResponseEntity.ok(meetingService.getMeeting(id));
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MeetingDTO> approveMeeting(@PathVariable UUID id,
                                                      @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(meetingService.approveMeeting(id, userId));
    }

    @PutMapping("/{id}/cancel")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<MeetingDTO> cancelMeeting(@PathVariable UUID id,
                                                     @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(meetingService.cancelMeeting(id, userId));
    }

    @PutMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MeetingDTO> completeMeeting(@PathVariable UUID id) {
        return ResponseEntity.ok(meetingService.completeMeeting(id));
    }

    @PutMapping("/{id}/no-show")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MeetingDTO> noShowMeeting(@PathVariable UUID id) {
        return ResponseEntity.ok(meetingService.noShowMeeting(id));
    }

    @GetMapping("/slots")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<SlotDTO>> getAvailableSlots(
            @RequestParam UUID hostUserId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(meetingService.getAvailableSlots(hostUserId, date));
    }

    @GetMapping("/calendar")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<Page<MeetingDTO>> getCalendarMeetings(
            @RequestParam Instant start,
            @RequestParam Instant end,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {
        return ResponseEntity.ok(meetingService.getCalendarMeetings(start, end,
                PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "slotStart"))));
    }
}
```

**Step 2: Update NotificationService email link/button for MEETING type**

In `NotificationService.java`, add to the `link` switch (line ~129):
```java
case "MEETING" -> portalUrl + "/en/dashboard/meetings/" + referenceId;
```

Add to the `buttonLabel` switch (line ~135):
```java
case "MEETING_REQUESTED" -> "View Meeting Request";
case "MEETING_APPROVED", "MEETING_CANCELLED", "MEETING_COMPLETED", "MEETING_NO_SHOW" -> "View Meeting";
```

Add to the `details` switch (line ~151):
```java
case "MEETING_REQUESTED" -> "<p style=\"margin:0;color:#475569;font-size:12px;\">A new meeting has been requested. Please review and approve or decline.</p>";
case "MEETING_APPROVED" -> "<p style=\"margin:0;color:#475569;font-size:12px;\">Your meeting request has been approved. See you there!</p>";
case "MEETING_CANCELLED" -> "<p style=\"margin:0;color:#D97706;font-size:12px;\">A meeting has been cancelled. Check the details for more information.</p>";
case "MEETING_COMPLETED" -> "<p style=\"margin:0;color:#475569;font-size:12px;\">Your meeting has been completed successfully.</p>";
case "MEETING_NO_SHOW" -> "<p style=\"margin:0;color:#DC2626;font-size:12px;\">You were marked as a no-show for a scheduled meeting.</p>";
```

**Step 3: Verify compilation**

Run: `cd backend && ./gradlew compileJava`

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/MeetingController.java backend/src/main/java/com/datagami/rentaxis/core/service/NotificationService.java
git commit -m "feat(meetings): add MeetingController REST API and notification email templates"
```

---

## Task 8: Frontend — RBAC + API Proxy + i18n

**Files:**
- Modify: `web/src/lib/rbac.ts` (add meeting permissions)
- Modify: `web/next.config.ts` (add proxy rewrite for `/api/proxy/v1/meetings` if not wildcarded)
- Modify: `web/messages/en.json` and `web/messages/ar.json` (add meeting translations)

**Step 1: Add RBAC permissions**

Add to `PERMISSIONS` in `web/src/lib/rbac.ts`:
```typescript
canManageMeetings: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER'] as UserRole[],
canCreateMeetings: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER', 'RENTER'] as UserRole[],
```

**Step 2: Add i18n strings**

Add a `"Meetings"` section to both `en.json` and `ar.json` message files with keys for:
- Page title, status labels, purpose labels, type labels, form labels, button labels, calendar view labels, slot picker labels, conflict messages

**Step 3: Verify proxy rewrite**

Check if `next.config.ts` has a wildcard rewrite for `/api/proxy/v1/**`. If not, add one for `/api/proxy/v1/meetings/:path*`.

**Step 4: Commit**

```bash
git add web/src/lib/rbac.ts web/messages/en.json web/messages/ar.json
git commit -m "feat(meetings): add RBAC permissions and i18n strings for meetings"
```

---

## Task 9: Frontend — Meetings List + Calendar Page

**Files:**
- Create: `web/src/app/[locale]/dashboard/meetings/page.tsx`

**Step 1: Install FullCalendar**

Run: `cd web && npm install @fullcalendar/react @fullcalendar/daygrid @fullcalendar/timegrid @fullcalendar/interaction`

**Step 2: Create the meetings page**

Follow pattern from `web/src/app/[locale]/dashboard/tickets/page.tsx`. Build:

- `"use client"` component
- State: meetings list, loading, pagination (page/size/total), view mode (calendar/table), filters (status, type, date range)
- Fetch from `/api/proxy/v1/meetings` (for PM/Admin) or `/api/proxy/v1/meetings/my` (for Renter)
- **Calendar view**: Use `@fullcalendar/react` with `dayGridMonth`, `timeGridWeek`, `timeGridDay` views. Map meetings to FullCalendar events with color coding:
  - REQUESTED = yellow
  - APPROVED = blue
  - COMPLETED = green
  - CANCELLED = gray
  - NO_SHOW = red
- **Table view**: Standard table with columns: Date/Time, Type, Purpose, Host, Requester, Status, Actions
- Pagination component (same as other pages)
- Toggle between Calendar/Table via tabs or buttons
- "New Meeting" button opens create modal

**Step 3: Verify**

Run: `cd web && npm run build`

**Step 4: Commit**

```bash
git add web/src/app/\\[locale\\]/dashboard/meetings/
git commit -m "feat(meetings): add meetings list page with calendar and table views"
```

---

## Task 10: Frontend — Create Meeting Modal

**Files:**
- Create: `web/src/app/[locale]/dashboard/meetings/CreateMeetingModal.tsx`

**Step 1: Create multi-step modal**

Build a modal component with steps:

1. **Type selection**: Radio buttons for Office Visit / Property Visit
2. **Purpose + context**:
   - If Office Visit: purpose dropdown (Cheque Replacement, Lease Renewal, Other) + lease picker (fetch from `/api/proxy/v1/leases`)
   - If Property Visit: property picker (fetch from `/api/proxy/v1/properties`), optional unit picker
3. **Date + slot picker**:
   - Date picker (calendar widget)
   - On date select: fetch `/api/proxy/v1/meetings/slots?hostUserId=X&date=YYYY-MM-DD`
   - Display available slots as a grid of clickable buttons (green = available, gray = taken)
   - If no host selected yet, auto-detect: for Office Visit use PM from lease; for Property Visit use PM from property
4. **Details** (conditional):
   - Cheque Replacement: multi-select of payment schedule items from the selected lease (fetch from `/api/proxy/v1/leases/{id}/payment-schedule`)
   - Lease Renewal: proposed start date, end date, rent amount fields
   - Other/Property Viewing: just notes
5. **Review + submit**: Summary of all selections, notes field, Submit button

On submit: POST to `/api/proxy/v1/meetings`. On 409 conflict, show the next available slot suggestion.

**Step 2: Verify**

Run: `cd web && npm run build`

**Step 3: Commit**

```bash
git add web/src/app/\\[locale\\]/dashboard/meetings/CreateMeetingModal.tsx
git commit -m "feat(meetings): add multi-step create meeting modal with slot picker"
```

---

## Task 11: Frontend — Meeting Detail Page

**Files:**
- Create: `web/src/app/[locale]/dashboard/meetings/[id]/page.tsx`

**Step 1: Create detail page**

Follow pattern from lease detail page. Build:

- Fetch meeting from `/api/proxy/v1/meetings/{id}`
- Display: type badge, purpose badge, status badge with color
- Meeting info section: date/time, host name, requester name
- Linked entity section: if lease → show lease details link; if property → show property/unit link
- Meeting details section (conditional):
  - Cheque Replacement: list of cheques being replaced (with amounts and due dates)
  - Lease Renewal: proposed terms table (start, end, rent)
- **Action buttons** (role-based):
  - PM/Admin sees: Approve (if REQUESTED), Complete (if APPROVED), No-Show (if APPROVED), Cancel
  - Requester sees: Cancel (if REQUESTED or APPROVED)
- Status timeline showing transitions

**Step 2: Verify**

Run: `cd web && npm run build`

**Step 3: Commit**

```bash
git add web/src/app/\\[locale\\]/dashboard/meetings/\\[id\\]/
git commit -m "feat(meetings): add meeting detail page with status actions"
```

---

## Task 12: Frontend — Renter Portal Meetings

**Files:**
- Modify: `web/src/app/[locale]/dashboard/renter-portal/page.tsx` (add meetings tab/section)
  OR
- Create: `web/src/app/[locale]/dashboard/renter-portal/meetings/page.tsx`

**Step 1: Add renter meetings view**

- Fetch from `/api/proxy/v1/meetings/my`
- Paginated chronological list with status badges
- "Request Meeting" button → opens create meeting modal (pre-filtered to renter's leases)
- Each meeting card shows: date/time, purpose, status, host PM name
- Tap/click opens the meeting detail page

**Step 2: Add navigation link**

Add "Meetings" to the renter portal sidebar/navigation.

**Step 3: Verify**

Run: `cd web && npm run build`

**Step 4: Commit**

```bash
git add web/src/app/\\[locale\\]/dashboard/renter-portal/
git commit -m "feat(meetings): add renter portal meetings view"
```

---

## Task 13: Dashboard Navigation

**Files:**
- Modify: sidebar/navigation component (find the dashboard layout that renders the sidebar)

**Step 1: Add meetings to sidebar**

Add a "Meetings" nav item with a calendar icon (from lucide-react: `CalendarDays`) to the dashboard sidebar, visible to roles: SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER, RENTER.

Link to `/dashboard/meetings`.

**Step 2: Commit**

```bash
git add web/src/app/\\[locale\\]/dashboard/
git commit -m "feat(meetings): add meetings to dashboard sidebar navigation"
```

---

## Task 14: End-to-End Testing

**Step 1: Start the backend**

Run: `cd backend && ./gradlew bootRun`

**Step 2: Test API endpoints manually**

```bash
# Get available slots
curl -H "Authorization: Bearer <token>" \
  "http://localhost:8080/api/v1/meetings/slots?hostUserId=<pm-uuid>&date=2026-04-15"

# Create a meeting
curl -X POST -H "Content-Type: application/json" -H "Authorization: Bearer <token>" \
  -H "X-User-Id: <renter-uuid>" \
  -d '{"type":"OFFICE_VISIT","purpose":"CHEQUE_REPLACEMENT","slotStart":"2026-04-15T05:00:00Z","hostUserId":"<pm-uuid>","leaseId":"<lease-uuid>","paymentScheduleIds":["<ps-uuid>"]}' \
  "http://localhost:8080/api/v1/meetings"

# List meetings
curl -H "Authorization: Bearer <token>" "http://localhost:8080/api/v1/meetings?page=0&size=20"

# Approve
curl -X PUT -H "Authorization: Bearer <token>" -H "X-User-Id: <pm-uuid>" \
  "http://localhost:8080/api/v1/meetings/<meeting-uuid>/approve"
```

**Step 3: Test frontend**

- Navigate to `/en/dashboard/meetings`
- Verify calendar view renders
- Verify table view with pagination
- Create a meeting through the modal
- Test conflict detection (book same slot twice)
- Test status transitions from detail page
- Test renter portal meetings view

**Step 4: Final commit**

```bash
git add -A
git commit -m "feat(meetings): complete meetings module with calendar, conflict detection, and notifications"
```

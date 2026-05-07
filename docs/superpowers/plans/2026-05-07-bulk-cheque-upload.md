# Bulk Cheque Upload Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a web-only bulk cheque upload flow on the lease detail page (folder pick → AI extract per image → editable review table → atomic commit) plus a shared `<DueDateDelta>` badge that visualizes the difference between cheque date and due date on existing payment rows too.

**Architecture:**
- Backend reuses the existing `POST /api/v1/cheques/extract` endpoint untouched. Adds one new endpoint `POST /api/v1/leases/{leaseId}/cheques/bulk-attach` backed by a new transactional `PaymentScheduleService.bulkAttachCheques(leaseId, items)` method that mirrors the per-row logic of the existing `collectPayment(...)` (writes cheque columns, flips `PENDING → COLLECTED`, fires per-row `CHEQUE_RECEIVED` event).
- Frontend has a 3-screen flow (Pick → Extract → Review). Auto-mapping (closest cheque-date to due-date, greedy) runs purely client-side. The shared `<DueDateDelta>` component is used in three places: bulk review table, lease detail timeline, global payments page.

**Tech Stack:** Spring Boot 4 + Spring Data JPA + PostgreSQL 16 (Testcontainers for backend tests), Next.js 16 + TypeScript 5 + Tailwind + next-intl, Vitest + Testing Library + MSW for frontend tests.

**Spec:** `docs/superpowers/specs/2026-05-07-bulk-cheque-upload-design.md` (commit `5eee74d`).

---

## File Structure

**Backend (new):**
- `backend/src/main/java/com/datagami/rentaxis/api/dto/BulkAttachChequeItem.java` — single-row payload
- `backend/src/main/java/com/datagami/rentaxis/api/dto/BulkAttachChequesRequest.java` — wraps the list
- `backend/src/main/java/com/datagami/rentaxis/api/dto/BulkAttachChequesResponse.java` — wraps the updated `PaymentScheduleDTO[]`
- `backend/src/main/java/com/datagami/rentaxis/api/dto/BulkAttachErrorRow.java` — `{scheduleId, reason}` for `400` / `409` bodies
- `backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceBulkAttachTest.java`
- `backend/src/test/java/com/datagami/rentaxis/api/LeaseChequeBulkAttachControllerTest.java`

**Backend (modify):**
- `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java` — add `bulkAttachCheques(...)` method
- `backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java` — add the new endpoint (we put it on `LeaseController` because the path is nested under `/api/v1/leases/{leaseId}/...`)

**Web (new):**
- `web/src/components/payments/DueDateDelta.tsx` — shared badge
- `web/src/components/payments/__tests__/DueDateDelta.test.tsx`
- `web/src/components/cheques/autoMapChequesToSchedules.ts` — pure mapping function
- `web/src/components/cheques/__tests__/autoMapChequesToSchedules.test.ts`
- `web/src/components/cheques/useBulkChequeExtract.ts` — hook orchestrating parallel `/extract` calls
- `web/src/components/cheques/BulkChequeUploadFlow.tsx` — the 3-screen wizard component (modal, mirrors `ChequeScanner.tsx` modal pattern)
- `web/src/components/cheques/__tests__/BulkChequeUploadFlow.test.tsx`

**Web (modify):**
- `web/src/app/[locale]/dashboard/leases/[id]/page.tsx` — add "Bulk upload cheques" entry point + render `<DueDateDelta>` on payment timeline rows
- `web/src/app/[locale]/dashboard/finance/payments/page.tsx` — render `<DueDateDelta>` on rows that already show both `dueDate` and `chequeDate` (skip if either column isn't present)
- `web/src/messages/en.json` + `web/src/messages/ar.json` — new `bulkChequeUpload` and `dueDateDelta` namespaces

---

## Phase 1 — Backend bulk-attach endpoint

### Task 1: Bulk-attach DTOs

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/BulkAttachChequeItem.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/BulkAttachChequesRequest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/BulkAttachChequesResponse.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/BulkAttachErrorRow.java`

- [ ] **Step 1: Create `BulkAttachChequeItem`**

```java
package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
public class BulkAttachChequeItem {
    @NotNull
    private UUID scheduleId;

    @NotBlank
    private String chequeNumber;

    @NotNull
    private LocalDate chequeDate;

    @NotBlank
    private String bankName;

    private String payerName;

    @NotBlank
    private String imageUrl;

    @NotBlank
    private String imageBlobPath;

    @NotNull
    private OffsetDateTime imageUploadedAt;
}
```

- [ ] **Step 2: Create `BulkAttachChequesRequest`**

```java
package com.datagami.rentaxis.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BulkAttachChequesRequest {
    @NotEmpty
    @Valid
    private List<BulkAttachChequeItem> items;
}
```

- [ ] **Step 3: Create `BulkAttachErrorRow`**

```java
package com.datagami.rentaxis.api.dto;

import java.util.UUID;

public record BulkAttachErrorRow(UUID scheduleId, String reason) {}
```

- [ ] **Step 4: Create `BulkAttachChequesResponse`**

```java
package com.datagami.rentaxis.api.dto;

import java.util.List;

public record BulkAttachChequesResponse(List<PaymentScheduleDTO> schedules) {}
```

- [ ] **Step 5: Compile**

Run: `cd backend && export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/BulkAttachChequeItem.java \
        backend/src/main/java/com/datagami/rentaxis/api/dto/BulkAttachChequesRequest.java \
        backend/src/main/java/com/datagami/rentaxis/api/dto/BulkAttachChequesResponse.java \
        backend/src/main/java/com/datagami/rentaxis/api/dto/BulkAttachErrorRow.java
git commit -m "feat(api): bulk-attach cheques DTOs"
```

---

### Task 2: PaymentScheduleService.bulkAttachCheques — failing test

**Files:**
- Create: `backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceBulkAttachTest.java`

The test is structured as a service-level integration test against a real Postgres via Testcontainers, mirroring the pattern in `PaymentScheduleServiceChequeImageTest` (already in the repo). It seeds a `LandlordOrg`, `Property`, `Unit`, `Lease`, and three `PaymentSchedule`s, then exercises the new method.

- [ ] **Step 1: Read an existing similar test to mirror its setup helpers**

Run: `cat backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceChequeImageTest.java | head -120`

This shows you how the project seeds tenant context, lease, and schedules. Reuse the same helpers (or copy-paste the seeding block) so this new test fits in.

- [ ] **Step 2: Write the failing test class**

Create `backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceBulkAttachTest.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BulkAttachChequeItem;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class PaymentScheduleServiceBulkAttachTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired PaymentScheduleService service;
    @Autowired PaymentScheduleRepository scheduleRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;

    private UUID tenantId;
    private Lease lease;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("BulkAttach-" + UUID.randomUUID());
        org = orgRepo.save(org);
        tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        User renterUser = new User();
        renterUser.setEmail("renter+" + UUID.randomUUID() + "@test");
        renterUser.setName("Test Renter");
        renterUser.setRole(UserRole.RENTER);
        renterUser.setStatus(UserStatus.ACTIVE);
        renterUser.setPasswordHash("placeholder");
        renterUser.setTenantId(tenantId);
        renterUser = userRepo.save(renterUser);

        Renter renter = new Renter();
        renter.setUserId(renterUser.getId());
        renter.setTenantId(tenantId);
        renter = renterRepo.save(renter);

        Property property = new Property();
        property.setName("Test Property");
        property.setTenantId(tenantId);
        property = propertyRepo.save(property);

        Unit unit = new Unit();
        unit.setProperty(property);
        unit.setUnitNumber("A1");
        unit.setTenantId(tenantId);
        unit = unitRepo.save(unit);

        lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setTenantId(tenantId);
        lease.setStartDate(LocalDate.of(2026, 6, 1));
        lease.setEndDate(LocalDate.of(2027, 5, 31));
        lease.setMonthlyRent(BigDecimal.valueOf(5000));
        lease = leaseRepo.save(lease);

        for (int i = 1; i <= 3; i++) {
            PaymentSchedule ps = new PaymentSchedule();
            ps.setTenantId(tenantId);
            ps.setLease(lease);
            ps.setUnit(unit);
            ps.setProperty(property);
            ps.setInstallmentNumber(i);
            ps.setDueDate(LocalDate.of(2026, 5 + i, 5));
            ps.setAmount(BigDecimal.valueOf(5000));
            ps.setStatus(PaymentStatus.PENDING);
            scheduleRepo.save(ps);
        }
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void bulkAttach_happyPath_attachesAllAndFlipsStatus() {
        List<PaymentSchedule> pending = scheduleRepo.findByLeaseId(lease.getId());
        assertThat(pending).hasSize(3);

        List<BulkAttachChequeItem> items = pending.stream()
                .map(ps -> {
                    BulkAttachChequeItem it = new BulkAttachChequeItem();
                    it.setScheduleId(ps.getId());
                    it.setChequeNumber("CHQ-" + ps.getInstallmentNumber());
                    it.setChequeDate(ps.getDueDate().minusDays(2));
                    it.setBankName("Emirates NBD");
                    it.setPayerName("Test Renter");
                    it.setImageUrl("https://blob.test/a-" + ps.getInstallmentNumber() + ".jpg");
                    it.setImageBlobPath("tenant/" + tenantId + "/cheques/a-" + ps.getInstallmentNumber() + ".jpg");
                    it.setImageUploadedAt(OffsetDateTime.now());
                    return it;
                })
                .toList();

        var result = service.bulkAttachCheques(lease.getId(), items);

        assertThat(result).hasSize(3);
        for (var ps : scheduleRepo.findByLeaseId(lease.getId())) {
            assertThat(ps.getStatus()).isEqualTo(PaymentStatus.COLLECTED);
            assertThat(ps.getChequeNumber()).startsWith("CHQ-");
            assertThat(ps.getChequeImageBlobPath()).isNotNull();
        }
    }

    @Test
    void bulkAttach_scheduleNotPending_throwsAndDoesNotPartiallyApply() {
        List<PaymentSchedule> pending = scheduleRepo.findByLeaseId(lease.getId());
        // Pre-collect the first one so it's not PENDING anymore.
        PaymentSchedule first = pending.get(0);
        first.setStatus(PaymentStatus.COLLECTED);
        first.setChequeNumber("PRE-EXISTING");
        scheduleRepo.save(first);

        List<BulkAttachChequeItem> items = pending.stream()
                .map(ps -> {
                    BulkAttachChequeItem it = new BulkAttachChequeItem();
                    it.setScheduleId(ps.getId());
                    it.setChequeNumber("CHQ-" + ps.getInstallmentNumber());
                    it.setChequeDate(ps.getDueDate());
                    it.setBankName("Emirates NBD");
                    it.setPayerName("Renter");
                    it.setImageUrl("https://blob.test/x.jpg");
                    it.setImageBlobPath("tenant/x.jpg");
                    it.setImageUploadedAt(OffsetDateTime.now());
                    return it;
                })
                .toList();

        assertThatThrownBy(() -> service.bulkAttachCheques(lease.getId(), items))
                .isInstanceOf(BulkAttachValidationException.class);

        // Verify NO partial writes — the two originally-PENDING schedules stay PENDING.
        List<PaymentSchedule> after = scheduleRepo.findByLeaseId(lease.getId());
        long stillPending = after.stream().filter(p -> p.getStatus() == PaymentStatus.PENDING).count();
        assertThat(stillPending).isEqualTo(2);
    }

    @Test
    void bulkAttach_duplicateScheduleIdInRequest_throws() {
        List<PaymentSchedule> pending = scheduleRepo.findByLeaseId(lease.getId());
        UUID dup = pending.get(0).getId();

        List<BulkAttachChequeItem> items = List.of(
                buildItem(dup, "CHQ-A", LocalDate.now()),
                buildItem(dup, "CHQ-B", LocalDate.now())
        );

        assertThatThrownBy(() -> service.bulkAttachCheques(lease.getId(), items))
                .isInstanceOf(BulkAttachValidationException.class);
    }

    @Test
    void bulkAttach_scheduleFromOtherLease_throws() {
        // Build a second lease with one schedule.
        Lease other = new Lease();
        other.setUnit(lease.getUnit());
        other.setRenter(lease.getRenter());
        other.setTenantId(tenantId);
        other.setStartDate(LocalDate.now());
        other.setEndDate(LocalDate.now().plusYears(1));
        other.setMonthlyRent(BigDecimal.valueOf(1000));
        other = leaseRepo.save(other);

        PaymentSchedule otherPs = new PaymentSchedule();
        otherPs.setTenantId(tenantId);
        otherPs.setLease(other);
        otherPs.setUnit(lease.getUnit());
        otherPs.setProperty(lease.getUnit().getProperty());
        otherPs.setInstallmentNumber(1);
        otherPs.setDueDate(LocalDate.now().plusMonths(1));
        otherPs.setAmount(BigDecimal.valueOf(1000));
        otherPs.setStatus(PaymentStatus.PENDING);
        otherPs = scheduleRepo.save(otherPs);

        // Try to attach to our lease using the other lease's schedule id.
        List<BulkAttachChequeItem> items = List.of(buildItem(otherPs.getId(), "CHQ-X", LocalDate.now()));

        assertThatThrownBy(() -> service.bulkAttachCheques(lease.getId(), items))
                .isInstanceOf(BulkAttachValidationException.class);
    }

    private BulkAttachChequeItem buildItem(UUID scheduleId, String num, LocalDate date) {
        BulkAttachChequeItem it = new BulkAttachChequeItem();
        it.setScheduleId(scheduleId);
        it.setChequeNumber(num);
        it.setChequeDate(date);
        it.setBankName("Bank");
        it.setPayerName("Payer");
        it.setImageUrl("https://blob.test/x.jpg");
        it.setImageBlobPath("tenant/x.jpg");
        it.setImageUploadedAt(OffsetDateTime.now());
        return it;
    }
}
```

- [ ] **Step 3: Run the test, expect compile failure**

Run: `cd backend && ./gradlew test --tests PaymentScheduleServiceBulkAttachTest`
Expected: COMPILATION FAILURE — `BulkAttachValidationException`, `service.bulkAttachCheques(...)`, and the new DTO references don't exist yet. That's the right kind of "failing" for TDD here.

---

### Task 3: Implement `PaymentScheduleService.bulkAttachCheques`

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/BulkAttachValidationException.java`

- [ ] **Step 1: Create the exception class**

Create `backend/src/main/java/com/datagami/rentaxis/core/service/BulkAttachValidationException.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BulkAttachErrorRow;
import lombok.Getter;

import java.util.List;

@Getter
public class BulkAttachValidationException extends RuntimeException {
    private final List<BulkAttachErrorRow> rows;
    private final boolean conflict; // true → controller maps to 409, false → 400

    public BulkAttachValidationException(List<BulkAttachErrorRow> rows, boolean conflict) {
        super("Bulk attach validation failed: " + rows);
        this.rows = rows;
        this.conflict = conflict;
    }

    public BulkAttachValidationException(String reason) {
        this(List.of(new BulkAttachErrorRow(null, reason)), false);
    }
}
```

- [ ] **Step 2: Add the service method**

In `PaymentScheduleService.java`, add the imports near the existing imports:

```java
import com.datagami.rentaxis.api.dto.BulkAttachChequeItem;
import com.datagami.rentaxis.api.dto.BulkAttachErrorRow;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
```

Then add this method near `collectPayment(...)` (after line ~317):

```java
    @Transactional
    public List<PaymentScheduleDTO> bulkAttachCheques(UUID leaseId, List<BulkAttachChequeItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BulkAttachValidationException("items must not be empty");
        }

        // Detect duplicate scheduleId / chequeNumber within the request.
        List<BulkAttachErrorRow> errors = new ArrayList<>();
        Set<UUID> seenScheduleIds = new HashSet<>();
        Set<String> seenChequeNumbers = new HashSet<>();
        for (BulkAttachChequeItem it : items) {
            if (!seenScheduleIds.add(it.getScheduleId())) {
                errors.add(new BulkAttachErrorRow(it.getScheduleId(), "duplicate_schedule_id_in_request"));
            }
            if (!seenChequeNumbers.add(it.getChequeNumber())) {
                errors.add(new BulkAttachErrorRow(it.getScheduleId(), "duplicate_cheque_number_in_request"));
            }
        }
        if (!errors.isEmpty()) {
            throw new BulkAttachValidationException(errors, false);
        }

        // Load every targeted schedule in one shot.
        List<UUID> scheduleIds = items.stream().map(BulkAttachChequeItem::getScheduleId).toList();
        List<PaymentSchedule> schedules = paymentScheduleRepository.findAllById(scheduleIds);
        Map<UUID, PaymentSchedule> byId = schedules.stream()
                .collect(Collectors.toMap(PaymentSchedule::getId, s -> s));

        // Validate each row.
        List<BulkAttachErrorRow> notPending = new ArrayList<>();
        List<BulkAttachErrorRow> badRows = new ArrayList<>();
        for (BulkAttachChequeItem it : items) {
            PaymentSchedule ps = byId.get(it.getScheduleId());
            if (ps == null) {
                badRows.add(new BulkAttachErrorRow(it.getScheduleId(), "schedule_not_found"));
                continue;
            }
            if (!ps.getLease().getId().equals(leaseId)) {
                badRows.add(new BulkAttachErrorRow(it.getScheduleId(), "schedule_not_in_lease"));
                continue;
            }
            if (ps.getStatus() != PaymentStatus.PENDING) {
                notPending.add(new BulkAttachErrorRow(it.getScheduleId(), "schedule_not_pending"));
            }
        }
        if (!badRows.isEmpty()) {
            throw new BulkAttachValidationException(badRows, false);
        }
        if (!notPending.isEmpty()) {
            throw new BulkAttachValidationException(notPending, true);
        }

        // Cheque number conflict against other schedules on this lease.
        List<PaymentSchedule> leaseSchedules = paymentScheduleRepository.findByLeaseId(leaseId);
        Set<UUID> targetIds = new HashSet<>(scheduleIds);
        Set<String> existingChequeNumbers = leaseSchedules.stream()
                .filter(ps -> !targetIds.contains(ps.getId()))
                .map(PaymentSchedule::getChequeNumber)
                .filter(n -> n != null && !n.isBlank())
                .collect(Collectors.toSet());
        List<BulkAttachErrorRow> chequeConflicts = items.stream()
                .filter(it -> existingChequeNumbers.contains(it.getChequeNumber()))
                .map(it -> new BulkAttachErrorRow(it.getScheduleId(), "cheque_number_already_used_on_lease"))
                .toList();
        if (!chequeConflicts.isEmpty()) {
            throw new BulkAttachValidationException(chequeConflicts, false);
        }

        // Apply.
        Instant now = Instant.now();
        List<PaymentSchedule> updated = new ArrayList<>(items.size());
        for (BulkAttachChequeItem it : items) {
            PaymentSchedule ps = byId.get(it.getScheduleId());
            ps.setStatus(PaymentStatus.COLLECTED);
            ps.setChequeNumber(it.getChequeNumber());
            ps.setBankName(it.getBankName());
            ps.setPayerName(it.getPayerName());
            ps.setChequeDate(it.getChequeDate());
            ps.setChequeImageUrl(it.getImageUrl());
            ps.setChequeImageBlobPath(it.getImageBlobPath());
            ps.setChequeImageUploadedAt(it.getImageUploadedAt());
            ps.setStatusChangedAt(now);
            updated.add(paymentScheduleRepository.save(ps));
        }

        // Fire one CHEQUE_RECEIVED event per row, mirroring single-cheque collectPayment.
        for (PaymentSchedule saved : updated) {
            events.publishEvent(new EmailEvent(this,
                    EmailEventType.CHEQUE_RECEIVED,
                    saved.getTenantId(),
                    new ChequePayload(
                            saved.getId(),
                            saved.getLease().getId(),
                            saved.getLease().getRenter().getUserId(),
                            null,
                            saved.getInstallmentNumber(),
                            saved.getChequeNumber(),
                            saved.getBankName(),
                            saved.getAmount() != null ? saved.getAmount().toPlainString() + " AED" : null,
                            saved.getDueDate() != null ? saved.getDueDate().toString() : null,
                            null,
                            null
                    ),
                    "CHEQUE_RECEIVED:" + saved.getId()));
        }

        return updated.stream().map(this::mapToDTO).toList();
    }
```

- [ ] **Step 3: Run the test, verify pass**

Run: `cd backend && ./gradlew test --tests PaymentScheduleServiceBulkAttachTest`
Expected: PASS (4 tests).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/PaymentScheduleService.java \
        backend/src/main/java/com/datagami/rentaxis/core/service/BulkAttachValidationException.java \
        backend/src/test/java/com/datagami/rentaxis/core/service/PaymentScheduleServiceBulkAttachTest.java
git commit -m "feat(payments): bulkAttachCheques service method"
```

---

### Task 4: Bulk-attach controller endpoint

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java`
- Create: `backend/src/test/java/com/datagami/rentaxis/api/LeaseChequeBulkAttachControllerTest.java`

- [ ] **Step 1: Write the failing controller test**

Create `backend/src/test/java/com/datagami/rentaxis/api/LeaseChequeBulkAttachControllerTest.java`:

```java
package com.datagami.rentaxis.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.datagami.rentaxis.api.dto.BulkAttachChequeItem;
import com.datagami.rentaxis.api.dto.BulkAttachChequesRequest;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentSchedule;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.PaymentStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class LeaseChequeBulkAttachControllerTest {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired PaymentScheduleRepository scheduleRepo;

    private UUID tenantId;
    private Lease lease;

    @BeforeEach
    void setUp() {
        LandlordOrg org = orgRepo.save(buildOrg());
        tenantId = org.getId();
        TenantContextHolder.setTenantId(tenantId);

        User u = new User();
        u.setEmail("r+" + UUID.randomUUID() + "@test");
        u.setName("Renter");
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("ph");
        u.setTenantId(tenantId);
        u = userRepo.save(u);
        Renter r = new Renter();
        r.setUserId(u.getId());
        r.setTenantId(tenantId);
        r = renterRepo.save(r);

        Property p = new Property();
        p.setName("P");
        p.setTenantId(tenantId);
        p = propertyRepo.save(p);
        Unit unit = new Unit();
        unit.setProperty(p);
        unit.setUnitNumber("A1");
        unit.setTenantId(tenantId);
        unit = unitRepo.save(unit);

        lease = new Lease();
        lease.setUnit(unit);
        lease.setRenter(r);
        lease.setTenantId(tenantId);
        lease.setStartDate(LocalDate.of(2026, 6, 1));
        lease.setEndDate(LocalDate.of(2027, 5, 31));
        lease.setMonthlyRent(BigDecimal.valueOf(5000));
        lease = leaseRepo.save(lease);

        for (int i = 1; i <= 2; i++) {
            PaymentSchedule ps = new PaymentSchedule();
            ps.setTenantId(tenantId);
            ps.setLease(lease);
            ps.setUnit(unit);
            ps.setProperty(p);
            ps.setInstallmentNumber(i);
            ps.setDueDate(LocalDate.of(2026, 5 + i, 5));
            ps.setAmount(BigDecimal.valueOf(5000));
            ps.setStatus(PaymentStatus.PENDING);
            scheduleRepo.save(ps);
        }
    }

    private LandlordOrg buildOrg() {
        LandlordOrg o = new LandlordOrg();
        o.setName("Org-" + UUID.randomUUID());
        return o;
    }

    @Test
    @WithMockUser(roles = "TENANT_ADMIN")
    void happyPath_returns200WithSchedules() throws Exception {
        BulkAttachChequesRequest req = new BulkAttachChequesRequest();
        req.setItems(scheduleRepo.findByLeaseId(lease.getId()).stream()
                .map(ps -> {
                    BulkAttachChequeItem it = new BulkAttachChequeItem();
                    it.setScheduleId(ps.getId());
                    it.setChequeNumber("C-" + ps.getInstallmentNumber());
                    it.setChequeDate(ps.getDueDate());
                    it.setBankName("ENBD");
                    it.setPayerName("R");
                    it.setImageUrl("https://blob/x.jpg");
                    it.setImageBlobPath("t/x.jpg");
                    it.setImageUploadedAt(OffsetDateTime.now());
                    return it;
                })
                .toList());

        mvc.perform(post("/api/v1/leases/" + lease.getId() + "/cheques/bulk-attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedules", org.hamcrest.Matchers.hasSize(2)));
    }

    @Test
    @WithMockUser(roles = "TENANT_ADMIN")
    void scheduleNotPending_returns409() throws Exception {
        // Pre-collect schedule 1 so it's no longer PENDING.
        var schedules = scheduleRepo.findByLeaseId(lease.getId());
        PaymentSchedule first = schedules.get(0);
        first.setStatus(PaymentStatus.COLLECTED);
        scheduleRepo.save(first);

        BulkAttachChequesRequest req = new BulkAttachChequesRequest();
        req.setItems(List.of(buildItem(first.getId(), "C-X", LocalDate.now())));

        mvc.perform(post("/api/v1/leases/" + lease.getId() + "/cheques/bulk-attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }

    @Test
    @WithMockUser(roles = "TENANT_ADMIN")
    void emptyItems_returns400() throws Exception {
        BulkAttachChequesRequest req = new BulkAttachChequesRequest();
        req.setItems(List.of());

        mvc.perform(post("/api/v1/leases/" + lease.getId() + "/cheques/bulk-attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "RENTER")
    void renterRole_returns403() throws Exception {
        BulkAttachChequesRequest req = new BulkAttachChequesRequest();
        req.setItems(List.of(buildItem(UUID.randomUUID(), "C", LocalDate.now())));

        mvc.perform(post("/api/v1/leases/" + lease.getId() + "/cheques/bulk-attach")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    private BulkAttachChequeItem buildItem(UUID scheduleId, String num, LocalDate date) {
        BulkAttachChequeItem it = new BulkAttachChequeItem();
        it.setScheduleId(scheduleId);
        it.setChequeNumber(num);
        it.setChequeDate(date);
        it.setBankName("Bank");
        it.setPayerName("Payer");
        it.setImageUrl("https://blob/x.jpg");
        it.setImageBlobPath("t/x.jpg");
        it.setImageUploadedAt(OffsetDateTime.now());
        return it;
    }
}
```

- [ ] **Step 2: Run, expect compile failure**

Run: `cd backend && ./gradlew test --tests LeaseChequeBulkAttachControllerTest`
Expected: COMPILE FAIL (controller endpoint doesn't exist yet).

- [ ] **Step 3: Add the endpoint to `LeaseController`**

Open `backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java`. Add the imports near the existing ones:

```java
import com.datagami.rentaxis.api.dto.BulkAttachChequesRequest;
import com.datagami.rentaxis.api.dto.BulkAttachChequesResponse;
import com.datagami.rentaxis.core.service.BulkAttachValidationException;
import com.datagami.rentaxis.core.service.PaymentScheduleService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
```

Inject `PaymentScheduleService` (the controller likely uses `@RequiredArgsConstructor`; just add the field):

```java
private final PaymentScheduleService paymentScheduleService;
```

Add the endpoint method:

```java
@PostMapping("/{leaseId}/cheques/bulk-attach")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
public ResponseEntity<BulkAttachChequesResponse> bulkAttachCheques(
        @PathVariable UUID leaseId,
        @Valid @RequestBody BulkAttachChequesRequest request) {
    var schedules = paymentScheduleService.bulkAttachCheques(leaseId, request.getItems());
    return ResponseEntity.ok(new BulkAttachChequesResponse(schedules));
}

@ExceptionHandler(BulkAttachValidationException.class)
public ResponseEntity<Map<String, Object>> handleBulkAttach(BulkAttachValidationException ex) {
    HttpStatus status = ex.isConflict() ? HttpStatus.CONFLICT : HttpStatus.BAD_REQUEST;
    return ResponseEntity.status(status).body(Map.of(
            "error", "validation_failed",
            "rows", ex.getRows()
    ));
}
```

If `Map` and `HttpStatus` aren't already imported, add them.

- [ ] **Step 4: Run the test, verify pass**

Run: `cd backend && ./gradlew test --tests LeaseChequeBulkAttachControllerTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Run the broader backend test suite to ensure nothing regressed**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/LeaseController.java \
        backend/src/test/java/com/datagami/rentaxis/api/LeaseChequeBulkAttachControllerTest.java
git commit -m "feat(api): POST /leases/{id}/cheques/bulk-attach endpoint"
```

---

## Phase 2 — Shared `<DueDateDelta>` badge

### Task 5: Create the `<DueDateDelta>` component with tests

**Files:**
- Create: `web/src/components/payments/DueDateDelta.tsx`
- Create: `web/src/components/payments/__tests__/DueDateDelta.test.tsx`

- [ ] **Step 1: Write the failing test**

Create `web/src/components/payments/__tests__/DueDateDelta.test.tsx`:

```tsx
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import DueDateDelta from "../DueDateDelta";

describe("DueDateDelta", () => {
  it("renders nothing when chequeDate is null", () => {
    const { container } = render(<DueDateDelta dueDate="2026-06-05" chequeDate={null} />);
    expect(container.firstChild).toBeNull();
  });

  it("renders 0d when same day", () => {
    render(<DueDateDelta dueDate="2026-06-05" chequeDate="2026-06-05" />);
    expect(screen.getByText("0d")).toBeInTheDocument();
  });

  it("renders −Nd in green when cheque is before due", () => {
    render(<DueDateDelta dueDate="2026-06-05" chequeDate="2026-06-02" />);
    const el = screen.getByText("−3d");
    expect(el.className).toMatch(/emerald|green/);
  });

  it("renders +Nd in red when cheque is after due", () => {
    render(<DueDateDelta dueDate="2026-06-05" chequeDate="2026-06-08" />);
    const el = screen.getByText("+3d");
    expect(el.className).toMatch(/red|rose/);
  });
});
```

- [ ] **Step 2: Run, expect failure**

Run: `cd web && npx vitest run src/components/payments/__tests__/DueDateDelta.test.tsx`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement the component**

Create `web/src/components/payments/DueDateDelta.tsx`:

```tsx
type Props = {
  dueDate: string | null | undefined;
  chequeDate: string | null | undefined;
  className?: string;
};

function daysBetween(a: string, b: string): number {
  const dayA = Date.UTC(
    Number(a.slice(0, 4)),
    Number(a.slice(5, 7)) - 1,
    Number(a.slice(8, 10))
  );
  const dayB = Date.UTC(
    Number(b.slice(0, 4)),
    Number(b.slice(5, 7)) - 1,
    Number(b.slice(8, 10))
  );
  return Math.round((dayB - dayA) / 86_400_000);
}

export default function DueDateDelta({ dueDate, chequeDate, className }: Props) {
  if (!dueDate || !chequeDate) return null;
  const delta = daysBetween(dueDate, chequeDate); // chequeDate − dueDate
  const isAfter = delta > 0;
  const tone = isAfter
    ? "border-red-300 bg-red-50 text-red-700"
    : "border-emerald-300 bg-emerald-50 text-emerald-700";
  const sign = delta === 0 ? "0d" : delta > 0 ? `+${delta}d` : `−${Math.abs(delta)}d`;
  return (
    <span
      className={
        "inline-flex items-center rounded-full border px-1.5 py-0.5 text-[11px] font-medium " +
        tone +
        (className ? " " + className : "")
      }
      title={`Cheque date is ${delta === 0 ? "exactly" : `${Math.abs(delta)} day${Math.abs(delta) === 1 ? "" : "s"} ${isAfter ? "after" : "before"}`} the due date`}
    >
      {sign}
    </span>
  );
}
```

Note: we use a hand-rolled `daysBetween` rather than `new Date(string)` to avoid timezone drift on date-only strings (`"2026-06-05"` parses to midnight UTC in modern engines, but explicit UTC parsing is safer across all environments).

- [ ] **Step 4: Run, verify pass**

Run: `cd web && npx vitest run src/components/payments/__tests__/DueDateDelta.test.tsx`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/src/components/payments/DueDateDelta.tsx \
        web/src/components/payments/__tests__/DueDateDelta.test.tsx
git commit -m "feat(payments): DueDateDelta badge component"
```

---

### Task 6: Wire `<DueDateDelta>` into the lease detail page

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`

- [ ] **Step 1: Locate the payment schedule rows**

Run: `grep -n "chequeDate\|dueDate" web/src/app/\[locale\]/dashboard/leases/\[id\]/page.tsx | head -20`

This shows you which JSX block renders the payment schedule rows for a lease. There may be multiple — pick the one that displays both `dueDate` and `chequeDate` for each row (typically a payment timeline / table).

- [ ] **Step 2: Import the component and render it next to the cheque date cell**

Add the import near the other component imports at the top:

```tsx
import DueDateDelta from "@/components/payments/DueDateDelta";
```

In the cell that renders the cheque date for each row, render the badge alongside it. Example shape (adapt to whatever the existing JSX looks like — preserve existing layout, only add the badge inline):

```tsx
{ps.chequeDate && (
  <span className="inline-flex items-center gap-1.5">
    <span>{ps.chequeDate}</span>
    <DueDateDelta dueDate={ps.dueDate} chequeDate={ps.chequeDate} />
  </span>
)}
```

If the existing cell uses different field names (e.g., `paymentRow.due_date`), adapt to those — the component cares only about ISO date strings.

- [ ] **Step 3: Smoke build**

Run: `cd web && npm run build`
Expected: build succeeds. Fix any TypeScript / import errors.

- [ ] **Step 4: Commit**

```bash
git add web/src/app/[locale]/dashboard/leases/[id]/page.tsx
git commit -m "feat(leases): show DueDateDelta badge on lease detail timeline"
```

---

### Task 7: Wire `<DueDateDelta>` into the global payments page

**Files:**
- Modify: `web/src/app/[locale]/dashboard/finance/payments/page.tsx`

- [ ] **Step 1: Check whether the table already shows both dates**

Run: `grep -n "dueDate\|chequeDate" web/src/app/\[locale\]/dashboard/finance/payments/page.tsx | head -10`

If the page does NOT already render both `dueDate` and `chequeDate` per row, **stop and skip this task** — adding a column would be unrelated scope. Mark the task `- [x]` with note "skipped — payments table doesn't show chequeDate".

If both ARE rendered, continue.

- [ ] **Step 2: Add the badge next to the cheque-date cell**

Same pattern as Task 6: import `DueDateDelta`, render it next to the cheque date.

```tsx
import DueDateDelta from "@/components/payments/DueDateDelta";
// ...
<DueDateDelta dueDate={row.dueDate} chequeDate={row.chequeDate} />
```

- [ ] **Step 3: Smoke build**

Run: `cd web && npm run build`
Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add web/src/app/[locale]/dashboard/finance/payments/page.tsx
git commit -m "feat(payments): show DueDateDelta badge on global payments page"
```

---

## Phase 3 — Frontend bulk upload flow

### Task 8: `autoMapChequesToSchedules` pure function

**Files:**
- Create: `web/src/components/cheques/autoMapChequesToSchedules.ts`
- Create: `web/src/components/cheques/__tests__/autoMapChequesToSchedules.test.ts`

- [ ] **Step 1: Write failing tests**

Create `web/src/components/cheques/__tests__/autoMapChequesToSchedules.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { autoMapChequesToSchedules } from "../autoMapChequesToSchedules";

const schedules = [
  { id: "s1", dueDate: "2026-06-05" },
  { id: "s2", dueDate: "2026-07-05" },
  { id: "s3", dueDate: "2026-08-05" },
];

describe("autoMapChequesToSchedules", () => {
  it("maps each cheque to its closest due date greedily", () => {
    const items = [
      { id: "i1", chequeDate: "2026-08-04", pinned: false, assignedScheduleId: null },
      { id: "i2", chequeDate: "2026-06-06", pinned: false, assignedScheduleId: null },
      { id: "i3", chequeDate: "2026-07-08", pinned: false, assignedScheduleId: null },
    ];
    const result = autoMapChequesToSchedules(items, schedules);
    expect(result.get("i1")).toBe("s3");
    expect(result.get("i2")).toBe("s1");
    expect(result.get("i3")).toBe("s2");
  });

  it("skips items with no chequeDate", () => {
    const items = [
      { id: "i1", chequeDate: null, pinned: false, assignedScheduleId: null },
      { id: "i2", chequeDate: "2026-06-04", pinned: false, assignedScheduleId: null },
    ];
    const result = autoMapChequesToSchedules(items, schedules);
    expect(result.has("i1")).toBe(false);
    expect(result.get("i2")).toBe("s1");
  });

  it("reserves schedules locked by pinned rows", () => {
    const items = [
      { id: "i1", chequeDate: "2026-06-04", pinned: true, assignedScheduleId: "s1" },
      { id: "i2", chequeDate: "2026-06-06", pinned: false, assignedScheduleId: null },
    ];
    const result = autoMapChequesToSchedules(items, schedules);
    // i1 is pinned, so it isn't in the result map. i2 should NOT take s1.
    expect(result.has("i1")).toBe(false);
    expect(result.get("i2")).toBe("s2");
  });

  it("returns no mapping for cheques when all schedules are taken", () => {
    const items = [
      { id: "i1", chequeDate: "2026-06-04", pinned: false, assignedScheduleId: null },
      { id: "i2", chequeDate: "2026-06-05", pinned: false, assignedScheduleId: null },
      { id: "i3", chequeDate: "2026-06-06", pinned: false, assignedScheduleId: null },
      { id: "i4", chequeDate: "2026-06-07", pinned: false, assignedScheduleId: null },
    ];
    const result = autoMapChequesToSchedules(items, schedules);
    expect(result.size).toBe(3); // 4th cheque is unmapped
  });
});
```

- [ ] **Step 2: Run, expect failure**

Run: `cd web && npx vitest run src/components/cheques/__tests__/autoMapChequesToSchedules.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement**

Create `web/src/components/cheques/autoMapChequesToSchedules.ts`:

```ts
export type AutoMapItem = {
  id: string;
  chequeDate: string | null;
  pinned: boolean;
  assignedScheduleId: string | null;
};

export type AutoMapSchedule = {
  id: string;
  dueDate: string;
};

function dayDistance(a: string, b: string): number {
  const ay = Number(a.slice(0, 4));
  const am = Number(a.slice(5, 7)) - 1;
  const ad = Number(a.slice(8, 10));
  const by = Number(b.slice(0, 4));
  const bm = Number(b.slice(5, 7)) - 1;
  const bd = Number(b.slice(8, 10));
  return Math.abs(Math.round((Date.UTC(ay, am, ad) - Date.UTC(by, bm, bd)) / 86_400_000));
}

export function autoMapChequesToSchedules(
  items: AutoMapItem[],
  schedules: AutoMapSchedule[]
): Map<string, string> {
  // Reserve schedules locked by pinned rows.
  const remaining = new Set(schedules.map(s => s.id));
  for (const item of items) {
    if (item.pinned && item.assignedScheduleId) {
      remaining.delete(item.assignedScheduleId);
    }
  }

  // Build all (item, schedule) pairs for non-pinned, dated items.
  const pairs: { itemId: string; scheduleId: string; dist: number }[] = [];
  for (const item of items) {
    if (item.pinned || !item.chequeDate) continue;
    for (const s of schedules) {
      if (!remaining.has(s.id)) continue;
      pairs.push({ itemId: item.id, scheduleId: s.id, dist: dayDistance(item.chequeDate, s.dueDate) });
    }
  }
  pairs.sort((a, b) => a.dist - b.dist);

  const assigned = new Set<string>();
  const result = new Map<string, string>();
  for (const p of pairs) {
    if (assigned.has(p.itemId) || !remaining.has(p.scheduleId)) continue;
    result.set(p.itemId, p.scheduleId);
    assigned.add(p.itemId);
    remaining.delete(p.scheduleId);
  }
  return result;
}
```

- [ ] **Step 4: Run, verify pass**

Run: `cd web && npx vitest run src/components/cheques/__tests__/autoMapChequesToSchedules.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add web/src/components/cheques/autoMapChequesToSchedules.ts \
        web/src/components/cheques/__tests__/autoMapChequesToSchedules.test.ts
git commit -m "feat(cheques): autoMapChequesToSchedules greedy mapping function"
```

---

### Task 9: `useBulkChequeExtract` hook

**Files:**
- Create: `web/src/components/cheques/useBulkChequeExtract.ts`

This hook orchestrates parallel extract calls (capped at 4 concurrent) and maintains per-row state. Trivial enough that we'll cover it inside the higher-level `BulkChequeUploadFlow.test.tsx` rather than testing the hook in isolation.

- [ ] **Step 1: Write the hook**

Create `web/src/components/cheques/useBulkChequeExtract.ts`:

```ts
"use client";

import { useCallback, useState } from "react";
import type { ChequeExtractionResponse } from "@/types/cheque";

export type BulkExtractItemStatus = "pending" | "extracting" | "extracted" | "failed";

export type BulkExtractItem = {
  id: string;
  file: File;
  previewUrl: string;
  status: BulkExtractItemStatus;
  response: ChequeExtractionResponse | null;
  error: string | null;
};

const MAX_CONCURRENT = 4;
const ALLOWED_TYPES = new Set([
  "image/jpeg",
  "image/jpg",
  "image/png",
  "image/heic",
  "image/heif",
]);

async function extractOne(file: File): Promise<ChequeExtractionResponse> {
  const form = new FormData();
  form.append("file", file);
  const res = await fetch("/api/proxy/v1/cheques/extract", { method: "POST", body: form });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.error ?? `Upload failed (${res.status})`);
  }
  return (await res.json()) as ChequeExtractionResponse;
}

export function buildItemsFromFiles(files: File[]): { items: BulkExtractItem[]; rejectedCount: number } {
  let rejectedCount = 0;
  const items: BulkExtractItem[] = [];
  for (const file of files) {
    if (!ALLOWED_TYPES.has(file.type.toLowerCase())) {
      rejectedCount++;
      continue;
    }
    items.push({
      id: crypto.randomUUID(),
      file,
      previewUrl: URL.createObjectURL(file),
      status: "pending",
      response: null,
      error: null,
    });
  }
  return { items, rejectedCount };
}

export function useBulkChequeExtract() {
  const [items, setItems] = useState<BulkExtractItem[]>([]);
  const [running, setRunning] = useState(false);

  const setItem = (id: string, patch: Partial<BulkExtractItem>) => {
    setItems(prev => prev.map(it => (it.id === id ? { ...it, ...patch } : it)));
  };

  const start = useCallback(async (toExtract: BulkExtractItem[]) => {
    setRunning(true);
    const queue = [...toExtract];
    const workers: Promise<void>[] = [];

    const next = async (): Promise<void> => {
      const job = queue.shift();
      if (!job) return;
      setItem(job.id, { status: "extracting" });
      try {
        const response = await extractOne(job.file);
        setItem(job.id, { status: "extracted", response });
      } catch (e) {
        setItem(job.id, { status: "failed", error: e instanceof Error ? e.message : "Failed" });
      }
      return next();
    };

    for (let i = 0; i < MAX_CONCURRENT; i++) workers.push(next());
    await Promise.all(workers);
    setRunning(false);
  }, []);

  const retry = useCallback(async (id: string) => {
    const target = items.find(it => it.id === id);
    if (!target) return;
    setItem(id, { status: "extracting", error: null });
    try {
      const response = await extractOne(target.file);
      setItem(id, { status: "extracted", response });
    } catch (e) {
      setItem(id, { status: "failed", error: e instanceof Error ? e.message : "Failed" });
    }
  }, [items]);

  const removeItem = (id: string) => {
    setItems(prev => {
      const target = prev.find(it => it.id === id);
      if (target) URL.revokeObjectURL(target.previewUrl);
      return prev.filter(it => it.id !== id);
    });
  };

  const reset = () => {
    setItems(prev => {
      prev.forEach(it => URL.revokeObjectURL(it.previewUrl));
      return [];
    });
  };

  return { items, setItems, running, start, retry, removeItem, reset };
}
```

- [ ] **Step 2: Smoke build**

Run: `cd web && npm run build`
Expected: build succeeds.

- [ ] **Step 3: Commit**

```bash
git add web/src/components/cheques/useBulkChequeExtract.ts
git commit -m "feat(cheques): useBulkChequeExtract hook (parallel extract, retry, remove)"
```

---

### Task 10: `BulkChequeUploadFlow` component (Pick → Extract → Review)

**Files:**
- Create: `web/src/components/cheques/BulkChequeUploadFlow.tsx`

This is the main UI piece. Modal pattern mirrors `ChequeScanner.tsx` so the visual language stays consistent. We split internal helpers as functions inside this same file (review row, footer, etc.) to keep the file self-contained — tests in Task 11 cover behavior end-to-end.

- [ ] **Step 1: Create the component**

Create `web/src/components/cheques/BulkChequeUploadFlow.tsx`:

```tsx
"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import Image from "next/image";
import { Camera, Loader2, X, Check, AlertTriangle, Trash2, Pin } from "lucide-react";
import { useTranslations } from "next-intl";
import { autoMapChequesToSchedules } from "./autoMapChequesToSchedules";
import { useBulkChequeExtract, buildItemsFromFiles, type BulkExtractItem } from "./useBulkChequeExtract";
import DueDateDelta from "@/components/payments/DueDateDelta";

type Schedule = {
  id: string;
  installmentNumber: number;
  dueDate: string;
  amount: string | number;
  status: string; // "PENDING" only enters the dropdown
};

type Props = {
  leaseId: string;
  schedules: Schedule[]; // ALL schedules for the lease, server-fetched
  onSuccess: () => void; // called after successful bulk-attach
  onClose: () => void;
};

type RowState = {
  itemId: string;
  chequeNumber: string;
  bankName: string;
  payerName: string;
  chequeDate: string | null;
  scheduleId: string | null;
  pinned: boolean;
};

type Step = 1 | 2 | 3;

export default function BulkChequeUploadFlow({ leaseId, schedules, onSuccess, onClose }: Props) {
  const t = useTranslations("bulkChequeUpload");
  const inputRef = useRef<HTMLInputElement | null>(null);
  const extract = useBulkChequeExtract();
  const [step, setStep] = useState<Step>(1);
  const [rows, setRows] = useState<RowState[]>([]);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [rejectedCount, setRejectedCount] = useState(0);

  const pendingSchedules = useMemo(
    () => schedules.filter(s => s.status === "PENDING").sort((a, b) => a.installmentNumber - b.installmentNumber),
    [schedules]
  );

  useEffect(() => () => extract.reset(), []); // cleanup blob URLs on unmount

  const onPick = (files: FileList | null) => {
    if (!files) return;
    const built = buildItemsFromFiles(Array.from(files));
    setRejectedCount(built.rejectedCount);
    extract.setItems(built.items);
  };

  const goExtract = async () => {
    setStep(2);
    await extract.start(extract.items);
    // Build initial rows from extracted items.
    const initialRows = extract.items.map<RowState>(it => {
      const ex = it.response?.extracted;
      return {
        itemId: it.id,
        chequeNumber: ex?.chequeNumber ?? "",
        bankName: ex?.bankName ?? "",
        payerName: ex?.payerName ?? "",
        chequeDate: ex?.chequeDate ?? null,
        scheduleId: null,
        pinned: false,
      };
    });
    const map = autoMapChequesToSchedules(
      initialRows.map(r => ({ id: r.itemId, chequeDate: r.chequeDate, pinned: r.pinned, assignedScheduleId: r.scheduleId })),
      pendingSchedules.map(s => ({ id: s.id, dueDate: s.dueDate }))
    );
    setRows(initialRows.map(r => ({ ...r, scheduleId: map.get(r.itemId) ?? null })));
    setStep(3);
  };

  const updateRow = (itemId: string, patch: Partial<RowState>) => {
    setRows(prev => {
      const next = prev.map(r => (r.itemId === itemId ? { ...r, ...patch } : r));
      // If this was a non-pin date change, re-run auto-map for non-pinned rows.
      if ("chequeDate" in patch && !next.find(r => r.itemId === itemId)?.pinned) {
        const map = autoMapChequesToSchedules(
          next.map(r => ({ id: r.itemId, chequeDate: r.chequeDate, pinned: r.pinned, assignedScheduleId: r.scheduleId })),
          pendingSchedules.map(s => ({ id: s.id, dueDate: s.dueDate }))
        );
        return next.map(r => (r.pinned ? r : { ...r, scheduleId: map.get(r.itemId) ?? null }));
      }
      return next;
    });
  };

  const pickSchedule = (itemId: string, scheduleId: string | null) => {
    setRows(prev => prev.map(r => (r.itemId === itemId ? { ...r, scheduleId, pinned: scheduleId !== null } : r)));
  };

  const togglePin = (itemId: string) => {
    setRows(prev => prev.map(r => (r.itemId === itemId ? { ...r, pinned: !r.pinned } : r)));
  };

  const removeRow = (itemId: string) => {
    extract.removeItem(itemId);
    setRows(prev => prev.filter(r => r.itemId !== itemId));
  };

  const counts = useMemo(() => {
    let needsDate = 0;
    let noSchedule = 0;
    let duplicateNumber = 0;
    const numberSeen = new Map<string, number>();
    for (const r of rows) {
      if (!r.chequeDate) needsDate++;
      if (!r.scheduleId) noSchedule++;
      if (r.chequeNumber.trim()) numberSeen.set(r.chequeNumber.trim(), (numberSeen.get(r.chequeNumber.trim()) ?? 0) + 1);
    }
    for (const v of numberSeen.values()) if (v > 1) duplicateNumber += v;
    const ready = rows.length - needsDate - noSchedule - duplicateNumber;
    return { needsDate, noSchedule, duplicateNumber, ready };
  }, [rows]);

  const canApprove =
    rows.length > 0 &&
    counts.needsDate === 0 &&
    counts.noSchedule === 0 &&
    counts.duplicateNumber === 0 &&
    rows.every(r => r.chequeNumber.trim() && r.bankName.trim());

  const approve = async () => {
    setSubmitting(true);
    setSubmitError(null);
    try {
      const items = rows.map(r => {
        const ex = extract.items.find(it => it.id === r.itemId);
        return {
          scheduleId: r.scheduleId,
          chequeNumber: r.chequeNumber.trim(),
          chequeDate: r.chequeDate,
          bankName: r.bankName.trim(),
          payerName: r.payerName.trim() || null,
          imageUrl: ex?.response?.image.url,
          imageBlobPath: ex?.response?.image.blobPath,
          imageUploadedAt: ex?.response?.image.uploadedAt,
        };
      });
      const res = await fetch(`/api/proxy/v1/leases/${leaseId}/cheques/bulk-attach`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ items }),
      });
      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        if (Array.isArray(body.rows)) {
          setSubmitError(t("rowConflictError"));
        } else {
          setSubmitError(t("genericError"));
        }
        return;
      }
      onSuccess();
    } catch {
      setSubmitError(t("networkError"));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4">
      <div className="w-full max-w-6xl rounded-xl border border-border bg-background shadow-lg">
        <div className="flex items-start justify-between border-b border-border px-5 py-4">
          <div>
            <p className="text-[11px] uppercase tracking-wider text-muted">{t("breadcrumb")}</p>
            <h3 className="text-base font-semibold">{t("title")}</h3>
          </div>
          <button type="button" onClick={onClose} className="rounded p-1 text-muted hover:bg-input/40">
            <X size={16} />
          </button>
        </div>

        <input
          ref={inputRef}
          type="file"
          // @ts-expect-error webkitdirectory is non-standard but supported
          webkitdirectory="true"
          multiple
          accept="image/*"
          className="hidden"
          onChange={(e) => onPick(e.target.files)}
        />

        <div className="px-5 py-4">
          {step === 1 && (
            <div className="grid gap-4">
              <button
                type="button"
                onClick={() => inputRef.current?.click()}
                className="flex min-h-[200px] flex-col items-center justify-center rounded-lg border border-dashed border-border bg-input/20 px-6 py-8 text-center hover:border-primary/60"
              >
                <Camera size={20} className="mb-2 text-muted" />
                <p className="text-sm font-medium">{t("pickFolder")}</p>
                <p className="mt-1 text-xs text-muted">{t("pickHint")}</p>
              </button>
              {extract.items.length > 0 && (
                <>
                  <p className="text-xs text-muted">
                    {t("selectedCount", { n: extract.items.length })}
                    {rejectedCount > 0 ? ` · ${t("rejectedCount", { n: rejectedCount })}` : ""}
                  </p>
                  <div className="grid grid-cols-4 gap-2 sm:grid-cols-6">
                    {extract.items.map(it => (
                      <div key={it.id} className="relative">
                        <Image src={it.previewUrl} alt="" width={120} height={80} unoptimized className="h-20 w-full rounded object-cover" />
                        <button
                          type="button"
                          aria-label={t("removeImage")}
                          onClick={() => extract.removeItem(it.id)}
                          className="absolute right-1 top-1 rounded-full bg-black/60 p-1 text-white"
                        >
                          <Trash2 size={10} />
                        </button>
                      </div>
                    ))}
                  </div>
                  <div className="flex justify-end">
                    <button
                      type="button"
                      onClick={() => void goExtract()}
                      className="rounded bg-primary px-3 py-1 text-xs font-semibold text-primary-foreground"
                    >
                      {t("continueToExtract")}
                    </button>
                  </div>
                </>
              )}
            </div>
          )}

          {step === 2 && (
            <div className="flex flex-col items-center gap-3 py-10">
              <Loader2 size={20} className="animate-spin text-primary" />
              <p className="text-sm">{t("extractingCount", {
                done: extract.items.filter(it => it.status === "extracted" || it.status === "failed").length,
                total: extract.items.length
              })}</p>
            </div>
          )}

          {step === 3 && (
            <div className="space-y-3">
              <table className="w-full text-xs">
                <thead className="text-left text-muted">
                  <tr>
                    <th className="py-1">{t("colImage")}</th>
                    <th>{t("colChequeNumber")}</th>
                    <th>{t("colBank")}</th>
                    <th>{t("colPayer")}</th>
                    <th>{t("colChequeDate")}</th>
                    <th>{t("colInstallment")}</th>
                    <th>Δ</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map(row => {
                    const item = extract.items.find(it => it.id === row.itemId);
                    if (!item) return null;
                    const sched = pendingSchedules.find(s => s.id === row.scheduleId) ?? null;
                    const usedSchedIds = new Set(rows.filter(r => r.itemId !== row.itemId && r.scheduleId).map(r => r.scheduleId));
                    return (
                      <tr key={row.itemId} className="border-t border-border align-top">
                        <td className="py-1 pr-2">
                          <a href={item.response?.image.url ?? "#"} target="_blank" rel="noreferrer">
                            <Image src={item.previewUrl} alt="" width={64} height={48} unoptimized className="h-12 w-16 rounded object-cover" />
                          </a>
                          {item.status === "failed" && (
                            <p className="mt-1 text-[10px] text-red-700">
                              <AlertTriangle size={10} className="inline" /> {t("extractionFailed")}
                            </p>
                          )}
                        </td>
                        <td className="pr-2">
                          <input
                            value={row.chequeNumber}
                            onChange={e => updateRow(row.itemId, { chequeNumber: e.target.value })}
                            className="w-28 rounded border border-border px-1 py-0.5"
                          />
                        </td>
                        <td className="pr-2">
                          <input
                            value={row.bankName}
                            onChange={e => updateRow(row.itemId, { bankName: e.target.value })}
                            className="w-32 rounded border border-border px-1 py-0.5"
                          />
                        </td>
                        <td className="pr-2">
                          <input
                            value={row.payerName}
                            onChange={e => updateRow(row.itemId, { payerName: e.target.value })}
                            className="w-32 rounded border border-border px-1 py-0.5"
                          />
                        </td>
                        <td className="pr-2">
                          <input
                            type="date"
                            value={row.chequeDate ?? ""}
                            onChange={e => updateRow(row.itemId, { chequeDate: e.target.value || null })}
                            className="rounded border border-border px-1 py-0.5"
                          />
                        </td>
                        <td className="pr-2">
                          <select
                            value={row.scheduleId ?? ""}
                            onChange={e => pickSchedule(row.itemId, e.target.value || null)}
                            className="rounded border border-border px-1 py-0.5"
                          >
                            <option value="">{t("pickInstallment")}</option>
                            {pendingSchedules
                              .filter(s => !usedSchedIds.has(s.id) || s.id === row.scheduleId)
                              .map(s => (
                                <option key={s.id} value={s.id}>
                                  #{s.installmentNumber} · {s.dueDate}
                                </option>
                              ))}
                          </select>
                        </td>
                        <td className="pr-2">
                          {sched && row.chequeDate && (
                            <DueDateDelta dueDate={sched.dueDate} chequeDate={row.chequeDate} />
                          )}
                        </td>
                        <td className="pr-2 text-right">
                          <button
                            type="button"
                            onClick={() => togglePin(row.itemId)}
                            aria-label={t("pinRow")}
                            className={"mr-1 rounded p-1 " + (row.pinned ? "text-primary" : "text-muted")}
                          >
                            <Pin size={12} />
                          </button>
                          <button
                            type="button"
                            onClick={() => removeRow(row.itemId)}
                            aria-label={t("removeRow")}
                            className="rounded p-1 text-muted hover:bg-input/40"
                          >
                            <Trash2 size={12} />
                          </button>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>

              <div className="flex items-center justify-between text-xs">
                <p className="text-muted">
                  {t("statusCounts", {
                    total: rows.length,
                    needsDate: counts.needsDate,
                    noSchedule: counts.noSchedule,
                    duplicate: counts.duplicateNumber,
                  })}
                </p>
                {submitError && <span className="text-red-700">{submitError}</span>}
                <button
                  type="button"
                  disabled={!canApprove || submitting}
                  onClick={() => void approve()}
                  className="rounded bg-primary px-3 py-1 text-xs font-semibold text-primary-foreground disabled:opacity-50"
                >
                  {submitting ? <Loader2 size={12} className="inline animate-spin" /> : <Check size={12} className="inline" />}{" "}
                  {t("approveAll", { ready: counts.ready, total: rows.length })}
                </button>
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 2: Smoke build**

Run: `cd web && npm run build`
Expected: build succeeds.

- [ ] **Step 3: Commit**

```bash
git add web/src/components/cheques/BulkChequeUploadFlow.tsx
git commit -m "feat(cheques): BulkChequeUploadFlow component (pick → extract → review)"
```

---

### Task 11: BulkChequeUploadFlow integration test

**Files:**
- Create: `web/src/components/cheques/__tests__/BulkChequeUploadFlow.test.tsx`

- [ ] **Step 1: Check that MSW is already configured for the project**

Run: `find web -name "msw*" -o -name "vitest.setup*" 2>/dev/null | head -5`

The repo's existing `ChequeScanner.test.tsx` mocks `fetch` directly — mirror that approach for simplicity (avoid adding MSW if it's not already there).

Run: `head -40 web/src/components/cheques/__tests__/ChequeScanner.test.tsx`

This shows the mocking pattern — copy it.

- [ ] **Step 2: Write the test**

Create `web/src/components/cheques/__tests__/BulkChequeUploadFlow.test.tsx`:

```tsx
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach, afterEach } from "vitest";
import BulkChequeUploadFlow from "../BulkChequeUploadFlow";

// Mock next-intl's useTranslations to return the key directly.
vi.mock("next-intl", () => ({
  useTranslations: () => (key: string, vars?: Record<string, unknown>) => {
    if (vars) return `${key}:${JSON.stringify(vars)}`;
    return key;
  },
}));

vi.mock("next/image", () => ({
  default: (props: any) => {
    // eslint-disable-next-line @next/next/no-img-element, jsx-a11y/alt-text
    return <img {...props} />;
  },
}));

const schedules = [
  { id: "s1", installmentNumber: 1, dueDate: "2026-06-05", amount: 5000, status: "PENDING" },
  { id: "s2", installmentNumber: 2, dueDate: "2026-07-05", amount: 5000, status: "PENDING" },
];

// File and crypto helpers.
function makeFile(name: string): File {
  return new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], name, { type: "image/png" });
}

beforeEach(() => {
  // jsdom doesn't ship URL.createObjectURL.
  // @ts-expect-error
  global.URL.createObjectURL = vi.fn(() => "blob:mock");
  // @ts-expect-error
  global.URL.revokeObjectURL = vi.fn();
  if (!("randomUUID" in (global.crypto ?? {}))) {
    // @ts-expect-error
    global.crypto = { ...global.crypto, randomUUID: () => `id-${Math.random().toString(36).slice(2)}` };
  }
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("BulkChequeUploadFlow", () => {
  it("auto-maps closest cheque date to due date and approves successfully", async () => {
    const fetchMock = vi.fn()
      // Two /extract calls:
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          image: { url: "u1", blobPath: "b1", uploadedAt: "2026-05-07T00:00:00Z" },
          extracted: { chequeNumber: "C-1", bankName: "ENBD", payerName: "R", chequeDate: "2026-06-04", confidence: "HIGH" },
          warnings: [],
        }),
      })
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({
          image: { url: "u2", blobPath: "b2", uploadedAt: "2026-05-07T00:00:01Z" },
          extracted: { chequeNumber: "C-2", bankName: "ENBD", payerName: "R", chequeDate: "2026-07-04", confidence: "HIGH" },
          warnings: [],
        }),
      })
      // bulk-attach call:
      .mockResolvedValueOnce({
        ok: true,
        json: async () => ({ schedules: [] }),
      });
    // @ts-expect-error
    global.fetch = fetchMock;

    const onSuccess = vi.fn();
    render(<BulkChequeUploadFlow leaseId="L1" schedules={schedules} onSuccess={onSuccess} onClose={() => {}} />);

    // Simulate folder pick.
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [makeFile("c1.png"), makeFile("c2.png")] });
    fireEvent.change(input);

    fireEvent.click(screen.getByText("continueToExtract"));

    await waitFor(() => screen.getByText("colChequeNumber"));

    // Both rows should auto-map: C-1 → s1, C-2 → s2.
    const selects = document.querySelectorAll("select");
    expect((selects[0] as HTMLSelectElement).value).toBe("s1");
    expect((selects[1] as HTMLSelectElement).value).toBe("s2");

    // Click approve.
    fireEvent.click(screen.getByText(/^approveAll/));

    await waitFor(() => expect(onSuccess).toHaveBeenCalled());

    // bulk-attach was the 3rd call.
    const lastCall = fetchMock.mock.calls[2];
    expect(lastCall[0]).toBe("/api/proxy/v1/leases/L1/cheques/bulk-attach");
  });

  it("disables approve when a row is missing a schedule", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        image: { url: "u1", blobPath: "b1", uploadedAt: "2026-05-07T00:00:00Z" },
        extracted: { chequeNumber: "C-1", bankName: "ENBD", payerName: "R", chequeDate: null, confidence: "LOW" },
        warnings: [],
      }),
    });
    // @ts-expect-error
    global.fetch = fetchMock;

    render(<BulkChequeUploadFlow leaseId="L1" schedules={schedules} onSuccess={() => {}} onClose={() => {}} />);
    const input = document.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, "files", { value: [makeFile("x.png")] });
    fireEvent.change(input);
    fireEvent.click(screen.getByText("continueToExtract"));

    await waitFor(() => screen.getByText(/^approveAll/));
    const approve = screen.getByText(/^approveAll/) as HTMLButtonElement;
    expect(approve.closest("button")).toBeDisabled();
  });
});
```

- [ ] **Step 3: Run, verify pass**

Run: `cd web && npx vitest run src/components/cheques/__tests__/BulkChequeUploadFlow.test.tsx`
Expected: PASS (2 tests).

- [ ] **Step 4: Commit**

```bash
git add web/src/components/cheques/__tests__/BulkChequeUploadFlow.test.tsx
git commit -m "test(cheques): BulkChequeUploadFlow integration tests"
```

---

### Task 12: Add the entry point on the lease detail page

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`

- [ ] **Step 1: Read the current page header / actions area**

Run: `head -120 web/src/app/\[locale\]/dashboard/leases/\[id\]/page.tsx`

Find where existing lease-level actions are rendered (e.g., next to the lease title or in an actions toolbar) and where the lease's payment schedules are loaded into a state variable.

- [ ] **Step 2: Add the button + modal mount**

Inside the page component, near the top of the body imports section, add:

```tsx
import BulkChequeUploadFlow from "@/components/cheques/BulkChequeUploadFlow";
import { useState } from "react";
```

In the component body (assuming the schedules array is already available as e.g. `schedules`):

```tsx
const [bulkOpen, setBulkOpen] = useState(false);
const hasPending = schedules.some(s => s.status === "PENDING");
```

Render the trigger button in the actions toolbar (paste this near the existing "Collect" action):

```tsx
<button
  type="button"
  disabled={!hasPending}
  onClick={() => setBulkOpen(true)}
  className="inline-flex items-center gap-1 rounded border border-border px-2 py-1 text-xs hover:bg-input/40 disabled:opacity-50"
>
  Bulk upload cheques
</button>

{bulkOpen && (
  <BulkChequeUploadFlow
    leaseId={leaseId}
    schedules={schedules}
    onClose={() => setBulkOpen(false)}
    onSuccess={() => {
      setBulkOpen(false);
      // refresh the schedules — call whatever fetch / SWR mutate / router.refresh()
      // pattern this page already uses for the existing collect action.
      window.location.reload();
    }}
  />
)}
```

If the page already uses `router.refresh()` or a SWR `mutate(...)` for similar actions (the existing single-cheque collect path will tell you), use the same pattern instead of `window.location.reload()`.

- [ ] **Step 3: Smoke build**

Run: `cd web && npm run build`
Expected: build succeeds.

- [ ] **Step 4: Manual verification in dev**

```bash
cd web && npm run dev
```

Open the lease detail page on a lease with PENDING installments, click "Bulk upload cheques", verify the modal opens and the folder picker fires.

- [ ] **Step 5: Commit**

```bash
git add web/src/app/[locale]/dashboard/leases/[id]/page.tsx
git commit -m "feat(leases): bulk cheque upload entry point on lease detail"
```

---

### Task 13: i18n strings (English + Arabic)

**Files:**
- Modify: `web/src/messages/en.json`
- Modify: `web/src/messages/ar.json`

- [ ] **Step 1: Add the `bulkChequeUpload` namespace to `en.json`**

Append a new key block:

```json
"bulkChequeUpload": {
  "breadcrumb": "Cheques · Bulk upload",
  "title": "Upload cheques in bulk",
  "pickFolder": "Choose a folder of cheque images",
  "pickHint": "Pick a folder containing all post-dated cheques for this lease.",
  "selectedCount": "{n} images selected",
  "rejectedCount": "{n} files were not images and were skipped",
  "removeImage": "Remove image",
  "continueToExtract": "Extract cheques",
  "extractingCount": "Extracting {done} of {total}…",
  "extractionFailed": "Extraction failed — fill in or remove",
  "colImage": "Image",
  "colChequeNumber": "Cheque #",
  "colBank": "Bank",
  "colPayer": "Payer",
  "colChequeDate": "Cheque date",
  "colInstallment": "Installment",
  "pickInstallment": "Pick an installment",
  "pinRow": "Pin / unpin assignment",
  "removeRow": "Remove this row",
  "statusCounts": "{total} selected · {needsDate} need date · {noSchedule} no installment · {duplicate} duplicate cheque #",
  "approveAll": "Approve all ({ready}/{total})",
  "rowConflictError": "Some rows could not be saved (conflict). Reload and try again.",
  "networkError": "Network error. Please try again.",
  "genericError": "Could not save. Please try again."
}
```

- [ ] **Step 2: Add Arabic translations to `ar.json`**

Mirror the same keys with Arabic strings. Examples:

```json
"bulkChequeUpload": {
  "breadcrumb": "الشيكات · رفع جماعي",
  "title": "رفع الشيكات بالجملة",
  "pickFolder": "اختر مجلد صور الشيكات",
  "pickHint": "اختر المجلد الذي يحتوي على جميع الشيكات المؤجلة لهذا العقد.",
  "selectedCount": "{n} صورة مختارة",
  "rejectedCount": "{n} ملفات لم تكن صورًا وتم تجاهلها",
  "removeImage": "إزالة الصورة",
  "continueToExtract": "استخراج الشيكات",
  "extractingCount": "جارٍ الاستخراج {done} من {total}…",
  "extractionFailed": "فشل الاستخراج — املأ الحقول أو احذف",
  "colImage": "الصورة",
  "colChequeNumber": "رقم الشيك",
  "colBank": "البنك",
  "colPayer": "الساحب",
  "colChequeDate": "تاريخ الشيك",
  "colInstallment": "القسط",
  "pickInstallment": "اختر القسط",
  "pinRow": "تثبيت / إلغاء تثبيت التعيين",
  "removeRow": "إزالة هذا الصف",
  "statusCounts": "{total} مختار · {needsDate} يحتاج تاريخ · {noSchedule} بدون قسط · {duplicate} رقم شيك مكرر",
  "approveAll": "اعتماد الكل ({ready}/{total})",
  "rowConflictError": "تعذر حفظ بعض الصفوف (تعارض). أعد التحميل وحاول مجددًا.",
  "networkError": "خطأ في الشبكة. يرجى المحاولة مرة أخرى.",
  "genericError": "تعذر الحفظ. حاول مرة أخرى."
}
```

- [ ] **Step 3: Smoke build to ensure JSON is valid**

Run: `cd web && npm run build`
Expected: build succeeds.

- [ ] **Step 4: Commit**

```bash
git add web/src/messages/en.json web/src/messages/ar.json
git commit -m "feat(i18n): bulk cheque upload strings (EN + AR)"
```

---

## Phase 4 — Manual smoke + ship

### Task 14: End-to-end smoke on local stack

**Files:** none

- [ ] **Step 1: Bring up the full stack**

```bash
docker compose up -d
```

- [ ] **Step 2: Seed a test lease with 6 PENDING installments**

Either via the existing lease-creation UI on the web dashboard, or via the API. Note the lease ID.

- [ ] **Step 3: Drop 6 cheque images into a single folder on disk**

Any 6 distinct cheque-like images. The extractor doesn't need genuine cheques to return *some* result, but to actually exercise the AI path use real-looking test cheque images if available.

- [ ] **Step 4: Run the bulk flow**

1. Navigate to the lease detail page.
2. Click "Bulk upload cheques".
3. Pick the folder. Verify all 6 thumbnails appear.
4. Click Extract. Verify the `done/total` counter increments.
5. On the review screen verify auto-mapping: cheques sorted by extracted date should map to installments by closest due date.
6. Verify the Δ column shows green or red badges.
7. Approve all.
8. After the toast, verify all 6 schedules show `COLLECTED` on the lease detail page with the new badge visible.

- [ ] **Step 5: Verify outbox**

```bash
docker compose exec backend curl -s http://localhost:8080/actuator/health
# Then query the email outbox via the existing admin endpoint or DB:
docker compose exec postgres psql -U rentaxis -d rentaxis -c \
  "SELECT event_type, status, created_at FROM email_outbox WHERE event_type = 'CHEQUE_RECEIVED' ORDER BY created_at DESC LIMIT 6;"
```

Expected: 6 `CHEQUE_RECEIVED` rows in `PENDING` or `SENT` state.

- [ ] **Step 6: Verify nothing leaked across tenants**

Log in as a TENANT_ADMIN of a different tenant and view the global payments page — none of the new cheques should appear.

- [ ] **Step 7: No commit needed for manual smoke**

If anomalies were found, file follow-up issues; otherwise the feature is ready for PR.

---

### Task 15: Open the PR

**Files:** none

- [ ] **Step 1: Push the branch**

If you've been working on a feature branch (e.g. `feat/bulk-cheque-upload`):

```bash
git push -u origin feat/bulk-cheque-upload
```

- [ ] **Step 2: Open PR**

```bash
gh pr create --title "feat: bulk cheque upload + DueDateDelta badge" --body "$(cat <<'EOF'
## Summary
- New `POST /api/v1/leases/{id}/cheques/bulk-attach` endpoint with all-or-nothing transactional commit.
- New web flow on the lease detail page: folder pick → parallel AI extract → editable review table → approve all.
- Shared `<DueDateDelta>` badge wired into the bulk review table, lease detail timeline, and global payments page.

Spec: `docs/superpowers/specs/2026-05-07-bulk-cheque-upload-design.md`
Plan: `docs/superpowers/plans/2026-05-07-bulk-cheque-upload.md`

## Test plan
- [x] `PaymentScheduleServiceBulkAttachTest` passes
- [x] `LeaseChequeBulkAttachControllerTest` passes
- [x] `DueDateDelta.test.tsx` passes
- [x] `autoMapChequesToSchedules.test.ts` passes
- [x] `BulkChequeUploadFlow.test.tsx` passes
- [x] Manual smoke: upload 6 cheques, all 6 attached, 6 CHEQUE_RECEIVED events fired, lease detail badge renders

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

---

## Self-Review

**Spec coverage check:**
- §3.1 components → Tasks 1–13 cover every row of the file structure table.
- §3.2 happy-path data flow → Task 10 implements all three screens; Task 11 tests the full flow.
- §3.3 permissions (SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER) → Task 4 endpoint annotation + 403 test.
- §4 auto-mapping algorithm → Task 8 (with pinned-row reservation).
- §5 `<DueDateDelta>` extension → Tasks 5 (component), 6 (lease detail), 7 (global payments).
- §6 review table UI → Task 10.
- §7 API contract → Tasks 1, 4 (DTOs, endpoint, error envelope).
- §8 edge cases → covered across Tasks 8 (auto-map cases 1, 4), 10 (folder filter case 1, beforeunload via cleanup, retry/remove for cases 3–4), 4 (cases 6, 8, 9 server-side).
- §9 testing → Tasks 2, 4, 5, 8, 11.
- §10 file touch list → matches Tasks 1–13.
- §11 out-of-scope is acknowledged at top of plan; nothing else needed.

**Placeholder scan:** zero TBD/TODO/"add error handling here". Each step has the actual code or the actual command.

**Type consistency:**
- `BulkAttachChequeItem` field names (`scheduleId`, `chequeNumber`, `chequeDate`, `bankName`, `payerName`, `imageUrl`, `imageBlobPath`, `imageUploadedAt`) match across DTO (Task 1), service test seed (Task 2), service implementation (Task 3), controller test (Task 4), and frontend POST body (Task 10).
- `BulkAttachValidationException` raised by service (Task 3) is caught by controller exception handler (Task 4) — both reference `rows` and `isConflict()`.
- `autoMapChequesToSchedules` parameter shape (Task 8) matches what `BulkChequeUploadFlow` passes (Task 10): `{id, chequeDate, pinned, assignedScheduleId}` for items, `{id, dueDate}` for schedules.
- `DueDateDelta` props (`dueDate`, `chequeDate`, `className`) — same call signature in Tasks 5, 6, 7, 10.

---

Plan complete.

# Maintenance ↔ Vendor/Staff Dispatch Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let maintenance tickets be dispatched to a Vendor or Staff member with referential integrity, and let repair costs be recorded and linked to financial transactions.

**Architecture:** Additive-only changes: one Liquibase changeset (72) adds nullable FK columns; `MaintenanceTicketService` gains `dispatchTicket`/`recordActualCost`; `FinancialTransaction` gains a plain-UUID `maintenanceTicketId`; new PM-accessible slim `/picker` endpoints avoid exposing vendor bank / staff identity data; the web ticket detail page reuses its existing `performAction` helper for the new endpoints.

**Tech Stack:** Java 21 + Spring Boot 4 + JPA + Liquibase (backend), Next.js 16 + TypeScript + Tailwind + next-intl (web), JUnit 5 + Mockito + AssertJ (backend tests), Vitest (web tests).

**Spec:** `docs/superpowers/plans/2026-08-23-maintenance-vendor-staff-dispatch-spec.md`

## Global Constraints

- Liquibase changesets are **append-only**; this feature uses a new file `72-maintenance-dispatch.yaml` (71 is the current latest — the "next: 37" note in CLAUDE.md is stale).
- All entities extend `BaseTenantEntity`; tenant scoping comes from the Hibernate tenant filter enabled by `TenantAspect` — never add manual `tenant_id` predicates.
- Backend identity arrives via `X-User-Id` / `X-User-Role` headers; endpoints guard with `@PreAuthorize` role checks (`ROLE_` prefix implied by `hasAnyRole`).
- Method-level `@PreAuthorize` overrides class-level — that is how `/picker` opens to `PROPERTY_MANAGER` on otherwise admin-only controllers.
- Web calls the backend through the proxy: `/api/proxy/v1/...` — never a hardcoded backend URL.
- The ticket detail page is hardcoded English (match it); `VendorPaymentDialog` is i18n'd — new strings go in **both** `web/messages/en.json` and `web/messages/ar.json` under the `Vendors` namespace.
- Conventional commits (`feat:`, `fix:`, `test:`).
- Backend tests: plain JUnit + `mock(...)` construction (no `@SpringBootTest`), AssertJ assertions — mirror `MaintenanceTicketServiceTest`.
- Mobile apps are **out of scope** (follow-up plan).

---

### Task 1: Schema + entity plumbing (changeset 72, entity/DTO fields)

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/72-maintenance-dispatch.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append include)
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/MaintenanceTicket.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/FinancialTransaction.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/MaintenanceTicketDTO.java`

**Interfaces:**
- Consumes: existing `Vendor`, `Staff` entities.
- Produces: `MaintenanceTicket.getVendor()/setVendor(Vendor)`, `getStaff()/setStaff(Staff)`, `getActualCost()/setActualCost(BigDecimal)`; `FinancialTransaction.getMaintenanceTicketId()/setMaintenanceTicketId(UUID)`; DTO getters/setters (Lombok `@Data`) for `vendorId`, `vendorName`, `staffId`, `staffName`, `actualCost`. Tasks 2–5 rely on these exact names.

- [ ] **Step 1: Write the changeset**

Create `backend/src/main/resources/db/changelog/changesets/72-maintenance-dispatch.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 72-maintenance-dispatch
      author: rentaxis-system
      changes:
        - addColumn:
            tableName: maintenance_tickets
            columns:
              - column: { name: vendor_id, type: uuid }
              - column: { name: staff_id, type: uuid }
              - column: { name: actual_cost, type: "decimal(14,2)" }
        - addColumn:
            tableName: financial_transactions
            columns:
              - column: { name: maintenance_ticket_id, type: uuid }
        - addForeignKeyConstraint:
            baseTableName: maintenance_tickets
            baseColumnNames: vendor_id
            referencedTableName: vendors
            referencedColumnNames: id
            constraintName: fk_maintenance_ticket_vendor
        - addForeignKeyConstraint:
            baseTableName: maintenance_tickets
            baseColumnNames: staff_id
            referencedTableName: staff
            referencedColumnNames: id
            constraintName: fk_maintenance_ticket_staff
        - addForeignKeyConstraint:
            baseTableName: financial_transactions
            baseColumnNames: maintenance_ticket_id
            referencedTableName: maintenance_tickets
            referencedColumnNames: id
            constraintName: fk_financial_txn_maintenance_ticket
        - createIndex:
            tableName: financial_transactions
            indexName: idx_financial_txn_maintenance_ticket
            columns:
              - column: { name: maintenance_ticket_id }
```

- [ ] **Step 2: Register it in the master changelog**

Append to the end of `db.changelog-master.yaml` (after the `71-promotions.yaml` include, same two-line style):

```yaml
  - include:
      file: db/changelog/changesets/72-maintenance-dispatch.yaml
```

- [ ] **Step 3: Add entity fields**

In `MaintenanceTicket.java`, directly after the `assignedTo` field (line 38-39), add:

```java
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendor_id")
    private Vendor vendor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "staff_id")
    private Staff staff;
```

After the `estimatedResolutionHours` field, add:

```java
    @Column(name = "actual_cost", precision = 14, scale = 2)
    private BigDecimal actualCost;
```

Add `import java.math.BigDecimal;` to the imports.

In `FinancialTransaction.java`, directly after the `staff` field (line 59-61), add (a plain UUID column, deliberately not a `@ManyToOne` — nothing navigates transaction→ticket, and a plain column keeps existing finance JSON payloads unchanged apart from one new scalar):

```java
    @Column(name = "maintenance_ticket_id")
    private UUID maintenanceTicketId;
```

- [ ] **Step 4: Add DTO fields**

In `MaintenanceTicketDTO.java`, in the "Enriched fields" section after `assigneeName`, add:

```java
    private UUID vendorId;
    private String vendorName;
    private UUID staffId;
    private String staffName;
    private java.math.BigDecimal actualCost;
```

- [ ] **Step 5: Compile**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Run the existing backend test suite (regression guard)**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL (schema is verified against a live DB in Task 8's smoke)

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/resources/db/changelog backend/src/main/java/com/datagami/rentaxis/domain/entity/MaintenanceTicket.java backend/src/main/java/com/datagami/rentaxis/domain/entity/FinancialTransaction.java backend/src/main/java/com/datagami/rentaxis/api/dto/MaintenanceTicketDTO.java
git commit -m "feat(maintenance): add vendor/staff dispatch and cost columns (changeset 72)"
```

---

### Task 2: `dispatchTicket` + `recordActualCost` service methods (TDD)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/DispatchTicketDTO.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/MaintenanceTicketService.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/MaintenanceTicketServiceTest.java`

**Interfaces:**
- Consumes: Task 1's entity/DTO fields; existing `recordHistory(...)`, `mapToDTO(...)`, `BusinessRuleViolationException`, `NotFoundException`.
- Produces: `MaintenanceTicketDTO dispatchTicket(UUID ticketId, UUID vendorId, UUID staffId, UUID performedBy)`; `MaintenanceTicketDTO recordActualCost(UUID ticketId, BigDecimal actualCost, UUID performedBy)`; `DispatchTicketDTO` with `getVendorId()`/`getStaffId()`. The service constructor gains two trailing params: `VendorRepository`, `StaffRepository` (Lombok `@RequiredArgsConstructor` — declare the fields **last** so existing construction order is preserved). Task 4 calls these signatures.

- [ ] **Step 1: Create `DispatchTicketDTO`**

```java
package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class DispatchTicketDTO {
    private UUID vendorId;
    private UUID staffId;
}
```

- [ ] **Step 2: Add the two repository fields to the service**

In `MaintenanceTicketService.java`, after the `private final ApplicationEventPublisher events;` field (line 54 — must stay last-but-two so the Lombok constructor appends, not reorders), add:

```java
    private final VendorRepository vendorRepository;
    private final StaffRepository staffRepository;
```

Add imports: `com.datagami.rentaxis.domain.entity.Vendor`, `com.datagami.rentaxis.domain.entity.Staff`, `com.datagami.rentaxis.domain.repository.VendorRepository`, `com.datagami.rentaxis.domain.repository.StaffRepository`, `java.math.BigDecimal`.

- [ ] **Step 3: Update the test fixture for the new constructor and write failing tests**

In `MaintenanceTicketServiceTest.java` `setUp()`, add before the `service = new MaintenanceTicketService(...)` call:

```java
        vendorRepository = mock(VendorRepository.class);
        staffRepository = mock(StaffRepository.class);
```

(with fields `private VendorRepository vendorRepository;` and `private StaffRepository staffRepository;` and imports for the two repository types plus `com.datagami.rentaxis.domain.entity.Vendor`, `com.datagami.rentaxis.domain.entity.Staff`, `com.datagami.rentaxis.api.exception.NotFoundException`, `java.math.BigDecimal`), and append the two mocks as the last two constructor arguments:

```java
        service = new MaintenanceTicketService(
                ticketRepository, replyRepository, attachmentRepository,
                propertyRepository, unitRepository, leaseRepository,
                userRepository, propertyAssignmentRepository, historyRepository,
                landlordOrgRepository, notificationService, events,
                vendorRepository, staffRepository);
```

Then add these tests (reuse the existing `ticket(...)` helper where a RESOLVED ticket is fine; build OPEN tickets inline as shown):

```java
    private MaintenanceTicket openTicket() {
        Property property = new Property();
        property.setId(UUID.randomUUID());
        MaintenanceTicket t = new MaintenanceTicket();
        t.setId(UUID.randomUUID());
        t.setProperty(property);
        t.setReportedBy(UUID.randomUUID());
        t.setTitle("Broken AC");
        t.setStatus(TicketStatus.OPEN);
        return t;
    }

    private Vendor activeVendor() {
        Vendor v = new Vendor();
        v.setId(UUID.randomUUID());
        v.setNameEn("CoolFix AC Services");
        v.setActive(true);
        return v;
    }

    private Staff activeStaff() {
        Staff s = new Staff();
        s.setId(UUID.randomUUID());
        s.setNameEn("Ahmed the Technician");
        s.setActive(true);
        return s;
    }

    // ---- dispatchTicket ----

    @Test
    void dispatchTicket_neitherIdProvided_throws() {
        assertThatThrownBy(() -> service.dispatchTicket(UUID.randomUUID(), null, null, UUID.randomUUID()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Exactly one");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void dispatchTicket_bothIdsProvided_throws() {
        assertThatThrownBy(() -> service.dispatchTicket(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Exactly one");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void dispatchTicket_vendor_setsVendorMovesOpenToAssignedAndRecordsHistory() {
        MaintenanceTicket t = openTicket();
        Vendor vendor = activeVendor();
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(vendorRepository.findById(vendor.getId())).thenReturn(Optional.of(vendor));
        when(ticketRepository.save(any(MaintenanceTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        MaintenanceTicketDTO dto = service.dispatchTicket(t.getId(), vendor.getId(), null, UUID.randomUUID());

        assertThat(dto.getVendorId()).isEqualTo(vendor.getId());
        assertThat(dto.getVendorName()).isEqualTo("CoolFix AC Services");
        assertThat(dto.getStatus()).isEqualTo(TicketStatus.ASSIGNED.name());
        verify(historyRepository).save(any());
    }

    @Test
    void dispatchTicket_staff_setsStaffAndClearsVendor() {
        MaintenanceTicket t = openTicket();
        t.setVendor(activeVendor());
        Staff staff = activeStaff();
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(staffRepository.findById(staff.getId())).thenReturn(Optional.of(staff));
        when(ticketRepository.save(any(MaintenanceTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        MaintenanceTicketDTO dto = service.dispatchTicket(t.getId(), null, staff.getId(), UUID.randomUUID());

        assertThat(dto.getStaffId()).isEqualTo(staff.getId());
        assertThat(dto.getStaffName()).isEqualTo("Ahmed the Technician");
        assertThat(dto.getVendorId()).isNull();
    }

    @Test
    void dispatchTicket_inactiveVendor_throws() {
        MaintenanceTicket t = openTicket();
        Vendor vendor = activeVendor();
        vendor.setActive(false);
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(vendorRepository.findById(vendor.getId())).thenReturn(Optional.of(vendor));

        assertThatThrownBy(() -> service.dispatchTicket(t.getId(), vendor.getId(), null, UUID.randomUUID()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("inactive");
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void dispatchTicket_unknownVendor_throwsNotFound() {
        MaintenanceTicket t = openTicket();
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(vendorRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.dispatchTicket(t.getId(), UUID.randomUUID(), null, UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    // ---- updateStatus: dispatched tickets may start work ----

    @Test
    void updateStatus_inProgressAllowedWhenDispatchedButNoUserAssigned() {
        MaintenanceTicket t = openTicket();
        t.setStatus(TicketStatus.ASSIGNED);
        t.setVendor(activeVendor());
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(ticketRepository.save(any(MaintenanceTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        MaintenanceTicketDTO dto = service.updateStatus(t.getId(), "IN_PROGRESS", UUID.randomUUID());

        assertThat(dto.getStatus()).isEqualTo(TicketStatus.IN_PROGRESS.name());
    }

    @Test
    void updateStatus_inProgressStillRejectedWhenNeitherAssignedNorDispatched() {
        MaintenanceTicket t = openTicket();
        t.setStatus(TicketStatus.ASSIGNED);
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));

        assertThatThrownBy(() -> service.updateStatus(t.getId(), "IN_PROGRESS", UUID.randomUUID()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("assigned or dispatched");
    }

    // ---- recordActualCost ----

    @Test
    void recordActualCost_negative_throws() {
        assertThatThrownBy(() -> service.recordActualCost(
                UUID.randomUUID(), new BigDecimal("-1"), UUID.randomUUID()))
                .isInstanceOf(BusinessRuleViolationException.class);
        verify(ticketRepository, never()).save(any());
    }

    @Test
    void recordActualCost_setsCostAndRecordsHistory() {
        MaintenanceTicket t = openTicket();
        when(ticketRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(ticketRepository.save(any(MaintenanceTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        MaintenanceTicketDTO dto = service.recordActualCost(t.getId(), new BigDecimal("450.00"), UUID.randomUUID());

        assertThat(dto.getActualCost()).isEqualByComparingTo("450.00");
        verify(historyRepository).save(any());
    }
```

Note: `historyRepository` is currently a local variable in `setUp()` — promote it to a field (`private TicketHistoryRepository historyRepository;`) so the new tests can `verify(...)` against it.

- [ ] **Step 4: Run tests to verify they fail**

Run: `cd backend && ./gradlew test --tests 'MaintenanceTicketServiceTest'`
Expected: COMPILATION FAILURE (`dispatchTicket`/`recordActualCost` not defined) — that counts as the failing state for compiled languages.

- [ ] **Step 5: Implement the service methods**

In `MaintenanceTicketService.java`, after `assignTicket(...)` (ends ~line 205), add:

```java
    @Transactional
    public MaintenanceTicketDTO dispatchTicket(UUID ticketId, UUID vendorId, UUID staffId, UUID performedBy) {
        if ((vendorId == null) == (staffId == null)) {
            throw new BusinessRuleViolationException("Exactly one of vendorId or staffId is required");
        }
        MaintenanceTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));

        String previousStatus = ticket.getStatus() != null ? ticket.getStatus().name() : null;
        String assigneeName;
        if (vendorId != null) {
            Vendor vendor = vendorRepository.findById(vendorId)
                    .orElseThrow(() -> new NotFoundException("Vendor not found"));
            if (!vendor.isActive()) {
                throw new BusinessRuleViolationException("Vendor is inactive");
            }
            ticket.setVendor(vendor);
            ticket.setStaff(null);
            assigneeName = vendor.getNameEn();
        } else {
            Staff staff = staffRepository.findById(staffId)
                    .orElseThrow(() -> new NotFoundException("Staff member not found"));
            if (!staff.isActive()) {
                throw new BusinessRuleViolationException("Staff member is inactive");
            }
            ticket.setStaff(staff);
            ticket.setVendor(null);
            assigneeName = staff.getNameEn();
        }
        if (ticket.getStatus() == TicketStatus.OPEN || ticket.getStatus() == TicketStatus.REOPENED) {
            ticket.setStatus(TicketStatus.ASSIGNED);
        }
        MaintenanceTicket saved = ticketRepository.save(ticket);
        // Vendors/staff have no user accounts yet, so unlike assignTicket there
        // is no in-app notification target for a dispatch.
        recordHistory(saved, "DISPATCHED", previousStatus, saved.getStatus().name(),
                null, null, performedBy, "Dispatched to " + assigneeName);
        log.info("Dispatched ticket {} to {} {}", ticketId,
                vendorId != null ? "vendor" : "staff", vendorId != null ? vendorId : staffId);
        return mapToDTO(saved);
    }

    @Transactional
    public MaintenanceTicketDTO recordActualCost(UUID ticketId, BigDecimal actualCost, UUID performedBy) {
        if (actualCost == null || actualCost.signum() < 0) {
            throw new BusinessRuleViolationException("actualCost must be zero or greater");
        }
        MaintenanceTicket ticket = ticketRepository.findById(ticketId)
                .orElseThrow(() -> new NotFoundException("Ticket not found"));
        ticket.setActualCost(actualCost);
        MaintenanceTicket saved = ticketRepository.save(ticket);
        String status = saved.getStatus() != null ? saved.getStatus().name() : null;
        recordHistory(saved, "COST_RECORDED", status, status, null, null, performedBy,
                "Actual cost recorded: " + actualCost);
        return mapToDTO(saved);
    }
```

- [ ] **Step 6: Update the IN_PROGRESS guard**

In `updateStatus(...)`, replace:

```java
        if (targetStatus == TicketStatus.IN_PROGRESS && ticket.getAssignedTo() == null) {
            throw new BusinessRuleViolationException("Ticket must be assigned before moving to IN_PROGRESS");
        }
```

with:

```java
        if (targetStatus == TicketStatus.IN_PROGRESS && ticket.getAssignedTo() == null
                && ticket.getVendor() == null && ticket.getStaff() == null) {
            throw new BusinessRuleViolationException("Ticket must be assigned or dispatched before moving to IN_PROGRESS");
        }
```

- [ ] **Step 7: Enrich `mapToDTO`**

In `mapToDTO(...)`, the basic-field section must copy the new scalar: add `dto.setActualCost(ticket.getActualCost());` next to the other direct field copies (e.g. after `dto.setEstimatedResolutionHours(...)`). Then, after the `unitNumber` enrichment try/catch (~line 611), add (same lazy-tolerant style as the surrounding blocks):

```java
        try {
            if (ticket.getVendor() != null) {
                dto.setVendorId(ticket.getVendor().getId());
                dto.setVendorName(ticket.getVendor().getNameEn());
            }
        } catch (Exception e) {
            // Lazy loading issue — skip
        }

        try {
            if (ticket.getStaff() != null) {
                dto.setStaffId(ticket.getStaff().getId());
                dto.setStaffName(ticket.getStaff().getNameEn());
            }
        } catch (Exception e) {
            // Lazy loading issue — skip
        }
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests 'MaintenanceTicketServiceTest'`
Expected: PASS (all new tests plus every pre-existing test)

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/MaintenanceTicketService.java backend/src/main/java/com/datagami/rentaxis/api/dto/DispatchTicketDTO.java backend/src/test/java/com/datagami/rentaxis/core/service/MaintenanceTicketServiceTest.java
git commit -m "feat(maintenance): dispatch tickets to vendors/staff and record actual cost"
```

---

### Task 3: Transaction ↔ ticket link in the finance service (TDD)

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/FinancialTransactionService.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/CreateSplitTransactionDTO.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/repository/FinancialTransactionRepository.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/FinancialTransactionServiceTest.java` (create if absent — `FinancialTransactionServiceFilterIT` exists but is an integration test; this task's tests are plain-Mockito unit tests in a new file, mirroring `MaintenanceTicketServiceTest` construction)

**Interfaces:**
- Consumes: `FinancialTransaction.maintenanceTicketId` (Task 1), existing explicit constructor of `FinancialTransactionService` (8 params today).
- Produces: constructor gains a 9th trailing param `MaintenanceTicketRepository maintenanceTicketRepository`; `List<FinancialTransaction> getByMaintenanceTicket(UUID ticketId)`; repository method `List<FinancialTransaction> findByMaintenanceTicketIdOrderByDateDesc(UUID maintenanceTicketId)`; `CreateSplitTransactionDTO.getMaintenanceTicketId()`. Task 4 calls `getByMaintenanceTicket`.

- [ ] **Step 1: Write failing unit tests**

Create `backend/src/test/java/com/datagami/rentaxis/core/service/FinancialTransactionServiceTest.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.FinancialTransaction;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.repository.AccountMappingRepository;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.FinancialTransactionRepository;
import com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.StaffRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FinancialTransactionServiceTest {

    private FinancialTransactionRepository repository;
    private AccountRepository accountRepository;
    private MaintenanceTicketRepository maintenanceTicketRepository;
    private FinancialTransactionService service;

    @BeforeEach
    void setUp() {
        repository = mock(FinancialTransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        UnitRepository unitRepository = mock(UnitRepository.class);
        PropertyRepository propertyRepository = mock(PropertyRepository.class);
        VendorRepository vendorRepository = mock(VendorRepository.class);
        StaffRepository staffRepository = mock(StaffRepository.class);
        AccountMappingRepository accountMappingRepository = mock(AccountMappingRepository.class);
        PaymentScheduleRepository paymentScheduleRepository = mock(PaymentScheduleRepository.class);
        maintenanceTicketRepository = mock(MaintenanceTicketRepository.class);

        service = new FinancialTransactionService(repository, accountRepository,
                unitRepository, propertyRepository, vendorRepository, staffRepository,
                accountMappingRepository, paymentScheduleRepository, maintenanceTicketRepository);
    }

    private FinancialTransaction expenseTxn() {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setCode("5100");
        account.setAccountType(AccountType.EXPENSE);
        FinancialTransaction txn = new FinancialTransaction();
        txn.setDate(LocalDate.of(2026, 8, 23));
        txn.setDescription("AC compressor replacement");
        txn.setAccount(account);
        txn.setDebit(new BigDecimal("450.00"));
        return txn;
    }

    @Test
    void createTransaction_unknownMaintenanceTicket_throws() {
        FinancialTransaction txn = expenseTxn();
        UUID ticketId = UUID.randomUUID();
        txn.setMaintenanceTicketId(ticketId);
        when(accountRepository.findById(txn.getAccount().getId()))
                .thenReturn(Optional.of(txn.getAccount()));
        when(maintenanceTicketRepository.existsById(ticketId)).thenReturn(false);

        assertThatThrownBy(() -> service.createTransaction(txn))
                .hasMessageContaining("Maintenance ticket not found");
        verify(repository, never()).save(any());
    }

    @Test
    void createTransaction_knownMaintenanceTicket_saves() {
        FinancialTransaction txn = expenseTxn();
        UUID ticketId = UUID.randomUUID();
        txn.setMaintenanceTicketId(ticketId);
        when(accountRepository.findById(txn.getAccount().getId()))
                .thenReturn(Optional.of(txn.getAccount()));
        when(maintenanceTicketRepository.existsById(ticketId)).thenReturn(true);
        when(repository.save(any(FinancialTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

        FinancialTransaction saved = service.createTransaction(txn);

        assertThat(saved.getMaintenanceTicketId()).isEqualTo(ticketId);
    }

    @Test
    void getByMaintenanceTicket_delegatesToRepository() {
        UUID ticketId = UUID.randomUUID();
        service.getByMaintenanceTicket(ticketId);
        verify(repository).findByMaintenanceTicketIdOrderByDateDesc(ticketId);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./gradlew test --tests 'FinancialTransactionServiceTest'`
Expected: COMPILATION FAILURE (constructor arity, missing methods)

- [ ] **Step 3: Implement**

In `FinancialTransactionRepository.java`, next to `findByVendorId`, add:

```java
    List<FinancialTransaction> findByMaintenanceTicketIdOrderByDateDesc(UUID maintenanceTicketId);
```

In `FinancialTransactionService.java`:
1. Add field `private final MaintenanceTicketRepository maintenanceTicketRepository;`, a 9th constructor parameter `MaintenanceTicketRepository maintenanceTicketRepository` (appended last), the assignment in the constructor body, and the import `com.datagami.rentaxis.domain.repository.MaintenanceTicketRepository`.
2. In `createTransaction(...)`, after the staff-resolution block (ends ~line 238), add:

```java
        if (txn.getMaintenanceTicketId() != null
                && !maintenanceTicketRepository.existsById(txn.getMaintenanceTicketId())) {
            throw new RuntimeException("Maintenance ticket not found: " + txn.getMaintenanceTicketId());
        }
```

(`RuntimeException` matches the error style already used for Unit/Property/Account/Vendor/Staff in this method.)
3. Add the read method:

```java
    @Transactional(readOnly = true)
    public List<FinancialTransaction> getByMaintenanceTicket(UUID ticketId) {
        return repository.findByMaintenanceTicketIdOrderByDateDesc(ticketId);
    }
```

In `CreateSplitTransactionDTO.java`, after `private UUID staffId;`, add:

```java
    private UUID maintenanceTicketId;
```

and in `FinancialTransactionService.createSplitTransaction(...)`, where the parent's optional vendor/staff are resolved (~line 487-495), add after the staff block:

```java
        if (dto.getMaintenanceTicketId() != null) {
            if (!maintenanceTicketRepository.existsById(dto.getMaintenanceTicketId())) {
                throw new RuntimeException("Maintenance ticket not found: " + dto.getMaintenanceTicketId());
            }
            parent.setMaintenanceTicketId(dto.getMaintenanceTicketId());
        }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests 'FinancialTransactionServiceTest' --tests 'FinancialTransactionServiceFilterIT'`
Expected: PASS (the IT also proves the existing constructor callers were updated — if the IT constructs the service directly, append the new mock/repository there too)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/FinancialTransactionService.java backend/src/main/java/com/datagami/rentaxis/api/dto/CreateSplitTransactionDTO.java backend/src/main/java/com/datagami/rentaxis/domain/repository/FinancialTransactionRepository.java backend/src/test/java/com/datagami/rentaxis/core/service/FinancialTransactionServiceTest.java
git commit -m "feat(finance): link transactions to maintenance tickets"
```

---

### Task 4: Ticket controller endpoints (dispatch, cost, linked transactions)

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/MaintenanceTicketController.java`

**Interfaces:**
- Consumes: `ticketService.dispatchTicket(...)`, `ticketService.recordActualCost(...)` (Task 2), `financialTransactionService.getByMaintenanceTicket(...)` (Task 3), `DispatchTicketDTO` (Task 2).
- Produces: `PUT /api/v1/tickets/{id}/dispatch`, `PUT /api/v1/tickets/{id}/cost`, `GET /api/v1/tickets/{id}/transactions` — the routes Task 6/7's web code calls.

The controller uses `@RequiredArgsConstructor` with a single `ticketService` field; the new `financialTransactionService` field is picked up automatically.

- [ ] **Step 1: Add the endpoints**

Add field `private final FinancialTransactionService financialTransactionService;` under the existing `ticketService` field, plus imports `com.datagami.rentaxis.core.service.FinancialTransactionService`, `com.datagami.rentaxis.domain.entity.FinancialTransaction`, `java.math.BigDecimal`. Then after the `assignTicket` endpoint (ends line 55), add:

```java
    @PutMapping("/{id}/dispatch")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MaintenanceTicketDTO> dispatchTicket(
            @PathVariable UUID id,
            @RequestBody DispatchTicketDTO body,
            @RequestHeader("X-User-Id") UUID performedBy) {
        return ResponseEntity.ok(
                ticketService.dispatchTicket(id, body.getVendorId(), body.getStaffId(), performedBy));
    }

    @PutMapping("/{id}/cost")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<MaintenanceTicketDTO> recordCost(
            @PathVariable UUID id,
            @RequestBody Map<String, BigDecimal> body,
            @RequestHeader("X-User-Id") UUID performedBy) {
        return ResponseEntity.ok(ticketService.recordActualCost(id, body.get("actualCost"), performedBy));
    }

    @GetMapping("/{id}/transactions")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<FinancialTransaction>> getTicketTransactions(@PathVariable UUID id) {
        return ResponseEntity.ok(financialTransactionService.getByMaintenanceTicket(id));
    }
```

(`/transactions` is admin-only on purpose — it exposes finance rows, matching the class-level guard on `FinancialTransactionController`.)

- [ ] **Step 2: Compile and run the backend suite**

Run: `cd backend && ./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/MaintenanceTicketController.java
git commit -m "feat(maintenance): expose dispatch, cost, and linked-transaction endpoints"
```

---

### Task 5: PM-accessible vendor/staff picker endpoints (TDD)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/AssigneePickerDTO.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/VendorService.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/StaffService.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/VendorController.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/StaffController.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/VendorServiceTest.java` (create if absent; if it exists, append)
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/StaffServiceTest.java` (create if absent; if it exists, append)

**Interfaces:**
- Consumes: `Vendor` (fields `id`, `nameEn`, `nameAr`, `contactPerson`, `isActive`), `Staff` (fields `id`, `nameEn`, `nameAr`, `designation`, `isActive`).
- Produces: `record AssigneePickerDTO(UUID id, String nameEn, String nameAr, String detail)`; `VendorService.getPickerItems()` / `StaffService.getPickerItems()` returning `List<AssigneePickerDTO>`; routes `GET /api/v1/vendors/picker` and `GET /api/v1/staff/picker`. Task 6's web code consumes `{id, nameEn, nameAr, detail}` JSON.

- [ ] **Step 1: Create the DTO**

```java
package com.datagami.rentaxis.api.dto;

import java.util.UUID;

/**
 * Slim assignee entry safe to expose to PROPERTY_MANAGER — deliberately
 * excludes bank, licence, salary, and identity-document fields that the raw
 * Vendor/Staff entities carry.
 */
public record AssigneePickerDTO(UUID id, String nameEn, String nameAr, String detail) {
}
```

- [ ] **Step 2: Write failing tests**

`VendorServiceTest.java` (adapt the mock/field names if the file already exists — the service's repository field is named `repository`):

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.AssigneePickerDTO;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import com.datagami.rentaxis.domain.repository.FinancialTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VendorServiceTest {

    private VendorRepository repository;
    private VendorService service;

    @BeforeEach
    void setUp() {
        repository = mock(VendorRepository.class);
        service = new VendorService(repository, mock(FinancialTransactionRepository.class));
    }

    private Vendor vendor(String name, boolean active) {
        Vendor v = new Vendor();
        v.setId(UUID.randomUUID());
        v.setNameEn(name);
        v.setNameAr(name + " AR");
        v.setContactPerson("Contact for " + name);
        v.setActive(active);
        return v;
    }

    @Test
    void getPickerItems_returnsOnlyActiveVendorsAsSlimItems() {
        Vendor active = vendor("CoolFix", true);
        Vendor inactive = vendor("GoneCo", false);
        when(repository.findAllByOrderByNameEnAsc()).thenReturn(List.of(active, inactive));

        List<AssigneePickerDTO> items = service.getPickerItems();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).id()).isEqualTo(active.getId());
        assertThat(items.get(0).nameEn()).isEqualTo("CoolFix");
        assertThat(items.get(0).nameAr()).isEqualTo("CoolFix AR");
        assertThat(items.get(0).detail()).isEqualTo("Contact for CoolFix");
    }
}
```

`StaffServiceTest.java` — same shape: `StaffService` takes its `StaffRepository` (field name `repository`) plus a `FinancialTransactionRepository` (it checks `findByStaffId` before delete); the picker test asserts `detail()` equals the staff member's `designation` and inactive staff are excluded:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.AssigneePickerDTO;
import com.datagami.rentaxis.domain.entity.Staff;
import com.datagami.rentaxis.domain.repository.FinancialTransactionRepository;
import com.datagami.rentaxis.domain.repository.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StaffServiceTest {

    private StaffRepository repository;
    private StaffService service;

    @BeforeEach
    void setUp() {
        repository = mock(StaffRepository.class);
        service = new StaffService(repository, mock(FinancialTransactionRepository.class));
    }

    @Test
    void getPickerItems_returnsOnlyActiveStaffWithDesignation() {
        Staff active = new Staff();
        active.setId(UUID.randomUUID());
        active.setNameEn("Ahmed");
        active.setNameAr("أحمد");
        active.setDesignation("HVAC Technician");
        active.setActive(true);
        Staff inactive = new Staff();
        inactive.setId(UUID.randomUUID());
        inactive.setNameEn("Former Employee");
        inactive.setActive(false);
        when(repository.findAllByOrderByNameEnAsc()).thenReturn(List.of(active, inactive));

        List<AssigneePickerDTO> items = service.getPickerItems();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).nameEn()).isEqualTo("Ahmed");
        assertThat(items.get(0).detail()).isEqualTo("HVAC Technician");
    }
}
```

(Both services' constructors are exactly `(repository, transactionRepository)` — verified against the source.)

- [ ] **Step 3: Run tests to verify they fail**

Run: `cd backend && ./gradlew test --tests 'VendorServiceTest' --tests 'StaffServiceTest'`
Expected: COMPILATION FAILURE (`getPickerItems` not defined)

- [ ] **Step 4: Implement the service methods**

In `VendorService.java`:

```java
    public List<AssigneePickerDTO> getPickerItems() {
        return repository.findAllByOrderByNameEnAsc().stream()
                .filter(Vendor::isActive)
                .map(v -> new AssigneePickerDTO(v.getId(), v.getNameEn(), v.getNameAr(), v.getContactPerson()))
                .toList();
    }
```

In `StaffService.java`:

```java
    public List<AssigneePickerDTO> getPickerItems() {
        return repository.findAllByOrderByNameEnAsc().stream()
                .filter(Staff::isActive)
                .map(s -> new AssigneePickerDTO(s.getId(), s.getNameEn(), s.getNameAr(), s.getDesignation()))
                .toList();
    }
```

(add `import com.datagami.rentaxis.api.dto.AssigneePickerDTO;` to both)

- [ ] **Step 5: Add the controller routes**

In `VendorController.java`, before the `getVendorById` mapping (so the literal `/picker` route is grouped with reads; Spring matches literals before `/{id}` regardless of order):

```java
    @GetMapping("/picker")
    @PreAuthorize("hasAnyRole('PROPERTY_MANAGER', 'TENANT_ADMIN', 'SUPER_ADMIN')")
    public ResponseEntity<List<AssigneePickerDTO>> getPicker() {
        return ResponseEntity.ok(service.getPickerItems());
    }
```

Same block in `StaffController.java`. The method-level `@PreAuthorize` replaces the class-level admin-only rule for just this route.

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd backend && ./gradlew test --tests 'VendorServiceTest' --tests 'StaffServiceTest'`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/AssigneePickerDTO.java backend/src/main/java/com/datagami/rentaxis/core/service/VendorService.java backend/src/main/java/com/datagami/rentaxis/core/service/StaffService.java backend/src/main/java/com/datagami/rentaxis/api/VendorController.java backend/src/main/java/com/datagami/rentaxis/api/StaffController.java backend/src/test/java/com/datagami/rentaxis/core/service/VendorServiceTest.java backend/src/test/java/com/datagami/rentaxis/core/service/StaffServiceTest.java
git commit -m "feat(vendors,staff): PM-accessible slim picker endpoints"
```

---

### Task 6: Web — dispatch UI + cost on the ticket detail page

**Files:**
- Modify: `web/src/app/[locale]/dashboard/tickets/[id]/page.tsx`
- Test: `web/src/app/[locale]/dashboard/tickets/[id]/__tests__/ticket-detail-contract.test.tsx` (extend its fetch mocks)

**Interfaces:**
- Consumes: `GET /api/proxy/v1/vendors/picker`, `GET /api/proxy/v1/staff/picker` (Task 5), `PUT /api/proxy/v1/tickets/{id}/dispatch`, `PUT .../cost` (Task 4); the page's existing `performAction(action, body)` helper (line 200) and `DetailRow` component.
- Produces: nothing downstream — leaf UI.

- [ ] **Step 1: Extend the `Ticket` type and add picker state**

In the `Ticket` type (line 18), after `assigneeName`, add:

```ts
    vendorId: string | null;
    vendorName: string | null;
    staffId: string | null;
    staffName: string | null;
    actualCost: number | null;
```

After the `StaffUser` type, add:

```ts
type PickerItem = {
    id: string;
    nameEn: string;
    nameAr?: string | null;
    detail?: string | null;
};
```

Next to the `staffUsers` state, add:

```ts
    const [vendorPickers, setVendorPickers] = useState<PickerItem[]>([]);
    const [staffPickers, setStaffPickers] = useState<PickerItem[]>([]);
    const [showDispatchVendor, setShowDispatchVendor] = useState(false);
    const [showDispatchStaff, setShowDispatchStaff] = useState(false);
    const [costInput, setCostInput] = useState("");
```

- [ ] **Step 2: Fetch pickers (PM included) and wire handlers**

After `fetchStaff` (line 172), add:

```ts
    const fetchPickers = useCallback(async () => {
        // Unlike /admin/users, the /picker endpoints are PM-accessible.
        if (!hasRole(userRole, ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER"])) return;
        try {
            const [vRes, sRes] = await Promise.all([
                fetch("/api/proxy/v1/vendors/picker"),
                fetch("/api/proxy/v1/staff/picker"),
            ]);
            if (vRes.ok) setVendorPickers(await vRes.json());
            if (sRes.ok) setStaffPickers(await sRes.json());
        } catch { /* dispatch dropdowns just stay hidden */ }
    }, [userRole]);
```

Add `fetchPickers()` to the `Promise.all` in the mount effect (line 175) and `fetchPickers` to its dependency array.

Next to `handleAssign` (line 222), add:

```ts
    const handleDispatchVendor = (vendorId: string) => {
        setShowDispatchVendor(false);
        performAction("dispatch", { vendorId });
    };
    const handleDispatchStaff = (staffId: string) => {
        setShowDispatchStaff(false);
        performAction("dispatch", { staffId });
    };
    const handleRecordCost = () => {
        const amt = Number(costInput);
        if (!costInput || Number.isNaN(amt) || amt < 0) return;
        performAction("cost", { actualCost: amt });
        setCostInput("");
    };
```

- [ ] **Step 3: Render dispatch dropdowns and detail rows**

In the Actions card, directly after the "Assign To..." dropdown block (ends line 564), add two sibling dropdowns with the same open/actionable statuses:

```tsx
                            {canManage && (ticket.status === "OPEN" || ticket.status === "REOPENED" || ticket.status === "ASSIGNED" || ticket.status === "IN_PROGRESS") && vendorPickers.length > 0 && (
                                <div className="relative">
                                    <button onClick={() => setShowDispatchVendor(!showDispatchVendor)} className="w-full flex items-center justify-center gap-2 bg-info/10 text-info px-4 py-2 rounded-lg text-xs font-semibold hover:bg-info/20 transition-all cursor-pointer">Dispatch to Vendor...</button>
                                    {showDispatchVendor && (
                                        <div className="absolute top-full left-0 right-0 mt-1 bg-surface border border-border rounded-lg shadow-lg z-10 max-h-48 overflow-y-auto">
                                            {vendorPickers.map(v => (
                                                <button key={v.id} onClick={() => handleDispatchVendor(v.id)} className="w-full text-left px-3 py-2 text-xs hover:bg-input transition-colors cursor-pointer">
                                                    <span className="font-medium text-foreground">{v.nameEn}</span>{v.detail && <span className="text-muted"> ({v.detail})</span>}
                                                </button>
                                            ))}
                                        </div>
                                    )}
                                </div>
                            )}
                            {canManage && (ticket.status === "OPEN" || ticket.status === "REOPENED" || ticket.status === "ASSIGNED" || ticket.status === "IN_PROGRESS") && staffPickers.length > 0 && (
                                <div className="relative">
                                    <button onClick={() => setShowDispatchStaff(!showDispatchStaff)} className="w-full flex items-center justify-center gap-2 bg-info/10 text-info px-4 py-2 rounded-lg text-xs font-semibold hover:bg-info/20 transition-all cursor-pointer">Dispatch to Staff...</button>
                                    {showDispatchStaff && (
                                        <div className="absolute top-full left-0 right-0 mt-1 bg-surface border border-border rounded-lg shadow-lg z-10 max-h-48 overflow-y-auto">
                                            {staffPickers.map(s => (
                                                <button key={s.id} onClick={() => handleDispatchStaff(s.id)} className="w-full text-left px-3 py-2 text-xs hover:bg-input transition-colors cursor-pointer">
                                                    <span className="font-medium text-foreground">{s.nameEn}</span>{s.detail && <span className="text-muted"> ({s.detail})</span>}
                                                </button>
                                            ))}
                                        </div>
                                    )}
                                </div>
                            )}
```

In the Ticket Info card, after the "Assigned To" `DetailRow` (line 528), add:

```tsx
                            {ticket.vendorName && <DetailRow icon={<Wrench size={12} />} label="Vendor" value={ticket.vendorName} />}
                            {ticket.staffName && <DetailRow icon={<User size={12} />} label="Technician" value={ticket.staffName} />}
                            {ticket.actualCost != null && <DetailRow icon={<Tag size={12} />} label="Actual Cost" value={`AED ${Number(ticket.actualCost).toLocaleString()}`} />}
```

In the Actions card, after the ETA input block (find `handleSetEta`'s input near the card's end), add a cost recorder visible to `canManage` when work is underway or done:

```tsx
                            {canManage && (ticket.status === "IN_PROGRESS" || ticket.status === "RESOLVED" || ticket.status === "CLOSED") && (
                                <div className="space-y-2">
                                    <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider">Record Actual Cost (AED)</label>
                                    <div className="flex items-center gap-2">
                                        <input type="number" min="0" step="0.01" value={costInput} onChange={(e) => setCostInput(e.target.value)} placeholder="0.00"
                                            className="flex-1 border border-border rounded-lg bg-surface px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none" />
                                        <button onClick={handleRecordCost} disabled={!costInput || actionLoading === "cost"}
                                            className={cn("flex items-center gap-1 px-3 py-2 rounded-lg text-xs font-semibold transition-all cursor-pointer shrink-0", !costInput || actionLoading === "cost" ? "bg-input text-muted cursor-not-allowed" : "bg-primary text-primary-foreground hover:bg-primary/90")}>
                                            {actionLoading === "cost" && <Loader2 size={12} className="animate-spin" />} Save
                                        </button>
                                    </div>
                                </div>
                            )}
```

- [ ] **Step 4: Run the existing web tests, extend the contract test**

Run: `cd web && npm test -- tickets`
Expected: the contract test may fail because the page now issues two extra fetches. Extend the test's fetch mock to answer `/api/proxy/v1/vendors/picker` and `/api/proxy/v1/staff/picker` with `[]` (follow the file's existing mock-routing style), and add one assertion-level test: with pickers mocked to `[{ id: "v1", nameEn: "CoolFix", detail: "Abu Khalid" }]` and an admin session, the "Dispatch to Vendor..." button renders.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd web && npm test -- tickets`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add "web/src/app/[locale]/dashboard/tickets/[id]/page.tsx" "web/src/app/[locale]/dashboard/tickets/[id]/__tests__/ticket-detail-contract.test.tsx"
git commit -m "feat(web): dispatch tickets to vendors/staff and record cost from ticket detail"
```

---

### Task 7: Web — linked expenses card + ticket-aware VendorPaymentDialog

**Files:**
- Modify: `web/src/components/vendors/VendorPaymentDialog.tsx`
- Modify: `web/src/app/[locale]/dashboard/tickets/[id]/page.tsx`
- Modify: `web/messages/en.json`, `web/messages/ar.json` (`Vendors` namespace)

**Interfaces:**
- Consumes: `GET /api/proxy/v1/tickets/{id}/transactions` (Task 4, admin-only), `maintenanceTicketId` accepted by `POST /api/proxy/v1/finance/transactions` (Task 3), `ticket.vendorId`/`vendorName` (Task 6's type).
- Produces: `VendorPaymentDialog` props gain `ticketId?: string; ticketTitle?: string`.

- [ ] **Step 1: Extend `VendorPaymentDialog`**

Add to `Props`:

```ts
    ticketId?: string;
    ticketTitle?: string;
```

destructure them in the component signature, include `maintenanceTicketId` in the submit body (after the `notes` line in the `body` object):

```ts
            if (ticketId) body.maintenanceTicketId = ticketId;
```

and render an info line as the first element inside the `<form>` when linked:

```tsx
                    {ticketId && (
                        <div className="col-span-2 bg-info/10 border border-info/20 rounded-lg px-4 py-3 text-xs text-info font-medium">
                            {t("linkedTicket", { title: ticketTitle ?? "" })}
                        </div>
                    )}
```

- [ ] **Step 2: Add the i18n keys**

`web/messages/en.json`, `Vendors` namespace:

```json
"linkedTicket": "This payment will be linked to maintenance ticket: {title}"
```

`web/messages/ar.json`, `Vendors` namespace:

```json
"linkedTicket": "سيتم ربط هذه الدفعة بتذكرة الصيانة: {title}"
```

- [ ] **Step 3: Linked-expenses card + Record Vendor Payment on ticket detail**

In `page.tsx`, add state and an admin-gated fetch:

```ts
    type TicketTxn = { id: string; date: string; description: string; debit: number; credit: number };
    const [ticketTxns, setTicketTxns] = useState<TicketTxn[]>([]);
    const [showVendorPayment, setShowVendorPayment] = useState(false);

    const fetchTicketTxns = useCallback(async () => {
        // /tickets/{id}/transactions is finance data — TENANT_ADMIN/SUPER_ADMIN only.
        if (!hasRole(userRole, ["SUPER_ADMIN", "TENANT_ADMIN"])) return;
        try {
            const res = await fetch(`/api/proxy/v1/tickets/${ticketId}/transactions`);
            if (res.ok) setTicketTxns(await res.json());
        } catch { /* card just stays empty */ }
    }, [ticketId, userRole]);
```

Add `fetchTicketTxns()` to the mount `Promise.all` (and dependency array). Import the dialog: `import VendorPaymentDialog from "@/components/vendors/VendorPaymentDialog";`.

In the right column, after the Actions card, add (admin-only):

```tsx
                    {hasRole(userRole, ["SUPER_ADMIN", "TENANT_ADMIN"]) && (
                        <div className="bg-surface rounded-xl border border-border p-5">
                            <h3 className="text-xs font-semibold text-muted uppercase tracking-wider mb-3">Maintenance Expenses</h3>
                            {ticketTxns.length === 0 ? (
                                <p className="text-xs text-muted">No linked expenses yet.</p>
                            ) : (
                                <div className="space-y-2">
                                    {ticketTxns.map(txn => (
                                        <div key={txn.id} className="flex items-center justify-between text-xs">
                                            <div>
                                                <p className="font-medium text-foreground">{txn.description}</p>
                                                <p className="text-muted">{new Date(txn.date).toLocaleDateString()}</p>
                                            </div>
                                            <span className="font-semibold text-foreground shrink-0">AED {Number(txn.debit).toLocaleString()}</span>
                                        </div>
                                    ))}
                                    <div className="flex items-center justify-between text-xs pt-2 border-t border-border">
                                        <span className="font-semibold text-muted uppercase tracking-wider">Total</span>
                                        <span className="font-bold text-foreground">AED {ticketTxns.reduce((sum, txn) => sum + Number(txn.debit || 0), 0).toLocaleString()}</span>
                                    </div>
                                </div>
                            )}
                            {ticket.vendorId && (
                                <button onClick={() => setShowVendorPayment(true)} className="mt-3 w-full flex items-center justify-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer">
                                    Record Vendor Payment
                                </button>
                            )}
                        </div>
                    )}
```

and at the bottom of the JSX (next to any existing modals/lightbox):

```tsx
            {ticket.vendorId && (
                <VendorPaymentDialog
                    open={showVendorPayment}
                    onClose={() => setShowVendorPayment(false)}
                    onSuccess={() => {
                        setShowVendorPayment(false);
                        fetchTicketTxns();
                        fetchTicket();
                    }}
                    vendor={{ id: ticket.vendorId, nameEn: ticket.vendorName ?? "" }}
                    ticketId={ticket.id}
                    ticketTitle={ticket.title}
                />
            )}
```

- [ ] **Step 4: Update the contract test mocks and run**

Add a `[]` mock response for `/api/proxy/v1/tickets/{id}/transactions` to the contract test's fetch routing (admin-session case).

Run: `cd web && npm test`
Expected: PASS (full web suite, not just tickets — the dialog is shared with the vendors and transactions pages)

- [ ] **Step 5: Commit**

```bash
git add web/src/components/vendors/VendorPaymentDialog.tsx "web/src/app/[locale]/dashboard/tickets/[id]/page.tsx" web/messages/en.json web/messages/ar.json "web/src/app/[locale]/dashboard/tickets/[id]/__tests__/ticket-detail-contract.test.tsx"
git commit -m "feat(web): link vendor payments to maintenance tickets"
```

---

### Task 8: End-to-end smoke against the live stack

**Files:** none (verification only)

**Interfaces:**
- Consumes: everything above, running against local PostgreSQL.

- [ ] **Step 1: Boot the stack and verify the migration applies**

Run: `docker compose up -d` (from the repo root; if the backend is run via `cd backend && ./gradlew bootRun` instead, ensure PostgreSQL is up first — see CLAUDE.md "Running Locally").
Check the backend log for Liquibase applying `72-maintenance-dispatch` with no errors:
Run: `docker compose logs backend | grep -i -A2 "72-maintenance-dispatch"`
Expected: the changeset ran; no `LiquibaseException`.

- [ ] **Step 2: API smoke with curl**

Log in (reuse the seeded local credentials documented in the team's local-testing notes / `project_mobile_e2e_setup`), then, with `$TOKEN`, `$TID` (an existing OPEN ticket id) and `$VID` (a vendor id):

```bash
curl -s -X GET  http://localhost:8081/api/v1/vendors/picker -H "Authorization: Bearer $TOKEN" | head -c 400
```

Expected: JSON array of `{id, nameEn, nameAr, detail}` — and no bank/IBAN fields.

```bash
curl -s -X PUT http://localhost:8081/api/v1/tickets/$TID/dispatch -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d "{\"vendorId\":\"$VID\"}" | head -c 600
```

Expected: ticket JSON with `"vendorId"`, `"vendorName"`, `"status":"ASSIGNED"`.

```bash
curl -s -X PUT http://localhost:8081/api/v1/tickets/$TID/cost -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"actualCost":450.00}' | head -c 400
```

Expected: `"actualCost":450.00` in the response.

```bash
curl -s -X GET http://localhost:8081/api/v1/tickets/$TID/transactions -H "Authorization: Bearer $TOKEN"
```

Expected: `[]` initially; after recording a vendor payment through the web dialog, one linked transaction.

(Adjust port to the local backend port if it differs — the local E2E notes use 8081.)

- [ ] **Step 3: Web smoke**

Start the web dev server, open a ticket as TENANT_ADMIN: dispatch to a vendor, record a cost, record a vendor payment via the new button, and confirm the Maintenance Expenses card lists it. Then re-check as PROPERTY_MANAGER: dispatch dropdowns render (pickers load), the Maintenance Expenses card does not.

- [ ] **Step 4: Full test suites one last time**

Run: `cd backend && ./gradlew test && cd ../web && npm test`
Expected: PASS + PASS

- [ ] **Step 5: Commit any smoke-fix fallout**

```bash
git status
```

If fixes were needed, commit them as `fix(maintenance): <what the smoke test caught>`.

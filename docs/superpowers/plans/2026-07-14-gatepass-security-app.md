# Gate Pass Module + Security Guard App Implementation Plan

> **Auth update (2026-07-30):** The WhatsApp/ACS OTP tasks below are historical
> and have been superseded by Firebase Phone Authentication. Current architecture
> and setup live in the design spec and
> `docs/runbooks/firebase-phone-auth-setup.md`; do not implement Tasks 5–6 as written.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Visitor gate passes created by renters (QR + numeric code), scanned by security guards using a new standalone Flutter app with Firebase phone login.

**Architecture:** Additive backend module (`gatepass` tables, `SECURITY_GUARD` role, Firebase ID-token exchange, scan validation service) reusing the existing header-identity auth and `NotificationService`. New Flutter app `mobile/apps/security` in the Melos monorepo consuming `rentaxis_core`; new screens in the renter app (create/share pass) and manager app (approvals, guard management).

**Tech Stack:** Java 21 / Spring Boot 4 / Liquibase / PostgreSQL 16; Flutter (Riverpod, GoRouter, `mobile_scanner`, `qr_flutter`, FlutterFire); Firebase Phone Authentication.

**Spec:** `docs/superpowers/specs/2026-07-14-gatepass-security-app-design.md`

**Read first:**
- Branch: work on `feat/gatepass-security-app` (off `main`).
- **Migration numbering:** latest changeset on `main` is `63-*`; `64-*` is taken by the unmerged `feat/cheque-deposit-reminder` branch. **Use `65-gate-pass.yaml`** and leave 64 unused here.
- **Prerequisite (business, not code):** standalone guard app deviates from SOW §3.2/§3.6 — a Change Request with GDH is required before client delivery. Code can proceed.
- Entity/repo/controller conventions: mirror `backend/src/main/java/com/datagami/rentaxis/domain/entity/Unit.java`, `domain/repository/*Repository.java`, `api/*Controller.java`. All entities extend `BaseTenantEntity` (gives `id`, `tenantId`, audit timestamps — check that class before writing entities and adjust field declarations to match exactly).
- Run backend tests with `cd backend && ./gradlew test --tests '<pattern>'`.

---

## Phase 1 — Backend

### Task 1: Liquibase changeset + `SECURITY_GUARD` role

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/65-gate-pass.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append include)
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/UserRole.java`

- [ ] **Step 1: Write the changeset**

```yaml
databaseChangeLog:
  - changeSet:
      id: 65-gate-pass
      author: rentaxis-system
      changes:
        - createTable:
            tableName: gate_passes
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: property_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_gate_pass_property, referencedTableName: properties, referencedColumnNames: id } }
              - column: { name: unit_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_gate_pass_unit, referencedTableName: units, referencedColumnNames: id } }
              - column: { name: created_by_user_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_gate_pass_creator, referencedTableName: users, referencedColumnNames: id } }
              - column: { name: guest_name, type: varchar(160), constraints: { nullable: false } }
              - column: { name: guest_phone, type: varchar(32), constraints: { nullable: false } }
              - column: { name: purpose, type: varchar(240) }
              - column: { name: vehicle_number, type: varchar(32) }
              - column: { name: pass_type, type: varchar(20), constraints: { nullable: false } }
              - column: { name: valid_from, type: timestamptz, constraints: { nullable: false } }
              - column: { name: valid_to, type: timestamptz, constraints: { nullable: false } }
              - column: { name: status, type: varchar(24), constraints: { nullable: false } }
              - column: { name: qr_token, type: varchar(64), constraints: { nullable: false, unique: true } }
              - column: { name: numeric_code, type: varchar(8), constraints: { nullable: false } }
              - column: { name: approved_by_user_id, type: uuid }
              - column: { name: approved_at, type: timestamptz }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
              - column: { name: updated_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - createIndex: { tableName: gate_passes, indexName: idx_gate_pass_tenant, columns: [ { column: { name: tenant_id } } ] }
        - createIndex: { tableName: gate_passes, indexName: idx_gate_pass_property_window, columns: [ { column: { name: property_id } }, { column: { name: valid_from } } ] }
        - createIndex: { tableName: gate_passes, indexName: idx_gate_pass_creator, columns: [ { column: { name: created_by_user_id } } ] }
        - sql:
            sql: >
              CREATE UNIQUE INDEX uq_gate_pass_numeric_active ON gate_passes (tenant_id, numeric_code)
              WHERE status IN ('PENDING_APPROVAL','ACTIVE');
        - createTable:
            tableName: gate_pass_scans
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: gate_pass_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_scan_gate_pass, referencedTableName: gate_passes, referencedColumnNames: id } }
              - column: { name: direction, type: varchar(8), constraints: { nullable: false } }
              - column: { name: scanned_by_user_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_scan_guard, referencedTableName: users, referencedColumnNames: id } }
              - column: { name: scanned_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
              - column: { name: result, type: varchar(12), constraints: { nullable: false } }
              - column: { name: rejection_reason, type: varchar(120) }
        - createIndex: { tableName: gate_pass_scans, indexName: idx_scan_pass, columns: [ { column: { name: gate_pass_id } } ] }
        - createIndex: { tableName: gate_pass_scans, indexName: idx_scan_tenant_time, columns: [ { column: { name: tenant_id } }, { column: { name: scanned_at } } ] }
        - createTable:
            tableName: guard_property_assignments
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: user_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_gpa_user, referencedTableName: users, referencedColumnNames: id } }
              - column: { name: property_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_gpa_property, referencedTableName: properties, referencedColumnNames: id } }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - addUniqueConstraint: { tableName: guard_property_assignments, columnNames: "user_id, property_id", constraintName: uq_gpa_user_property }
        - createTable:
            tableName: login_otps
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: phone_number, type: varchar(32), constraints: { nullable: false } }
              - column: { name: code_hash, type: varchar(72), constraints: { nullable: false } }
              - column: { name: expires_at, type: timestamptz, constraints: { nullable: false } }
              - column: { name: attempt_count, type: int, constraints: { nullable: false }, defaultValueNumeric: 0 }
              - column: { name: consumed_at, type: timestamptz }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - createIndex: { tableName: login_otps, indexName: idx_login_otp_phone, columns: [ { column: { name: phone_number } }, { column: { name: created_at } } ] }
        - sql:
            sql: >
              CREATE UNIQUE INDEX uq_users_guard_phone ON users (phone_number)
              WHERE role = 'SECURITY_GUARD';
```

- [ ] **Step 2: Register in master changelog** — append to `db.changelog-master.yaml`:

```yaml
  - include:
      file: db/changelog/changesets/65-gate-pass.yaml
```

- [ ] **Step 3: Add the enum value** in `UserRole.java`:

```java
public enum UserRole {
    SUPER_ADMIN, TENANT_ADMIN, PROPERTY_MANAGER, TENANT_USER, RENTER, SECURITY_GUARD
}
```

- [ ] **Step 4: Verify migration applies** — `cd backend && ./gradlew bootRun` against local Postgres (docker compose up -d postgres). Expected: Liquibase log line `ChangeSet ...65-gate-pass ran successfully`, app starts. Stop it.

- [ ] **Step 5: Commit** — `git add -A backend && git commit -m "feat(gatepass): schema for gate passes, scans, guard assignments, login OTPs + SECURITY_GUARD role"`

### Task 2: Entities, enums, repositories

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/GatePassType.java`, `GatePassStatus.java`, `ScanDirection.java`, `ScanResult.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/GatePass.java`, `GatePassScan.java`, `GuardPropertyAssignment.java`, `LoginOtp.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/GatePassRepository.java`, `GatePassScanRepository.java`, `GuardPropertyAssignmentRepository.java`, `LoginOtpRepository.java`

- [ ] **Step 1: Enums** (one file each, package `com.datagami.rentaxis.domain.entity.enums`):

```java
public enum GatePassType { SINGLE_USE, RECURRING }
public enum GatePassStatus { PENDING_APPROVAL, ACTIVE, USED, EXPIRED, CANCELLED }
public enum ScanDirection { ENTRY, EXIT }
public enum ScanResult { ALLOWED, REJECTED }
```

- [ ] **Step 2: `GatePass` entity** — mirror `Unit.java`'s style (extends `BaseTenantEntity`, `@Table(name = "gate_passes")`). Fields with `@Enumerated(EnumType.STRING)` for the enums:

```java
@Entity
@Table(name = "gate_passes")
public class GatePass extends BaseTenantEntity {
    @Column(name = "property_id", nullable = false) private UUID propertyId;
    @Column(name = "unit_id", nullable = false) private UUID unitId;
    @Column(name = "created_by_user_id", nullable = false) private UUID createdByUserId;
    @Column(name = "guest_name", nullable = false) private String guestName;
    @Column(name = "guest_phone", nullable = false) private String guestPhone;
    private String purpose;
    @Column(name = "vehicle_number") private String vehicleNumber;
    @Enumerated(EnumType.STRING) @Column(name = "pass_type", nullable = false) private GatePassType passType;
    @Column(name = "valid_from", nullable = false) private OffsetDateTime validFrom;
    @Column(name = "valid_to", nullable = false) private OffsetDateTime validTo;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private GatePassStatus status;
    @Column(name = "qr_token", nullable = false, unique = true) private String qrToken;
    @Column(name = "numeric_code", nullable = false) private String numericCode;
    @Column(name = "approved_by_user_id") private UUID approvedByUserId;
    @Column(name = "approved_at") private OffsetDateTime approvedAt;
    // getters/setters per existing entity style
}
```

`GatePassScan`, `GuardPropertyAssignment` follow the same pattern (columns per the changeset). `LoginOtp` is **not** tenant-scoped — extend whatever plain base entity exists (check how a non-tenant entity like `LandlordOrg` handles id; if none exists without tenant, declare `@Id UUID id` locally):

```java
@Entity
@Table(name = "login_otps")
public class LoginOtp {
    @Id private UUID id = UUID.randomUUID();
    @Column(name = "phone_number", nullable = false) private String phoneNumber;
    @Column(name = "code_hash", nullable = false) private String codeHash;
    @Column(name = "expires_at", nullable = false) private OffsetDateTime expiresAt;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "consumed_at") private OffsetDateTime consumedAt;
    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt = OffsetDateTime.now();
    // getters/setters
}
```

- [ ] **Step 3: Repositories** (package `com.datagami.rentaxis.domain.repository`):

```java
public interface GatePassRepository extends JpaRepository<GatePass, UUID> {
    Optional<GatePass> findByQrToken(String qrToken);
    Optional<GatePass> findByTenantIdAndNumericCodeAndStatusIn(UUID tenantId, String numericCode, Collection<GatePassStatus> statuses);
    List<GatePass> findByTenantIdAndCreatedByUserIdOrderByCreatedAtDesc(UUID tenantId, UUID userId);
    List<GatePass> findByTenantIdAndPropertyIdInAndStatusAndValidFromLessThanEqualAndValidToGreaterThanEqual(
        UUID tenantId, Collection<UUID> propertyIds, GatePassStatus status, OffsetDateTime windowEnd, OffsetDateTime windowStart);
    List<GatePass> findByTenantIdAndStatusAndPropertyIdIn(UUID tenantId, GatePassStatus status, Collection<UUID> propertyIds);
    boolean existsByTenantIdAndNumericCodeAndStatusIn(UUID tenantId, String numericCode, Collection<GatePassStatus> statuses);
}

public interface GatePassScanRepository extends JpaRepository<GatePassScan, UUID> {
    List<GatePassScan> findByGatePassIdOrderByScannedAtAsc(UUID gatePassId);
    boolean existsByGatePassIdAndDirectionAndResult(UUID gatePassId, ScanDirection direction, ScanResult result);
    List<GatePassScan> findByTenantIdAndScannedAtBetween(UUID tenantId, OffsetDateTime from, OffsetDateTime to);
}

public interface GuardPropertyAssignmentRepository extends JpaRepository<GuardPropertyAssignment, UUID> {
    List<GuardPropertyAssignment> findByUserId(UUID userId);
    void deleteByUserIdAndPropertyId(UUID userId, UUID propertyId);
}

public interface LoginOtpRepository extends JpaRepository<LoginOtp, UUID> {
    Optional<LoginOtp> findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(String phoneNumber);
    long countByPhoneNumberAndCreatedAtAfter(String phoneNumber, OffsetDateTime after);
}
```

- [ ] **Step 4: Compile** — `cd backend && ./gradlew compileJava`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** — `git commit -am "feat(gatepass): entities and repositories"`

### Task 3: GatePassService — create / approve / cancel (TDD)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/GatePassService.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/GatePassServiceTest.java`

- [ ] **Step 1: Write failing tests** (plain Mockito unit test, mirror an existing service test in `backend/src/test/java/com/datagami/rentaxis/core/` for style):

```java
@ExtendWith(MockitoExtension.class)
class GatePassServiceTest {
    @Mock GatePassRepository passRepo;
    @Mock NotificationService notifications;
    @InjectMocks GatePassService service;

    UUID tenantId = UUID.randomUUID(); UUID userId = UUID.randomUUID();

    @Test void singleUsePassIsActiveImmediatelyWithCodes() {
        when(passRepo.existsByTenantIdAndNumericCodeAndStatusIn(any(), any(), any())).thenReturn(false);
        when(passRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        GatePass p = service.create(tenantId, userId, propId(), unitId(), "Ali", "+9715...", "Visit",
                null, GatePassType.SINGLE_USE, from(), to());
        assertEquals(GatePassStatus.ACTIVE, p.getStatus());
        assertNotNull(p.getQrToken());
        assertEquals(8, p.getNumericCode().length());
    }

    @Test void recurringPassStartsPendingApprovalAndNotifies() {
        when(passRepo.existsByTenantIdAndNumericCodeAndStatusIn(any(), any(), any())).thenReturn(false);
        when(passRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        GatePass p = service.create(tenantId, userId, propId(), unitId(), "Maid", "+9715...", "Daily help",
                null, GatePassType.RECURRING, from(), to());
        assertEquals(GatePassStatus.PENDING_APPROVAL, p.getStatus());
    }

    @Test void numericCodeRegeneratedOnCollision() {
        when(passRepo.existsByTenantIdAndNumericCodeAndStatusIn(any(), any(), any()))
            .thenReturn(true).thenReturn(false);
        when(passRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service.create(tenantId, userId, propId(), unitId(), "Ali", "+9715...", null,
                null, GatePassType.SINGLE_USE, from(), to());
        verify(passRepo, times(2)).existsByTenantIdAndNumericCodeAndStatusIn(any(), any(), any());
    }

    @Test void approveActivatesAndStampsApprover() { /* set status PENDING_APPROVAL, call approve, assert ACTIVE + approvedBy */ }
    @Test void cancelRejectsAlreadyUsedPass() { /* status USED -> expect IllegalStateException */ }
}
```

(Write `approveActivatesAndStampsApprover` and `cancelRejectsAlreadyUsedPass` out fully — construct a `GatePass`, stub `passRepo.findById`, assert the state transition and that `notifications.notify(...)` was called with type `"GATE_PASS_APPROVED"`.)

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests '*GatePassServiceTest*'`. Expected: compile error (`GatePassService` missing).

- [ ] **Step 3: Implement**

```java
@Service
public class GatePassService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final EnumSet<GatePassStatus> NON_TERMINAL =
        EnumSet.of(GatePassStatus.PENDING_APPROVAL, GatePassStatus.ACTIVE);

    private final GatePassRepository passRepo;
    private final NotificationService notifications;
    // constructor injection

    @Transactional
    public GatePass create(UUID tenantId, UUID createdBy, UUID propertyId, UUID unitId,
                           String guestName, String guestPhone, String purpose, String vehicleNumber,
                           GatePassType type, OffsetDateTime validFrom, OffsetDateTime validTo) {
        if (!validTo.isAfter(validFrom)) throw new IllegalArgumentException("validTo must be after validFrom");
        GatePass p = new GatePass();
        p.setTenantId(tenantId); p.setCreatedByUserId(createdBy);
        p.setPropertyId(propertyId); p.setUnitId(unitId);
        p.setGuestName(guestName); p.setGuestPhone(guestPhone);
        p.setPurpose(purpose); p.setVehicleNumber(vehicleNumber);
        p.setPassType(type); p.setValidFrom(validFrom); p.setValidTo(validTo);
        p.setStatus(type == GatePassType.RECURRING ? GatePassStatus.PENDING_APPROVAL : GatePassStatus.ACTIVE);
        p.setQrToken(UUID.randomUUID().toString().replace("-", "") + Long.toHexString(RANDOM.nextLong()));
        p.setNumericCode(uniqueNumericCode(tenantId));
        return passRepo.save(p);
    }

    private String uniqueNumericCode(UUID tenantId) {
        for (int i = 0; i < 10; i++) {
            String code = String.format("%08d", RANDOM.nextInt(100_000_000));
            if (!passRepo.existsByTenantIdAndNumericCodeAndStatusIn(tenantId, code, NON_TERMINAL)) return code;
        }
        throw new IllegalStateException("could not allocate numeric code");
    }

    @Transactional
    public GatePass approve(UUID tenantId, UUID passId, UUID approverId, boolean approved) {
        GatePass p = passRepo.findById(passId)
            .filter(x -> x.getTenantId().equals(tenantId))
            .orElseThrow(() -> new EntityNotFoundException("gate pass"));
        if (p.getStatus() != GatePassStatus.PENDING_APPROVAL)
            throw new IllegalStateException("pass is not pending approval");
        p.setStatus(approved ? GatePassStatus.ACTIVE : GatePassStatus.CANCELLED);
        p.setApprovedByUserId(approverId); p.setApprovedAt(OffsetDateTime.now());
        notifications.notify(tenantId, p.getCreatedByUserId(),
            approved ? "GATE_PASS_APPROVED" : "GATE_PASS_REJECTED",
            approved ? "Gate pass approved" : "Gate pass rejected",
            "Pass for " + p.getGuestName(), "GATE_PASS", p.getId());
        return passRepo.save(p);
    }

    @Transactional
    public GatePass cancel(UUID tenantId, UUID passId, UUID requesterId) {
        GatePass p = passRepo.findById(passId)
            .filter(x -> x.getTenantId().equals(tenantId) && x.getCreatedByUserId().equals(requesterId))
            .orElseThrow(() -> new EntityNotFoundException("gate pass"));
        if (p.getStatus() == GatePassStatus.USED) throw new IllegalStateException("pass already used");
        p.setStatus(GatePassStatus.CANCELLED);
        return passRepo.save(p);
    }
}
```

- [ ] **Step 4: Run tests** — `./gradlew test --tests '*GatePassServiceTest*'`. Expected: all PASS.
- [ ] **Step 5: Commit** — `git commit -am "feat(gatepass): pass creation, approval, cancellation service"`

### Task 4: Scan validation service (TDD)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/GatePassScanService.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/core/GatePassScanServiceTest.java`

- [ ] **Step 1: Failing tests covering the validation matrix.** One test per row; each builds a pass + guard assignment and asserts `ScanOutcome` (a small record the service returns: `record ScanOutcome(ScanResult result, String reason, GatePass pass)`).

| Case | Expected |
|---|---|
| ACTIVE single-use, inside window, guard assigned to property, ENTRY | ALLOWED; pass → USED; scan row written; renter notified `GATE_PASS_ARRIVAL` |
| Same pass scanned ENTRY twice | second → REJECTED "already used" |
| Recurring ACTIVE, inside validity, second ENTRY on later day | ALLOWED (recurring is multi-entry); status stays ACTIVE |
| Before `valid_from` / after `valid_to` | REJECTED "outside validity window" (after → also marks pass EXPIRED) |
| Status PENDING_APPROVAL | REJECTED "pending approval" |
| Status CANCELLED | REJECTED "cancelled" |
| Guard not assigned to the pass's property | REJECTED "not authorized for this property" |
| Unknown qrToken/numericCode | REJECTED "not found" |
| EXIT scan for a pass with a prior ALLOWED ENTRY | ALLOWED, logged, no status change |
| EXIT with no prior entry | REJECTED "no entry recorded" |

Rejected scans (except "not found") also write a `gate_pass_scans` row with `result=REJECTED` and the reason.

- [ ] **Step 2: Run to verify failure** — `./gradlew test --tests '*GatePassScanServiceTest*'`. Expected: FAIL (class missing).

- [ ] **Step 3: Implement**

```java
@Service
public class GatePassScanService {
    public record ScanOutcome(ScanResult result, String reason, GatePass pass) {}

    private final GatePassRepository passRepo;
    private final GatePassScanRepository scanRepo;
    private final GuardPropertyAssignmentRepository assignmentRepo;
    private final NotificationService notifications;
    // constructor injection

    @Transactional
    public ScanOutcome scan(UUID tenantId, UUID guardUserId, String qrToken, String numericCode, ScanDirection direction) {
        GatePass pass = (qrToken != null
                ? passRepo.findByQrToken(qrToken)
                : passRepo.findByTenantIdAndNumericCodeAndStatusIn(tenantId, numericCode,
                      EnumSet.of(GatePassStatus.PENDING_APPROVAL, GatePassStatus.ACTIVE, GatePassStatus.USED)))
            .filter(p -> p.getTenantId().equals(tenantId))
            .orElse(null);
        if (pass == null) return new ScanOutcome(ScanResult.REJECTED, "not found", null);

        Set<UUID> guardProps = assignmentRepo.findByUserId(guardUserId).stream()
            .map(GuardPropertyAssignment::getPropertyId).collect(Collectors.toSet());
        if (!guardProps.contains(pass.getPropertyId()))
            return reject(pass, guardUserId, direction, "not authorized for this property");

        OffsetDateTime now = OffsetDateTime.now();
        if (direction == ScanDirection.EXIT) {
            if (!scanRepo.existsByGatePassIdAndDirectionAndResult(pass.getId(), ScanDirection.ENTRY, ScanResult.ALLOWED))
                return reject(pass, guardUserId, direction, "no entry recorded");
            record(pass, guardUserId, ScanDirection.EXIT, ScanResult.ALLOWED, null);
            return new ScanOutcome(ScanResult.ALLOWED, null, pass);
        }
        switch (pass.getStatus()) {
            case PENDING_APPROVAL: return reject(pass, guardUserId, direction, "pending approval");
            case CANCELLED:        return reject(pass, guardUserId, direction, "cancelled");
            case EXPIRED:          return reject(pass, guardUserId, direction, "expired");
            case USED:             return reject(pass, guardUserId, direction, "already used");
            case ACTIVE:           break;
        }
        if (now.isBefore(pass.getValidFrom()))
            return reject(pass, guardUserId, direction, "outside validity window");
        if (now.isAfter(pass.getValidTo())) {
            pass.setStatus(GatePassStatus.EXPIRED); passRepo.save(pass);
            return reject(pass, guardUserId, direction, "outside validity window");
        }
        if (pass.getPassType() == GatePassType.SINGLE_USE) {
            pass.setStatus(GatePassStatus.USED); passRepo.save(pass);
        }
        record(pass, guardUserId, ScanDirection.ENTRY, ScanResult.ALLOWED, null);
        notifications.notify(tenantId, pass.getCreatedByUserId(), "GATE_PASS_ARRIVAL",
            "Your guest has arrived", pass.getGuestName() + " was scanned in at the gate",
            "GATE_PASS", pass.getId());
        return new ScanOutcome(ScanResult.ALLOWED, null, pass);
    }

    private ScanOutcome reject(GatePass p, UUID guard, ScanDirection dir, String reason) {
        record(p, guard, dir, ScanResult.REJECTED, reason);
        return new ScanOutcome(ScanResult.REJECTED, reason, p);
    }
    private void record(GatePass p, UUID guard, ScanDirection dir, ScanResult res, String reason) {
        GatePassScan s = new GatePassScan();
        s.setTenantId(p.getTenantId()); s.setGatePassId(p.getId());
        s.setDirection(dir); s.setScannedByUserId(guard);
        s.setScannedAt(OffsetDateTime.now()); s.setResult(res); s.setRejectionReason(reason);
        scanRepo.save(s);
    }
}
```

- [ ] **Step 4: Run tests** — `./gradlew test --tests '*GatePassScanServiceTest*'`. Expected: PASS.
- [ ] **Step 5: Commit** — `git commit -am "feat(gatepass): scan validation with entry/exit logging and arrival notification"`

### Task 5: OTP auth — service, `OtpSender`, endpoints (TDD)

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/otp/OtpSender.java`, `LoggingOtpSender.java`, `OtpLoginService.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/AuthController.java` (two endpoints; `/api/auth/**` is already permitAll in `SecurityConfig`)
- Test: `backend/src/test/java/com/datagami/rentaxis/core/OtpLoginServiceTest.java`

- [ ] **Step 1: Failing tests**

```java
@ExtendWith(MockitoExtension.class)
class OtpLoginServiceTest {
    @Mock LoginOtpRepository otpRepo;
    @Mock UserRepository userRepo;   // confirm actual repo name/package before writing
    @Mock OtpSender sender;
    @InjectMocks OtpLoginService service;

    @Test void requestSendsCodeForActiveGuard() { /* guard user exists -> otpRepo.save called, sender.send(phone, code) called */ }
    @Test void requestIsSilentForUnknownPhone() { /* no user -> no send, no exception (anti-enumeration) */ }
    @Test void requestThrottledAfterThreeInFifteenMinutes() { /* countByPhoneNumberAndCreatedAtAfter returns 3 -> TooManyRequests exception, no send */ }
    @Test void verifyHappyPathConsumesOtpAndReturnsUser() { /* bcrypt-matching code, not expired -> returns guard User, consumedAt set */ }
    @Test void verifyWrongCodeIncrementsAttempts() { /* wrong code -> BadCredentials, attemptCount incremented */ }
    @Test void verifyFailsAfterFiveAttempts() { /* attemptCount=5 -> rejected even with correct code */ }
    @Test void verifyFailsWhenExpired() { /* expiresAt in past -> rejected */ }
}
```

Write each body fully: build a `User` with `role=SECURITY_GUARD`, `status=ACTIVE`, phone `+971501234567`; stub `userRepo` lookup by phone+role (add `Optional<User> findByPhoneNumberAndRole(String phone, UserRole role)` to the existing `UserRepository` if absent); use a real `BCryptPasswordEncoder` in the service so tests hash a known code.

- [ ] **Step 2: Run to verify failure.** `./gradlew test --tests '*OtpLoginServiceTest*'` — FAIL.

- [ ] **Step 3: Implement**

```java
public interface OtpSender { void send(String phoneNumber, String code); }

@Component
@ConditionalOnProperty(name = "gatepass.otp.channel", havingValue = "log", matchIfMissing = true)
public class LoggingOtpSender implements OtpSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingOtpSender.class);
    public void send(String phoneNumber, String code) {
        log.warn("DEV OTP for {}: {}", phoneNumber, code);
    }
}

@Service
public class OtpLoginService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_ATTEMPTS = 5;
    private final LoginOtpRepository otpRepo;
    private final UserRepository userRepo;
    private final OtpSender sender;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    // constructor injection

    @Transactional
    public void requestOtp(String phone) {
        String normalized = normalize(phone);
        if (otpRepo.countByPhoneNumberAndCreatedAtAfter(normalized, OffsetDateTime.now().minusMinutes(15)) >= 3)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many OTP requests");
        Optional<User> guard = userRepo.findByPhoneNumberAndRole(normalized, UserRole.SECURITY_GUARD)
            .filter(u -> u.getStatus() == UserStatus.ACTIVE);
        if (guard.isEmpty()) return; // 200 either way — no user enumeration
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        LoginOtp otp = new LoginOtp();
        otp.setPhoneNumber(normalized);
        otp.setCodeHash(encoder.encode(code));
        otp.setExpiresAt(OffsetDateTime.now().plusMinutes(5));
        otpRepo.save(otp);
        sender.send(normalized, code);
    }

    @Transactional
    public User verifyOtp(String phone, String code) {
        String normalized = normalize(phone);
        LoginOtp otp = otpRepo.findTopByPhoneNumberAndConsumedAtIsNullOrderByCreatedAtDesc(normalized)
            .filter(o -> o.getExpiresAt().isAfter(OffsetDateTime.now()))
            .filter(o -> o.getAttemptCount() < MAX_ATTEMPTS)
            .orElseThrow(() -> new BadCredentialsException("invalid or expired code"));
        if (!encoder.matches(code, otp.getCodeHash())) {
            otp.setAttemptCount(otp.getAttemptCount() + 1);
            otpRepo.save(otp);
            throw new BadCredentialsException("invalid or expired code");
        }
        otp.setConsumedAt(OffsetDateTime.now());
        otpRepo.save(otp);
        return userRepo.findByPhoneNumberAndRole(normalized, UserRole.SECURITY_GUARD)
            .filter(u -> u.getStatus() == UserStatus.ACTIVE)
            .orElseThrow(() -> new BadCredentialsException("no active guard for phone"));
    }

    private String normalize(String phone) {
        String p = phone.replaceAll("[\\s-]", "");
        if (!p.matches("\\+\\d{8,15}")) throw new IllegalArgumentException("phone must be E.164, e.g. +9715xxxxxxxx");
        return p;
    }
}
```

- [ ] **Step 4: Endpoints in `AuthController`** (reuse the existing `AuthResponse` record and multi-tenant helper used by `/login`):

```java
public record OtpRequestBody(String phone) {}
public record OtpVerifyBody(String phone, String code) {}

@PostMapping("/otp/request")
public ResponseEntity<Void> requestOtp(@RequestBody OtpRequestBody body) {
    otpLoginService.requestOtp(body.phone());
    return ResponseEntity.ok().build();
}

@PostMapping("/otp/verify")
public ResponseEntity<AuthResponse> verifyOtp(@RequestBody OtpVerifyBody body) {
    User user = otpLoginService.verifyOtp(body.phone(), body.code());
    return ResponseEntity.ok(new AuthResponse(
        user.getId().toString(), user.getEmail(), user.getName(), user.getRole().name(),
        user.getTenantId() != null ? user.getTenantId().toString() : null,
        List.of() /* match how /login builds tenantIds — copy that block */));
}
```

- [ ] **Step 5: Run tests + compile** — `./gradlew test --tests '*OtpLoginServiceTest*' compileJava`. Expected: PASS.
- [ ] **Step 6: Manual check** — `./gradlew bootRun`; `curl -X POST localhost:8080/api/auth/otp/request -H 'Content-Type: application/json' -d '{"phone":"+971501234567"}'` → 200 and (after creating a guard user) code in logs; verify returns the AuthResponse JSON.
- [ ] **Step 7: Commit** — `git commit -am "feat(auth): phone OTP login for security guards with pluggable OtpSender"`

### Task 6: ACS WhatsApp OTP sender (config-gated)

**Files:**
- Modify: `backend/build.gradle` — add `implementation 'com.azure:azure-communication-messages:1.1.5'` (align version with the existing `azure-communication-email` BOM usage)
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/otp/AcsWhatsAppOtpSender.java`

- [ ] **Step 1: Implement** (activated only when configured; `LoggingOtpSender` remains the default):

```java
@Component
@ConditionalOnProperty(name = "gatepass.otp.channel", havingValue = "whatsapp")
public class AcsWhatsAppOtpSender implements OtpSender {
    private final NotificationMessagesClient client;
    private final String channelRegistrationId; // ACS WhatsApp channel GUID
    private final String templateName;          // Meta-approved AUTHENTICATION template

    public AcsWhatsAppOtpSender(
            @Value("${AZURE_COMMUNICATION_CONNECTION_STRING}") String connectionString,
            @Value("${gatepass.otp.whatsapp-channel-id}") String channelRegistrationId,
            @Value("${gatepass.otp.template-name:gatepass_otp}") String templateName) {
        this.client = new NotificationMessagesClientBuilder().connectionString(connectionString).buildClient();
        this.channelRegistrationId = channelRegistrationId;
        this.templateName = templateName;
    }

    @Override
    public void send(String phoneNumber, String code) {
        MessageTemplate template = new MessageTemplate(templateName, "en");
        MessageTemplateText body = new MessageTemplateText("otp", code);
        // Authentication templates require the code in both body and url-button component:
        MessageTemplateQuickAction button = new MessageTemplateQuickAction("verify").setPayload(code);
        template.setValues(List.of(body, button))
                .setBindings(new WhatsAppMessageTemplateBindings()
                    .setBody(List.of(new WhatsAppMessageTemplateBindingsComponent("otp")))
                    .setButtons(List.of(new WhatsAppMessageTemplateBindingsButton(
                        WhatsAppMessageButtonSubType.URL.toString(), "verify"))));
        client.send(new TemplateNotificationContent(channelRegistrationId, List.of(phoneNumber), template));
    }
}
```

> Verify exact SDK class names against the installed `azure-communication-messages` version javadoc — Azure renamed some binding classes between betas. Compile is the check.

- [ ] **Step 2: Config** — document in `backend/src/main/resources/application.yaml` (defaults) and `.env.example` if present:

```yaml
gatepass:
  otp:
    channel: ${GATEPASS_OTP_CHANNEL:log}      # log | whatsapp
    whatsapp-channel-id: ${ACS_WHATSAPP_CHANNEL_ID:}
    template-name: ${GATEPASS_OTP_TEMPLATE_NAME:gatepass_otp}
```

- [ ] **Step 3: Compile** — `./gradlew compileJava`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** — `git commit -am "feat(auth): ACS WhatsApp OTP sender behind gatepass.otp.channel config"`

**Ops prerequisites (parallel, not code):** connect a WhatsApp Business Account to the existing ACS resource (Azure portal → ACS → Advanced Messaging → Connect WhatsApp), create an *authentication* template named `gatepass_otp`, wait for Meta approval, then set `GATEPASS_OTP_CHANNEL=whatsapp` + `ACS_WHATSAPP_CHANNEL_ID` in the deploy env.

### Task 7: Controllers — renter, guard, manager endpoints + RBAC

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/GatePassController.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/GatePassDtos.java` (records)
- Test: `backend/src/test/java/com/datagami/rentaxis/api/GatePassControllerTest.java` (`@WebMvcTest` if the codebase has them; otherwise MockMvc standalone — mirror an existing controller test)

- [ ] **Step 1: DTOs**

```java
public class GatePassDtos {
    public record CreateGatePassRequest(UUID propertyId, UUID unitId, String guestName, String guestPhone,
        String purpose, String vehicleNumber, GatePassType passType, OffsetDateTime validFrom, OffsetDateTime validTo) {}
    public record GatePassResponse(UUID id, UUID propertyId, UUID unitId, String guestName, String guestPhone,
        String purpose, String vehicleNumber, GatePassType passType, OffsetDateTime validFrom, OffsetDateTime validTo,
        GatePassStatus status, String qrToken, String numericCode, OffsetDateTime createdAt) {}
    // Guard-facing view: NO renter identity beyond unit number; no financials anywhere.
    public record ScanRequest(String qrToken, String numericCode, ScanDirection direction) {}
    public record ScanResponse(ScanResult result, String reason, String guestName, String guestPhone,
        String vehicleNumber, String purpose, String unitNumber, GatePassType passType,
        OffsetDateTime validFrom, OffsetDateTime validTo) {}
    public record ApprovalDecision(boolean approved) {}
    public static GatePassResponse toResponse(GatePass p) { /* straightforward mapping */ }
}
```

- [ ] **Step 2: Controller** — identity comes from the security context the same way existing controllers read it (copy the pattern used by e.g. `MarketplaceController` to get user id/tenant id):

```java
@RestController
@RequestMapping("/api/v1/gatepass")
public class GatePassController {
    // inject GatePassService, GatePassScanService, GatePassRepository,
    // GuardPropertyAssignmentRepository, UnitRepository

    @PostMapping
    @PreAuthorize("hasRole('RENTER')")
    public GatePassResponse create(@RequestBody CreateGatePassRequest req) { ... } // validate unit belongs to caller's active lease (reuse lease lookup used by renter endpoints)

    @GetMapping("/mine")
    @PreAuthorize("hasRole('RENTER')")
    public List<GatePassResponse> mine() { ... }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('RENTER')")
    public GatePassResponse cancel(@PathVariable UUID id) { ... }

    @PostMapping("/scan")
    @PreAuthorize("hasRole('SECURITY_GUARD')")
    public ScanResponse scan(@RequestBody ScanRequest req) { ... } // maps ScanOutcome -> ScanResponse; looks up unitNumber via UnitRepository

    @GetMapping("/expected-today")
    @PreAuthorize("hasRole('SECURITY_GUARD')")
    public List<ScanResponse> expectedToday() { ... } // ACTIVE passes overlapping today for guard's assigned properties

    @GetMapping("/approvals")
    @PreAuthorize("hasAnyRole('SECURITY_GUARD','TENANT_ADMIN','PROPERTY_MANAGER')")
    public List<GatePassResponse> approvals() { ... } // PENDING_APPROVAL; guards see only assigned properties, managers see all tenant passes

    @PostMapping("/{id}/approval")
    @PreAuthorize("hasAnyRole('SECURITY_GUARD','TENANT_ADMIN','PROPERTY_MANAGER')")
    public GatePassResponse decide(@PathVariable UUID id, @RequestBody ApprovalDecision d) { ... }

    @GetMapping("/report")
    @PreAuthorize("hasAnyRole('TENANT_ADMIN','PROPERTY_MANAGER')")
    public List<Map<String, Object>> report(@RequestParam OffsetDateTime from, @RequestParam OffsetDateTime to,
                                            @RequestParam(required = false) UUID propertyId) { ... } // scans joined to passes

    // Guard assignment management
    @GetMapping("/guards/{userId}/properties") @PreAuthorize("hasAnyRole('TENANT_ADMIN','PROPERTY_MANAGER')")
    public List<UUID> guardProperties(@PathVariable UUID userId) { ... }
    @PutMapping("/guards/{userId}/properties") @PreAuthorize("hasAnyRole('TENANT_ADMIN','PROPERTY_MANAGER')")
    public void setGuardProperties(@PathVariable UUID userId, @RequestBody List<UUID> propertyIds) { ... } // replace-all semantics; verify target user role == SECURITY_GUARD and same tenant
}
```

Every `...` body is a thin delegation to the Task 3/4 services plus the tenant/user extraction pattern; write them out during implementation — no business logic in the controller.

- [ ] **Step 3: Controller tests** — happy-path per endpoint + two RBAC denials that matter most: `RENTER` calling `/scan` → 403, `SECURITY_GUARD` calling `/report` → 403. Also assert `ScanResponse` JSON contains no `createdByUserId`/lease fields.
- [ ] **Step 4: Run** — `./gradlew test --tests '*GatePass*'`. Expected: PASS.
- [ ] **Step 5: Also update `UserController`'s role-assignment gate** — `canAssignRole`/`privilegeRank` must allow TENANT_ADMIN/PROPERTY_MANAGER to create `SECURITY_GUARD` users (rank it lowest, alongside RENTER). Guard creation requires `phoneNumber`; add a service-level check rejecting a phone already used by another guard (any tenant) with a clear 409.
- [ ] **Step 6: Full build** — `./gradlew test`. Expected: PASS (fix anything the enum addition broke, e.g. exhaustive switches).
- [ ] **Step 7: Commit** — `git commit -am "feat(gatepass): REST endpoints with SECURITY_GUARD RBAC, report, guard assignments"`

---

## Phase 2 — Guard app (`mobile/apps/security`)

### Task 8: rentaxis_core additions (OTP + gatepass API service)

**Files:**
- Modify: `mobile/packages/rentaxis_core/lib/api/services/auth_service.dart`
- Create: `mobile/packages/rentaxis_core/lib/api/services/gate_pass_service.dart`
- Modify: `mobile/packages/rentaxis_core/lib/providers/auth_provider.dart`
- Modify: `mobile/packages/rentaxis_core/lib/rentaxis_core.dart` (export)

- [ ] **Step 1: AuthService methods**

```dart
Future<void> requestOtp(String phone) async {
  await _client.dio.post('/auth/otp/request', data: {'phone': phone});
}

Future<Map<String, dynamic>> verifyOtp(String phone, String code) async {
  final res = await _client.dio.post('/auth/otp/verify', data: {'phone': phone, 'code': code});
  return res.data as Map<String, dynamic>;
}
```

- [ ] **Step 2: `AuthNotifier.loginWithOtp`** — mirror the existing `login(email, password)`: call `verifyOtp`, persist `userId`, `userRole`, `tenantId`, `userTenantId` to `FlutterSecureStorage`, set state authenticated.

- [ ] **Step 3: GatePassApiService** (same thin-Dio style as the other ~24 services):

```dart
class GatePassApiService {
  final ApiClient _client;
  GatePassApiService(this._client);

  Future<Map<String, dynamic>> create(Map<String, dynamic> body) async =>
      (await _client.dio.post('/v1/gatepass', data: body)).data;
  Future<List<dynamic>> mine() async => (await _client.dio.get('/v1/gatepass/mine')).data;
  Future<Map<String, dynamic>> cancel(String id) async =>
      (await _client.dio.post('/v1/gatepass/$id/cancel')).data;
  Future<Map<String, dynamic>> scan({String? qrToken, String? numericCode, required String direction}) async =>
      (await _client.dio.post('/v1/gatepass/scan',
          data: {'qrToken': qrToken, 'numericCode': numericCode, 'direction': direction})).data;
  Future<List<dynamic>> expectedToday() async => (await _client.dio.get('/v1/gatepass/expected-today')).data;
  Future<List<dynamic>> approvals() async => (await _client.dio.get('/v1/gatepass/approvals')).data;
  Future<Map<String, dynamic>> decide(String id, bool approved) async =>
      (await _client.dio.post('/v1/gatepass/$id/approval', data: {'approved': approved})).data;
}
```

- [ ] **Step 4: Export + analyze** — add exports to `rentaxis_core.dart`; run `cd mobile && melos bootstrap && cd packages/rentaxis_core && flutter analyze`. Expected: no errors.
- [ ] **Step 5: Commit** — `git commit -am "feat(mobile-core): OTP login + gate pass API service"`

### Task 9: Scaffold the security app

**Files:**
- Create: `mobile/apps/security/` (flutter create), `pubspec.yaml`, `lib/main.dart`, `lib/app.dart`, `lib/router.dart`

- [ ] **Step 1:** `cd mobile/apps && flutter create --org com.datagami.rentaxis --project-name rentaxis_security security --platforms android,ios`
- [ ] **Step 2: pubspec.yaml deps** (mirror renter app versions):

```yaml
dependencies:
  flutter: { sdk: flutter }
  rentaxis_core: { path: ../../packages/rentaxis_core }
  flutter_riverpod: ^2.6.1
  go_router: ^14.8.1
  mobile_scanner: ^6.0.2
  intl: ^0.19.0
```

- [ ] **Step 3: main/app/router** — copy the renter app's `main.dart`/`app.dart`/`router.dart` shape: `ProviderScope` → `MaterialApp.router(theme: AppTheme.lightTheme)`; `routerProvider` watches `authProvider` and redirects unauthenticated → `/login`. Routes: `/login`, `/otp`, `/` (home), `/scan`, `/result`, `/approvals`.
- [ ] **Step 4: Android camera permission** — `mobile_scanner` handles runtime permission; confirm `android/app/src/main/AndroidManifest.xml` gets `<uses-permission android:name="android.permission.CAMERA"/>` and iOS `Info.plist` gets `NSCameraUsageDescription`.
- [ ] **Step 5:** `cd mobile && melos bootstrap && cd apps/security && flutter analyze && flutter build apk --debug`. Expected: builds.
- [ ] **Step 6: Commit** — `git commit -am "feat(security-app): scaffold guard app in Melos monorepo"`

### Task 10: OTP login screens

**Files:**
- Create: `mobile/apps/security/lib/screens/phone_login_screen.dart`, `otp_screen.dart`
- Test: `mobile/apps/security/test/otp_login_test.dart`

- [ ] **Step 1: PhoneLoginScreen** — `ConsumerStatefulWidget`; single intl phone field (default `+971` prefix), Continue button → `ref.read(authServiceProvider).requestOtp(phone)` → `context.go('/otp', extra: phone)`. Error snackbar on failure; loading state on button.
- [ ] **Step 2: OtpScreen** — 6-digit code field, auto-submit on 6 chars → `ref.read(authProvider.notifier).loginWithOtp(phone, code)`; "Resend code" button disabled for a 60 s countdown; on success router redirect lands on `/`. Show "Invalid or expired code" on 401.
- [ ] **Step 3: Widget test** — pump `PhoneLoginScreen` with a mocked `authServiceProvider` override; enter phone, tap Continue, verify `requestOtp` called with `+971...` and navigation invoked. Same for wrong-code error rendering on `OtpScreen`.
- [ ] **Step 4:** `flutter test && flutter analyze`. Expected: PASS.
- [ ] **Step 5: Manual E2E** — backend running with `LoggingOtpSender`; create a guard user via API/SQL; `flutter run`; log in with the code from backend logs.
- [ ] **Step 6: Commit** — `git commit -am "feat(security-app): phone + OTP login flow"`

### Task 11: Home, scan, result, approvals screens

**Files:**
- Create: `mobile/apps/security/lib/screens/home_screen.dart`, `scan_screen.dart`, `scan_result_screen.dart`, `approvals_screen.dart`
- Create: `mobile/apps/security/lib/providers/gate_pass_provider.dart`

- [ ] **Step 1: gate_pass_provider.dart** — `Provider((ref) => GatePassApiService(ref.watch(apiClientProvider)))` plus `FutureProvider.autoDispose` for `expectedToday` and `approvals` lists.
- [ ] **Step 2: HomeScreen** — bottom nav (Visitors / Scan / Approvals). Visitors tab: `expectedToday` list grouped by property — guest name, unit, window, vehicle chip; pull-to-refresh; empty state widget from core. Prominent central "Scan" FAB → `/scan`.
- [ ] **Step 3: ScanScreen** — `MobileScanner(onDetect: ...)` full-screen; on first detected barcode value call `scan(qrToken: value, direction: 'ENTRY')` and push `/result` with the response map; debounce so one pass isn't scanned twice (ignore detections for 3 s after a hit). Below the viewfinder: "Enter code instead" → bottom sheet with 8-digit field → `scan(numericCode: ..., direction: 'ENTRY')`.
- [ ] **Step 4: ScanResultScreen** — takes the scan response map. ALLOWED: green header, guest name/phone/vehicle/unit/purpose/window, buttons **Done** and **Log exit** (`scan(direction: 'EXIT')` with the same code, then Done). REJECTED: red header with `reason` text and **Scan again**.
- [ ] **Step 5: ApprovalsScreen** — pending recurring passes list; each card: guest, unit, window, purpose; Approve/Reject buttons → `decide(id, true|false)` → refresh list.
- [ ] **Step 6: RTL/i18n** — wrap user-visible strings with the same i18n approach the renter app uses (check `apps/renter` — if it uses hardcoded strings today, keep EN strings and add AR via `intl` ARB only if renter app already does; do not invent a new i18n system).
- [ ] **Step 7:** `flutter analyze && flutter test`; manual E2E: create pass as renter via API, scan its QR (render QR from `qr_token` with any online generator during dev), verify entry logged and renter's in-app notification row created.
- [ ] **Step 8: Commit** — `git commit -am "feat(security-app): visitors list, QR/numeric scanning, entry-exit results, approvals"`

---

## Phase 3 — Renter app

### Task 12: Create / list / share gate passes

**Files:**
- Modify: `mobile/apps/renter/pubspec.yaml` — add `qr_flutter: ^4.1.0`, `share_plus: ^10.1.2`
- Create: `mobile/apps/renter/lib/screens/gatepass/gate_pass_list_screen.dart`, `gate_pass_create_screen.dart`, `gate_pass_detail_screen.dart`
- Modify: `mobile/apps/renter/lib/router.dart` (routes `/gatepass`, `/gatepass/create`, `/gatepass/:id`), plus home-screen entry tile (find the renter home grid and add a "Gate Pass" tile)

- [ ] **Step 1: List screen** — `mine()` list with status chips (`ACTIVE` green, `PENDING_APPROVAL` amber, `USED`/`EXPIRED`/`CANCELLED` grey), FAB → create.
- [ ] **Step 2: Create screen** — form: guest name*, guest phone*, purpose, vehicle number, type toggle (Single visit / Recurring), date+time range pickers (single: one day window; recurring: start date + expiry date, all-day window), unit auto-filled from the renter's active lease (fetch via existing lease service). Submit → `create(...)` → detail screen.
- [ ] **Step 3: Detail screen** — `QrImageView(data: pass['qrToken'])` large, numeric code in big monospace with copy button, validity window, status; Share button → `Share.share("Gate pass for <property>: show this QR or code <numeric_code> at the gate. Valid <from>–<to>.")`; Cancel pass button (confirmation dialog) when status is ACTIVE/PENDING_APPROVAL.
- [ ] **Step 4:** `flutter analyze`; manual E2E renter→guard round trip on two devices/emulators.
- [ ] **Step 5: Commit** — `git commit -am "feat(renter-app): gate pass creation, QR/code sharing, pass management"`

---

## Phase 4 — Manager surfaces

### Task 13: Manager app — approvals + guard management

**Files:**
- Create: `mobile/apps/manager/lib/screens/gatepass/gate_pass_approvals_screen.dart`, `guard_management_screen.dart`
- Modify: `mobile/apps/manager/lib/router.dart` + navigation menu

- [ ] **Step 1: Approvals screen** — same as guard approvals tab but tenant-wide (reuses `approvals()`/`decide()`).
- [ ] **Step 2: Guard management** — list users with role `SECURITY_GUARD` (existing staff/user service), create-guard form (name, phone E.164, email optional), property assignment multi-select → `PUT /v1/gatepass/guards/{id}/properties`.
- [ ] **Step 3:** `flutter analyze`; manual check; commit `git commit -am "feat(manager-app): gate pass approvals and guard management"`

### Task 14: Web — report + feature toggle

**Files:**
- Create: `web/src/app/[locale]/dashboard/gatepass/page.tsx` (match the actual dashboard route structure — check `web/src/app` layout before creating)
- Modify: web navigation config; API proxy needs no change (uses the existing rewrite)

- [ ] **Step 1: Report page** — date-range + property filter → `GET /api/proxy/v1/gatepass/report` → table (guest, unit, direction, result, guard, time) + client-side CSV download button (`Blob` from rows). Use `useTranslations()` with new EN/AR message keys.
- [ ] **Step 2: Feature toggle** — add a `gatepass` flag to the existing tenant feature mechanism (`TenantFeatureController` exists — wire the same pattern the other feature flags use; renter app hides the Gate Pass tile when disabled, backend `create` endpoint rejects when disabled).
- [ ] **Step 3:** `cd web && npm run build` passes; manual check in browser; commit `git commit -am "feat(web): gate pass usage report with CSV export and feature toggle"`

---

## Final verification (whole feature)

- [ ] `cd backend && ./gradlew test` — all green.
- [ ] `cd mobile && melos exec -- flutter analyze` — no errors in the three apps + core.
- [ ] E2E happy path: manager creates guard + assigns property → guard logs in via OTP (log sender) → renter creates single-use pass → guard scans QR → ALLOWED, pass USED, renter notified → second scan REJECTED → exit logged.
- [ ] E2E recurring: renter creates recurring pass → manager approves → guard scans on two different days → both ALLOWED.
- [ ] RBAC probe: guard token calling `/api/v1/payment-schedules` (or any finance endpoint) → 403.
- [ ] AR/RTL visual pass over new renter + guard screens.

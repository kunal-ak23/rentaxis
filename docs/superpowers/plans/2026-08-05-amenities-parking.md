# Amenities & Parking Booking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Admins define bookable amenities and numbered parking spots per property (optionally limited to specific towers); renters with an active lease request them; admins approve/reject seeing all other applicants; approved parking is held until released. No payments.

**Architecture:** Unified booking module modeled on the gate-pass module: two inventory entities (`PropertyAmenity`, `ParkingSpot`) with building-scope join tables (empty scope = all towers) plus one shared `booking_requests` table with a resource-type discriminator. Thin services, RBAC + ownership in controllers, in-app notifications via `AFTER_COMMIT` events. Same REST contract consumed by the Next.js dashboard/renter portal and both Flutter apps via `rentaxis_core`.

**Tech Stack:** Java 21 / Spring Boot 4 / Liquibase / PostgreSQL (backend); Next.js 16 + TypeScript + next-intl (web); Flutter + Riverpod + GoRouter + Dio (mobile).

**Spec:** `docs/superpowers/specs/2026-08-04-amenities-parking-design.md`

**Branch:** `feat/miftah-admin`. Sections are independent after the backend section: execute B-tasks first (B1 → B9 in order), then W-tasks and M-tasks in any order.

---

## Backend (B): Amenities, Parking Spots & Booking Requests

Grounding verified against the repo: `backend/gradlew` exists; entities follow the `GatePass` raw-UUID-FK style (`@GeneratedValue(strategy = GenerationType.UUID)`, `Instant` timestamps, `@PreUpdate`); services follow `UnitListingService`/`InterestService` (tenantId first param, `NotFoundException` on cross-tenant, `@Service @Transactional`); controllers follow `UnitListingController` (`checkPropertyManagerAccess` via `SecurityContextHolder` + `UserPropertyAssignmentRepository.existsByUserIdAndPropertyId`) and `GatePassController` (`requireUnitOnActiveLease`, `isGuard()`-style role branching, `currentUserId()` = `UUID.fromString(auth.getName())`); notifications follow `ListingNotificationService` (`@TransactionalEventListener(AFTER_COMMIT)` + `REQUIRES_NEW` + per-recipient try/catch) calling `NotificationService.notify(tenantId, userId, type, title, message, referenceType, referenceId)`; exceptions: `NotFoundException`→404, `BusinessRuleViolationException`→400, `SlotConflictException(String, Instant)`→409, `AccessDeniedException`→403 (all handled in `GlobalExceptionHandler`). `PropertyRepository.existsByIdAndTenantId(UUID, UUID)` and `UserRepository.findByTenantIdAndIdIn(UUID, Collection<UUID>)` exist. Latest changeset is `68-backfill-unit-lease-metrics.yaml`; new changeset is `69`.

All API shapes below are exactly the canonical contract (field names must not be renamed — web and mobile sections consume them verbatim).

---

### Task B1: Liquibase changeset 69-amenities-parking

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/69-amenities-parking.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml` (append include after the current last entry, `68-backfill-unit-lease-metrics.yaml`)

- [ ] **Step 1: Write the changeset file**

Create `backend/src/main/resources/db/changelog/changesets/69-amenities-parking.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 69-amenities-parking
      author: rentaxis-system
      changes:
        # ---- property_amenities: free-form bookable amenities on a property ----
        # fk_amenity_property RESTRICTs: inventory is soft-deactivated (active=false),
        # never hard-deleted, so a property with amenities must be emptied first.
        - createTable:
            tableName: property_amenities
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: property_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_amenity_property, referencedTableName: properties, referencedColumnNames: id } }
              - column: { name: name_en, type: varchar(160), constraints: { nullable: false } }
              - column: { name: name_ar, type: varchar(160) }
              - column: { name: description, type: text }
              - column: { name: bookable, type: boolean, constraints: { nullable: false }, defaultValueBoolean: true }
              - column: { name: active, type: boolean, constraints: { nullable: false }, defaultValueBoolean: true }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
              - column: { name: updated_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - createIndex: { tableName: property_amenities, indexName: idx_property_amenities_tenant, columns: [ { column: { name: tenant_id } }, { column: { name: property_id } } ] }

        # ---- amenity_building_scopes: zero rows for an amenity = visible to all towers ----
        # Pure join rows, not audit data — cascade both ways (same reasoning as
        # guard_property_assignments in changeset 65).
        - createTable:
            tableName: amenity_building_scopes
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: amenity_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_abs_amenity, referencedTableName: property_amenities, referencedColumnNames: id, deleteCascade: true } }
              - column: { name: building_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_abs_building, referencedTableName: buildings, referencedColumnNames: id, deleteCascade: true } }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - addUniqueConstraint: { tableName: amenity_building_scopes, columnNames: "amenity_id, building_id", constraintName: uq_amenity_building }

        # ---- parking_spots: real numbered inventory ----
        - createTable:
            tableName: parking_spots
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: property_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_spot_property, referencedTableName: properties, referencedColumnNames: id } }
              - column: { name: spot_number, type: varchar(32), constraints: { nullable: false } }
              - column: { name: level, type: varchar(32) }
              - column: { name: covered, type: boolean, constraints: { nullable: false }, defaultValueBoolean: true }
              - column: { name: active, type: boolean, constraints: { nullable: false }, defaultValueBoolean: true }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
              - column: { name: updated_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - addUniqueConstraint: { tableName: parking_spots, columnNames: "property_id, spot_number", constraintName: uq_parking_spot_number }
        - createIndex: { tableName: parking_spots, indexName: idx_parking_spots_tenant, columns: [ { column: { name: tenant_id } }, { column: { name: property_id } } ] }

        # ---- parking_spot_building_scopes: same semantics as amenity scopes ----
        - createTable:
            tableName: parking_spot_building_scopes
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: parking_spot_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_psbs_spot, referencedTableName: parking_spots, referencedColumnNames: id, deleteCascade: true } }
              - column: { name: building_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_psbs_building, referencedTableName: buildings, referencedColumnNames: id, deleteCascade: true } }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - addUniqueConstraint: { tableName: parking_spot_building_scopes, columnNames: "parking_spot_id, building_id", constraintName: uq_parking_spot_building }

        # ---- booking_requests: one shared request table for both resource types ----
        # property_id is derived server-side from the resource, never client-supplied.
        # fk_booking_renter_user RESTRICTs on user deletion: booking requests are an
        # audit trail of who asked for what — a renter with booking history must be
        # deactivated, not hard-deleted (same reasoning as fk_gate_pass_creator in
        # changeset 65). amenity_id/parking_spot_id RESTRICT so request history
        # survives; inventory is soft-deactivated, not deleted. decided_by_user_id
        # deliberately has no FK, matching gate_passes.approved_by_user_id.
        - createTable:
            tableName: booking_requests
            columns:
              - column: { name: id, type: uuid, constraints: { primaryKey: true, nullable: false } }
              - column: { name: tenant_id, type: uuid, constraints: { nullable: false } }
              - column: { name: property_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_booking_property, referencedTableName: properties, referencedColumnNames: id } }
              - column: { name: resource_type, type: varchar(20), constraints: { nullable: false } }
              - column: { name: amenity_id, type: uuid, constraints: { foreignKeyName: fk_booking_amenity, referencedTableName: property_amenities, referencedColumnNames: id } }
              - column: { name: parking_spot_id, type: uuid, constraints: { foreignKeyName: fk_booking_spot, referencedTableName: parking_spots, referencedColumnNames: id } }
              - column: { name: unit_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_booking_unit, referencedTableName: units, referencedColumnNames: id } }
              - column: { name: renter_user_id, type: uuid, constraints: { nullable: false, foreignKeyName: fk_booking_renter_user, referencedTableName: users, referencedColumnNames: id } }
              - column: { name: note, type: text }
              - column: { name: preferred_date, type: date }
              - column: { name: status, type: varchar(20), constraints: { nullable: false }, defaultValue: PENDING }
              - column: { name: admin_note, type: text }
              - column: { name: decided_by_user_id, type: uuid }
              - column: { name: decided_at, type: timestamptz }
              - column: { name: created_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
              - column: { name: updated_at, type: timestamptz, constraints: { nullable: false }, defaultValueComputed: now() }
        - createIndex: { tableName: booking_requests, indexName: idx_booking_tenant_property_status, columns: [ { column: { name: tenant_id } }, { column: { name: property_id } }, { column: { name: status } } ] }
        - createIndex: { tableName: booking_requests, indexName: idx_booking_tenant_renter, columns: [ { column: { name: tenant_id } }, { column: { name: renter_user_id } } ] }
        - createIndex: { tableName: booking_requests, indexName: idx_booking_amenity, columns: [ { column: { name: amenity_id } } ] }
        - createIndex: { tableName: booking_requests, indexName: idx_booking_spot, columns: [ { column: { name: parking_spot_id } } ] }
        # XOR check + partial unique indexes (raw sql, gate-pass style):
        # - exactly one of amenity_id / parking_spot_id
        # - at most one APPROVED row per parking spot (the DB backstop behind the 409)
        # - no duplicate PENDING by the same renter for the same resource (idempotency backstop)
        - sql:
            splitStatements: false
            sql: |
              ALTER TABLE booking_requests ADD CONSTRAINT chk_booking_exactly_one_resource
                CHECK ((amenity_id IS NULL) <> (parking_spot_id IS NULL));
              CREATE UNIQUE INDEX uq_booking_spot_active
                ON booking_requests (parking_spot_id) WHERE status = 'APPROVED';
              CREATE UNIQUE INDEX uq_booking_pending_renter_amenity
                ON booking_requests (renter_user_id, amenity_id)
                WHERE status = 'PENDING' AND amenity_id IS NOT NULL;
              CREATE UNIQUE INDEX uq_booking_pending_renter_spot
                ON booking_requests (renter_user_id, parking_spot_id)
                WHERE status = 'PENDING' AND parking_spot_id IS NOT NULL;
```

- [ ] **Step 2: Append the include to the master changelog**

`db.changelog-master.yaml` has uncommitted edits on this branch — run `git diff backend/src/main/resources/db/changelog/db.changelog-master.yaml` first to see them; they belong to this feature branch and ride along. Apply this exact edit hunk (old → new):

```yaml
  - include:
      file: db/changelog/changesets/68-backfill-unit-lease-metrics.yaml
```

becomes

```yaml
  - include:
      file: db/changelog/changesets/68-backfill-unit-lease-metrics.yaml
  - include:
      file: db/changelog/changesets/69-amenities-parking.yaml
```

- [ ] **Step 3: Validate the changeset by booting the app against Testcontainers Postgres** (any `@SpringBootTest` applies the full changelog; a malformed changeset fails the context boot). Requires Docker running.

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew test --tests "com.datagami.rentaxis.api.GatePassControllerTest"
```

Expected: `BUILD SUCCESSFUL` (takes ~2–4 min; a Liquibase error in changeset 69 would surface as `LiquibaseException` in every test's context init).

- [ ] **Step 4: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add backend/src/main/resources/db/changelog/changesets/69-amenities-parking.yaml backend/src/main/resources/db/changelog/db.changelog-master.yaml && git commit -m "feat(facilities): liquibase schema for amenities, parking spots, booking requests"
```

---

### Task B2: Enums BookingResourceType and BookingRequestStatus

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/BookingResourceType.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/BookingRequestStatus.java`

- [ ] **Step 1: Write both enum files**

`BookingResourceType.java`:

```java
package com.datagami.rentaxis.domain.entity.enums;

public enum BookingResourceType {
    AMENITY,
    PARKING_SPOT
}
```

`BookingRequestStatus.java`:

```java
package com.datagami.rentaxis.domain.entity.enums;

public enum BookingRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED,
    RELEASED
}
```

- [ ] **Step 2: Compile**

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/BookingResourceType.java backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/BookingRequestStatus.java && git commit -m "feat(facilities): booking resource and status enums"
```

---

### Task B3: Entities and repositories

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/PropertyAmenity.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/AmenityBuildingScope.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/ParkingSpot.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/ParkingSpotBuildingScope.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/BookingRequest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/PropertyAmenityRepository.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/AmenityBuildingScopeRepository.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/ParkingSpotRepository.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/ParkingSpotBuildingScopeRepository.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/repository/BookingRequestRepository.java`

- [ ] **Step 1: Write the five entities** (GatePass style: raw UUID FK columns, `GenerationType.UUID`, `Instant` timestamps, `@PreUpdate`)

`PropertyAmenity.java`:

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "property_amenities")
@Getter
@Setter
public class PropertyAmenity extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "name_en", nullable = false, length = 160)
    private String nameEn;

    @Column(name = "name_ar", length = 160)
    private String nameAr;

    @Column(columnDefinition = "text")
    private String description;

    @Column(nullable = false)
    private boolean bookable = true;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
```

`AmenityBuildingScope.java`:

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Zero rows for an amenity = visible to all towers of its property. */
@Entity
@Table(name = "amenity_building_scopes")
@Getter
@Setter
public class AmenityBuildingScope extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "amenity_id", nullable = false)
    private UUID amenityId;

    @Column(name = "building_id", nullable = false)
    private UUID buildingId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
```

`ParkingSpot.java`:

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "parking_spots")
@Getter
@Setter
public class ParkingSpot extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Column(name = "spot_number", nullable = false, length = 32)
    private String spotNumber;

    @Column(length = 32)
    private String level;

    @Column(nullable = false)
    private boolean covered = true;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
```

`ParkingSpotBuildingScope.java`:

```java
package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Zero rows for a spot = visible to all towers of its property. */
@Entity
@Table(name = "parking_spot_building_scopes")
@Getter
@Setter
public class ParkingSpotBuildingScope extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "parking_spot_id", nullable = false)
    private UUID parkingSpotId;

    @Column(name = "building_id", nullable = false)
    private UUID buildingId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
```

`BookingRequest.java`:

```java
package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A renter's request for an amenity or a parking spot. Exactly one of
 * {@code amenityId}/{@code parkingSpotId} is non-null (DB CHECK enforces it).
 * {@code propertyId} is always derived server-side from the resource.
 */
@Entity
@Table(name = "booking_requests")
@Getter
@Setter
public class BookingRequest extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "property_id", nullable = false)
    private UUID propertyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 20)
    private BookingResourceType resourceType;

    @Column(name = "amenity_id")
    private UUID amenityId;

    @Column(name = "parking_spot_id")
    private UUID parkingSpotId;

    @Column(name = "unit_id", nullable = false)
    private UUID unitId;

    @Column(name = "renter_user_id", nullable = false)
    private UUID renterUserId;

    @Column(columnDefinition = "text")
    private String note;

    @Column(name = "preferred_date")
    private LocalDate preferredDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BookingRequestStatus status = BookingRequestStatus.PENDING;

    @Column(name = "admin_note", columnDefinition = "text")
    private String adminNote;

    @Column(name = "decided_by_user_id")
    private UUID decidedByUserId;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    public void onPreUpdate() {
        this.updatedAt = Instant.now();
    }
}
```

- [ ] **Step 2: Write the five repositories**

`PropertyAmenityRepository.java`:

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PropertyAmenityRepository extends JpaRepository<PropertyAmenity, UUID> {

    Page<PropertyAmenity> findByTenantId(UUID tenantId, Pageable pageable);

    Page<PropertyAmenity> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId, Pageable pageable);

    List<PropertyAmenity> findByTenantIdAndPropertyIdAndActiveTrue(UUID tenantId, UUID propertyId);
}
```

`AmenityBuildingScopeRepository.java`:

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.AmenityBuildingScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface AmenityBuildingScopeRepository extends JpaRepository<AmenityBuildingScope, UUID> {

    List<AmenityBuildingScope> findByAmenityId(UUID amenityId);

    List<AmenityBuildingScope> findByAmenityIdIn(Collection<UUID> amenityIds);

    void deleteByAmenityId(UUID amenityId);
}
```

`ParkingSpotRepository.java`:

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ParkingSpot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ParkingSpotRepository extends JpaRepository<ParkingSpot, UUID> {

    Page<ParkingSpot> findByTenantId(UUID tenantId, Pageable pageable);

    Page<ParkingSpot> findByTenantIdAndPropertyId(UUID tenantId, UUID propertyId, Pageable pageable);

    List<ParkingSpot> findByTenantIdAndPropertyIdAndActiveTrue(UUID tenantId, UUID propertyId);

    boolean existsByTenantIdAndPropertyIdAndSpotNumber(UUID tenantId, UUID propertyId, String spotNumber);
}
```

`ParkingSpotBuildingScopeRepository.java`:

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.ParkingSpotBuildingScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface ParkingSpotBuildingScopeRepository extends JpaRepository<ParkingSpotBuildingScope, UUID> {

    List<ParkingSpotBuildingScope> findByParkingSpotId(UUID parkingSpotId);

    List<ParkingSpotBuildingScope> findByParkingSpotIdIn(Collection<UUID> parkingSpotIds);

    void deleteByParkingSpotId(UUID parkingSpotId);
}
```

`BookingRequestRepository.java`:

```java
package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRequestRepository extends JpaRepository<BookingRequest, UUID> {

    /**
     * Admin inbox: every filter optional. Explicit tenantId in the JPQL, belt and
     * braces on top of the Hibernate tenantFilter, matching repo convention.
     */
    @Query("""
        SELECT b FROM BookingRequest b
        WHERE b.tenantId = :tenantId
          AND (:propertyId IS NULL OR b.propertyId = :propertyId)
          AND (:status IS NULL OR b.status = :status)
          AND (:resourceType IS NULL OR b.resourceType = :resourceType)
        """)
    Page<BookingRequest> search(@Param("tenantId") UUID tenantId,
                                @Param("propertyId") UUID propertyId,
                                @Param("status") BookingRequestStatus status,
                                @Param("resourceType") BookingResourceType resourceType,
                                Pageable pageable);

    List<BookingRequest> findByTenantIdAndRenterUserIdOrderByCreatedAtAsc(UUID tenantId, UUID renterUserId);

    Optional<BookingRequest> findFirstByTenantIdAndRenterUserIdAndAmenityIdAndStatus(
            UUID tenantId, UUID renterUserId, UUID amenityId, BookingRequestStatus status);

    Optional<BookingRequest> findFirstByTenantIdAndRenterUserIdAndParkingSpotIdAndStatus(
            UUID tenantId, UUID renterUserId, UUID parkingSpotId, BookingRequestStatus status);

    boolean existsByParkingSpotIdAndStatus(UUID parkingSpotId, BookingRequestStatus status);

    long countByAmenityIdAndStatus(UUID amenityId, BookingRequestStatus status);

    long countByParkingSpotIdAndStatus(UUID parkingSpotId, BookingRequestStatus status);

    List<BookingRequest> findByAmenityIdAndStatusInOrderByCreatedAtAsc(
            UUID amenityId, Collection<BookingRequestStatus> statuses);

    List<BookingRequest> findByParkingSpotIdAndStatusInOrderByCreatedAtAsc(
            UUID parkingSpotId, Collection<BookingRequestStatus> statuses);
}
```

- [ ] **Step 3: Compile**

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add backend/src/main/java/com/datagami/rentaxis/domain/entity/PropertyAmenity.java backend/src/main/java/com/datagami/rentaxis/domain/entity/AmenityBuildingScope.java backend/src/main/java/com/datagami/rentaxis/domain/entity/ParkingSpot.java backend/src/main/java/com/datagami/rentaxis/domain/entity/ParkingSpotBuildingScope.java backend/src/main/java/com/datagami/rentaxis/domain/entity/BookingRequest.java backend/src/main/java/com/datagami/rentaxis/domain/repository/PropertyAmenityRepository.java backend/src/main/java/com/datagami/rentaxis/domain/repository/AmenityBuildingScopeRepository.java backend/src/main/java/com/datagami/rentaxis/domain/repository/ParkingSpotRepository.java backend/src/main/java/com/datagami/rentaxis/domain/repository/ParkingSpotBuildingScopeRepository.java backend/src/main/java/com/datagami/rentaxis/domain/repository/BookingRequestRepository.java && git commit -m "feat(facilities): entities and repositories for amenities, parking, bookings"
```

---

### Task B4: DTO records

**Files:**
- Create (all in `backend/src/main/java/com/datagami/rentaxis/api/dto/`): `AmenityDTO.java`, `ParkingSpotDTO.java`, `BookingRequestDTO.java`, `BookingDetailDTO.java`, `MyFacilitiesDTO.java`, `AmenityCreateRequest.java`, `AmenityUpdateRequest.java`, `ParkingSpotCreateRequest.java`, `ParkingSpotUpdateRequest.java`, `ParkingSpotBulkCreateRequest.java`, `BookingCreateRequest.java`, `DecisionRequest.java`

- [ ] **Step 1: Write the twelve record files** (field names are the canonical wire contract — do not rename)

`AmenityDTO.java`:

```java
package com.datagami.rentaxis.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AmenityDTO(
        UUID id,
        UUID propertyId,
        String nameEn,
        String nameAr,
        String description,
        boolean bookable,
        boolean active,
        List<UUID> buildingIds,
        long pendingCount,
        Instant createdAt,
        Instant updatedAt) {
}
```

`ParkingSpotDTO.java`:

```java
package com.datagami.rentaxis.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ParkingSpotDTO(
        UUID id,
        UUID propertyId,
        String spotNumber,
        String level,
        boolean covered,
        boolean active,
        List<UUID> buildingIds,
        boolean held,
        long pendingCount,
        Instant createdAt,
        Instant updatedAt) {
}
```

`BookingRequestDTO.java`:

```java
package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BookingRequestDTO(
        UUID id,
        BookingResourceType resourceType,
        UUID amenityId,
        UUID parkingSpotId,
        String resourceName,
        UUID propertyId,
        UUID unitId,
        String unitNumber,
        UUID renterUserId,
        String renterName,
        String renterEmail,
        String renterPhone,
        String note,
        LocalDate preferredDate,
        BookingRequestStatus status,
        String adminNote,
        UUID decidedByUserId,
        Instant decidedAt,
        Instant createdAt) {
}
```

`BookingDetailDTO.java`:

```java
package com.datagami.rentaxis.api.dto;

import java.util.List;

/** Admin detail: the request plus all other PENDING/APPROVED requests for the same resource. */
public record BookingDetailDTO(
        BookingRequestDTO request,
        List<BookingRequestDTO> otherRequests) {
}
```

`MyFacilitiesDTO.java`:

```java
package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Renter-facing facility catalogue. Carries pendingCount only — never other
 * applicants' identities.
 */
public record MyFacilitiesDTO(
        List<RenterAmenityDTO> amenities,
        List<RenterParkingSpotDTO> parkingSpots) {

    public record RenterAmenityDTO(
            UUID id,
            UUID propertyId,
            String propertyName,
            String nameEn,
            String nameAr,
            String description,
            boolean bookable,
            long pendingCount) {
    }

    public record RenterParkingSpotDTO(
            UUID id,
            UUID propertyId,
            String propertyName,
            String spotNumber,
            String level,
            boolean covered,
            boolean held,
            long pendingCount) {
    }
}
```

`AmenityCreateRequest.java`:

```java
package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/** bookable null = true; buildingIds null/empty = visible to all towers. */
public record AmenityCreateRequest(
        UUID propertyId,
        String nameEn,
        String nameAr,
        String description,
        Boolean bookable,
        List<UUID> buildingIds) {
}
```

`AmenityUpdateRequest.java`:

```java
package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/** Patch semantics: null = unchanged; non-null buildingIds replaces the scope set. */
public record AmenityUpdateRequest(
        String nameEn,
        String nameAr,
        String description,
        Boolean bookable,
        Boolean active,
        List<UUID> buildingIds) {
}
```

`ParkingSpotCreateRequest.java`:

```java
package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/** covered null = true; buildingIds null/empty = visible to all towers. */
public record ParkingSpotCreateRequest(
        UUID propertyId,
        String spotNumber,
        String level,
        Boolean covered,
        List<UUID> buildingIds) {
}
```

`ParkingSpotUpdateRequest.java`:

```java
package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/** Patch semantics: null = unchanged; non-null buildingIds replaces the scope set. */
public record ParkingSpotUpdateRequest(
        String spotNumber,
        String level,
        Boolean covered,
        Boolean active,
        List<UUID> buildingIds) {
}
```

`ParkingSpotBulkCreateRequest.java`:

```java
package com.datagami.rentaxis.api.dto;

import java.util.List;
import java.util.UUID;

/** Creates one spot per entry in spotNumbers, all sharing level/covered/buildingIds. */
public record ParkingSpotBulkCreateRequest(
        UUID propertyId,
        List<String> spotNumbers,
        String level,
        Boolean covered,
        List<UUID> buildingIds) {
}
```

`BookingCreateRequest.java`:

```java
package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;

import java.time.LocalDate;
import java.util.UUID;

public record BookingCreateRequest(
        BookingResourceType resourceType,
        UUID resourceId,
        UUID unitId,
        LocalDate preferredDate,
        String note) {
}
```

`DecisionRequest.java`:

```java
package com.datagami.rentaxis.api.dto;

public record DecisionRequest(String adminNote) {
}
```

- [ ] **Step 2: Compile**

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add backend/src/main/java/com/datagami/rentaxis/api/dto/AmenityDTO.java backend/src/main/java/com/datagami/rentaxis/api/dto/ParkingSpotDTO.java backend/src/main/java/com/datagami/rentaxis/api/dto/BookingRequestDTO.java backend/src/main/java/com/datagami/rentaxis/api/dto/BookingDetailDTO.java backend/src/main/java/com/datagami/rentaxis/api/dto/MyFacilitiesDTO.java backend/src/main/java/com/datagami/rentaxis/api/dto/AmenityCreateRequest.java backend/src/main/java/com/datagami/rentaxis/api/dto/AmenityUpdateRequest.java backend/src/main/java/com/datagami/rentaxis/api/dto/ParkingSpotCreateRequest.java backend/src/main/java/com/datagami/rentaxis/api/dto/ParkingSpotUpdateRequest.java backend/src/main/java/com/datagami/rentaxis/api/dto/ParkingSpotBulkCreateRequest.java backend/src/main/java/com/datagami/rentaxis/api/dto/BookingCreateRequest.java backend/src/main/java/com/datagami/rentaxis/api/dto/DecisionRequest.java && git commit -m "feat(facilities): API DTO records for amenities, parking, bookings"
```

---

### Task B5: FacilityService (TDD)

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/FacilityServiceTest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/FacilityService.java`

- [ ] **Step 1: Write the failing test** (plain-`mock()` style of `UnitListingServiceTest`)

`FacilityServiceTest.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.AmenityCreateRequest;
import com.datagami.rentaxis.api.dto.AmenityUpdateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotBulkCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotCreateRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.AmenityBuildingScope;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.repository.AmenityBuildingScopeRepository;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotBuildingScopeRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FacilityServiceTest {

    private PropertyAmenityRepository amenityRepository;
    private AmenityBuildingScopeRepository amenityScopeRepository;
    private ParkingSpotRepository spotRepository;
    private ParkingSpotBuildingScopeRepository spotScopeRepository;
    private PropertyRepository propertyRepository;
    private BuildingRepository buildingRepository;
    private FacilityService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private final UUID buildingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        amenityRepository = mock(PropertyAmenityRepository.class);
        amenityScopeRepository = mock(AmenityBuildingScopeRepository.class);
        spotRepository = mock(ParkingSpotRepository.class);
        spotScopeRepository = mock(ParkingSpotBuildingScopeRepository.class);
        propertyRepository = mock(PropertyRepository.class);
        buildingRepository = mock(BuildingRepository.class);
        service = new FacilityService(amenityRepository, amenityScopeRepository,
                spotRepository, spotScopeRepository, propertyRepository, buildingRepository);

        when(amenityRepository.save(any(PropertyAmenity.class))).thenAnswer(inv -> {
            PropertyAmenity a = inv.getArgument(0);
            if (a.getId() == null) a.setId(UUID.randomUUID());
            return a;
        });
        when(spotRepository.save(any(ParkingSpot.class))).thenAnswer(inv -> {
            ParkingSpot s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        when(propertyRepository.existsByIdAndTenantId(propertyId, tenantId)).thenReturn(true);
    }

    private Building buildingInProperty(UUID id) {
        Building b = new Building();
        b.setId(id);
        return b;
    }

    private Unit unitInProperty(UUID unitBuildingId) {
        Property property = new Property();
        property.setId(propertyId);
        Unit unit = new Unit();
        unit.setId(UUID.randomUUID());
        unit.setProperty(property);
        if (unitBuildingId != null) {
            Building building = buildingInProperty(unitBuildingId);
            unit.setBuilding(building);
        }
        return unit;
    }

    private PropertyAmenity amenity(boolean bookable) {
        PropertyAmenity a = new PropertyAmenity();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setPropertyId(propertyId);
        a.setNameEn("Gym");
        a.setBookable(bookable);
        return a;
    }

    // ---- amenity CRUD ----

    @Test
    void createAmenity_savesWithTenantPropertyAndScopes() {
        when(buildingRepository.findByPropertyId(propertyId))
                .thenReturn(List.of(buildingInProperty(buildingId)));

        PropertyAmenity created = service.createAmenity(tenantId, new AmenityCreateRequest(
                propertyId, "Gym", "نادي", "24/7 gym", null, List.of(buildingId)));

        assertThat(created.getTenantId()).isEqualTo(tenantId);
        assertThat(created.getPropertyId()).isEqualTo(propertyId);
        assertThat(created.isBookable()).isTrue();
        assertThat(created.isActive()).isTrue();
        verify(amenityScopeRepository).save(any(AmenityBuildingScope.class));
    }

    @Test
    void createAmenity_propertyNotInTenant_throwsNotFound() {
        UUID foreignProperty = UUID.randomUUID();
        when(propertyRepository.existsByIdAndTenantId(foreignProperty, tenantId)).thenReturn(false);

        assertThatThrownBy(() -> service.createAmenity(tenantId, new AmenityCreateRequest(
                foreignProperty, "Gym", null, null, null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createAmenity_buildingFromOtherProperty_throws400() {
        when(buildingRepository.findByPropertyId(propertyId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.createAmenity(tenantId, new AmenityCreateRequest(
                propertyId, "Gym", null, null, null, List.of(UUID.randomUUID()))))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void updateAmenity_nullFieldsLeaveValuesUnchanged() {
        PropertyAmenity existing = amenity(true);
        existing.setDescription("old");
        when(amenityRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        PropertyAmenity updated = service.updateAmenity(tenantId, existing.getId(),
                new AmenityUpdateRequest("Pool", null, null, null, null, null));

        assertThat(updated.getNameEn()).isEqualTo("Pool");
        assertThat(updated.getDescription()).isEqualTo("old");
        assertThat(updated.isBookable()).isTrue();
    }

    @Test
    void updateAmenity_nonNullBuildingIdsReplacesScopeSet() {
        PropertyAmenity existing = amenity(true);
        when(amenityRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(buildingRepository.findByPropertyId(propertyId))
                .thenReturn(List.of(buildingInProperty(buildingId)));

        service.updateAmenity(tenantId, existing.getId(),
                new AmenityUpdateRequest(null, null, null, null, null, List.of(buildingId)));

        verify(amenityScopeRepository).deleteByAmenityId(existing.getId());
        verify(amenityScopeRepository).save(any(AmenityBuildingScope.class));
    }

    @Test
    void getAmenity_wrongTenant_throwsNotFound() {
        PropertyAmenity foreign = amenity(true);
        foreign.setTenantId(UUID.randomUUID());
        when(amenityRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.getAmenity(tenantId, foreign.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deactivateAmenity_setsActiveFalse() {
        PropertyAmenity existing = amenity(true);
        when(amenityRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        service.deactivateAmenity(tenantId, existing.getId());

        assertThat(existing.isActive()).isFalse();
    }

    // ---- parking CRUD ----

    @Test
    void createParkingSpot_duplicateNumber_throws400() {
        when(spotRepository.existsByTenantIdAndPropertyIdAndSpotNumber(tenantId, propertyId, "B1-07"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.createParkingSpot(tenantId, new ParkingSpotCreateRequest(
                propertyId, "B1-07", "B1", null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void bulkCreateParkingSpots_createsOnePerNumberWithSharedAttributes() {
        List<ParkingSpot> created = service.bulkCreateParkingSpots(tenantId,
                new ParkingSpotBulkCreateRequest(propertyId, List.of("B1-01", "B1-02", "B1-03"),
                        "B1", false, null));

        assertThat(created).hasSize(3);
        assertThat(created).allSatisfy(s -> {
            assertThat(s.getLevel()).isEqualTo("B1");
            assertThat(s.isCovered()).isFalse();
            assertThat(s.getPropertyId()).isEqualTo(propertyId);
        });
        verify(spotRepository, times(3)).save(any(ParkingSpot.class));
    }

    @Test
    void bulkCreateParkingSpots_existingNumber_throws400() {
        when(spotRepository.existsByTenantIdAndPropertyIdAndSpotNumber(tenantId, propertyId, "B1-02"))
                .thenReturn(true);

        assertThatThrownBy(() -> service.bulkCreateParkingSpots(tenantId,
                new ParkingSpotBulkCreateRequest(propertyId, List.of("B1-01", "B1-02"), null, null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    // ---- renter visibility ----

    @Test
    void visibleFacilities_unscopedAmenity_visibleToAnyUnit() {
        PropertyAmenity a = amenity(true);
        when(amenityRepository.findByTenantIdAndPropertyIdAndActiveTrue(tenantId, propertyId))
                .thenReturn(List.of(a));
        when(amenityScopeRepository.findByAmenityIdIn(List.of(a.getId()))).thenReturn(List.of());
        when(spotRepository.findByTenantIdAndPropertyIdAndActiveTrue(tenantId, propertyId))
                .thenReturn(List.of());

        FacilityService.VisibleFacilities visible =
                service.visibleFacilities(tenantId, unitInProperty(buildingId));

        assertThat(visible.amenities()).containsExactly(a);
    }

    @Test
    void visibleFacilities_scopedAmenity_hiddenFromOtherTower() {
        PropertyAmenity a = amenity(true);
        AmenityBuildingScope scope = new AmenityBuildingScope();
        scope.setAmenityId(a.getId());
        scope.setBuildingId(buildingId);
        when(amenityRepository.findByTenantIdAndPropertyIdAndActiveTrue(tenantId, propertyId))
                .thenReturn(List.of(a));
        when(amenityScopeRepository.findByAmenityIdIn(List.of(a.getId()))).thenReturn(List.of(scope));
        when(spotRepository.findByTenantIdAndPropertyIdAndActiveTrue(tenantId, propertyId))
                .thenReturn(List.of());

        FacilityService.VisibleFacilities otherTower =
                service.visibleFacilities(tenantId, unitInProperty(UUID.randomUUID()));
        FacilityService.VisibleFacilities scopedTower =
                service.visibleFacilities(tenantId, unitInProperty(buildingId));

        assertThat(otherTower.amenities()).isEmpty();
        assertThat(scopedTower.amenities()).containsExactly(a);
    }

    @Test
    void visibleFacilities_unitWithoutBuilding_seesOnlyUnscoped() {
        PropertyAmenity unscoped = amenity(true);
        PropertyAmenity scoped = amenity(true);
        AmenityBuildingScope scope = new AmenityBuildingScope();
        scope.setAmenityId(scoped.getId());
        scope.setBuildingId(buildingId);
        when(amenityRepository.findByTenantIdAndPropertyIdAndActiveTrue(tenantId, propertyId))
                .thenReturn(List.of(unscoped, scoped));
        when(amenityScopeRepository.findByAmenityIdIn(List.of(unscoped.getId(), scoped.getId())))
                .thenReturn(List.of(scope));
        when(spotRepository.findByTenantIdAndPropertyIdAndActiveTrue(tenantId, propertyId))
                .thenReturn(List.of());

        FacilityService.VisibleFacilities visible =
                service.visibleFacilities(tenantId, unitInProperty(null));

        assertThat(visible.amenities()).containsExactly(unscoped);
    }

    @Test
    void amenityVisibleToUnit_differentProperty_isFalse() {
        PropertyAmenity a = amenity(true);
        a.setPropertyId(UUID.randomUUID());

        assertThat(service.amenityVisibleToUnit(a, unitInProperty(buildingId))).isFalse();
    }
}
```

- [ ] **Step 2: Run — expect compile failure** (`FacilityService` does not exist yet)

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew test --tests "com.datagami.rentaxis.core.service.FacilityServiceTest"
```

Expected: `BUILD FAILED` with `error: cannot find symbol ... class FacilityService`.

- [ ] **Step 3: Implement `FacilityService.java`**

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.AmenityCreateRequest;
import com.datagami.rentaxis.api.dto.AmenityUpdateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotBulkCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotUpdateRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.AmenityBuildingScope;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.ParkingSpotBuildingScope;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.repository.AmenityBuildingScopeRepository;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotBuildingScopeRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Inventory CRUD and renter visibility for bookable facilities. No role logic
 * here — RBAC and property-manager assignment checks live in the controllers,
 * same split as the gate-pass module. Cross-tenant lookups throw
 * {@link NotFoundException} (404, never 403) so ids cannot be probed.
 */
@Service
@Transactional
public class FacilityService {

    private final PropertyAmenityRepository amenityRepository;
    private final AmenityBuildingScopeRepository amenityScopeRepository;
    private final ParkingSpotRepository spotRepository;
    private final ParkingSpotBuildingScopeRepository spotScopeRepository;
    private final PropertyRepository propertyRepository;
    private final BuildingRepository buildingRepository;

    public FacilityService(PropertyAmenityRepository amenityRepository,
                           AmenityBuildingScopeRepository amenityScopeRepository,
                           ParkingSpotRepository spotRepository,
                           ParkingSpotBuildingScopeRepository spotScopeRepository,
                           PropertyRepository propertyRepository,
                           BuildingRepository buildingRepository) {
        this.amenityRepository = amenityRepository;
        this.amenityScopeRepository = amenityScopeRepository;
        this.spotRepository = spotRepository;
        this.spotScopeRepository = spotScopeRepository;
        this.propertyRepository = propertyRepository;
        this.buildingRepository = buildingRepository;
    }

    // ------------------------------------------------------------ amenities

    @Transactional(readOnly = true)
    public Page<PropertyAmenity> listAmenities(UUID tenantId, UUID propertyId, Pageable pageable) {
        if (propertyId != null) {
            return amenityRepository.findByTenantIdAndPropertyId(tenantId, propertyId, pageable);
        }
        return amenityRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public PropertyAmenity getAmenity(UUID tenantId, UUID id) {
        PropertyAmenity amenity = amenityRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Amenity not found"));
        if (!Objects.equals(amenity.getTenantId(), tenantId)) {
            throw new NotFoundException("Amenity not found");
        }
        return amenity;
    }

    public PropertyAmenity createAmenity(UUID tenantId, AmenityCreateRequest req) {
        if (req.propertyId() == null) {
            throw new BusinessRuleViolationException("propertyId is required");
        }
        if (req.nameEn() == null || req.nameEn().isBlank()) {
            throw new BusinessRuleViolationException("nameEn is required");
        }
        requirePropertyInTenant(tenantId, req.propertyId());

        PropertyAmenity amenity = new PropertyAmenity();
        amenity.setTenantId(tenantId);
        amenity.setPropertyId(req.propertyId());
        amenity.setNameEn(req.nameEn().trim());
        amenity.setNameAr(req.nameAr());
        amenity.setDescription(req.description());
        amenity.setBookable(req.bookable() == null || req.bookable());
        amenity.setActive(true);
        PropertyAmenity saved = amenityRepository.save(amenity);
        replaceAmenityScopes(tenantId, saved, req.buildingIds() == null ? List.of() : req.buildingIds());
        return saved;
    }

    public PropertyAmenity updateAmenity(UUID tenantId, UUID id, AmenityUpdateRequest req) {
        PropertyAmenity amenity = getAmenity(tenantId, id);
        if (req.nameEn() != null) amenity.setNameEn(req.nameEn().trim());
        if (req.nameAr() != null) amenity.setNameAr(req.nameAr());
        if (req.description() != null) amenity.setDescription(req.description());
        if (req.bookable() != null) amenity.setBookable(req.bookable());
        if (req.active() != null) amenity.setActive(req.active());
        PropertyAmenity saved = amenityRepository.save(amenity);
        if (req.buildingIds() != null) {
            replaceAmenityScopes(tenantId, saved, req.buildingIds());
        }
        return saved;
    }

    /** Soft-deactivate: hides from renters, leaves existing requests untouched. */
    public void deactivateAmenity(UUID tenantId, UUID id) {
        PropertyAmenity amenity = getAmenity(tenantId, id);
        amenity.setActive(false);
        amenityRepository.save(amenity);
    }

    @Transactional(readOnly = true)
    public List<UUID> amenityBuildingIds(UUID amenityId) {
        return amenityScopeRepository.findByAmenityId(amenityId).stream()
                .map(AmenityBuildingScope::getBuildingId)
                .toList();
    }

    // -------------------------------------------------------------- parking

    @Transactional(readOnly = true)
    public Page<ParkingSpot> listParkingSpots(UUID tenantId, UUID propertyId, Pageable pageable) {
        if (propertyId != null) {
            return spotRepository.findByTenantIdAndPropertyId(tenantId, propertyId, pageable);
        }
        return spotRepository.findByTenantId(tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public ParkingSpot getParkingSpot(UUID tenantId, UUID id) {
        ParkingSpot spot = spotRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Parking spot not found"));
        if (!Objects.equals(spot.getTenantId(), tenantId)) {
            throw new NotFoundException("Parking spot not found");
        }
        return spot;
    }

    public ParkingSpot createParkingSpot(UUID tenantId, ParkingSpotCreateRequest req) {
        if (req.propertyId() == null) {
            throw new BusinessRuleViolationException("propertyId is required");
        }
        requirePropertyInTenant(tenantId, req.propertyId());
        String spotNumber = requireSpotNumber(req.spotNumber());
        requireSpotNumberFree(tenantId, req.propertyId(), spotNumber);

        ParkingSpot spot = newSpot(tenantId, req.propertyId(), spotNumber, req.level(), req.covered());
        ParkingSpot saved = spotRepository.save(spot);
        replaceSpotScopes(tenantId, saved, req.buildingIds() == null ? List.of() : req.buildingIds());
        return saved;
    }

    public List<ParkingSpot> bulkCreateParkingSpots(UUID tenantId, ParkingSpotBulkCreateRequest req) {
        if (req.propertyId() == null) {
            throw new BusinessRuleViolationException("propertyId is required");
        }
        requirePropertyInTenant(tenantId, req.propertyId());
        if (req.spotNumbers() == null || req.spotNumbers().isEmpty()) {
            throw new BusinessRuleViolationException("spotNumbers must not be empty");
        }
        // Dedupe while preserving order; validate every number before creating any.
        Set<String> numbers = new LinkedHashSet<>();
        for (String raw : req.spotNumbers()) {
            numbers.add(requireSpotNumber(raw));
        }
        for (String number : numbers) {
            requireSpotNumberFree(tenantId, req.propertyId(), number);
        }
        List<UUID> buildingIds = req.buildingIds() == null ? List.of() : req.buildingIds();
        List<ParkingSpot> created = new ArrayList<>();
        for (String number : numbers) {
            ParkingSpot saved = spotRepository.save(
                    newSpot(tenantId, req.propertyId(), number, req.level(), req.covered()));
            replaceSpotScopes(tenantId, saved, buildingIds);
            created.add(saved);
        }
        return created;
    }

    public ParkingSpot updateParkingSpot(UUID tenantId, UUID id, ParkingSpotUpdateRequest req) {
        ParkingSpot spot = getParkingSpot(tenantId, id);
        if (req.spotNumber() != null) {
            String spotNumber = requireSpotNumber(req.spotNumber());
            if (!spotNumber.equals(spot.getSpotNumber())) {
                requireSpotNumberFree(tenantId, spot.getPropertyId(), spotNumber);
                spot.setSpotNumber(spotNumber);
            }
        }
        if (req.level() != null) spot.setLevel(req.level());
        if (req.covered() != null) spot.setCovered(req.covered());
        if (req.active() != null) spot.setActive(req.active());
        ParkingSpot saved = spotRepository.save(spot);
        if (req.buildingIds() != null) {
            replaceSpotScopes(tenantId, saved, req.buildingIds());
        }
        return saved;
    }

    /** Soft-deactivate: hides from renters, leaves existing requests untouched. */
    public void deactivateParkingSpot(UUID tenantId, UUID id) {
        ParkingSpot spot = getParkingSpot(tenantId, id);
        spot.setActive(false);
        spotRepository.save(spot);
    }

    @Transactional(readOnly = true)
    public List<UUID> parkingSpotBuildingIds(UUID parkingSpotId) {
        return spotScopeRepository.findByParkingSpotId(parkingSpotId).stream()
                .map(ParkingSpotBuildingScope::getBuildingId)
                .toList();
    }

    // -------------------------------------------------- renter visibility

    public record VisibleFacilities(List<PropertyAmenity> amenities, List<ParkingSpot> parkingSpots) {
    }

    /**
     * Everything the given unit may see: active facilities of the unit's property
     * where the facility has no scope rows (all towers) OR the unit's building is
     * in the scope set. A unit with no building sees only unscoped facilities.
     * Scopes are batched — two queries regardless of facility count.
     */
    @Transactional(readOnly = true)
    public VisibleFacilities visibleFacilities(UUID tenantId, Unit unit) {
        UUID propertyId = unit.getProperty().getId();
        UUID unitBuildingId = unit.getBuilding() == null ? null : unit.getBuilding().getId();

        List<PropertyAmenity> amenities =
                amenityRepository.findByTenantIdAndPropertyIdAndActiveTrue(tenantId, propertyId);
        Map<UUID, List<UUID>> amenityScopes = amenities.isEmpty() ? Map.of()
                : amenityScopeRepository.findByAmenityIdIn(
                        amenities.stream().map(PropertyAmenity::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(AmenityBuildingScope::getAmenityId,
                        Collectors.mapping(AmenityBuildingScope::getBuildingId, Collectors.toList())));
        List<PropertyAmenity> visibleAmenities = amenities.stream()
                .filter(a -> visible(amenityScopes.getOrDefault(a.getId(), List.of()), unitBuildingId))
                .toList();

        List<ParkingSpot> spots =
                spotRepository.findByTenantIdAndPropertyIdAndActiveTrue(tenantId, propertyId);
        Map<UUID, List<UUID>> spotScopes = spots.isEmpty() ? Map.of()
                : spotScopeRepository.findByParkingSpotIdIn(
                        spots.stream().map(ParkingSpot::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(ParkingSpotBuildingScope::getParkingSpotId,
                        Collectors.mapping(ParkingSpotBuildingScope::getBuildingId, Collectors.toList())));
        List<ParkingSpot> visibleSpots = spots.stream()
                .filter(s -> visible(spotScopes.getOrDefault(s.getId(), List.of()), unitBuildingId))
                .toList();

        return new VisibleFacilities(visibleAmenities, visibleSpots);
    }

    /** Single-resource visibility check for the booking path. Includes the property match. */
    @Transactional(readOnly = true)
    public boolean amenityVisibleToUnit(PropertyAmenity amenity, Unit unit) {
        if (!Objects.equals(amenity.getPropertyId(), unit.getProperty().getId())) {
            return false;
        }
        UUID unitBuildingId = unit.getBuilding() == null ? null : unit.getBuilding().getId();
        return visible(amenityBuildingIds(amenity.getId()), unitBuildingId);
    }

    /** Single-resource visibility check for the booking path. Includes the property match. */
    @Transactional(readOnly = true)
    public boolean parkingSpotVisibleToUnit(ParkingSpot spot, Unit unit) {
        if (!Objects.equals(spot.getPropertyId(), unit.getProperty().getId())) {
            return false;
        }
        UUID unitBuildingId = unit.getBuilding() == null ? null : unit.getBuilding().getId();
        return visible(parkingSpotBuildingIds(spot.getId()), unitBuildingId);
    }

    private static boolean visible(List<UUID> scopeBuildingIds, UUID unitBuildingId) {
        if (scopeBuildingIds.isEmpty()) {
            return true; // no scope rows = all towers
        }
        return unitBuildingId != null && scopeBuildingIds.contains(unitBuildingId);
    }

    // -------------------------------------------------------------- helpers

    private void requirePropertyInTenant(UUID tenantId, UUID propertyId) {
        // Explicit tenant-scoped existence check, not the Hibernate filter's job —
        // same reasoning as GatePassController.setGuardProperties.
        if (!propertyRepository.existsByIdAndTenantId(propertyId, tenantId)) {
            throw new NotFoundException("Property not found");
        }
    }

    private static String requireSpotNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessRuleViolationException("spotNumber is required");
        }
        return raw.trim();
    }

    private void requireSpotNumberFree(UUID tenantId, UUID propertyId, String spotNumber) {
        // Friendly 400 ahead of uq_parking_spot_number; the DB constraint is the backstop.
        if (spotRepository.existsByTenantIdAndPropertyIdAndSpotNumber(tenantId, propertyId, spotNumber)) {
            throw new BusinessRuleViolationException("Spot number already exists: " + spotNumber);
        }
    }

    private static ParkingSpot newSpot(UUID tenantId, UUID propertyId, String spotNumber,
                                       String level, Boolean covered) {
        ParkingSpot spot = new ParkingSpot();
        spot.setTenantId(tenantId);
        spot.setPropertyId(propertyId);
        spot.setSpotNumber(spotNumber);
        spot.setLevel(level);
        spot.setCovered(covered == null || covered);
        spot.setActive(true);
        return spot;
    }

    private void replaceAmenityScopes(UUID tenantId, PropertyAmenity amenity, List<UUID> buildingIds) {
        List<UUID> requested = validateBuildings(amenity.getPropertyId(), buildingIds);
        amenityScopeRepository.deleteByAmenityId(amenity.getId());
        // uq_amenity_building is checked per-statement: flush so a re-inserted
        // building does not collide with its own pending delete (same reasoning
        // as GatePassController.setGuardProperties).
        amenityScopeRepository.flush();
        for (UUID buildingId : requested) {
            AmenityBuildingScope scope = new AmenityBuildingScope();
            scope.setTenantId(tenantId);
            scope.setAmenityId(amenity.getId());
            scope.setBuildingId(buildingId);
            amenityScopeRepository.save(scope);
        }
    }

    private void replaceSpotScopes(UUID tenantId, ParkingSpot spot, List<UUID> buildingIds) {
        List<UUID> requested = validateBuildings(spot.getPropertyId(), buildingIds);
        spotScopeRepository.deleteByParkingSpotId(spot.getId());
        spotScopeRepository.flush();
        for (UUID buildingId : requested) {
            ParkingSpotBuildingScope scope = new ParkingSpotBuildingScope();
            scope.setTenantId(tenantId);
            scope.setParkingSpotId(spot.getId());
            scope.setBuildingId(buildingId);
            spotScopeRepository.save(scope);
        }
    }

    private List<UUID> validateBuildings(UUID propertyId, List<UUID> buildingIds) {
        List<UUID> requested = buildingIds.stream().filter(Objects::nonNull).distinct().toList();
        if (requested.isEmpty()) {
            return requested;
        }
        Set<UUID> propertyBuildings = buildingRepository.findByPropertyId(propertyId).stream()
                .map(Building::getId)
                .collect(Collectors.toSet());
        for (UUID buildingId : requested) {
            if (!propertyBuildings.contains(buildingId)) {
                throw new BusinessRuleViolationException("Building is not in this property: " + buildingId);
            }
        }
        return requested;
    }
}
```

- [ ] **Step 4: Run — expect pass**

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew test --tests "com.datagami.rentaxis.core.service.FacilityServiceTest"
```

Expected: `BUILD SUCCESSFUL`, 14 tests passing.

- [ ] **Step 5: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add backend/src/main/java/com/datagami/rentaxis/core/service/FacilityService.java backend/src/test/java/com/datagami/rentaxis/core/service/FacilityServiceTest.java && git commit -m "feat(facilities): FacilityService inventory CRUD and renter visibility"
```

---

### Task B6: BookingService (TDD)

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/BookingServiceTest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/event/BookingRequestedEvent.java` (needed by the service; test verifies it)
- Create: `backend/src/main/java/com/datagami/rentaxis/core/event/BookingDecidedEvent.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/BookingService.java`

- [ ] **Step 1: Write the two event records** (tiny, and the test compiles against them)

`backend/src/main/java/com/datagami/rentaxis/core/event/BookingRequestedEvent.java`:

```java
package com.datagami.rentaxis.core.event;

import java.util.UUID;

public record BookingRequestedEvent(UUID bookingId, UUID tenantId) {
}
```

`backend/src/main/java/com/datagami/rentaxis/core/event/BookingDecidedEvent.java`:

```java
package com.datagami.rentaxis.core.event;

import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;

import java.util.UUID;

public record BookingDecidedEvent(UUID bookingId, UUID tenantId, UUID renterUserId,
                                  BookingRequestStatus status) {
}
```

- [ ] **Step 2: Write the failing test**

`BookingServiceTest.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BookingCreateRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.SlotConflictException;
import com.datagami.rentaxis.core.event.BookingDecidedEvent;
import com.datagami.rentaxis.core.event.BookingRequestedEvent;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingServiceTest {

    private BookingRequestRepository bookingRepository;
    private FacilityService facilityService;
    private ApplicationEventPublisher eventPublisher;
    private BookingService service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID renterUserId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private Unit unit;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRequestRepository.class);
        facilityService = mock(FacilityService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new BookingService(bookingRepository, facilityService, eventPublisher);

        when(bookingRepository.save(any(BookingRequest.class))).thenAnswer(inv -> {
            BookingRequest b = inv.getArgument(0);
            if (b.getId() == null) b.setId(UUID.randomUUID());
            return b;
        });

        Property property = new Property();
        property.setId(propertyId);
        unit = new Unit();
        unit.setId(UUID.randomUUID());
        unit.setProperty(property);
    }

    private PropertyAmenity amenity(boolean bookable, boolean active) {
        PropertyAmenity a = new PropertyAmenity();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setPropertyId(propertyId);
        a.setNameEn("Gym");
        a.setBookable(bookable);
        a.setActive(active);
        return a;
    }

    private ParkingSpot spot(boolean active) {
        ParkingSpot s = new ParkingSpot();
        s.setId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setPropertyId(propertyId);
        s.setSpotNumber("B1-07");
        s.setActive(active);
        return s;
    }

    private BookingRequest booking(BookingResourceType type, BookingRequestStatus status) {
        BookingRequest b = new BookingRequest();
        b.setId(UUID.randomUUID());
        b.setTenantId(tenantId);
        b.setPropertyId(propertyId);
        b.setUnitId(unit.getId());
        b.setRenterUserId(renterUserId);
        b.setResourceType(type);
        if (type == BookingResourceType.AMENITY) {
            b.setAmenityId(UUID.randomUUID());
        } else {
            b.setParkingSpotId(UUID.randomUUID());
        }
        b.setStatus(status);
        return b;
    }

    // ---- create ----

    @Test
    void create_amenity_savesPendingWithDerivedPropertyAndPublishesEvent() {
        PropertyAmenity a = amenity(true, true);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(facilityService.amenityVisibleToUnit(a, unit)).thenReturn(true);
        when(bookingRepository.findFirstByTenantIdAndRenterUserIdAndAmenityIdAndStatus(
                tenantId, renterUserId, a.getId(), BookingRequestStatus.PENDING))
                .thenReturn(Optional.empty());

        BookingRequest created = service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, "pls"));

        assertThat(created.getStatus()).isEqualTo(BookingRequestStatus.PENDING);
        assertThat(created.getPropertyId()).isEqualTo(propertyId);
        assertThat(created.getAmenityId()).isEqualTo(a.getId());
        verify(eventPublisher).publishEvent(any(BookingRequestedEvent.class));
    }

    @Test
    void create_nonBookableAmenity_throws400() {
        PropertyAmenity a = amenity(false, true);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(facilityService.amenityVisibleToUnit(a, unit)).thenReturn(true);

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, null)))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void create_amenityNotVisibleToUnit_throwsNotFound() {
        PropertyAmenity a = amenity(true, true);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(facilityService.amenityVisibleToUnit(a, unit)).thenReturn(false);

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_inactiveAmenity_throwsNotFound() {
        PropertyAmenity a = amenity(true, false);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void create_existingPending_returnsItWithoutSavingOrEvent() {
        PropertyAmenity a = amenity(true, true);
        BookingRequest existing = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);
        when(facilityService.amenityVisibleToUnit(a, unit)).thenReturn(true);
        when(bookingRepository.findFirstByTenantIdAndRenterUserIdAndAmenityIdAndStatus(
                tenantId, renterUserId, a.getId(), BookingRequestStatus.PENDING))
                .thenReturn(Optional.of(existing));

        BookingRequest result = service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.AMENITY, a.getId(), unit.getId(), null, null));

        assertThat(result).isSameAs(existing);
        verify(bookingRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void create_parkingSpotAlreadyApproved_throwsConflict() {
        ParkingSpot s = spot(true);
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);
        when(facilityService.parkingSpotVisibleToUnit(s, unit)).thenReturn(true);
        when(bookingRepository.findFirstByTenantIdAndRenterUserIdAndParkingSpotIdAndStatus(
                tenantId, renterUserId, s.getId(), BookingRequestStatus.PENDING))
                .thenReturn(Optional.empty());
        when(bookingRepository.existsByParkingSpotIdAndStatus(s.getId(), BookingRequestStatus.APPROVED))
                .thenReturn(true);

        assertThatThrownBy(() -> service.create(tenantId, renterUserId, unit,
                new BookingCreateRequest(BookingResourceType.PARKING_SPOT, s.getId(), unit.getId(), null, null)))
                .isInstanceOf(SlotConflictException.class);
    }

    // ---- transitions ----

    @Test
    void approve_pending_setsDecisionFieldsAndPublishes() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        UUID adminId = UUID.randomUUID();
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));

        BookingRequest approved = service.approve(tenantId, b.getId(), adminId, "ok");

        assertThat(approved.getStatus()).isEqualTo(BookingRequestStatus.APPROVED);
        assertThat(approved.getDecidedByUserId()).isEqualTo(adminId);
        assertThat(approved.getDecidedAt()).isNotNull();
        assertThat(approved.getAdminNote()).isEqualTo("ok");
        verify(eventPublisher).publishEvent(any(BookingDecidedEvent.class));
    }

    @Test
    void approve_parkingWhenSpotHeldElsewhere_throwsConflict() {
        BookingRequest b = booking(BookingResourceType.PARKING_SPOT, BookingRequestStatus.PENDING);
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));
        when(bookingRepository.existsByParkingSpotIdAndStatus(b.getParkingSpotId(),
                BookingRequestStatus.APPROVED)).thenReturn(true);

        assertThatThrownBy(() -> service.approve(tenantId, b.getId(), UUID.randomUUID(), null))
                .isInstanceOf(SlotConflictException.class);
    }

    @Test
    void approve_nonPending_throws400() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.REJECTED);
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.approve(tenantId, b.getId(), UUID.randomUUID(), null))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void reject_pending_setsRejectedAndPublishes() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));

        BookingRequest rejected = service.reject(tenantId, b.getId(), UUID.randomUUID(), "no");

        assertThat(rejected.getStatus()).isEqualTo(BookingRequestStatus.REJECTED);
        verify(eventPublisher).publishEvent(any(BookingDecidedEvent.class));
    }

    @Test
    void cancel_othersBooking_throwsNotFound() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.cancel(tenantId, b.getId(), UUID.randomUUID()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void cancel_ownPending_setsCancelledWithoutEvent() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));

        BookingRequest cancelled = service.cancel(tenantId, b.getId(), renterUserId);

        assertThat(cancelled.getStatus()).isEqualTo(BookingRequestStatus.CANCELLED);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void release_amenity_throws400() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.APPROVED);
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.release(tenantId, b.getId(), renterUserId, false))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    @Test
    void release_renterNotOwner_throwsNotFound() {
        BookingRequest b = booking(BookingResourceType.PARKING_SPOT, BookingRequestStatus.APPROVED);
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.release(tenantId, b.getId(), UUID.randomUUID(), false))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void release_adminOnApprovedParking_setsReleasedAndPublishes() {
        BookingRequest b = booking(BookingResourceType.PARKING_SPOT, BookingRequestStatus.APPROVED);
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));

        BookingRequest released = service.release(tenantId, b.getId(), UUID.randomUUID(), true);

        assertThat(released.getStatus()).isEqualTo(BookingRequestStatus.RELEASED);
        verify(eventPublisher).publishEvent(any(BookingDecidedEvent.class));
    }

    @Test
    void get_wrongTenant_throwsNotFound() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        b.setTenantId(UUID.randomUUID());
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));

        assertThatThrownBy(() -> service.get(tenantId, b.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void otherRequests_excludesTheRequestItself() {
        BookingRequest b = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        BookingRequest sibling = booking(BookingResourceType.AMENITY, BookingRequestStatus.PENDING);
        sibling.setAmenityId(b.getAmenityId());
        when(bookingRepository.findByAmenityIdAndStatusInOrderByCreatedAtAsc(
                eq(b.getAmenityId()), any())).thenReturn(List.of(b, sibling));

        assertThat(service.otherRequests(b)).containsExactly(sibling);
    }
}
```

- [ ] **Step 3: Run — expect compile failure** (`BookingService` missing)

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew test --tests "com.datagami.rentaxis.core.service.BookingServiceTest"
```

Expected: `BUILD FAILED`, `error: cannot find symbol ... class BookingService`.

- [ ] **Step 4: Implement `BookingService.java`**

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.BookingCreateRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.SlotConflictException;
import com.datagami.rentaxis.core.event.BookingDecidedEvent;
import com.datagami.rentaxis.core.event.BookingRequestedEvent;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Booking request lifecycle. No role logic — RBAC and the renter's
 * active-lease ownership check live in {@code BookingController}, same split
 * as the gate-pass module. The {@code unit} passed to {@link #create} must
 * already be verified as leased by the caller. Cross-tenant and
 * not-visible-to-caller lookups throw {@link NotFoundException} (404, never
 * 403) so ids cannot be probed. The partial unique indexes
 * uq_booking_spot_active / uq_booking_pending_renter_* are the DB backstops
 * behind the conflict and idempotency checks here.
 */
@Service
@Transactional
public class BookingService {

    private static final List<BookingRequestStatus> OPEN_STATUSES =
            List.of(BookingRequestStatus.PENDING, BookingRequestStatus.APPROVED);

    private final BookingRequestRepository bookingRepository;
    private final FacilityService facilityService;
    private final ApplicationEventPublisher eventPublisher;

    public BookingService(BookingRequestRepository bookingRepository,
                          FacilityService facilityService,
                          ApplicationEventPublisher eventPublisher) {
        this.bookingRepository = bookingRepository;
        this.facilityService = facilityService;
        this.eventPublisher = eventPublisher;
    }

    public BookingRequest create(UUID tenantId, UUID renterUserId, Unit unit, BookingCreateRequest req) {
        if (req.resourceType() == null) {
            throw new BusinessRuleViolationException("resourceType is required");
        }
        if (req.resourceId() == null) {
            throw new BusinessRuleViolationException("resourceId is required");
        }

        BookingRequest booking = new BookingRequest();
        booking.setTenantId(tenantId);
        booking.setUnitId(unit.getId());
        booking.setRenterUserId(renterUserId);
        booking.setResourceType(req.resourceType());
        booking.setNote(req.note());
        booking.setPreferredDate(req.preferredDate());
        booking.setStatus(BookingRequestStatus.PENDING);

        if (req.resourceType() == BookingResourceType.AMENITY) {
            PropertyAmenity amenity = facilityService.getAmenity(tenantId, req.resourceId());
            // Inactive or out-of-scope resources are indistinguishable from missing ones.
            if (!amenity.isActive() || !facilityService.amenityVisibleToUnit(amenity, unit)) {
                throw new NotFoundException("Amenity not found");
            }
            if (!amenity.isBookable()) {
                throw new BusinessRuleViolationException("This amenity is not bookable");
            }
            Optional<BookingRequest> existing =
                    bookingRepository.findFirstByTenantIdAndRenterUserIdAndAmenityIdAndStatus(
                            tenantId, renterUserId, amenity.getId(), BookingRequestStatus.PENDING);
            if (existing.isPresent()) {
                return existing.get(); // idempotent, like InterestService.addInterest
            }
            booking.setAmenityId(amenity.getId());
            // propertyId always derives from the resource, never from the client.
            booking.setPropertyId(amenity.getPropertyId());
        } else {
            ParkingSpot spot = facilityService.getParkingSpot(tenantId, req.resourceId());
            if (!spot.isActive() || !facilityService.parkingSpotVisibleToUnit(spot, unit)) {
                throw new NotFoundException("Parking spot not found");
            }
            Optional<BookingRequest> existing =
                    bookingRepository.findFirstByTenantIdAndRenterUserIdAndParkingSpotIdAndStatus(
                            tenantId, renterUserId, spot.getId(), BookingRequestStatus.PENDING);
            if (existing.isPresent()) {
                return existing.get();
            }
            if (bookingRepository.existsByParkingSpotIdAndStatus(spot.getId(), BookingRequestStatus.APPROVED)) {
                throw new SlotConflictException("Parking spot is already assigned", null);
            }
            booking.setParkingSpotId(spot.getId());
            booking.setPropertyId(spot.getPropertyId());
        }

        BookingRequest saved = bookingRepository.save(booking);
        eventPublisher.publishEvent(new BookingRequestedEvent(saved.getId(), tenantId));
        return saved;
    }

    @Transactional(readOnly = true)
    public BookingRequest get(UUID tenantId, UUID id) {
        BookingRequest booking = bookingRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Booking not found"));
        if (!Objects.equals(booking.getTenantId(), tenantId)) {
            throw new NotFoundException("Booking not found");
        }
        return booking;
    }

    public BookingRequest approve(UUID tenantId, UUID id, UUID adminUserId, String adminNote) {
        BookingRequest booking = get(tenantId, id);
        requirePending(booking);
        if (booking.getResourceType() == BookingResourceType.PARKING_SPOT
                && bookingRepository.existsByParkingSpotIdAndStatus(
                        booking.getParkingSpotId(), BookingRequestStatus.APPROVED)) {
            throw new SlotConflictException("Parking spot is already assigned to another renter", null);
        }
        return decide(booking, BookingRequestStatus.APPROVED, adminUserId, adminNote);
    }

    public BookingRequest reject(UUID tenantId, UUID id, UUID adminUserId, String adminNote) {
        BookingRequest booking = get(tenantId, id);
        requirePending(booking);
        return decide(booking, BookingRequestStatus.REJECTED, adminUserId, adminNote);
    }

    /** Renter withdraws their own PENDING request. 404 on someone else's — no probing. */
    public BookingRequest cancel(UUID tenantId, UUID id, UUID renterUserId) {
        BookingRequest booking = get(tenantId, id);
        if (!renterUserId.equals(booking.getRenterUserId())) {
            throw new NotFoundException("Booking not found");
        }
        requirePending(booking);
        booking.setStatus(BookingRequestStatus.CANCELLED);
        return bookingRepository.save(booking);
    }

    /** APPROVED parking only. actorIsAdmin=false enforces the renter-owner rule. */
    public BookingRequest release(UUID tenantId, UUID id, UUID actorUserId, boolean actorIsAdmin) {
        BookingRequest booking = get(tenantId, id);
        if (!actorIsAdmin && !actorUserId.equals(booking.getRenterUserId())) {
            throw new NotFoundException("Booking not found");
        }
        if (booking.getResourceType() != BookingResourceType.PARKING_SPOT) {
            throw new BusinessRuleViolationException("Only parking bookings can be released");
        }
        if (booking.getStatus() != BookingRequestStatus.APPROVED) {
            throw new BusinessRuleViolationException("Only approved bookings can be released");
        }
        booking.setStatus(BookingRequestStatus.RELEASED);
        booking.setDecidedByUserId(actorUserId);
        booking.setDecidedAt(Instant.now());
        BookingRequest saved = bookingRepository.save(booking);
        eventPublisher.publishEvent(new BookingDecidedEvent(
                saved.getId(), tenantId, saved.getRenterUserId(), BookingRequestStatus.RELEASED));
        return saved;
    }

    @Transactional(readOnly = true)
    public Page<BookingRequest> search(UUID tenantId, UUID propertyId, BookingRequestStatus status,
                                       BookingResourceType resourceType, Pageable pageable) {
        return bookingRepository.search(tenantId, propertyId, status, resourceType, pageable);
    }

    @Transactional(readOnly = true)
    public List<BookingRequest> listMine(UUID tenantId, UUID renterUserId) {
        return bookingRepository.findByTenantIdAndRenterUserIdOrderByCreatedAtAsc(tenantId, renterUserId);
    }

    /** All other PENDING/APPROVED requests for the same resource — the admin's context. */
    @Transactional(readOnly = true)
    public List<BookingRequest> otherRequests(BookingRequest booking) {
        List<BookingRequest> siblings = booking.getResourceType() == BookingResourceType.AMENITY
                ? bookingRepository.findByAmenityIdAndStatusInOrderByCreatedAtAsc(
                        booking.getAmenityId(), OPEN_STATUSES)
                : bookingRepository.findByParkingSpotIdAndStatusInOrderByCreatedAtAsc(
                        booking.getParkingSpotId(), OPEN_STATUSES);
        return siblings.stream().filter(b -> !b.getId().equals(booking.getId())).toList();
    }

    @Transactional(readOnly = true)
    public long countPendingForAmenity(UUID amenityId) {
        return bookingRepository.countByAmenityIdAndStatus(amenityId, BookingRequestStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public long countPendingForSpot(UUID parkingSpotId) {
        return bookingRepository.countByParkingSpotIdAndStatus(parkingSpotId, BookingRequestStatus.PENDING);
    }

    @Transactional(readOnly = true)
    public boolean spotHeld(UUID parkingSpotId) {
        return bookingRepository.existsByParkingSpotIdAndStatus(parkingSpotId, BookingRequestStatus.APPROVED);
    }

    private BookingRequest decide(BookingRequest booking, BookingRequestStatus status,
                                  UUID adminUserId, String adminNote) {
        booking.setStatus(status);
        booking.setAdminNote(adminNote);
        booking.setDecidedByUserId(adminUserId);
        booking.setDecidedAt(Instant.now());
        BookingRequest saved = bookingRepository.save(booking);
        eventPublisher.publishEvent(new BookingDecidedEvent(
                saved.getId(), saved.getTenantId(), saved.getRenterUserId(), status));
        return saved;
    }

    private static void requirePending(BookingRequest booking) {
        if (booking.getStatus() != BookingRequestStatus.PENDING) {
            throw new BusinessRuleViolationException("Booking is not pending");
        }
    }
}
```

- [ ] **Step 5: Run — expect pass**

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew test --tests "com.datagami.rentaxis.core.service.BookingServiceTest"
```

Expected: `BUILD SUCCESSFUL`, 17 tests passing.

- [ ] **Step 6: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add backend/src/main/java/com/datagami/rentaxis/core/service/BookingService.java backend/src/main/java/com/datagami/rentaxis/core/event/BookingRequestedEvent.java backend/src/main/java/com/datagami/rentaxis/core/event/BookingDecidedEvent.java backend/src/test/java/com/datagami/rentaxis/core/service/BookingServiceTest.java && git commit -m "feat(facilities): BookingService request lifecycle with events"
```

---

### Task B7: BookingNotificationService (TDD)

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/core/service/BookingNotificationServiceTest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/core/service/BookingNotificationService.java`

- [ ] **Step 1: Write the failing test**

`BookingNotificationServiceTest.java`:

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.event.BookingDecidedEvent;
import com.datagami.rentaxis.core.event.BookingRequestedEvent;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BookingNotificationServiceTest {

    private BookingRequestRepository bookingRepository;
    private PropertyAmenityRepository amenityRepository;
    private ParkingSpotRepository parkingSpotRepository;
    private UserRepository userRepository;
    private NotificationService notificationService;
    private BookingNotificationService service;

    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRequestRepository.class);
        amenityRepository = mock(PropertyAmenityRepository.class);
        parkingSpotRepository = mock(ParkingSpotRepository.class);
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);
        service = new BookingNotificationService(bookingRepository, amenityRepository,
                parkingSpotRepository, userRepository, notificationService);
    }

    private User user(UserRole role) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setRole(role);
        return u;
    }

    private BookingRequest amenityBooking() {
        BookingRequest b = new BookingRequest();
        b.setId(UUID.randomUUID());
        b.setTenantId(tenantId);
        b.setResourceType(BookingResourceType.AMENITY);
        b.setAmenityId(UUID.randomUUID());
        b.setRenterUserId(UUID.randomUUID());
        PropertyAmenity a = new PropertyAmenity();
        a.setId(b.getAmenityId());
        a.setNameEn("Gym");
        when(bookingRepository.findById(b.getId())).thenReturn(Optional.of(b));
        when(amenityRepository.findById(b.getAmenityId())).thenReturn(Optional.of(a));
        return b;
    }

    @Test
    void onBookingRequested_notifiesAdminsAndPMsOnly() {
        BookingRequest b = amenityBooking();
        User admin = user(UserRole.TENANT_ADMIN);
        User pm = user(UserRole.PROPERTY_MANAGER);
        User renter = user(UserRole.RENTER);
        when(userRepository.findByTenantId(tenantId)).thenReturn(List.of(admin, pm, renter));

        service.onBookingRequested(new BookingRequestedEvent(b.getId(), tenantId));

        verify(notificationService).notify(eq(tenantId), eq(admin.getId()), eq("BOOKING_REQUESTED"),
                anyString(), anyString(), eq("BOOKING"), eq(b.getId()));
        verify(notificationService).notify(eq(tenantId), eq(pm.getId()), eq("BOOKING_REQUESTED"),
                anyString(), anyString(), eq("BOOKING"), eq(b.getId()));
        verify(notificationService, never()).notify(eq(tenantId), eq(renter.getId()), anyString(),
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void onBookingRequested_oneFailingRecipientDoesNotStopOthers() {
        BookingRequest b = amenityBooking();
        User admin1 = user(UserRole.TENANT_ADMIN);
        User admin2 = user(UserRole.TENANT_ADMIN);
        when(userRepository.findByTenantId(tenantId)).thenReturn(List.of(admin1, admin2));
        doThrow(new RuntimeException("boom")).when(notificationService).notify(
                eq(tenantId), eq(admin1.getId()), anyString(), anyString(), anyString(), anyString(), any());

        service.onBookingRequested(new BookingRequestedEvent(b.getId(), tenantId));

        verify(notificationService).notify(eq(tenantId), eq(admin2.getId()), eq("BOOKING_REQUESTED"),
                anyString(), anyString(), eq("BOOKING"), eq(b.getId()));
    }

    @Test
    void onBookingDecided_approved_notifiesRenterWithApprovedType() {
        BookingRequest b = amenityBooking();

        service.onBookingDecided(new BookingDecidedEvent(
                b.getId(), tenantId, b.getRenterUserId(), BookingRequestStatus.APPROVED));

        verify(notificationService).notify(eq(tenantId), eq(b.getRenterUserId()),
                eq("BOOKING_APPROVED"), anyString(), anyString(), eq("BOOKING"), eq(b.getId()));
    }

    @Test
    void onBookingDecided_released_notifiesRenterWithReleasedType() {
        BookingRequest b = amenityBooking();

        service.onBookingDecided(new BookingDecidedEvent(
                b.getId(), tenantId, b.getRenterUserId(), BookingRequestStatus.RELEASED));

        verify(notificationService).notify(eq(tenantId), eq(b.getRenterUserId()),
                eq("BOOKING_RELEASED"), anyString(), anyString(), eq("BOOKING"), eq(b.getId()));
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew test --tests "com.datagami.rentaxis.core.service.BookingNotificationServiceTest"
```

Expected: `BUILD FAILED`, `cannot find symbol ... class BookingNotificationService`.

- [ ] **Step 3: Implement `BookingNotificationService.java`** (same AFTER_COMMIT + REQUIRES_NEW + per-recipient try/catch pattern as `ListingNotificationService`; `BOOKING_REQUESTED`/`BOOKING_*` types have no `EmailEventType` mapping in `NotificationService.mapLegacyType`, so `notify` produces in-app rows only — matching the spec)

```java
package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.core.event.BookingDecidedEvent;
import com.datagami.rentaxis.core.event.BookingRequestedEvent;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.BookingRequestRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingNotificationService {

    private final BookingRequestRepository bookingRepository;
    private final PropertyAmenityRepository amenityRepository;
    private final ParkingSpotRepository parkingSpotRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    /** New request → in-app row for every TENANT_ADMIN and PROPERTY_MANAGER of the tenant. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingRequested(BookingRequestedEvent event) {
        Optional<BookingRequest> bookingOpt = bookingRepository.findById(event.bookingId());
        if (bookingOpt.isEmpty()) {
            log.warn("BookingRequestedEvent for missing booking {}", event.bookingId());
            return;
        }
        String resourceName = resourceName(bookingOpt.get());

        Set<UserRole> notifyRoles = Set.of(UserRole.TENANT_ADMIN, UserRole.PROPERTY_MANAGER);
        List<User> recipients = userRepository.findByTenantId(event.tenantId()).stream()
                .filter(u -> notifyRoles.contains(u.getRole()))
                .toList();
        for (User recipient : recipients) {
            try {
                notificationService.notify(
                        event.tenantId(),
                        recipient.getId(),
                        "BOOKING_REQUESTED",
                        "New booking request",
                        "A renter has requested " + resourceName + ".",
                        "BOOKING",
                        event.bookingId());
            } catch (Exception ex) {
                log.error("Failed to notify user {} for booking {} — {}",
                        recipient.getId(), event.bookingId(), ex.getMessage());
                // Continue — one failing recipient must not abort the rest
            }
        }
        log.info("BookingRequestedEvent processed: notified {} users for booking {}",
                recipients.size(), event.bookingId());
    }

    /** Decision → in-app row for the requesting renter. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onBookingDecided(BookingDecidedEvent event) {
        String type = switch (event.status()) {
            case APPROVED -> "BOOKING_APPROVED";
            case REJECTED -> "BOOKING_REJECTED";
            case RELEASED -> "BOOKING_RELEASED";
            default -> null;
        };
        if (type == null) {
            return;
        }
        String resourceName = bookingRepository.findById(event.bookingId())
                .map(this::resourceName)
                .orElse("your booking");
        String message = switch (event.status()) {
            case APPROVED -> "Your booking request for " + resourceName + " has been approved.";
            case REJECTED -> "Your booking request for " + resourceName + " has been rejected.";
            default -> "Your parking booking for " + resourceName + " has been released.";
        };
        try {
            notificationService.notify(event.tenantId(), event.renterUserId(), type,
                    "Booking update", message, "BOOKING", event.bookingId());
        } catch (Exception ex) {
            log.error("Failed to notify renter {} for booking {} — {}",
                    event.renterUserId(), event.bookingId(), ex.getMessage());
        }
    }

    private String resourceName(BookingRequest booking) {
        if (booking.getResourceType() == BookingResourceType.AMENITY) {
            return amenityRepository.findById(booking.getAmenityId())
                    .map(PropertyAmenity::getNameEn)
                    .orElse("an amenity");
        }
        return parkingSpotRepository.findById(booking.getParkingSpotId())
                .map(s -> "parking spot " + s.getSpotNumber())
                .orElse("a parking spot");
    }
}
```

- [ ] **Step 4: Run — expect pass**

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew test --tests "com.datagami.rentaxis.core.service.BookingNotificationServiceTest"
```

Expected: `BUILD SUCCESSFUL`, 4 tests passing.

- [ ] **Step 5: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add backend/src/main/java/com/datagami/rentaxis/core/service/BookingNotificationService.java backend/src/test/java/com/datagami/rentaxis/core/service/BookingNotificationServiceTest.java && git commit -m "feat(facilities): in-app notifications for booking events"
```

---

### Task B8: AmenityController + ParkingSpotController (TDD)

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/api/AmenityControllerTest.java`
- Test: `backend/src/test/java/com/datagami/rentaxis/api/ParkingSpotControllerTest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/AmenityController.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/ParkingSpotController.java`

- [ ] **Step 1: Write both failing tests** (Mockito style of `UnitListingControllerTest`; auth names are UUID strings because `checkPropertyManagerAccess` parses them)

`AmenityControllerTest.java`:

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.AmenityCreateRequest;
import com.datagami.rentaxis.api.dto.AmenityDTO;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.core.service.BookingService;
import com.datagami.rentaxis.core.service.FacilityService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AmenityControllerTest {

    @Mock
    FacilityService facilityService;

    @Mock
    BookingService bookingService;

    @Mock
    UserPropertyAssignmentRepository assignmentRepository;

    @InjectMocks
    AmenityController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();
    private final UUID pmUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
        authenticateAs(adminUserId, "ROLE_TENANT_ADMIN");
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(UUID userId, String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null,
                        Collections.singletonList(new SimpleGrantedAuthority(authority))));
    }

    private PropertyAmenity amenity() {
        PropertyAmenity a = new PropertyAmenity();
        a.setId(UUID.randomUUID());
        a.setTenantId(tenantId);
        a.setPropertyId(propertyId);
        a.setNameEn("Gym");
        return a;
    }

    @Test
    void list_returns200WithMappedDTO() {
        PropertyAmenity a = amenity();
        when(facilityService.listAmenities(eq(tenantId), eq(propertyId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a)));
        when(facilityService.amenityBuildingIds(a.getId())).thenReturn(List.of());
        when(bookingService.countPendingForAmenity(a.getId())).thenReturn(2L);

        ResponseEntity<org.springframework.data.domain.Page<AmenityDTO>> response =
                controller.list(propertyId, PageRequest.of(0, 20));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        AmenityDTO dto = response.getBody().getContent().getFirst();
        assertThat(dto.nameEn()).isEqualTo("Gym");
        assertThat(dto.pendingCount()).isEqualTo(2L);
    }

    @Test
    void create_returns201() {
        PropertyAmenity a = amenity();
        when(facilityService.createAmenity(eq(tenantId), any())).thenReturn(a);
        when(facilityService.amenityBuildingIds(a.getId())).thenReturn(List.of());
        when(bookingService.countPendingForAmenity(a.getId())).thenReturn(0L);

        ResponseEntity<AmenityDTO> response = controller.create(
                new AmenityCreateRequest(propertyId, "Gym", null, null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void create_pmWithoutAssignment_throwsAccessDenied() {
        authenticateAs(pmUserId, "ROLE_PROPERTY_MANAGER");
        when(assignmentRepository.existsByUserIdAndPropertyId(pmUserId, propertyId)).thenReturn(false);

        assertThatThrownBy(() -> controller.create(
                new AmenityCreateRequest(propertyId, "Gym", null, null, null, null)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void create_pmWithAssignment_allowed() {
        authenticateAs(pmUserId, "ROLE_PROPERTY_MANAGER");
        when(assignmentRepository.existsByUserIdAndPropertyId(pmUserId, propertyId)).thenReturn(true);
        PropertyAmenity a = amenity();
        when(facilityService.createAmenity(eq(tenantId), any())).thenReturn(a);
        when(facilityService.amenityBuildingIds(a.getId())).thenReturn(List.of());
        when(bookingService.countPendingForAmenity(a.getId())).thenReturn(0L);

        assertThat(controller.create(new AmenityCreateRequest(propertyId, "Gym", null, null, null, null))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void list_pmWithoutPropertyId_throwsAccessDenied() {
        authenticateAs(pmUserId, "ROLE_PROPERTY_MANAGER");

        assertThatThrownBy(() -> controller.list(null, PageRequest.of(0, 20)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void delete_returns204AndDelegates() {
        PropertyAmenity a = amenity();
        when(facilityService.getAmenity(tenantId, a.getId())).thenReturn(a);

        ResponseEntity<Void> response = controller.deactivate(a.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(facilityService).deactivateAmenity(tenantId, a.getId());
    }
}
```

`ParkingSpotControllerTest.java`:

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ParkingSpotBulkCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotDTO;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.core.service.BookingService;
import com.datagami.rentaxis.core.service.FacilityService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ParkingSpotControllerTest {

    @Mock
    FacilityService facilityService;

    @Mock
    BookingService bookingService;

    @Mock
    UserPropertyAssignmentRepository assignmentRepository;

    @InjectMocks
    ParkingSpotController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();
    private final UUID adminUserId = UUID.randomUUID();
    private final UUID pmUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(adminUserId.toString(), null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private ParkingSpot spot(String number) {
        ParkingSpot s = new ParkingSpot();
        s.setId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setPropertyId(propertyId);
        s.setSpotNumber(number);
        return s;
    }

    @Test
    void create_returns201WithHeldFlag() {
        ParkingSpot s = spot("B1-07");
        when(facilityService.createParkingSpot(eq(tenantId), any())).thenReturn(s);
        when(facilityService.parkingSpotBuildingIds(s.getId())).thenReturn(List.of());
        when(bookingService.countPendingForSpot(s.getId())).thenReturn(1L);
        when(bookingService.spotHeld(s.getId())).thenReturn(true);

        ResponseEntity<ParkingSpotDTO> response = controller.create(
                new ParkingSpotCreateRequest(propertyId, "B1-07", "B1", null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().held()).isTrue();
        assertThat(response.getBody().pendingCount()).isEqualTo(1L);
    }

    @Test
    void bulk_returns201WithAllSpots() {
        List<ParkingSpot> created = List.of(spot("B1-01"), spot("B1-02"));
        when(facilityService.bulkCreateParkingSpots(eq(tenantId), any())).thenReturn(created);
        for (ParkingSpot s : created) {
            when(facilityService.parkingSpotBuildingIds(s.getId())).thenReturn(List.of());
            when(bookingService.countPendingForSpot(s.getId())).thenReturn(0L);
            when(bookingService.spotHeld(s.getId())).thenReturn(false);
        }

        ResponseEntity<List<ParkingSpotDTO>> response = controller.bulkCreate(
                new ParkingSpotBulkCreateRequest(propertyId, List.of("B1-01", "B1-02"), null, null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    void update_pmWithoutAssignmentOnSpotProperty_throwsAccessDenied() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(pmUserId.toString(), null,
                        Collections.singletonList(new SimpleGrantedAuthority("ROLE_PROPERTY_MANAGER"))));
        ParkingSpot s = spot("B1-07");
        when(facilityService.getParkingSpot(tenantId, s.getId())).thenReturn(s);
        when(assignmentRepository.existsByUserIdAndPropertyId(pmUserId, propertyId)).thenReturn(false);

        assertThatThrownBy(() -> controller.update(s.getId(),
                new com.datagami.rentaxis.api.dto.ParkingSpotUpdateRequest(null, null, null, false, null)))
                .isInstanceOf(AccessDeniedException.class);
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew test --tests "com.datagami.rentaxis.api.AmenityControllerTest" --tests "com.datagami.rentaxis.api.ParkingSpotControllerTest"
```

Expected: `BUILD FAILED`, `cannot find symbol ... class AmenityController`.

- [ ] **Step 3: Implement `AmenityController.java`**

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.AmenityCreateRequest;
import com.datagami.rentaxis.api.dto.AmenityDTO;
import com.datagami.rentaxis.api.dto.AmenityUpdateRequest;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.core.service.BookingService;
import com.datagami.rentaxis.core.service.FacilityService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Admin inventory surface for property amenities. RBAC lives here, not in
 * {@link FacilityService} — same split as the gate-pass module. PROPERTY_MANAGER
 * callers are additionally checked against their property assignments.
 */
@RestController
@RequestMapping("/api/v1/amenities")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
public class AmenityController {

    private final FacilityService facilityService;
    private final BookingService bookingService;
    private final UserPropertyAssignmentRepository assignmentRepository;

    public AmenityController(FacilityService facilityService,
                             BookingService bookingService,
                             UserPropertyAssignmentRepository assignmentRepository) {
        this.facilityService = facilityService;
        this.bookingService = bookingService;
        this.assignmentRepository = assignmentRepository;
    }

    @GetMapping
    public ResponseEntity<Page<AmenityDTO>> list(
            @RequestParam(required = false) UUID propertyId,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        checkPropertyManagerAccess(propertyId);
        UUID tenantId = TenantContextHolder.getTenantId();
        Page<PropertyAmenity> page = facilityService.listAmenities(tenantId, propertyId, pageable);
        return ResponseEntity.ok(page.map(this::toDTO));
    }

    @PostMapping
    public ResponseEntity<AmenityDTO> create(@RequestBody AmenityCreateRequest req) {
        checkPropertyManagerAccess(req.propertyId());
        UUID tenantId = TenantContextHolder.getTenantId();
        PropertyAmenity created = facilityService.createAmenity(tenantId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDTO(created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<AmenityDTO> update(@PathVariable UUID id,
                                             @RequestBody AmenityUpdateRequest req) {
        UUID tenantId = TenantContextHolder.getTenantId();
        checkPropertyManagerAccess(facilityService.getAmenity(tenantId, id).getPropertyId());
        return ResponseEntity.ok(toDTO(facilityService.updateAmenity(tenantId, id, req)));
    }

    /** Soft-deactivate — existing booking requests are untouched. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        UUID tenantId = TenantContextHolder.getTenantId();
        checkPropertyManagerAccess(facilityService.getAmenity(tenantId, id).getPropertyId());
        facilityService.deactivateAmenity(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * For PROPERTY_MANAGER callers, verifies the property is assigned to them
     * (UnitListingController.checkPropertyManagerAccess pattern). SUPER_ADMIN
     * and TENANT_ADMIN are unrestricted. A PM must always name a property.
     */
    private void checkPropertyManagerAccess(UUID propertyId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isPm = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PROPERTY_MANAGER"));
        if (!isPm) return;

        if (propertyId == null) {
            throw new AccessDeniedException("propertyId is required for property managers");
        }
        UUID userId = UUID.fromString(auth.getName());
        if (!assignmentRepository.existsByUserIdAndPropertyId(userId, propertyId)) {
            throw new AccessDeniedException("You are not assigned to this property");
        }
    }

    /**
     * Per-row scope + pending-count lookups; page-size bounded, matching the
     * UnitListingController.toSummary precedent (countActiveInterests per row).
     */
    private AmenityDTO toDTO(PropertyAmenity a) {
        return new AmenityDTO(a.getId(), a.getPropertyId(), a.getNameEn(), a.getNameAr(),
                a.getDescription(), a.isBookable(), a.isActive(),
                facilityService.amenityBuildingIds(a.getId()),
                bookingService.countPendingForAmenity(a.getId()),
                a.getCreatedAt(), a.getUpdatedAt());
    }
}
```

- [ ] **Step 4: Implement `ParkingSpotController.java`**

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.ParkingSpotBulkCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotCreateRequest;
import com.datagami.rentaxis.api.dto.ParkingSpotDTO;
import com.datagami.rentaxis.api.dto.ParkingSpotUpdateRequest;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.core.service.BookingService;
import com.datagami.rentaxis.core.service.FacilityService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Admin inventory surface for parking spots. Same RBAC split as AmenityController. */
@RestController
@RequestMapping("/api/v1/parking-spots")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER')")
public class ParkingSpotController {

    private final FacilityService facilityService;
    private final BookingService bookingService;
    private final UserPropertyAssignmentRepository assignmentRepository;

    public ParkingSpotController(FacilityService facilityService,
                                 BookingService bookingService,
                                 UserPropertyAssignmentRepository assignmentRepository) {
        this.facilityService = facilityService;
        this.bookingService = bookingService;
        this.assignmentRepository = assignmentRepository;
    }

    @GetMapping
    public ResponseEntity<Page<ParkingSpotDTO>> list(
            @RequestParam(required = false) UUID propertyId,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        checkPropertyManagerAccess(propertyId);
        UUID tenantId = TenantContextHolder.getTenantId();
        Page<ParkingSpot> page = facilityService.listParkingSpots(tenantId, propertyId, pageable);
        return ResponseEntity.ok(page.map(this::toDTO));
    }

    @PostMapping
    public ResponseEntity<ParkingSpotDTO> create(@RequestBody ParkingSpotCreateRequest req) {
        checkPropertyManagerAccess(req.propertyId());
        UUID tenantId = TenantContextHolder.getTenantId();
        ParkingSpot created = facilityService.createParkingSpot(tenantId, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDTO(created));
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<ParkingSpotDTO>> bulkCreate(@RequestBody ParkingSpotBulkCreateRequest req) {
        checkPropertyManagerAccess(req.propertyId());
        UUID tenantId = TenantContextHolder.getTenantId();
        List<ParkingSpot> created = facilityService.bulkCreateParkingSpots(tenantId, req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(created.stream().map(this::toDTO).toList());
    }

    @PutMapping("/{id}")
    public ResponseEntity<ParkingSpotDTO> update(@PathVariable UUID id,
                                                 @RequestBody ParkingSpotUpdateRequest req) {
        UUID tenantId = TenantContextHolder.getTenantId();
        checkPropertyManagerAccess(facilityService.getParkingSpot(tenantId, id).getPropertyId());
        return ResponseEntity.ok(toDTO(facilityService.updateParkingSpot(tenantId, id, req)));
    }

    /** Soft-deactivate — existing booking requests are untouched. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        UUID tenantId = TenantContextHolder.getTenantId();
        checkPropertyManagerAccess(facilityService.getParkingSpot(tenantId, id).getPropertyId());
        facilityService.deactivateParkingSpot(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    private void checkPropertyManagerAccess(UUID propertyId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isPm = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PROPERTY_MANAGER"));
        if (!isPm) return;

        if (propertyId == null) {
            throw new AccessDeniedException("propertyId is required for property managers");
        }
        UUID userId = UUID.fromString(auth.getName());
        if (!assignmentRepository.existsByUserIdAndPropertyId(userId, propertyId)) {
            throw new AccessDeniedException("You are not assigned to this property");
        }
    }

    private ParkingSpotDTO toDTO(ParkingSpot s) {
        return new ParkingSpotDTO(s.getId(), s.getPropertyId(), s.getSpotNumber(), s.getLevel(),
                s.isCovered(), s.isActive(),
                facilityService.parkingSpotBuildingIds(s.getId()),
                bookingService.spotHeld(s.getId()),
                bookingService.countPendingForSpot(s.getId()),
                s.getCreatedAt(), s.getUpdatedAt());
    }
}
```

- [ ] **Step 5: Run — expect pass**

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew test --tests "com.datagami.rentaxis.api.AmenityControllerTest" --tests "com.datagami.rentaxis.api.ParkingSpotControllerTest"
```

Expected: `BUILD SUCCESSFUL`, 9 tests passing.

- [ ] **Step 6: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add backend/src/main/java/com/datagami/rentaxis/api/AmenityController.java backend/src/main/java/com/datagami/rentaxis/api/ParkingSpotController.java backend/src/test/java/com/datagami/rentaxis/api/AmenityControllerTest.java backend/src/test/java/com/datagami/rentaxis/api/ParkingSpotControllerTest.java && git commit -m "feat(facilities): amenity and parking-spot admin controllers"
```

---

### Task B9: BookingController incl. /api/v1/facilities/my (TDD)

**Files:**
- Test: `backend/src/test/java/com/datagami/rentaxis/api/BookingControllerTest.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/api/BookingController.java`

- [ ] **Step 1: Write the failing test**

`BookingControllerTest.java`:

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.BookingCreateRequest;
import com.datagami.rentaxis.api.dto.BookingRequestDTO;
import com.datagami.rentaxis.api.dto.MyFacilitiesDTO;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.BookingService;
import com.datagami.rentaxis.core.service.FacilityService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {

    @Mock BookingService bookingService;
    @Mock FacilityService facilityService;
    @Mock RenterRepository renterRepository;
    @Mock LeaseRepository leaseRepository;
    @Mock UnitRepository unitRepository;
    @Mock UserRepository userRepository;
    @Mock UserPropertyAssignmentRepository assignmentRepository;
    @Mock PropertyAmenityRepository amenityRepository;
    @Mock ParkingSpotRepository parkingSpotRepository;

    @InjectMocks
    BookingController controller;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID renterUserId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();

    private Renter renter;
    private Unit unit;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
        authenticateAs(renterUserId, "ROLE_RENTER");

        Property property = new Property();
        property.setId(propertyId);
        property.setNameEn("Marina Heights");
        unit = new Unit();
        unit.setId(UUID.randomUUID());
        unit.setUnitNumber("1204");
        unit.setProperty(property);

        renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setTenantId(tenantId);
        renter.setUserId(renterUserId);

        lenient().when(userRepository.findByTenantIdAndIdIn(eq(tenantId), any()))
                .thenReturn(List.of());
        lenient().when(unitRepository.findAllById(any())).thenReturn(List.of(unit));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(UUID userId, String authority) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null,
                        Collections.singletonList(new SimpleGrantedAuthority(authority))));
    }

    private Lease activeLease() {
        Lease lease = new Lease();
        lease.setTenantId(tenantId);
        lease.setStatus(LeaseStatus.ACTIVE);
        lease.setUnit(unit);
        lease.setRenter(renter);
        return lease;
    }

    private BookingRequest booking(BookingResourceType type) {
        BookingRequest b = new BookingRequest();
        b.setId(UUID.randomUUID());
        b.setTenantId(tenantId);
        b.setPropertyId(propertyId);
        b.setUnitId(unit.getId());
        b.setRenterUserId(renterUserId);
        b.setResourceType(type);
        if (type == BookingResourceType.AMENITY) b.setAmenityId(UUID.randomUUID());
        else b.setParkingSpotId(UUID.randomUUID());
        b.setStatus(BookingRequestStatus.PENDING);
        return b;
    }

    @Test
    void createBooking_unitNotOnCallerActiveLease_throwsNotFound() {
        when(renterRepository.findByUserId(renterUserId)).thenReturn(Optional.of(renter));
        when(leaseRepository.findByUnitIdAndStatus(unit.getId(), LeaseStatus.ACTIVE))
                .thenReturn(List.of());

        assertThatThrownBy(() -> controller.create(new BookingCreateRequest(
                BookingResourceType.AMENITY, UUID.randomUUID(), unit.getId(), null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void createBooking_activeLease_delegatesAndReturns201() {
        when(renterRepository.findByUserId(renterUserId)).thenReturn(Optional.of(renter));
        when(leaseRepository.findByUnitIdAndStatus(unit.getId(), LeaseStatus.ACTIVE))
                .thenReturn(List.of(activeLease()));
        BookingRequest saved = booking(BookingResourceType.AMENITY);
        when(bookingService.create(eq(tenantId), eq(renterUserId), eq(unit), any())).thenReturn(saved);
        when(amenityRepository.findAllById(List.of(saved.getAmenityId()))).thenReturn(List.of());

        ResponseEntity<BookingRequestDTO> response = controller.create(new BookingCreateRequest(
                BookingResourceType.AMENITY, saved.getAmenityId(), unit.getId(), null, null));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().unitNumber()).isEqualTo("1204");
    }

    @Test
    void release_asRenter_callsServiceWithActorIsAdminFalse() {
        BookingRequest released = booking(BookingResourceType.PARKING_SPOT);
        released.setStatus(BookingRequestStatus.RELEASED);
        when(bookingService.release(tenantId, released.getId(), renterUserId, false)).thenReturn(released);
        when(parkingSpotRepository.findAllById(List.of(released.getParkingSpotId()))).thenReturn(List.of());

        ResponseEntity<BookingRequestDTO> response = controller.release(released.getId());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().status()).isEqualTo(BookingRequestStatus.RELEASED);
    }

    @Test
    void release_asAdmin_callsServiceWithActorIsAdminTrue() {
        UUID adminId = UUID.randomUUID();
        authenticateAs(adminId, "ROLE_TENANT_ADMIN");
        BookingRequest approved = booking(BookingResourceType.PARKING_SPOT);
        approved.setStatus(BookingRequestStatus.APPROVED);
        when(bookingService.get(tenantId, approved.getId())).thenReturn(approved);
        BookingRequest released = booking(BookingResourceType.PARKING_SPOT);
        released.setStatus(BookingRequestStatus.RELEASED);
        when(bookingService.release(tenantId, approved.getId(), adminId, true)).thenReturn(released);
        when(parkingSpotRepository.findAllById(List.of(released.getParkingSpotId()))).thenReturn(List.of());

        assertThat(controller.release(approved.getId()).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void adminGet_pmWithoutAssignment_throwsAccessDenied() {
        UUID pmId = UUID.randomUUID();
        authenticateAs(pmId, "ROLE_PROPERTY_MANAGER");
        BookingRequest b = booking(BookingResourceType.AMENITY);
        when(bookingService.get(tenantId, b.getId())).thenReturn(b);
        when(assignmentRepository.existsByUserIdAndPropertyId(pmId, propertyId)).thenReturn(false);

        assertThatThrownBy(() -> controller.get(b.getId()))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void myFacilities_mapsCountsHeldAndPropertyName() {
        when(renterRepository.findByUserId(renterUserId)).thenReturn(Optional.of(renter));
        Lease lease = activeLease();
        when(leaseRepository.findByRenterId(renter.getId())).thenReturn(List.of(lease));
        PropertyAmenity a = new PropertyAmenity();
        a.setId(UUID.randomUUID());
        a.setPropertyId(propertyId);
        a.setNameEn("Gym");
        a.setBookable(true);
        ParkingSpot s = new ParkingSpot();
        s.setId(UUID.randomUUID());
        s.setPropertyId(propertyId);
        s.setSpotNumber("B1-07");
        s.setCovered(true);
        when(facilityService.visibleFacilities(tenantId, unit))
                .thenReturn(new FacilityService.VisibleFacilities(List.of(a), List.of(s)));
        when(bookingService.countPendingForAmenity(a.getId())).thenReturn(3L);
        when(bookingService.countPendingForSpot(s.getId())).thenReturn(1L);
        when(bookingService.spotHeld(s.getId())).thenReturn(true);

        ResponseEntity<MyFacilitiesDTO> response = controller.myFacilities();

        MyFacilitiesDTO body = response.getBody();
        assertThat(body.amenities()).hasSize(1);
        assertThat(body.amenities().getFirst().propertyName()).isEqualTo("Marina Heights");
        assertThat(body.amenities().getFirst().pendingCount()).isEqualTo(3L);
        assertThat(body.parkingSpots().getFirst().held()).isTrue();
    }

    @Test
    void cancel_delegatesWithCurrentUserId() {
        BookingRequest cancelled = booking(BookingResourceType.AMENITY);
        cancelled.setStatus(BookingRequestStatus.CANCELLED);
        when(bookingService.cancel(tenantId, cancelled.getId(), renterUserId)).thenReturn(cancelled);
        when(amenityRepository.findAllById(List.of(cancelled.getAmenityId()))).thenReturn(List.of());

        assertThat(controller.cancel(cancelled.getId()).getBody().status())
                .isEqualTo(BookingRequestStatus.CANCELLED);
    }
}
```

- [ ] **Step 2: Run — expect compile failure**

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew test --tests "com.datagami.rentaxis.api.BookingControllerTest"
```

Expected: `BUILD FAILED`, `cannot find symbol ... class BookingController`.

- [ ] **Step 3: Implement `BookingController.java`**

```java
package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.BookingCreateRequest;
import com.datagami.rentaxis.api.dto.BookingDetailDTO;
import com.datagami.rentaxis.api.dto.BookingRequestDTO;
import com.datagami.rentaxis.api.dto.DecisionRequest;
import com.datagami.rentaxis.api.dto.MyFacilitiesDTO;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.BookingService;
import com.datagami.rentaxis.core.service.FacilityService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BookingRequest;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.ParkingSpot;
import com.datagami.rentaxis.domain.entity.PropertyAmenity;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.BookingRequestStatus;
import com.datagami.rentaxis.domain.entity.enums.BookingResourceType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.ParkingSpotRepository;
import com.datagami.rentaxis.domain.repository.PropertyAmenityRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Booking lifecycle HTTP surface, admin and renter sides. RBAC lives here —
 * {@code BookingService} implements none of it (gate-pass split). Two checks
 * exist nowhere else: {@link #requireUnitOnActiveLease} (a renter may only
 * book against a unit on their own ACTIVE lease — the service trusts the unit
 * completely), and the {@link #release} role branch (renter path passes
 * actorIsAdmin=false so the service enforces the owner rule). Renters never
 * see other applicants — otherRequests appears only in the admin detail.
 */
@RestController
@RequestMapping("/api/v1")
public class BookingController {

    private static final String RENTER_AUTHORITY = "ROLE_RENTER";

    private final BookingService bookingService;
    private final FacilityService facilityService;
    private final RenterRepository renterRepository;
    private final LeaseRepository leaseRepository;
    private final UnitRepository unitRepository;
    private final UserRepository userRepository;
    private final UserPropertyAssignmentRepository assignmentRepository;
    private final PropertyAmenityRepository amenityRepository;
    private final ParkingSpotRepository parkingSpotRepository;

    public BookingController(BookingService bookingService,
                             FacilityService facilityService,
                             RenterRepository renterRepository,
                             LeaseRepository leaseRepository,
                             UnitRepository unitRepository,
                             UserRepository userRepository,
                             UserPropertyAssignmentRepository assignmentRepository,
                             PropertyAmenityRepository amenityRepository,
                             ParkingSpotRepository parkingSpotRepository) {
        this.bookingService = bookingService;
        this.facilityService = facilityService;
        this.renterRepository = renterRepository;
        this.leaseRepository = leaseRepository;
        this.unitRepository = unitRepository;
        this.userRepository = userRepository;
        this.assignmentRepository = assignmentRepository;
        this.amenityRepository = amenityRepository;
        this.parkingSpotRepository = parkingSpotRepository;
    }

    // ----------------------------------------------------------------- admin

    @GetMapping("/bookings")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    public ResponseEntity<Page<BookingRequestDTO>> list(
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) BookingRequestStatus status,
            @RequestParam(required = false) BookingResourceType resourceType,
            @PageableDefault(sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        checkPropertyManagerAccess(propertyId);
        Page<BookingRequest> page = bookingService.search(tenantId(), propertyId, status, resourceType, pageable);
        Map<UUID, String> unitNumbers = unitNumbers(page.getContent());
        Map<UUID, User> renters = renterUsers(page.getContent());
        Map<UUID, String> resourceNames = resourceNames(page.getContent());
        return ResponseEntity.ok(page.map(b -> toDTO(b, unitNumbers, renters, resourceNames)));
    }

    @GetMapping("/bookings/{id}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    public ResponseEntity<BookingDetailDTO> get(@PathVariable UUID id) {
        BookingRequest booking = bookingService.get(tenantId(), id);
        checkPropertyManagerAccess(booking.getPropertyId());
        return ResponseEntity.ok(new BookingDetailDTO(
                toDTO(booking), toDTOs(bookingService.otherRequests(booking))));
    }

    @PostMapping("/bookings/{id}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    public ResponseEntity<BookingRequestDTO> approve(@PathVariable UUID id,
                                                     @RequestBody(required = false) DecisionRequest body) {
        UUID tenantId = tenantId();
        checkPropertyManagerAccess(bookingService.get(tenantId, id).getPropertyId());
        return ResponseEntity.ok(toDTO(bookingService.approve(
                tenantId, id, currentUserId(), body == null ? null : body.adminNote())));
    }

    @PostMapping("/bookings/{id}/reject")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')")
    public ResponseEntity<BookingRequestDTO> reject(@PathVariable UUID id,
                                                    @RequestBody(required = false) DecisionRequest body) {
        UUID tenantId = tenantId();
        checkPropertyManagerAccess(bookingService.get(tenantId, id).getPropertyId());
        return ResponseEntity.ok(toDTO(bookingService.reject(
                tenantId, id, currentUserId(), body == null ? null : body.adminNote())));
    }

    /**
     * Shared admin/renter endpoint, branching on role like GatePassController.isGuard():
     * renters go through the owner-enforcing path, admins (after the PM assignment
     * check) may release any approved parking booking in the tenant.
     */
    @PostMapping("/bookings/{id}/release")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER','RENTER')")
    public ResponseEntity<BookingRequestDTO> release(@PathVariable UUID id) {
        UUID tenantId = tenantId();
        if (isRenter()) {
            return ResponseEntity.ok(toDTO(bookingService.release(tenantId, id, currentUserId(), false)));
        }
        BookingRequest booking = bookingService.get(tenantId, id);
        checkPropertyManagerAccess(booking.getPropertyId());
        return ResponseEntity.ok(toDTO(bookingService.release(tenantId, id, currentUserId(), true)));
    }

    // ---------------------------------------------------------------- renter

    /**
     * Facilities visible to the caller's active-lease units. Renters get
     * pendingCount only — never other applicants' identities.
     */
    @GetMapping("/facilities/my")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<MyFacilitiesDTO> myFacilities() {
        UUID tenantId = tenantId();
        UUID userId = currentUserId();
        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No renter profile linked to this user"));
        if (!tenantId.equals(renter.getTenantId())) {
            throw new NotFoundException("No renter profile linked to this user");
        }
        List<Unit> units = leaseRepository.findByRenterId(renter.getId()).stream()
                .filter(l -> l.getStatus() == LeaseStatus.ACTIVE)
                .filter(l -> tenantId.equals(l.getTenantId()))
                .map(Lease::getUnit)
                .toList();

        Map<UUID, MyFacilitiesDTO.RenterAmenityDTO> amenities = new LinkedHashMap<>();
        Map<UUID, MyFacilitiesDTO.RenterParkingSpotDTO> spots = new LinkedHashMap<>();
        for (Unit unit : units) {
            FacilityService.VisibleFacilities visible = facilityService.visibleFacilities(tenantId, unit);
            String propertyName = unit.getProperty().getNameEn();
            for (PropertyAmenity a : visible.amenities()) {
                amenities.putIfAbsent(a.getId(), new MyFacilitiesDTO.RenterAmenityDTO(
                        a.getId(), a.getPropertyId(), propertyName, a.getNameEn(), a.getNameAr(),
                        a.getDescription(), a.isBookable(),
                        bookingService.countPendingForAmenity(a.getId())));
            }
            for (ParkingSpot s : visible.parkingSpots()) {
                spots.putIfAbsent(s.getId(), new MyFacilitiesDTO.RenterParkingSpotDTO(
                        s.getId(), s.getPropertyId(), propertyName, s.getSpotNumber(), s.getLevel(),
                        s.isCovered(), bookingService.spotHeld(s.getId()),
                        bookingService.countPendingForSpot(s.getId())));
            }
        }
        return ResponseEntity.ok(new MyFacilitiesDTO(
                List.copyOf(amenities.values()), List.copyOf(spots.values())));
    }

    @PostMapping("/bookings")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<BookingRequestDTO> create(@RequestBody BookingCreateRequest req) {
        UUID tenantId = tenantId();
        UUID userId = currentUserId();
        Unit unit = requireUnitOnActiveLease(tenantId, userId, req.unitId());
        BookingRequest booking = bookingService.create(tenantId, userId, unit, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(toDTO(booking));
    }

    @GetMapping("/bookings/my")
    @PreAuthorize("hasRole('RENTER')")
    public List<BookingRequestDTO> mine() {
        return toDTOs(bookingService.listMine(tenantId(), currentUserId()));
    }

    @PostMapping("/bookings/{id}/cancel")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<BookingRequestDTO> cancel(@PathVariable UUID id) {
        return ResponseEntity.ok(toDTO(bookingService.cancel(tenantId(), id, currentUserId())));
    }

    // -------------------------------------------------------------- helpers

    /**
     * The renter-side authorization check for POST /bookings: the caller must hold
     * an ACTIVE lease on the unit. 404 rather than 403 so a renter cannot use the
     * status code to discover unit ids (GatePassController.requireUnitOnActiveLease).
     */
    private Unit requireUnitOnActiveLease(UUID tenantId, UUID userId, UUID unitId) {
        if (unitId == null) {
            throw new BusinessRuleViolationException("unitId is required");
        }
        Renter renter = renterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("No renter profile linked to this user"));
        if (!tenantId.equals(renter.getTenantId())) {
            throw new NotFoundException("No renter profile linked to this user");
        }
        Lease lease = leaseRepository.findByUnitIdAndStatus(unitId, LeaseStatus.ACTIVE).stream()
                .filter(l -> tenantId.equals(l.getTenantId()))
                .filter(l -> l.getRenter().getId().equals(renter.getId()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Unit is not on an active lease of yours"));
        return lease.getUnit();
    }

    /** UnitListingController.checkPropertyManagerAccess pattern, keyed on propertyId. */
    private void checkPropertyManagerAccess(UUID propertyId) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean isPm = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_PROPERTY_MANAGER"));
        if (!isPm) return;

        if (propertyId == null) {
            throw new AccessDeniedException("propertyId is required for property managers");
        }
        UUID userId = UUID.fromString(auth.getName());
        if (!assignmentRepository.existsByUserIdAndPropertyId(userId, propertyId)) {
            throw new AccessDeniedException("You are not assigned to this property");
        }
    }

    private boolean isRenter() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(RENTER_AUTHORITY::equals);
    }

    private UUID currentUserId() {
        return UUID.fromString(SecurityContextHolder.getContext().getAuthentication().getName());
    }

    private UUID tenantId() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            throw new BusinessRuleViolationException("No tenant context on this request");
        }
        return tenantId;
    }

    // ---- mapping: batched joins, GatePassController.toSummaries style ----

    private List<BookingRequestDTO> toDTOs(List<BookingRequest> bookings) {
        Map<UUID, String> unitNumbers = unitNumbers(bookings);
        Map<UUID, User> renters = renterUsers(bookings);
        Map<UUID, String> resourceNames = resourceNames(bookings);
        return bookings.stream().map(b -> toDTO(b, unitNumbers, renters, resourceNames)).toList();
    }

    private BookingRequestDTO toDTO(BookingRequest b) {
        return toDTOs(List.of(b)).getFirst();
    }

    private BookingRequestDTO toDTO(BookingRequest b, Map<UUID, String> unitNumbers,
                                    Map<UUID, User> renters, Map<UUID, String> resourceNames) {
        User renter = renters.get(b.getRenterUserId());
        UUID resourceId = b.getResourceType() == BookingResourceType.AMENITY
                ? b.getAmenityId() : b.getParkingSpotId();
        return new BookingRequestDTO(b.getId(), b.getResourceType(), b.getAmenityId(),
                b.getParkingSpotId(), resourceNames.get(resourceId), b.getPropertyId(),
                b.getUnitId(), unitNumbers.get(b.getUnitId()), b.getRenterUserId(),
                renter == null ? null : renter.getName(),
                renter == null ? null : renter.getEmail(),
                renter == null ? null : renter.getPhoneNumber(),
                b.getNote(), b.getPreferredDate(), b.getStatus(), b.getAdminNote(),
                b.getDecidedByUserId(), b.getDecidedAt(), b.getCreatedAt());
    }

    private Map<UUID, String> unitNumbers(List<BookingRequest> bookings) {
        List<UUID> distinct = bookings.stream().map(BookingRequest::getUnitId).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> byId = new HashMap<>();
        for (Unit unit : unitRepository.findAllById(distinct)) {
            byId.put(unit.getId(), unit.getUnitNumber());
        }
        return byId;
    }

    /** Tenant-scoped in SQL, matching GatePassController.guardNames. */
    private Map<UUID, User> renterUsers(List<BookingRequest> bookings) {
        List<UUID> distinct = bookings.stream().map(BookingRequest::getRenterUserId).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<UUID, User> byId = new HashMap<>();
        for (User user : userRepository.findByTenantIdAndIdIn(tenantId(), distinct)) {
            byId.put(user.getId(), user);
        }
        return byId;
    }

    private Map<UUID, String> resourceNames(List<BookingRequest> bookings) {
        List<UUID> amenityIds = bookings.stream()
                .filter(b -> b.getAmenityId() != null)
                .map(BookingRequest::getAmenityId).distinct().toList();
        List<UUID> spotIds = bookings.stream()
                .filter(b -> b.getParkingSpotId() != null)
                .map(BookingRequest::getParkingSpotId).distinct().toList();
        Map<UUID, String> names = new HashMap<>();
        if (!amenityIds.isEmpty()) {
            for (PropertyAmenity a : amenityRepository.findAllById(amenityIds)) {
                names.put(a.getId(), a.getNameEn());
            }
        }
        if (!spotIds.isEmpty()) {
            for (ParkingSpot s : parkingSpotRepository.findAllById(spotIds)) {
                names.put(s.getId(), s.getSpotNumber());
            }
        }
        return names;
    }
}
```

- [ ] **Step 4: Run — expect pass**

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew test --tests "com.datagami.rentaxis.api.BookingControllerTest"
```

Expected: `BUILD SUCCESSFUL`, 7 tests passing.

- [ ] **Step 5: Run the whole new-feature test set plus a full compile as regression gate**

```bash
cd /Users/kunalsharma/datagami/rentaxis/backend && ./gradlew test --tests "com.datagami.rentaxis.core.service.FacilityServiceTest" --tests "com.datagami.rentaxis.core.service.BookingServiceTest" --tests "com.datagami.rentaxis.core.service.BookingNotificationServiceTest" --tests "com.datagami.rentaxis.api.AmenityControllerTest" --tests "com.datagami.rentaxis.api.ParkingSpotControllerTest" --tests "com.datagami.rentaxis.api.BookingControllerTest"
```

Expected: `BUILD SUCCESSFUL`, 51 tests passing.

- [ ] **Step 6: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add backend/src/main/java/com/datagami/rentaxis/api/BookingController.java backend/src/test/java/com/datagami/rentaxis/api/BookingControllerTest.java && git commit -m "feat(facilities): booking controller and renter facilities endpoint"
```


---

## Web (Next.js) — Tasks W1–W6

Grounding notes for every task in this section (verified against the repo):

- The Next.js middleware (`web/src/proxy.ts`) authenticates every `/api/proxy/*` request from the NextAuth session cookie and injects `X-User-Id` / `X-User-Role` / `X-Tenant-Id`, and `next.config.ts` rewrites `/api/proxy/:path*` → `${BACKEND_URL}/api/:path*`. Therefore all helpers below call `/api/proxy/v1/...` with **no** Authorization header — the same pattern as `dashboard/properties/[id]/page.tsx`.
- `npx tsc --noEmit` was run from `web/` and exits 0 on the current tree — it is the verification gate for every task.
- `messages/en.json` ends with the `GatePass` namespace closing at `    }\n}` (same for `ar.json`) — the i18n edit hunks anchor there.
- `PageResponse<T>` already exists in `web/src/types/listing.ts` (`{content, totalElements, totalPages, number, size}`) and is reused, not redefined.
- The shared `Pagination` component (`web/src/components/ui/Pagination.tsx`) is 1-based (`currentPage`, `totalItems`, `itemsPerPage`, `onPageChange`); pages below convert from Spring's 0-based `number`.
- `GET /api/proxy/v1/properties` returns `Array<{ property: {id, nameEn, nameAr, ...}, ... }>` (verified in `dashboard/properties/page.tsx` — `s.property.nameEn`).
- `ConfirmDialog` (`web/src/components/ui/confirm-dialog.tsx`) props: `isOpen, onClose, onConfirm, title, description?, confirmText?, cancelText?, isDestructive?, isLoading?`.
- Backend returns all lists sorted `createdAt` ASC (project standard) — no client-side sorting.

---

### Task W1: TS types + typed API helpers

**Files:**
- Create: `web/src/types/facility.ts`
- Create: `web/src/lib/api/facilities.ts`
- Test: none (no web test framework used here; gate is `npx tsc --noEmit`)

- [ ] **Step 1: Create `web/src/types/facility.ts` mirroring the canonical API contract exactly**

Full file content:

```ts
// Types for the amenities & parking booking feature.
// Field names mirror the backend DTO records exactly — do not rename.

export type BookingResourceType = 'AMENITY' | 'PARKING_SPOT'
export type BookingRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'RELEASED'

export interface AmenityDTO {
  id: string
  propertyId: string
  nameEn: string
  nameAr: string | null
  description: string | null
  bookable: boolean
  active: boolean
  buildingIds: string[]
  pendingCount: number
  createdAt: string
  updatedAt: string
}

export interface ParkingSpotDTO {
  id: string
  propertyId: string
  spotNumber: string
  level: string | null
  covered: boolean
  active: boolean
  buildingIds: string[]
  held: boolean
  pendingCount: number
  createdAt: string
  updatedAt: string
}

export interface BookingRequestDTO {
  id: string
  resourceType: BookingResourceType
  amenityId: string | null
  parkingSpotId: string | null
  resourceName: string
  propertyId: string
  unitId: string
  unitNumber: string | null
  renterUserId: string
  renterName: string | null
  renterEmail: string | null
  renterPhone: string | null
  note: string | null
  preferredDate: string | null
  status: BookingRequestStatus
  adminNote: string | null
  decidedByUserId: string | null
  decidedAt: string | null
  createdAt: string
}

export interface BookingDetailDTO {
  request: BookingRequestDTO
  /** PENDING + APPROVED requests for the same resource, excluding this one. */
  otherRequests: BookingRequestDTO[]
}

export interface RenterAmenityDTO {
  id: string
  propertyId: string
  propertyName: string | null
  nameEn: string
  nameAr: string | null
  description: string | null
  bookable: boolean
  pendingCount: number
}

export interface RenterParkingSpotDTO {
  id: string
  propertyId: string
  propertyName: string | null
  spotNumber: string
  level: string | null
  covered: boolean
  held: boolean
  pendingCount: number
}

export interface MyFacilitiesDTO {
  amenities: RenterAmenityDTO[]
  parkingSpots: RenterParkingSpotDTO[]
}

export interface AmenityCreateRequest {
  propertyId: string
  nameEn: string
  nameAr?: string
  description?: string
  bookable?: boolean
  buildingIds?: string[]
}

/** null/omitted = unchanged; a non-null buildingIds replaces the scope set. */
export interface AmenityUpdateRequest {
  nameEn?: string
  nameAr?: string
  description?: string
  bookable?: boolean
  active?: boolean
  buildingIds?: string[]
}

export interface ParkingSpotCreateRequest {
  propertyId: string
  spotNumber: string
  level?: string
  covered?: boolean
  buildingIds?: string[]
}

export interface ParkingSpotUpdateRequest {
  spotNumber?: string
  level?: string
  covered?: boolean
  active?: boolean
  buildingIds?: string[]
}

export interface ParkingSpotBulkCreateRequest {
  propertyId: string
  spotNumbers: string[]
  level?: string
  covered?: boolean
  buildingIds?: string[]
}

export interface BookingCreateRequest {
  resourceType: BookingResourceType
  resourceId: string
  unitId: string
  preferredDate?: string
  note?: string
}

export interface DecisionRequest {
  adminNote?: string
}
```

- [ ] **Step 2: Create `web/src/lib/api/facilities.ts` with typed helpers + `ApiError` + `parseSpotNumbers`**

Full file content:

```ts
import type { PageResponse } from '@/types/listing'
import type {
  AmenityDTO,
  AmenityCreateRequest,
  AmenityUpdateRequest,
  ParkingSpotDTO,
  ParkingSpotCreateRequest,
  ParkingSpotUpdateRequest,
  ParkingSpotBulkCreateRequest,
  BookingRequestDTO,
  BookingDetailDTO,
  BookingCreateRequest,
  DecisionRequest,
  MyFacilitiesDTO,
  BookingResourceType,
  BookingRequestStatus,
} from '@/types/facility'

// Auth is injected by the Next.js middleware for every /api/proxy/* request
// (see src/proxy.ts) — no Authorization header needed here.
const AMENITIES = '/api/proxy/v1/amenities'
const SPOTS = '/api/proxy/v1/parking-spots'
const BOOKINGS = '/api/proxy/v1/bookings'
const FACILITIES = '/api/proxy/v1/facilities'

const JSON_HEADERS = { 'Content-Type': 'application/json' }

/** Error carrying the HTTP status so callers can branch on 409 (spot held) / 400. */
export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
    this.name = 'ApiError'
  }
}

async function handle<T>(res: Response, action: string): Promise<T> {
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new ApiError(res.status, text || `${action} failed: ${res.status}`)
  }
  return res.json() as Promise<T>
}

async function handleVoid(res: Response, action: string): Promise<void> {
  if (!res.ok) {
    const text = await res.text().catch(() => '')
    throw new ApiError(res.status, text || `${action} failed: ${res.status}`)
  }
}

// ─── Amenities (admin) ───────────────────────────────────────────────────────

export async function fetchAmenities(
  propertyId: string,
  page = 0,
  size = 10
): Promise<PageResponse<AmenityDTO>> {
  const q = new URLSearchParams({ propertyId, page: String(page), size: String(size) })
  return handle(await fetch(`${AMENITIES}?${q}`), 'fetchAmenities')
}

export async function createAmenity(body: AmenityCreateRequest): Promise<AmenityDTO> {
  return handle(
    await fetch(AMENITIES, { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }),
    'createAmenity'
  )
}

export async function updateAmenity(id: string, body: AmenityUpdateRequest): Promise<AmenityDTO> {
  return handle(
    await fetch(`${AMENITIES}/${id}`, { method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body) }),
    'updateAmenity'
  )
}

export async function deactivateAmenity(id: string): Promise<void> {
  return handleVoid(await fetch(`${AMENITIES}/${id}`, { method: 'DELETE' }), 'deactivateAmenity')
}

// ─── Parking spots (admin) ───────────────────────────────────────────────────

export async function fetchParkingSpots(
  propertyId: string,
  page = 0,
  size = 10
): Promise<PageResponse<ParkingSpotDTO>> {
  const q = new URLSearchParams({ propertyId, page: String(page), size: String(size) })
  return handle(await fetch(`${SPOTS}?${q}`), 'fetchParkingSpots')
}

export async function createParkingSpot(body: ParkingSpotCreateRequest): Promise<ParkingSpotDTO> {
  return handle(
    await fetch(SPOTS, { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }),
    'createParkingSpot'
  )
}

export async function bulkCreateParkingSpots(
  body: ParkingSpotBulkCreateRequest
): Promise<ParkingSpotDTO[]> {
  return handle(
    await fetch(`${SPOTS}/bulk`, { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }),
    'bulkCreateParkingSpots'
  )
}

export async function updateParkingSpot(
  id: string,
  body: ParkingSpotUpdateRequest
): Promise<ParkingSpotDTO> {
  return handle(
    await fetch(`${SPOTS}/${id}`, { method: 'PUT', headers: JSON_HEADERS, body: JSON.stringify(body) }),
    'updateParkingSpot'
  )
}

export async function deactivateParkingSpot(id: string): Promise<void> {
  return handleVoid(await fetch(`${SPOTS}/${id}`, { method: 'DELETE' }), 'deactivateParkingSpot')
}

// ─── Bookings (admin inbox) ──────────────────────────────────────────────────

export interface BookingFilters {
  propertyId?: string
  status?: BookingRequestStatus
  resourceType?: BookingResourceType
  page?: number
  size?: number
}

export async function fetchBookings(
  filters: BookingFilters
): Promise<PageResponse<BookingRequestDTO>> {
  const q = new URLSearchParams()
  if (filters.propertyId) q.set('propertyId', filters.propertyId)
  if (filters.status) q.set('status', filters.status)
  if (filters.resourceType) q.set('resourceType', filters.resourceType)
  if (filters.page !== undefined) q.set('page', String(filters.page))
  if (filters.size !== undefined) q.set('size', String(filters.size))
  return handle(await fetch(`${BOOKINGS}?${q}`), 'fetchBookings')
}

export async function fetchBooking(id: string): Promise<BookingDetailDTO> {
  return handle(await fetch(`${BOOKINGS}/${id}`), 'fetchBooking')
}

/** Throws ApiError with status 409 when the parking spot is already APPROVED elsewhere. */
export async function approveBooking(id: string, body: DecisionRequest): Promise<BookingRequestDTO> {
  return handle(
    await fetch(`${BOOKINGS}/${id}/approve`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(body),
    }),
    'approveBooking'
  )
}

export async function rejectBooking(id: string, body: DecisionRequest): Promise<void> {
  return handleVoid(
    await fetch(`${BOOKINGS}/${id}/reject`, {
      method: 'POST',
      headers: JSON_HEADERS,
      body: JSON.stringify(body),
    }),
    'rejectBooking'
  )
}

/** Admin OR the owning renter; APPROVED parking only. */
export async function releaseBooking(id: string): Promise<void> {
  return handleVoid(await fetch(`${BOOKINGS}/${id}/release`, { method: 'POST' }), 'releaseBooking')
}

// ─── Renter side ─────────────────────────────────────────────────────────────

export async function fetchMyFacilities(): Promise<MyFacilitiesDTO> {
  return handle(await fetch(`${FACILITIES}/my`), 'fetchMyFacilities')
}

/** Idempotent: an existing PENDING request by the caller for the same resource is returned. */
export async function createBooking(body: BookingCreateRequest): Promise<BookingRequestDTO> {
  return handle(
    await fetch(BOOKINGS, { method: 'POST', headers: JSON_HEADERS, body: JSON.stringify(body) }),
    'createBooking'
  )
}

export async function fetchMyBookings(): Promise<BookingRequestDTO[]> {
  return handle(await fetch(`${BOOKINGS}/my`), 'fetchMyBookings')
}

export async function cancelBooking(id: string): Promise<void> {
  return handleVoid(await fetch(`${BOOKINGS}/${id}/cancel`, { method: 'POST' }), 'cancelBooking')
}

// ─── Bulk spot-number entry parser ───────────────────────────────────────────

/**
 * Parses comma-separated spot numbers with numeric range expansion.
 *   "B1-05, B1-06"  -> ["B1-05", "B1-06"]        (prefixes differ around the dash → literal)
 *   "P10-P20"       -> ["P10", "P11", ..., "P20"] (same prefix both sides → expanded)
 *   "10-12"         -> ["10", "11", "12"]
 * Zero-padding of the start bound is preserved ("P08-P10" -> P08, P09, P10).
 * Ranges longer than 500 entries are kept literal to guard against typos.
 * Duplicates are removed; order of first appearance is kept.
 */
export function parseSpotNumbers(input: string): string[] {
  const out: string[] = []
  for (const raw of input.split(',')) {
    const entry = raw.trim()
    if (!entry) continue
    const m = entry.match(/^(.*?)(\d+)\s*-\s*(.*?)(\d+)$/)
    if (m && m[1] === m[3]) {
      const prefix = m[1]
      const start = parseInt(m[2], 10)
      const end = parseInt(m[4], 10)
      const width = m[2].length
      if (end >= start && end - start <= 500) {
        for (let n = start; n <= end; n++) {
          out.push(`${prefix}${String(n).padStart(width, '0')}`)
        }
        continue
      }
    }
    out.push(entry)
  }
  return Array.from(new Set(out))
}
```

- [ ] **Step 3: Verify**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && npx tsc --noEmit
```
Expected output: nothing printed, exit code 0.

Manual checks:
- `grep -c "export async function" src/lib/api/facilities.ts` → `16` (4 amenity + 5 spot + 5 admin booking + ... run it; expected `15` functions: fetchAmenities, createAmenity, updateAmenity, deactivateAmenity, fetchParkingSpots, createParkingSpot, bulkCreateParkingSpots, updateParkingSpot, deactivateParkingSpot, fetchBookings, fetchBooking, approveBooking, rejectBooking, releaseBooking, fetchMyFacilities, createBooking, fetchMyBookings, cancelBooking — expected count **18**).
- Confirm no field in `facility.ts` deviates from the contract: `grep -n "resourceName\|renterUserId\|pendingCount\|spotNumbers" src/types/facility.ts` shows all four.

- [ ] **Step 4: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add web/src/types/facility.ts web/src/lib/api/facilities.ts && git commit -m "feat(web): facility types and typed API helpers for amenities, parking, bookings" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
Expected output: `[<branch> <hash>] feat(web): facility types and typed API helpers...` with `2 files changed`.

---

### Task W2: i18n — Facilities + Bookings namespaces (EN + AR)

**Files:**
- Modify: `web/messages/en.json` (append after the `GatePass` namespace at end of file)
- Modify: `web/messages/ar.json` (same position)

- [ ] **Step 1: Append the two namespaces to `web/messages/en.json`**

Edit hunk — replace this exact text at the end of the file:

```json
      "SINGLE_USE": "Single use",
      "RECURRING": "Recurring"
    }
}
```

with:

```json
      "SINGLE_USE": "Single use",
      "RECURRING": "Recurring"
    },
  "Facilities": {
    "amenitiesTab": "Amenities",
    "parkingTab": "Parking",
    "addAmenity": "Add Amenity",
    "editAmenity": "Edit Amenity",
    "addSpot": "Add Spot",
    "editSpot": "Edit Spot",
    "bulkAddSpots": "Bulk Add Spots",
    "nameEn": "Name (EN)",
    "nameAr": "Name (AR)",
    "description": "Description",
    "bookable": "Bookable",
    "notBookable": "Not bookable",
    "active": "Active",
    "inactive": "Inactive",
    "towers": "Towers",
    "allTowers": "All towers",
    "towersHint": "Leave all unchecked to make it available to every tower.",
    "spotNumber": "Spot Number",
    "spotNumbers": "Spot Numbers",
    "bulkHint": "Comma-separated; ranges expand (e.g. P10-P20 becomes P10, P11 ... P20).",
    "bulkPreview": "{count} spots will be created",
    "level": "Level",
    "levelPlaceholder": "e.g. B1",
    "covered": "Covered",
    "uncovered": "Uncovered",
    "held": "Held",
    "available": "Available",
    "deactivate": "Deactivate",
    "deactivateAmenityTitle": "Deactivate Amenity",
    "deactivateSpotTitle": "Deactivate Parking Spot",
    "deactivateConfirm": "Renters will no longer see it. Existing requests are not affected.",
    "save": "Save",
    "saving": "Saving...",
    "cancel": "Cancel",
    "loading": "Loading...",
    "loadError": "Failed to load. Please try again.",
    "saveError": "Failed to save. Please try again.",
    "noAmenities": "No amenities yet.",
    "noSpots": "No parking spots yet.",
    "colName": "Name",
    "colTowers": "Towers",
    "colBookable": "Bookable",
    "colStatus": "Status",
    "colPending": "Pending",
    "colCreated": "Created",
    "colSpot": "Spot",
    "colLevel": "Level",
    "colCovered": "Covered",
    "colHeld": "Held",
    "yes": "Yes",
    "no": "No",
    "renterTitle": "Facilities & Parking",
    "renterSubtitle": "Amenities and parking available to your unit.",
    "entryCardTitle": "Facilities & Parking",
    "entryCardDesc": "Request amenities and parking for your unit",
    "backToPortal": "Back to My Leases",
    "amenitiesSection": "Amenities",
    "parkingSection": "Parking",
    "request": "Request",
    "requestTitle": "Request {name}",
    "unit": "Unit",
    "preferredDate": "Preferred date (optional)",
    "note": "Note (optional)",
    "notePlaceholder": "Anything the manager should know",
    "submitRequest": "Submit Request",
    "submitting": "Submitting...",
    "requestError": "Could not submit the request. Please try again.",
    "spotTaken": "This spot is already assigned to another renter.",
    "pendingHint": "{count, plural, =0 {No pending requests} one {# pending request} other {# pending requests}}",
    "myRequests": "My Requests",
    "noRequests": "No requests yet.",
    "cancelRequest": "Cancel Request",
    "cancelRequestConfirm": "Withdraw this pending request?",
    "releaseSpot": "Release Spot",
    "releaseSpotConfirm": "Give up this parking spot? It becomes available to others.",
    "noFacilities": "No facilities are available for your unit yet.",
    "noActiveLease": "You need an active lease to request facilities."
  },
  "Bookings": {
    "navLabel": "Bookings",
    "title": "Booking Requests",
    "subtitle": "Review amenity and parking requests from renters.",
    "noAccess": "You do not have access to booking requests.",
    "allProperties": "All properties",
    "allStatuses": "All statuses",
    "allTypes": "All types",
    "typeAMENITY": "Amenity",
    "typePARKING_SPOT": "Parking",
    "statusPENDING": "Pending",
    "statusAPPROVED": "Approved",
    "statusREJECTED": "Rejected",
    "statusCANCELLED": "Cancelled",
    "statusRELEASED": "Released",
    "colResource": "Resource",
    "colType": "Type",
    "colRenter": "Renter",
    "colUnit": "Unit",
    "colPreferred": "Preferred Date",
    "colStatus": "Status",
    "colRequested": "Requested",
    "detailTitle": "Booking Request",
    "renterContact": "Renter details",
    "requestedOn": "Requested on",
    "preferredDate": "Preferred date",
    "renterNote": "Renter note",
    "adminNote": "Admin note",
    "adminNoteLabel": "Note to renter (optional)",
    "decidedAt": "Decided on",
    "otherRequests": "Other requests for this resource",
    "noOtherRequests": "No other pending or approved requests.",
    "approve": "Approve",
    "reject": "Reject",
    "release": "Release",
    "working": "Working...",
    "spotConflict": "This spot is already approved for another renter.",
    "actionError": "Action failed. Please try again.",
    "loading": "Loading...",
    "loadError": "Failed to load booking requests.",
    "noBookings": "No booking requests found.",
    "retry": "Retry"
  }
}
```

- [ ] **Step 2: Append the two namespaces to `web/messages/ar.json`**

Edit hunk — replace this exact text at the end of the file:

```json
      "SINGLE_USE": "استخدام واحد",
      "RECURRING": "متكرر"
    }
}
```

with:

```json
      "SINGLE_USE": "استخدام واحد",
      "RECURRING": "متكرر"
    },
  "Facilities": {
    "amenitiesTab": "المرافق",
    "parkingTab": "المواقف",
    "addAmenity": "إضافة مرفق",
    "editAmenity": "تعديل المرفق",
    "addSpot": "إضافة موقف",
    "editSpot": "تعديل الموقف",
    "bulkAddSpots": "إضافة مواقف متعددة",
    "nameEn": "الاسم (إنجليزي)",
    "nameAr": "الاسم (عربي)",
    "description": "الوصف",
    "bookable": "قابل للحجز",
    "notBookable": "غير قابل للحجز",
    "active": "نشط",
    "inactive": "غير نشط",
    "towers": "الأبراج",
    "allTowers": "جميع الأبراج",
    "towersHint": "اترك الكل بدون تحديد ليكون متاحًا لجميع الأبراج.",
    "spotNumber": "رقم الموقف",
    "spotNumbers": "أرقام المواقف",
    "bulkHint": "مفصولة بفواصل؛ يتم توسيع النطاقات (مثال: P10-P20 تصبح P10، P11 ... P20).",
    "bulkPreview": "سيتم إنشاء {count} من المواقف",
    "level": "الطابق",
    "levelPlaceholder": "مثال: B1",
    "covered": "مظلل",
    "uncovered": "مكشوف",
    "held": "محجوز",
    "available": "متاح",
    "deactivate": "إلغاء التفعيل",
    "deactivateAmenityTitle": "إلغاء تفعيل المرفق",
    "deactivateSpotTitle": "إلغاء تفعيل الموقف",
    "deactivateConfirm": "لن يظهر للمستأجرين بعد الآن. الطلبات الحالية لن تتأثر.",
    "save": "حفظ",
    "saving": "جارٍ الحفظ...",
    "cancel": "إلغاء",
    "loading": "جارٍ التحميل...",
    "loadError": "تعذر التحميل. حاول مرة أخرى.",
    "saveError": "تعذر الحفظ. حاول مرة أخرى.",
    "noAmenities": "لا توجد مرافق بعد.",
    "noSpots": "لا توجد مواقف بعد.",
    "colName": "الاسم",
    "colTowers": "الأبراج",
    "colBookable": "قابل للحجز",
    "colStatus": "الحالة",
    "colPending": "قيد الانتظار",
    "colCreated": "تاريخ الإنشاء",
    "colSpot": "الموقف",
    "colLevel": "الطابق",
    "colCovered": "مظلل",
    "colHeld": "الحجز",
    "yes": "نعم",
    "no": "لا",
    "renterTitle": "المرافق والمواقف",
    "renterSubtitle": "المرافق والمواقف المتاحة لوحدتك.",
    "entryCardTitle": "المرافق والمواقف",
    "entryCardDesc": "اطلب المرافق والمواقف لوحدتك",
    "backToPortal": "العودة إلى عقودي",
    "amenitiesSection": "المرافق",
    "parkingSection": "المواقف",
    "request": "طلب",
    "requestTitle": "طلب {name}",
    "unit": "الوحدة",
    "preferredDate": "التاريخ المفضل (اختياري)",
    "note": "ملاحظة (اختياري)",
    "notePlaceholder": "أي شيء يجب أن يعرفه المدير",
    "submitRequest": "إرسال الطلب",
    "submitting": "جارٍ الإرسال...",
    "requestError": "تعذر إرسال الطلب. حاول مرة أخرى.",
    "spotTaken": "هذا الموقف مخصص بالفعل لمستأجر آخر.",
    "pendingHint": "{count, plural, =0 {لا توجد طلبات قيد الانتظار} one {طلب واحد قيد الانتظار} two {طلبان قيد الانتظار} few {# طلبات قيد الانتظار} many {# طلبًا قيد الانتظار} other {# طلب قيد الانتظار}}",
    "myRequests": "طلباتي",
    "noRequests": "لا توجد طلبات بعد.",
    "cancelRequest": "إلغاء الطلب",
    "cancelRequestConfirm": "هل تريد سحب هذا الطلب المعلق؟",
    "releaseSpot": "إخلاء الموقف",
    "releaseSpotConfirm": "هل تريد التخلي عن هذا الموقف؟ سيصبح متاحًا للآخرين.",
    "noFacilities": "لا توجد مرافق متاحة لوحدتك بعد.",
    "noActiveLease": "تحتاج إلى عقد إيجار نشط لطلب المرافق."
  },
  "Bookings": {
    "navLabel": "الحجوزات",
    "title": "طلبات الحجز",
    "subtitle": "مراجعة طلبات المرافق والمواقف من المستأجرين.",
    "noAccess": "ليس لديك صلاحية الوصول إلى طلبات الحجز.",
    "allProperties": "كل العقارات",
    "allStatuses": "كل الحالات",
    "allTypes": "كل الأنواع",
    "typeAMENITY": "مرفق",
    "typePARKING_SPOT": "موقف",
    "statusPENDING": "قيد الانتظار",
    "statusAPPROVED": "مقبول",
    "statusREJECTED": "مرفوض",
    "statusCANCELLED": "ملغى",
    "statusRELEASED": "تم الإخلاء",
    "colResource": "العنصر",
    "colType": "النوع",
    "colRenter": "المستأجر",
    "colUnit": "الوحدة",
    "colPreferred": "التاريخ المفضل",
    "colStatus": "الحالة",
    "colRequested": "تاريخ الطلب",
    "detailTitle": "طلب الحجز",
    "renterContact": "بيانات المستأجر",
    "requestedOn": "تاريخ الطلب",
    "preferredDate": "التاريخ المفضل",
    "renterNote": "ملاحظة المستأجر",
    "adminNote": "ملاحظة الإدارة",
    "adminNoteLabel": "ملاحظة للمستأجر (اختياري)",
    "decidedAt": "تاريخ القرار",
    "otherRequests": "طلبات أخرى لهذا العنصر",
    "noOtherRequests": "لا توجد طلبات أخرى قيد الانتظار أو مقبولة.",
    "approve": "موافقة",
    "reject": "رفض",
    "release": "إخلاء",
    "working": "جارٍ التنفيذ...",
    "spotConflict": "هذا الموقف مقبول بالفعل لمستأجر آخر.",
    "actionError": "فشل الإجراء. حاول مرة أخرى.",
    "loading": "جارٍ التحميل...",
    "loadError": "تعذر تحميل طلبات الحجز.",
    "noBookings": "لا توجد طلبات حجز.",
    "retry": "إعادة المحاولة"
  }
}
```

- [ ] **Step 3: Verify JSON validity and EN/AR key parity**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && python3 -m json.tool messages/en.json > /dev/null && python3 -m json.tool messages/ar.json > /dev/null && node -e "const en=require('./messages/en.json'),ar=require('./messages/ar.json');for(const ns of['Facilities','Bookings']){const a=Object.keys(en[ns]).sort().join(),b=Object.keys(ar[ns]).sort().join();if(a!==b){console.error(ns+' keys differ');process.exit(1)}};console.log('OK')" && npx tsc --noEmit
```
Expected output: `OK`, then nothing from tsc, exit 0.

- [ ] **Step 4: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add web/messages/en.json web/messages/ar.json && git commit -m "feat(web): Facilities and Bookings i18n namespaces (EN/AR)" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
Expected output: `2 files changed`.

---

### Task W3: Property detail — Amenities tab

**Files:**
- Create: `web/src/app/[locale]/dashboard/properties/[id]/_components/AmenitiesTab.tsx`
- Modify: `web/src/lib/rbac.ts` (PERMISSIONS map, after `canViewGatePassReport` at line 31)
- Modify: `web/src/app/[locale]/dashboard/properties/[id]/page.tsx` (imports line 7–11, hooks ~line 65, permissions ~line 72, tab union line 74, tabs array ~line 240, render ~line 477)

- [ ] **Step 1: Add the `canManageFacilities` permission to `web/src/lib/rbac.ts`**

Edit hunk — replace:

```ts
    canViewGatePassReport: ['TENANT_ADMIN', 'PROPERTY_MANAGER'] as UserRole[],
} as const;
```

with:

```ts
    canViewGatePassReport: ['TENANT_ADMIN', 'PROPERTY_MANAGER'] as UserRole[],
    // Mirrors the amenities/parking/bookings controllers' @PreAuthorize
    // hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER'). Unlike
    // canViewGatePassReport, SUPER_ADMIN is deliberately included here because
    // the backend admits it.
    canManageFacilities: ['SUPER_ADMIN', 'TENANT_ADMIN', 'PROPERTY_MANAGER'] as UserRole[],
} as const;
```

- [ ] **Step 2: Create `web/src/app/[locale]/dashboard/properties/[id]/_components/AmenitiesTab.tsx`**

Full file content:

```tsx
"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Plus, Pencil, Ban } from "lucide-react";
import { cn } from "@/lib/utils";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pagination } from "@/components/ui/Pagination";
import { fetchAmenities, createAmenity, updateAmenity, deactivateAmenity } from "@/lib/api/facilities";
import type { AmenityDTO } from "@/types/facility";

export type BuildingOption = { id: string; nameEn: string; nameAr?: string | null };

interface AmenitiesTabProps {
    propertyId: string;
    buildings: BuildingOption[];
    canManage: boolean;
}

const PAGE_SIZE = 10;
const EMPTY_FORM = { nameEn: "", nameAr: "", description: "", bookable: true, buildingIds: [] as string[] };

export function AmenitiesTab({ propertyId, buildings, canManage }: AmenitiesTabProps) {
    const t = useTranslations("Facilities");
    const locale = useLocale();
    const [rows, setRows] = useState<AmenityDTO[]>([]);
    const [totalElements, setTotalElements] = useState(0);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [showForm, setShowForm] = useState(false);
    const [editing, setEditing] = useState<AmenityDTO | null>(null);
    const [form, setForm] = useState(EMPTY_FORM);
    const [submitting, setSubmitting] = useState(false);
    const [formError, setFormError] = useState<string | null>(null);
    const [deactivating, setDeactivating] = useState<AmenityDTO | null>(null);

    const load = useCallback(async (p: number) => {
        setLoading(true);
        setError(null);
        try {
            const data = await fetchAmenities(propertyId, p, PAGE_SIZE);
            setRows(data.content);
            setTotalElements(data.totalElements);
            setPage(data.number);
        } catch {
            setError(t("loadError"));
        } finally {
            setLoading(false);
        }
    }, [propertyId, t]);

    useEffect(() => { load(0); }, [load]);

    const openAdd = () => {
        setEditing(null);
        setForm(EMPTY_FORM);
        setFormError(null);
        setShowForm(true);
    };

    const openEdit = (a: AmenityDTO) => {
        setEditing(a);
        setForm({
            nameEn: a.nameEn,
            nameAr: a.nameAr ?? "",
            description: a.description ?? "",
            bookable: a.bookable,
            buildingIds: a.buildingIds,
        });
        setFormError(null);
        setShowForm(true);
    };

    const toggleBuilding = (id: string) => {
        setForm(f => ({
            ...f,
            buildingIds: f.buildingIds.includes(id)
                ? f.buildingIds.filter(b => b !== id)
                : [...f.buildingIds, id],
        }));
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setSubmitting(true);
        setFormError(null);
        try {
            if (editing) {
                await updateAmenity(editing.id, {
                    nameEn: form.nameEn,
                    nameAr: form.nameAr || undefined,
                    description: form.description || undefined,
                    bookable: form.bookable,
                    buildingIds: form.buildingIds,
                });
            } else {
                await createAmenity({
                    propertyId,
                    nameEn: form.nameEn,
                    nameAr: form.nameAr || undefined,
                    description: form.description || undefined,
                    bookable: form.bookable,
                    buildingIds: form.buildingIds,
                });
            }
            setShowForm(false);
            load(editing ? page : 0);
        } catch {
            setFormError(t("saveError"));
        } finally {
            setSubmitting(false);
        }
    };

    const handleDeactivate = async () => {
        if (!deactivating) return;
        try {
            await deactivateAmenity(deactivating.id);
            setDeactivating(null);
            load(page);
        } catch {
            setDeactivating(null);
            setError(t("saveError"));
        }
    };

    const towerLabel = (a: AmenityDTO) => {
        if (a.buildingIds.length === 0) return t("allTowers");
        return a.buildingIds
            .map(id => {
                const b = buildings.find(x => x.id === id);
                if (!b) return null;
                return locale === "ar" && b.nameAr ? b.nameAr : b.nameEn;
            })
            .filter(Boolean)
            .join(", ");
    };

    return (
        <div>
            <div className="flex justify-between items-center mb-6">
                <h2 className="text-lg font-bold">{t("amenitiesTab")}</h2>
                {canManage && (
                    <button
                        onClick={openAdd}
                        className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-full text-xs font-bold hover:opacity-90 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                    >
                        <Plus size={14} /> {t("addAmenity")}
                    </button>
                )}
            </div>

            {error && (
                <div className="mb-4 px-4 py-3 rounded-xl bg-error/10 text-error border border-error/20 text-xs font-semibold">
                    {error}
                </div>
            )}

            <div className="bg-surface border border-border rounded-xl overflow-hidden">
                <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                        <thead className="bg-background text-muted text-xs font-semibold uppercase tracking-[0.15em]">
                            <tr>
                                <th className="px-6 py-4 text-start">{t("colName")}</th>
                                <th className="px-6 py-4 text-start">{t("colTowers")}</th>
                                <th className="px-6 py-4 text-start">{t("colBookable")}</th>
                                <th className="px-6 py-4 text-start">{t("colStatus")}</th>
                                <th className="px-6 py-4 text-start">{t("colPending")}</th>
                                <th className="px-6 py-4 text-start">{t("colCreated")}</th>
                                {canManage && <th className="px-6 py-4" />}
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                            {rows.map(a => (
                                <tr key={a.id} className="hover:bg-background/50 transition-all duration-200">
                                    <td className="px-6 py-4">
                                        <p className="font-bold text-foreground">{a.nameEn}</p>
                                        {a.nameAr && <p className="text-xs text-muted font-medium" dir="rtl">{a.nameAr}</p>}
                                    </td>
                                    <td className="px-6 py-4 text-muted text-xs font-medium">{towerLabel(a)}</td>
                                    <td className="px-6 py-4">
                                        <span className={cn(
                                            "px-2 py-1 text-[10px] font-bold uppercase tracking-widest rounded-md",
                                            a.bookable ? "bg-success/10 text-success" : "bg-input text-muted"
                                        )}>
                                            {a.bookable ? t("bookable") : t("notBookable")}
                                        </span>
                                    </td>
                                    <td className="px-6 py-4">
                                        <span className={cn(
                                            "px-2 py-1 text-[10px] font-bold uppercase tracking-widest rounded-md",
                                            a.active ? "bg-success/10 text-success" : "bg-error/10 text-error"
                                        )}>
                                            {a.active ? t("active") : t("inactive")}
                                        </span>
                                    </td>
                                    <td className="px-6 py-4">
                                        {a.pendingCount > 0 ? (
                                            <span className="px-2 py-1 text-[10px] font-bold rounded-md bg-warning/10 text-warning tabular-nums">
                                                {a.pendingCount}
                                            </span>
                                        ) : (
                                            <span className="text-xs text-muted">0</span>
                                        )}
                                    </td>
                                    <td className="px-6 py-4 text-muted text-xs tabular-nums">
                                        {new Date(a.createdAt).toLocaleDateString()}
                                    </td>
                                    {canManage && (
                                        <td className="px-6 py-4">
                                            <div className="flex items-center gap-1 justify-end">
                                                <button
                                                    onClick={() => openEdit(a)}
                                                    aria-label={t("editAmenity")}
                                                    className="p-1.5 rounded hover:bg-background text-muted hover:text-foreground transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                                >
                                                    <Pencil size={13} />
                                                </button>
                                                {a.active && (
                                                    <button
                                                        onClick={() => setDeactivating(a)}
                                                        aria-label={t("deactivate")}
                                                        className="p-1.5 rounded hover:bg-error/10 text-muted hover:text-error transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                                    >
                                                        <Ban size={13} />
                                                    </button>
                                                )}
                                            </div>
                                        </td>
                                    )}
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
                {loading && (
                    <div className="text-center py-12 text-muted font-medium text-xs">{t("loading")}</div>
                )}
                {!loading && rows.length === 0 && (
                    <div className="text-center py-12 text-muted font-medium text-xs">{t("noAmenities")}</div>
                )}
                {!loading && totalElements > PAGE_SIZE && (
                    <div className="px-6 pb-4">
                        <Pagination
                            currentPage={page + 1}
                            totalItems={totalElements}
                            itemsPerPage={PAGE_SIZE}
                            onPageChange={(p) => load(p - 1)}
                        />
                    </div>
                )}
            </div>

            {/* Add / Edit dialog */}
            {showForm && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={() => setShowForm(false)}>
                    <div className="bg-surface rounded-xl border border-border shadow-xl w-full max-w-lg mx-4 p-6" onClick={(e) => e.stopPropagation()}>
                        <h3 className="text-lg font-bold text-foreground mb-4">
                            {editing ? t("editAmenity") : t("addAmenity")}
                        </h3>
                        <form onSubmit={handleSubmit} className="space-y-4">
                            <div className="grid grid-cols-2 gap-4">
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("nameEn")} *</label>
                                    <input
                                        required
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                        value={form.nameEn}
                                        onChange={e => setForm({ ...form, nameEn: e.target.value })}
                                    />
                                </div>
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("nameAr")}</label>
                                    <input
                                        dir="rtl"
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                        value={form.nameAr}
                                        onChange={e => setForm({ ...form, nameAr: e.target.value })}
                                    />
                                </div>
                            </div>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("description")}</label>
                                <textarea
                                    rows={2}
                                    className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 resize-none"
                                    value={form.description}
                                    onChange={e => setForm({ ...form, description: e.target.value })}
                                />
                            </div>
                            <label className="flex items-center gap-2 cursor-pointer">
                                <input
                                    type="checkbox"
                                    checked={form.bookable}
                                    onChange={e => setForm({ ...form, bookable: e.target.checked })}
                                    className="accent-[var(--gold-500)]"
                                />
                                <span className="text-xs font-semibold text-foreground">{t("bookable")}</span>
                            </label>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("towers")}</label>
                                <p className="text-[10px] text-muted mb-2">{t("towersHint")}</p>
                                <div className="flex flex-wrap gap-2">
                                    {buildings.map(b => {
                                        const selected = form.buildingIds.includes(b.id);
                                        return (
                                            <button
                                                type="button"
                                                key={b.id}
                                                onClick={() => toggleBuilding(b.id)}
                                                className={cn(
                                                    "px-3 py-1.5 rounded-full text-[11px] font-bold border transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none",
                                                    selected
                                                        ? "bg-primary/10 text-primary border-primary/30"
                                                        : "bg-input text-muted border-border hover:text-foreground"
                                                )}
                                            >
                                                {locale === "ar" && b.nameAr ? b.nameAr : b.nameEn}
                                            </button>
                                        );
                                    })}
                                    {buildings.length === 0 && (
                                        <span className="text-xs text-muted italic">{t("allTowers")}</span>
                                    )}
                                </div>
                            </div>
                            {formError && (
                                <p className="text-xs font-semibold text-error">{formError}</p>
                            )}
                            <div className="flex justify-end gap-2 pt-2">
                                <button
                                    type="button"
                                    onClick={() => setShowForm(false)}
                                    className="px-4 py-2 text-xs font-bold text-muted cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg transition-all duration-200"
                                >
                                    {t("cancel")}
                                </button>
                                <button
                                    type="submit"
                                    disabled={submitting}
                                    className="px-4 py-2 bg-primary text-white rounded-lg text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
                                >
                                    {submitting ? t("saving") : t("save")}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            <ConfirmDialog
                isOpen={deactivating !== null}
                onClose={() => setDeactivating(null)}
                onConfirm={handleDeactivate}
                title={t("deactivateAmenityTitle")}
                description={t("deactivateConfirm")}
                confirmText={t("deactivate")}
                isDestructive
            />
        </div>
    );
}
```

- [ ] **Step 3: Wire the tab into `web/src/app/[locale]/dashboard/properties/[id]/page.tsx` (5 hunks)**

Hunk A — replace the lucide import (line 7):

```tsx
import { Building2, Home, FileText, ArrowLeft, Plus, MapPin, Upload, Calendar, DollarSign, Settings, Wrench, Zap, Hammer, Shield, Hospital, Pill, Siren, HelpCircle, Phone, Mail, Pencil, Trash2 } from "lucide-react";
```
with:
```tsx
import { Building2, Home, FileText, ArrowLeft, Plus, MapPin, Upload, Calendar, DollarSign, Settings, Wrench, Zap, Hammer, Shield, Hospital, Pill, Siren, HelpCircle, Phone, Mail, Pencil, Trash2, Dumbbell } from "lucide-react";
import { AmenitiesTab } from "./_components/AmenitiesTab";
```

Hunk B — replace:
```tsx
    const tOnlinePayments = useTranslations("OnlinePayments");
```
with:
```tsx
    const tOnlinePayments = useTranslations("OnlinePayments");
    const tFacilities = useTranslations("Facilities");
```

Hunk C — replace:
```tsx
    const canManageRentSettings = userRole ? canConfigureRentSettings(userRole) : false;
```
with:
```tsx
    const canManageRentSettings = userRole ? canConfigureRentSettings(userRole) : false;
    const canManageFacilities = hasPermission(userRole, 'canManageFacilities');
```

Hunk D — replace:
```tsx
    const [activeTab, setActiveTab] = useState<"overview" | "buildings" | "units" | "leases">("overview");
```
with:
```tsx
    const [activeTab, setActiveTab] = useState<"overview" | "buildings" | "units" | "leases" | "amenities" | "parking">("overview");
```

Hunk E — replace:
```tsx
                    { id: "leases", label: "Leases", icon: FileText }
```
with:
```tsx
                    { id: "leases", label: "Leases", icon: FileText },
                    { id: "amenities", label: tFacilities("amenitiesTab"), icon: Dumbbell }
```

Hunk F — replace:
```tsx
            {activeTab === "leases" && (
                <LeasesTab propertyId={propertyId} />
            )}
```
with:
```tsx
            {activeTab === "leases" && (
                <LeasesTab propertyId={propertyId} />
            )}

            {activeTab === "amenities" && (
                <AmenitiesTab propertyId={propertyId} buildings={buildings} canManage={canManageFacilities} />
            )}
```

- [ ] **Step 4: Verify**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && npx tsc --noEmit
```
Expected output: nothing, exit 0.

Manual checks (backend running per `docker compose up -d` or `./gradlew bootRun`, web via `npm run dev`):
- Open `http://localhost:3000/en/dashboard/properties/<propertyId>` as TENANT_ADMIN → an **Amenities** tab appears after Leases with a dumbbell icon.
- Add an amenity with EN+AR names, leave towers unchecked → row shows "All towers"; add another selecting one tower → row shows that tower's name.
- Edit toggles Bookable off → badge flips to "Not bookable"; Deactivate shows ConfirmDialog → row flips to "Inactive" and the Ban button disappears.
- Switch URL to `/ar/...` → tab label "المرافق", table headers Arabic, layout not broken in RTL (headers use `text-start`).
- Create 11 amenities → pagination footer appears; rows are oldest-first (createdAt ASC from backend).

- [ ] **Step 5: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add web/src/lib/rbac.ts "web/src/app/[locale]/dashboard/properties/[id]/page.tsx" "web/src/app/[locale]/dashboard/properties/[id]/_components/AmenitiesTab.tsx" && git commit -m "feat(web): amenities tab on property detail with tower scoping" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
Expected output: `3 files changed`.

---

### Task W4: Property detail — Parking tab (single + bulk add)

**Files:**
- Create: `web/src/app/[locale]/dashboard/properties/[id]/_components/ParkingTab.tsx`
- Modify: `web/src/app/[locale]/dashboard/properties/[id]/page.tsx` (import hunk, tabs array, render block — anchors created in W3)

- [ ] **Step 1: Create `web/src/app/[locale]/dashboard/properties/[id]/_components/ParkingTab.tsx`**

Full file content:

```tsx
"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Plus, Pencil, Ban, Rows3 } from "lucide-react";
import { cn } from "@/lib/utils";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pagination } from "@/components/ui/Pagination";
import {
    fetchParkingSpots,
    createParkingSpot,
    updateParkingSpot,
    bulkCreateParkingSpots,
    deactivateParkingSpot,
    parseSpotNumbers,
} from "@/lib/api/facilities";
import type { ParkingSpotDTO } from "@/types/facility";
import type { BuildingOption } from "./AmenitiesTab";

interface ParkingTabProps {
    propertyId: string;
    buildings: BuildingOption[];
    canManage: boolean;
}

type FormMode = { kind: "add" } | { kind: "edit"; spot: ParkingSpotDTO } | { kind: "bulk" };

const PAGE_SIZE = 10;

export function ParkingTab({ propertyId, buildings, canManage }: ParkingTabProps) {
    const t = useTranslations("Facilities");
    const locale = useLocale();
    const [rows, setRows] = useState<ParkingSpotDTO[]>([]);
    const [totalElements, setTotalElements] = useState(0);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [mode, setMode] = useState<FormMode | null>(null);
    const [spotNumber, setSpotNumber] = useState("");
    const [bulkInput, setBulkInput] = useState("");
    const [level, setLevel] = useState("");
    const [covered, setCovered] = useState(true);
    const [buildingIds, setBuildingIds] = useState<string[]>([]);
    const [submitting, setSubmitting] = useState(false);
    const [formError, setFormError] = useState<string | null>(null);
    const [deactivating, setDeactivating] = useState<ParkingSpotDTO | null>(null);

    const load = useCallback(async (p: number) => {
        setLoading(true);
        setError(null);
        try {
            const data = await fetchParkingSpots(propertyId, p, PAGE_SIZE);
            setRows(data.content);
            setTotalElements(data.totalElements);
            setPage(data.number);
        } catch {
            setError(t("loadError"));
        } finally {
            setLoading(false);
        }
    }, [propertyId, t]);

    useEffect(() => { load(0); }, [load]);

    const openMode = (m: FormMode) => {
        setMode(m);
        setFormError(null);
        if (m.kind === "edit") {
            setSpotNumber(m.spot.spotNumber);
            setLevel(m.spot.level ?? "");
            setCovered(m.spot.covered);
            setBuildingIds(m.spot.buildingIds);
        } else {
            setSpotNumber("");
            setBulkInput("");
            setLevel("");
            setCovered(true);
            setBuildingIds([]);
        }
    };

    const toggleBuilding = (id: string) => {
        setBuildingIds(ids => ids.includes(id) ? ids.filter(b => b !== id) : [...ids, id]);
    };

    const bulkCount = mode?.kind === "bulk" ? parseSpotNumbers(bulkInput).length : 0;

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!mode) return;
        setSubmitting(true);
        setFormError(null);
        try {
            if (mode.kind === "edit") {
                await updateParkingSpot(mode.spot.id, {
                    spotNumber,
                    level: level || undefined,
                    covered,
                    buildingIds,
                });
            } else if (mode.kind === "add") {
                await createParkingSpot({
                    propertyId,
                    spotNumber,
                    level: level || undefined,
                    covered,
                    buildingIds,
                });
            } else {
                const spotNumbers = parseSpotNumbers(bulkInput);
                if (spotNumbers.length === 0) {
                    setFormError(t("saveError"));
                    setSubmitting(false);
                    return;
                }
                await bulkCreateParkingSpots({
                    propertyId,
                    spotNumbers,
                    level: level || undefined,
                    covered,
                    buildingIds,
                });
            }
            setMode(null);
            load(mode.kind === "edit" ? page : 0);
        } catch {
            setFormError(t("saveError"));
        } finally {
            setSubmitting(false);
        }
    };

    const handleDeactivate = async () => {
        if (!deactivating) return;
        try {
            await deactivateParkingSpot(deactivating.id);
            setDeactivating(null);
            load(page);
        } catch {
            setDeactivating(null);
            setError(t("saveError"));
        }
    };

    const towerLabel = (s: ParkingSpotDTO) => {
        if (s.buildingIds.length === 0) return t("allTowers");
        return s.buildingIds
            .map(id => {
                const b = buildings.find(x => x.id === id);
                if (!b) return null;
                return locale === "ar" && b.nameAr ? b.nameAr : b.nameEn;
            })
            .filter(Boolean)
            .join(", ");
    };

    return (
        <div>
            <div className="flex justify-between items-center mb-6">
                <h2 className="text-lg font-bold">{t("parkingTab")}</h2>
                {canManage && (
                    <div className="flex items-center gap-2">
                        <button
                            onClick={() => openMode({ kind: "add" })}
                            className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-full text-xs font-bold hover:opacity-90 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Plus size={14} /> {t("addSpot")}
                        </button>
                        <button
                            onClick={() => openMode({ kind: "bulk" })}
                            className="flex items-center gap-2 bg-background text-foreground px-4 py-2 rounded-full text-xs font-bold hover:bg-input transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Rows3 size={14} /> {t("bulkAddSpots")}
                        </button>
                    </div>
                )}
            </div>

            {error && (
                <div className="mb-4 px-4 py-3 rounded-xl bg-error/10 text-error border border-error/20 text-xs font-semibold">
                    {error}
                </div>
            )}

            <div className="bg-surface border border-border rounded-xl overflow-hidden">
                <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                        <thead className="bg-background text-muted text-xs font-semibold uppercase tracking-[0.15em]">
                            <tr>
                                <th className="px-6 py-4 text-start">{t("colSpot")}</th>
                                <th className="px-6 py-4 text-start">{t("colLevel")}</th>
                                <th className="px-6 py-4 text-start">{t("colCovered")}</th>
                                <th className="px-6 py-4 text-start">{t("colTowers")}</th>
                                <th className="px-6 py-4 text-start">{t("colHeld")}</th>
                                <th className="px-6 py-4 text-start">{t("colStatus")}</th>
                                <th className="px-6 py-4 text-start">{t("colPending")}</th>
                                {canManage && <th className="px-6 py-4" />}
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                            {rows.map(s => (
                                <tr key={s.id} className="hover:bg-background/50 transition-all duration-200">
                                    <td className="px-6 py-4 font-bold text-foreground">{s.spotNumber}</td>
                                    <td className="px-6 py-4 text-muted text-xs">{s.level ?? "—"}</td>
                                    <td className="px-6 py-4 text-xs font-semibold text-muted">
                                        {s.covered ? t("yes") : t("no")}
                                    </td>
                                    <td className="px-6 py-4 text-muted text-xs font-medium">{towerLabel(s)}</td>
                                    <td className="px-6 py-4">
                                        <span className={cn(
                                            "px-2 py-1 text-[10px] font-bold uppercase tracking-widest rounded-md",
                                            s.held ? "bg-warning/10 text-warning" : "bg-success/10 text-success"
                                        )}>
                                            {s.held ? t("held") : t("available")}
                                        </span>
                                    </td>
                                    <td className="px-6 py-4">
                                        <span className={cn(
                                            "px-2 py-1 text-[10px] font-bold uppercase tracking-widest rounded-md",
                                            s.active ? "bg-success/10 text-success" : "bg-error/10 text-error"
                                        )}>
                                            {s.active ? t("active") : t("inactive")}
                                        </span>
                                    </td>
                                    <td className="px-6 py-4">
                                        {s.pendingCount > 0 ? (
                                            <span className="px-2 py-1 text-[10px] font-bold rounded-md bg-warning/10 text-warning tabular-nums">
                                                {s.pendingCount}
                                            </span>
                                        ) : (
                                            <span className="text-xs text-muted">0</span>
                                        )}
                                    </td>
                                    {canManage && (
                                        <td className="px-6 py-4">
                                            <div className="flex items-center gap-1 justify-end">
                                                <button
                                                    onClick={() => openMode({ kind: "edit", spot: s })}
                                                    aria-label={t("editSpot")}
                                                    className="p-1.5 rounded hover:bg-background text-muted hover:text-foreground transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                                >
                                                    <Pencil size={13} />
                                                </button>
                                                {s.active && (
                                                    <button
                                                        onClick={() => setDeactivating(s)}
                                                        aria-label={t("deactivate")}
                                                        className="p-1.5 rounded hover:bg-error/10 text-muted hover:text-error transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                                    >
                                                        <Ban size={13} />
                                                    </button>
                                                )}
                                            </div>
                                        </td>
                                    )}
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
                {loading && (
                    <div className="text-center py-12 text-muted font-medium text-xs">{t("loading")}</div>
                )}
                {!loading && rows.length === 0 && (
                    <div className="text-center py-12 text-muted font-medium text-xs">{t("noSpots")}</div>
                )}
                {!loading && totalElements > PAGE_SIZE && (
                    <div className="px-6 pb-4">
                        <Pagination
                            currentPage={page + 1}
                            totalItems={totalElements}
                            itemsPerPage={PAGE_SIZE}
                            onPageChange={(p) => load(p - 1)}
                        />
                    </div>
                )}
            </div>

            {/* Add / Edit / Bulk dialog */}
            {mode && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={() => setMode(null)}>
                    <div className="bg-surface rounded-xl border border-border shadow-xl w-full max-w-lg mx-4 p-6" onClick={(e) => e.stopPropagation()}>
                        <h3 className="text-lg font-bold text-foreground mb-4">
                            {mode.kind === "edit" ? t("editSpot") : mode.kind === "bulk" ? t("bulkAddSpots") : t("addSpot")}
                        </h3>
                        <form onSubmit={handleSubmit} className="space-y-4">
                            {mode.kind === "bulk" ? (
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("spotNumbers")} *</label>
                                    <textarea
                                        required
                                        rows={3}
                                        placeholder="B1-01, B1-02, P10-P20"
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 resize-none font-mono"
                                        value={bulkInput}
                                        onChange={e => setBulkInput(e.target.value)}
                                        dir="ltr"
                                    />
                                    <p className="text-[10px] text-muted mt-1">{t("bulkHint")}</p>
                                    {bulkCount > 0 && (
                                        <p className="text-[10px] font-bold text-primary mt-1">{t("bulkPreview", { count: bulkCount })}</p>
                                    )}
                                </div>
                            ) : (
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("spotNumber")} *</label>
                                    <input
                                        required
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                        value={spotNumber}
                                        onChange={e => setSpotNumber(e.target.value)}
                                        dir="ltr"
                                    />
                                </div>
                            )}
                            <div className="grid grid-cols-2 gap-4 items-end">
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("level")}</label>
                                    <input
                                        placeholder={t("levelPlaceholder")}
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                        value={level}
                                        onChange={e => setLevel(e.target.value)}
                                        dir="ltr"
                                    />
                                </div>
                                <label className="flex items-center gap-2 cursor-pointer pb-2">
                                    <input
                                        type="checkbox"
                                        checked={covered}
                                        onChange={e => setCovered(e.target.checked)}
                                        className="accent-[var(--gold-500)]"
                                    />
                                    <span className="text-xs font-semibold text-foreground">{t("covered")}</span>
                                </label>
                            </div>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("towers")}</label>
                                <p className="text-[10px] text-muted mb-2">{t("towersHint")}</p>
                                <div className="flex flex-wrap gap-2">
                                    {buildings.map(b => {
                                        const selected = buildingIds.includes(b.id);
                                        return (
                                            <button
                                                type="button"
                                                key={b.id}
                                                onClick={() => toggleBuilding(b.id)}
                                                className={cn(
                                                    "px-3 py-1.5 rounded-full text-[11px] font-bold border transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none",
                                                    selected
                                                        ? "bg-primary/10 text-primary border-primary/30"
                                                        : "bg-input text-muted border-border hover:text-foreground"
                                                )}
                                            >
                                                {locale === "ar" && b.nameAr ? b.nameAr : b.nameEn}
                                            </button>
                                        );
                                    })}
                                    {buildings.length === 0 && (
                                        <span className="text-xs text-muted italic">{t("allTowers")}</span>
                                    )}
                                </div>
                            </div>
                            {formError && (
                                <p className="text-xs font-semibold text-error">{formError}</p>
                            )}
                            <div className="flex justify-end gap-2 pt-2">
                                <button
                                    type="button"
                                    onClick={() => setMode(null)}
                                    className="px-4 py-2 text-xs font-bold text-muted cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg transition-all duration-200"
                                >
                                    {t("cancel")}
                                </button>
                                <button
                                    type="submit"
                                    disabled={submitting}
                                    className="px-4 py-2 bg-primary text-white rounded-lg text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
                                >
                                    {submitting ? t("saving") : t("save")}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            <ConfirmDialog
                isOpen={deactivating !== null}
                onClose={() => setDeactivating(null)}
                onConfirm={handleDeactivate}
                title={t("deactivateSpotTitle")}
                description={t("deactivateConfirm")}
                confirmText={t("deactivate")}
                isDestructive
            />
        </div>
    );
}
```

- [ ] **Step 2: Wire the tab into `web/src/app/[locale]/dashboard/properties/[id]/page.tsx` (3 hunks on W3 anchors)**

Hunk A — replace:
```tsx
import { Building2, Home, FileText, ArrowLeft, Plus, MapPin, Upload, Calendar, DollarSign, Settings, Wrench, Zap, Hammer, Shield, Hospital, Pill, Siren, HelpCircle, Phone, Mail, Pencil, Trash2, Dumbbell } from "lucide-react";
import { AmenitiesTab } from "./_components/AmenitiesTab";
```
with:
```tsx
import { Building2, Home, FileText, ArrowLeft, Plus, MapPin, Upload, Calendar, DollarSign, Settings, Wrench, Zap, Hammer, Shield, Hospital, Pill, Siren, HelpCircle, Phone, Mail, Pencil, Trash2, Dumbbell, Car } from "lucide-react";
import { AmenitiesTab } from "./_components/AmenitiesTab";
import { ParkingTab } from "./_components/ParkingTab";
```

Hunk B — replace:
```tsx
                    { id: "amenities", label: tFacilities("amenitiesTab"), icon: Dumbbell }
```
with:
```tsx
                    { id: "amenities", label: tFacilities("amenitiesTab"), icon: Dumbbell },
                    { id: "parking", label: tFacilities("parkingTab"), icon: Car }
```

Hunk C — replace:
```tsx
            {activeTab === "amenities" && (
                <AmenitiesTab propertyId={propertyId} buildings={buildings} canManage={canManageFacilities} />
            )}
```
with:
```tsx
            {activeTab === "amenities" && (
                <AmenitiesTab propertyId={propertyId} buildings={buildings} canManage={canManageFacilities} />
            )}

            {activeTab === "parking" && (
                <ParkingTab propertyId={propertyId} buildings={buildings} canManage={canManageFacilities} />
            )}
```

- [ ] **Step 3: Verify**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && npx tsc --noEmit
```
Expected output: nothing, exit 0.

Manual checks:
- Parking tab appears with a car icon; single add creates one spot.
- Bulk dialog: type `B1-01, B1-02, P10-P20` → preview reads "13 spots will be created" (2 literals + 11 expanded); submit → 13 rows appear across pages.
- Bulk with a tower selected → all created spots show that tower.
- Duplicate spot number for the same property → backend rejects (unique `(property_id, spot_number)`) and the dialog shows the save-error message rather than closing.
- `/ar/...`: dialog labels Arabic; spot-number/level inputs stay LTR (`dir="ltr"`), layout intact.

- [ ] **Step 4: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add "web/src/app/[locale]/dashboard/properties/[id]/page.tsx" "web/src/app/[locale]/dashboard/properties/[id]/_components/ParkingTab.tsx" && git commit -m "feat(web): parking tab with single and bulk spot creation" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
Expected output: `2 files changed`.

---

### Task W5: Bookings inbox page, detail drawer, sidebar nav

**Files:**
- Create: `web/src/app/[locale]/dashboard/bookings/page.tsx`
- Create: `web/src/app/[locale]/dashboard/bookings/_components/BookingDetailDrawer.tsx`
- Modify: `web/src/components/ui/MvpSidebar.tsx` (lucide import ~line 26, translations hooks ~line 50, menuItems ~line 90)

- [ ] **Step 1: Create `web/src/app/[locale]/dashboard/bookings/_components/BookingDetailDrawer.tsx`**

Full file content:

```tsx
"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations } from "next-intl";
import { X, Loader2, CalendarCheck, Users } from "lucide-react";
import { cn } from "@/lib/utils";
import { fetchBooking, approveBooking, rejectBooking, releaseBooking, ApiError } from "@/lib/api/facilities";
import type { BookingDetailDTO, BookingRequestStatus } from "@/types/facility";

export const BOOKING_STATUS_CLASSES: Record<BookingRequestStatus, string> = {
  PENDING: "bg-warning/10 text-warning border border-warning/20",
  APPROVED: "bg-success/10 text-success border border-success/20",
  REJECTED: "bg-error/10 text-error border border-error/20",
  CANCELLED: "bg-input text-muted border border-border",
  RELEASED: "bg-info/10 text-info border border-info/20",
};

interface BookingDetailDrawerProps {
  bookingId: string;
  onClose: () => void;
  /** Called after any successful approve/reject/release so the table can refresh. */
  onChanged: () => void;
}

export function BookingDetailDrawer({ bookingId, onClose, onChanged }: BookingDetailDrawerProps) {
  const t = useTranslations("Bookings");
  const [detail, setDetail] = useState<BookingDetailDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [adminNote, setAdminNote] = useState("");
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setDetail(await fetchBooking(bookingId));
    } catch {
      setError(t("loadError"));
    } finally {
      setLoading(false);
    }
  }, [bookingId, t]);

  useEffect(() => { load(); }, [load]);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [onClose]);

  const act = async (action: "approve" | "reject" | "release") => {
    setWorking(true);
    setError(null);
    try {
      if (action === "approve") await approveBooking(bookingId, { adminNote: adminNote || undefined });
      else if (action === "reject") await rejectBooking(bookingId, { adminNote: adminNote || undefined });
      else await releaseBooking(bookingId);
      onChanged();
      await load();
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) setError(t("spotConflict"));
      else setError(t("actionError"));
    } finally {
      setWorking(false);
    }
  };

  const req = detail?.request;
  const statusLabel = (s: BookingRequestStatus) => t(`status${s}`);

  return (
    <>
      {/* Overlay */}
      <div
        className="fixed inset-0 bg-black/20 backdrop-blur-sm z-[100]"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Drawer (logical end side — RTL-safe) */}
      <div
        className="fixed inset-y-0 end-0 w-full max-w-md bg-surface shadow-2xl z-[101] flex flex-col border-s border-border"
        role="dialog"
        aria-modal="true"
        aria-label={t("detailTitle")}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-border shrink-0">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-primary/10 flex items-center justify-center">
              <CalendarCheck size={16} className="text-primary" />
            </div>
            <h2 className="text-sm font-bold text-foreground">{t("detailTitle")}</h2>
          </div>
          <button
            onClick={onClose}
            className="p-2 text-muted hover:text-foreground rounded-lg transition-colors cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30"
            aria-label="Close"
          >
            <X size={18} />
          </button>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto">
          {loading ? (
            <div className="flex flex-col items-center justify-center h-48 gap-3">
              <Loader2 size={24} className="animate-spin text-muted" />
              <p className="text-xs text-muted">{t("loading")}</p>
            </div>
          ) : req ? (
            <div className="px-6 py-4 space-y-5">
              {/* Resource + status */}
              <div className="flex items-start justify-between gap-2">
                <div>
                  <p className="text-sm font-bold text-foreground">{req.resourceName}</p>
                  <p className="text-[11px] text-muted font-semibold uppercase tracking-widest">
                    {t(`type${req.resourceType}`)}
                  </p>
                </div>
                <span className={cn(
                  "inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold shrink-0",
                  BOOKING_STATUS_CLASSES[req.status]
                )}>
                  {statusLabel(req.status)}
                </span>
              </div>

              {/* Renter details */}
              <div className="bg-input/40 rounded-xl border border-border p-4 space-y-1">
                <p className="text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-2">{t("renterContact")}</p>
                <p className="text-sm font-semibold text-foreground">{req.renterName ?? "—"}</p>
                {req.renterEmail && (
                  <a href={`mailto:${req.renterEmail}`} className="text-xs text-primary hover:underline block truncate">
                    {req.renterEmail}
                  </a>
                )}
                {req.renterPhone && (
                  <a href={`tel:${req.renterPhone}`} className="text-xs text-foreground hover:text-primary transition-colors block">
                    {req.renterPhone}
                  </a>
                )}
                {req.unitNumber && (
                  <p className="text-xs text-muted">{t("colUnit")}: {req.unitNumber}</p>
                )}
              </div>

              {/* Request details */}
              <div className="space-y-2 text-xs">
                <p className="text-muted">
                  <span className="font-semibold">{t("requestedOn")}:</span>{" "}
                  {new Date(req.createdAt).toLocaleDateString()}
                </p>
                {req.preferredDate && (
                  <p className="text-muted">
                    <span className="font-semibold">{t("preferredDate")}:</span>{" "}
                    {new Date(req.preferredDate).toLocaleDateString()}
                  </p>
                )}
                {req.note && (
                  <p className="text-muted italic">
                    <span className="font-semibold not-italic">{t("renterNote")}:</span> &ldquo;{req.note}&rdquo;
                  </p>
                )}
                {req.adminNote && (
                  <p className="text-muted">
                    <span className="font-semibold">{t("adminNote")}:</span> {req.adminNote}
                  </p>
                )}
                {req.decidedAt && (
                  <p className="text-muted">
                    <span className="font-semibold">{t("decidedAt")}:</span>{" "}
                    {new Date(req.decidedAt).toLocaleDateString()}
                  </p>
                )}
              </div>

              {/* Other applicants */}
              <div>
                <p className="text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-2 flex items-center gap-1.5">
                  <Users size={12} /> {t("otherRequests")}
                </p>
                {detail && detail.otherRequests.length > 0 ? (
                  <div className="divide-y divide-border border border-border rounded-xl overflow-hidden">
                    {detail.otherRequests.map(o => (
                      <div key={o.id} className="px-4 py-3 flex items-center justify-between gap-2 bg-input/20">
                        <div className="min-w-0">
                          <p className="text-xs font-semibold text-foreground truncate">{o.renterName ?? "—"}</p>
                          <p className="text-[10px] text-muted">
                            {o.unitNumber ? `${t("colUnit")} ${o.unitNumber} · ` : ""}
                            {new Date(o.createdAt).toLocaleDateString()}
                          </p>
                        </div>
                        <span className={cn(
                          "inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold shrink-0",
                          BOOKING_STATUS_CLASSES[o.status]
                        )}>
                          {statusLabel(o.status)}
                        </span>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="text-xs text-muted italic">{t("noOtherRequests")}</p>
                )}
              </div>
            </div>
          ) : null}
        </div>

        {/* Footer actions */}
        {req && (req.status === "PENDING" || (req.status === "APPROVED" && req.resourceType === "PARKING_SPOT")) && (
          <div className="shrink-0 px-6 py-4 border-t border-border bg-input/30 space-y-3">
            {error && <p className="text-xs font-semibold text-error">{error}</p>}
            {req.status === "PENDING" && (
              <>
                <div>
                  <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1">
                    {t("adminNoteLabel")}
                  </label>
                  <textarea
                    rows={2}
                    className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 resize-none"
                    value={adminNote}
                    onChange={e => setAdminNote(e.target.value)}
                  />
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={() => act("approve")}
                    disabled={working}
                    className="flex-1 px-4 py-2.5 bg-success/10 text-success hover:bg-success/20 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-success/30 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {working ? t("working") : t("approve")}
                  </button>
                  <button
                    onClick={() => act("reject")}
                    disabled={working}
                    className="flex-1 px-4 py-2.5 bg-error/10 text-error hover:bg-error/20 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-error/30 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {working ? t("working") : t("reject")}
                  </button>
                </div>
              </>
            )}
            {req.status === "APPROVED" && req.resourceType === "PARKING_SPOT" && (
              <button
                onClick={() => act("release")}
                disabled={working}
                className="w-full px-4 py-2.5 bg-info/10 text-info hover:bg-info/20 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-info/30 disabled:opacity-50 disabled:cursor-not-allowed"
              >
                {working ? t("working") : t("release")}
              </button>
            )}
          </div>
        )}
        {!loading && !req && error && (
          <div className="px-6 py-4 text-xs font-semibold text-error">{error}</div>
        )}
      </div>
    </>
  );
}
```

- [ ] **Step 2: Create `web/src/app/[locale]/dashboard/bookings/page.tsx`**

Full file content:

```tsx
"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations, useLocale } from "next-intl";
import { useSession } from "next-auth/react";
import { CalendarCheck } from "lucide-react";
import { cn } from "@/lib/utils";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { Pagination } from "@/components/ui/Pagination";
import { fetchBookings } from "@/lib/api/facilities";
import type { BookingRequestDTO, BookingRequestStatus, BookingResourceType } from "@/types/facility";
import { BookingDetailDrawer, BOOKING_STATUS_CLASSES } from "./_components/BookingDetailDrawer";

const PAGE_SIZE = 10;
const STATUSES: BookingRequestStatus[] = ["PENDING", "APPROVED", "REJECTED", "CANCELLED", "RELEASED"];

type PropertyOption = { id: string; nameEn: string; nameAr: string | null };

export default function BookingsPage() {
    const t = useTranslations("Bookings");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canView = hasPermission(userRole, "canManageFacilities");

    const [rows, setRows] = useState<BookingRequestDTO[]>([]);
    const [totalElements, setTotalElements] = useState(0);
    const [page, setPage] = useState(0);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [properties, setProperties] = useState<PropertyOption[]>([]);
    const [propertyId, setPropertyId] = useState("");
    const [status, setStatus] = useState<"" | BookingRequestStatus>("PENDING");
    const [resourceType, setResourceType] = useState<"" | BookingResourceType>("");
    const [openId, setOpenId] = useState<string | null>(null);

    useEffect(() => {
        (async () => {
            try {
                const res = await fetch("/api/proxy/v1/properties");
                if (res.ok) {
                    const data: Array<{ property: PropertyOption }> = await res.json();
                    setProperties(data.map(s => s.property));
                }
            } catch (err) {
                console.error(err);
            }
        })();
    }, []);

    const load = useCallback(async (p: number) => {
        setLoading(true);
        setError(null);
        try {
            const data = await fetchBookings({
                propertyId: propertyId || undefined,
                status: status || undefined,
                resourceType: resourceType || undefined,
                page: p,
                size: PAGE_SIZE,
            });
            setRows(data.content);
            setTotalElements(data.totalElements);
            setPage(data.number);
        } catch {
            setError(t("loadError"));
        } finally {
            setLoading(false);
        }
    }, [propertyId, status, resourceType, t]);

    useEffect(() => { load(0); }, [load]);

    if (session && !canView) {
        return (
            <div className="p-8 max-w-7xl mx-auto">
                <p className="text-sm font-semibold text-muted">{t("noAccess")}</p>
            </div>
        );
    }

    const propertyName = (p: PropertyOption) =>
        locale === "ar" && p.nameAr ? p.nameAr : p.nameEn;

    return (
        <div className="p-8 max-w-7xl mx-auto">
            <div className="flex items-center gap-3 mb-1">
                <div className="w-9 h-9 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20">
                    <CalendarCheck size={18} />
                </div>
                <h1 className="text-xl font-bold text-foreground tracking-tight">{t("title")}</h1>
            </div>
            <p className="text-xs text-muted font-medium mb-6">{t("subtitle")}</p>

            {/* Filters */}
            <div className="flex flex-wrap items-center gap-3 mb-6">
                <select
                    value={propertyId}
                    onChange={e => setPropertyId(e.target.value)}
                    className="bg-input border border-border rounded-lg px-3 py-2 text-xs font-semibold text-foreground cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                >
                    <option value="">{t("allProperties")}</option>
                    {properties.map(p => (
                        <option key={p.id} value={p.id}>{propertyName(p)}</option>
                    ))}
                </select>
                <select
                    value={status}
                    onChange={e => setStatus(e.target.value as "" | BookingRequestStatus)}
                    className="bg-input border border-border rounded-lg px-3 py-2 text-xs font-semibold text-foreground cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                >
                    <option value="">{t("allStatuses")}</option>
                    {STATUSES.map(s => (
                        <option key={s} value={s}>{t(`status${s}`)}</option>
                    ))}
                </select>
                <select
                    value={resourceType}
                    onChange={e => setResourceType(e.target.value as "" | BookingResourceType)}
                    className="bg-input border border-border rounded-lg px-3 py-2 text-xs font-semibold text-foreground cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                >
                    <option value="">{t("allTypes")}</option>
                    <option value="AMENITY">{t("typeAMENITY")}</option>
                    <option value="PARKING_SPOT">{t("typePARKING_SPOT")}</option>
                </select>
            </div>

            {error && (
                <div className="mb-4 px-4 py-3 rounded-xl bg-error/10 text-error border border-error/20 text-xs font-semibold flex items-center justify-between">
                    <span>{error}</span>
                    <button onClick={() => load(page)} className="font-bold underline cursor-pointer">{t("retry")}</button>
                </div>
            )}

            <div className="bg-surface border border-border rounded-xl overflow-hidden">
                <div className="overflow-x-auto">
                    <table className="w-full text-sm">
                        <thead className="bg-background text-muted text-xs font-semibold uppercase tracking-[0.15em]">
                            <tr>
                                <th className="px-6 py-4 text-start">{t("colResource")}</th>
                                <th className="px-6 py-4 text-start">{t("colType")}</th>
                                <th className="px-6 py-4 text-start">{t("colRenter")}</th>
                                <th className="px-6 py-4 text-start">{t("colUnit")}</th>
                                <th className="px-6 py-4 text-start">{t("colPreferred")}</th>
                                <th className="px-6 py-4 text-start">{t("colRequested")}</th>
                                <th className="px-6 py-4 text-start">{t("colStatus")}</th>
                            </tr>
                        </thead>
                        <tbody className="divide-y divide-border">
                            {rows.map(r => (
                                <tr
                                    key={r.id}
                                    onClick={() => setOpenId(r.id)}
                                    className="hover:bg-background/50 transition-all duration-200 cursor-pointer"
                                >
                                    <td className="px-6 py-4 font-bold text-foreground">{r.resourceName}</td>
                                    <td className="px-6 py-4">
                                        <span className="px-2 py-1 text-[10px] font-bold uppercase tracking-widest bg-background rounded-md">
                                            {t(`type${r.resourceType}`)}
                                        </span>
                                    </td>
                                    <td className="px-6 py-4 text-muted font-medium">{r.renterName ?? "—"}</td>
                                    <td className="px-6 py-4 text-muted text-xs">{r.unitNumber ?? "—"}</td>
                                    <td className="px-6 py-4 text-muted text-xs tabular-nums">
                                        {r.preferredDate ? new Date(r.preferredDate).toLocaleDateString() : "—"}
                                    </td>
                                    <td className="px-6 py-4 text-muted text-xs tabular-nums">
                                        {new Date(r.createdAt).toLocaleDateString()}
                                    </td>
                                    <td className="px-6 py-4">
                                        <span className={cn(
                                            "inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold",
                                            BOOKING_STATUS_CLASSES[r.status]
                                        )}>
                                            {t(`status${r.status}`)}
                                        </span>
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
                {loading && (
                    <div className="text-center py-12 text-muted font-medium text-xs">{t("loading")}</div>
                )}
                {!loading && rows.length === 0 && !error && (
                    <div className="text-center py-12 text-muted font-medium text-xs">{t("noBookings")}</div>
                )}
                {!loading && totalElements > PAGE_SIZE && (
                    <div className="px-6 pb-4">
                        <Pagination
                            currentPage={page + 1}
                            totalItems={totalElements}
                            itemsPerPage={PAGE_SIZE}
                            onPageChange={(p) => load(p - 1)}
                        />
                    </div>
                )}
            </div>

            {openId && (
                <BookingDetailDrawer
                    bookingId={openId}
                    onClose={() => setOpenId(null)}
                    onChanged={() => load(page)}
                />
            )}
        </div>
    );
}
```

- [ ] **Step 3: Add the sidebar nav entry in `web/src/components/ui/MvpSidebar.tsx` (3 hunks)**

Hunk A — replace:
```tsx
    CalendarDays,
    ScanLine,
} from 'lucide-react';
```
with:
```tsx
    CalendarDays,
    ScanLine,
    CalendarCheck,
} from 'lucide-react';
```

Hunk B — replace:
```tsx
    const tGatePass = useTranslations("GatePass");
```
with:
```tsx
    const tGatePass = useTranslations("GatePass");
    const tBookings = useTranslations("Bookings");
```

Hunk C — replace:
```tsx
                ...(hasPermission(userRole, 'canViewGatePassReport')
                    ? [{ name: tGatePass("navLabel"), href: "/dashboard/gatepass", icon: ScanLine, tourId: 'sidebar-gatepass' }]
                    : []),
```
with:
```tsx
                ...(hasPermission(userRole, 'canViewGatePassReport')
                    ? [{ name: tGatePass("navLabel"), href: "/dashboard/gatepass", icon: ScanLine, tourId: 'sidebar-gatepass' }]
                    : []),
                ...(hasPermission(userRole, 'canManageFacilities')
                    ? [{ name: tBookings("navLabel"), href: "/dashboard/bookings", icon: CalendarCheck, tourId: 'sidebar-bookings' }]
                    : []),
```

- [ ] **Step 4: Verify**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && npx tsc --noEmit
```
Expected output: nothing, exit 0.

Manual checks:
- TENANT_ADMIN, PROPERTY_MANAGER, and SUPER_ADMIN see a "Bookings" sidebar item (calendar-check icon); a RENTER session does not (renter nav is a different item list entirely).
- `/en/dashboard/bookings` defaults to the Pending filter; after a renter submits a request (Task W6 or curl), it appears oldest-first.
- Row click opens the drawer from the end side; approving with a note flips the row to Approved and shows the note on re-open; other applicants for the same resource are listed with name, unit and status.
- Approving a second PENDING request for an already-held spot shows the spot-conflict message (409) and the request stays PENDING.
- Release appears only for APPROVED parking rows, not amenities.
- `/ar/dashboard/bookings`: drawer slides from the left (logical end in RTL), all labels Arabic.

- [ ] **Step 5: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add "web/src/app/[locale]/dashboard/bookings/page.tsx" "web/src/app/[locale]/dashboard/bookings/_components/BookingDetailDrawer.tsx" web/src/components/ui/MvpSidebar.tsx && git commit -m "feat(web): bookings inbox with detail drawer, decisions, and sidebar nav" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
Expected output: `3 files changed`.

---

### Task W6: Renter portal — facilities page + home entry card

**Files:**
- Create: `web/src/app/[locale]/dashboard/renter-portal/facilities/page.tsx`
- Modify: `web/src/app/[locale]/dashboard/renter-portal/page.tsx` (lucide import line 5, hooks ~line 56, entry card before the leases list ~line 341)

- [ ] **Step 1: Create `web/src/app/[locale]/dashboard/renter-portal/facilities/page.tsx`**

Full file content:

```tsx
"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations, useLocale } from "next-intl";
import { ArrowLeft, Dumbbell, Car, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { Link } from "@/i18n/routing";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import {
    fetchMyFacilities,
    fetchMyBookings,
    createBooking,
    cancelBooking,
    releaseBooking,
    ApiError,
} from "@/lib/api/facilities";
import type {
    MyFacilitiesDTO,
    BookingRequestDTO,
    BookingRequestStatus,
    BookingResourceType,
} from "@/types/facility";

const STATUS_CLASSES: Record<BookingRequestStatus, string> = {
    PENDING: "bg-warning/10 text-warning border border-warning/20",
    APPROVED: "bg-success/10 text-success border border-success/20",
    REJECTED: "bg-error/10 text-error border border-error/20",
    CANCELLED: "bg-input text-muted border border-border",
    RELEASED: "bg-info/10 text-info border border-info/20",
};

type ActiveLease = {
    id: string;
    unitId: string;
    unitIdentifier: string;
    propertyName: string;
    status: string;
};

type RequestTarget = {
    resourceType: BookingResourceType;
    resourceId: string;
    name: string;
};

type PendingAction = { kind: "cancel" | "release"; booking: BookingRequestDTO };

export default function RenterFacilitiesPage() {
    const t = useTranslations("Facilities");
    const tB = useTranslations("Bookings");
    const locale = useLocale();

    const [facilities, setFacilities] = useState<MyFacilitiesDTO | null>(null);
    const [bookings, setBookings] = useState<BookingRequestDTO[]>([]);
    const [activeLeases, setActiveLeases] = useState<ActiveLease[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    // Request dialog state
    const [target, setTarget] = useState<RequestTarget | null>(null);
    const [unitId, setUnitId] = useState("");
    const [preferredDate, setPreferredDate] = useState("");
    const [note, setNote] = useState("");
    const [submitting, setSubmitting] = useState(false);
    const [dialogError, setDialogError] = useState<string | null>(null);

    // Cancel / release confirmation
    const [pendingAction, setPendingAction] = useState<PendingAction | null>(null);
    const [actionLoading, setActionLoading] = useState(false);

    const loadFacilities = useCallback(async () => {
        setFacilities(await fetchMyFacilities());
    }, []);

    const loadBookings = useCallback(async () => {
        setBookings(await fetchMyBookings());
    }, []);

    const loadLeases = useCallback(async () => {
        const res = await fetch("/api/proxy/v1/leases/my-leases");
        if (res.ok) {
            const all: ActiveLease[] = await res.json();
            setActiveLeases(all.filter(l => l.status === "ACTIVE"));
        }
    }, []);

    useEffect(() => {
        (async () => {
            setLoading(true);
            setError(null);
            try {
                await Promise.all([loadFacilities(), loadBookings(), loadLeases()]);
            } catch {
                setError(t("loadError"));
            } finally {
                setLoading(false);
            }
        })();
    }, [loadFacilities, loadBookings, loadLeases, t]);

    const openRequest = (rt: RequestTarget) => {
        setTarget(rt);
        setPreferredDate("");
        setNote("");
        setDialogError(null);
        setUnitId(activeLeases[0]?.unitId ?? "");
    };

    const submitRequest = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!target || !unitId) return;
        setSubmitting(true);
        setDialogError(null);
        try {
            await createBooking({
                resourceType: target.resourceType,
                resourceId: target.resourceId,
                unitId,
                preferredDate: preferredDate || undefined,
                note: note || undefined,
            });
            setTarget(null);
            await Promise.all([loadFacilities(), loadBookings()]);
        } catch (err) {
            if (err instanceof ApiError && err.status === 409) setDialogError(t("spotTaken"));
            else setDialogError(t("requestError"));
        } finally {
            setSubmitting(false);
        }
    };

    const runPendingAction = async () => {
        if (!pendingAction) return;
        setActionLoading(true);
        try {
            if (pendingAction.kind === "cancel") await cancelBooking(pendingAction.booking.id);
            else await releaseBooking(pendingAction.booking.id);
            setPendingAction(null);
            await Promise.all([loadFacilities(), loadBookings()]);
        } catch {
            setPendingAction(null);
            setError(t("requestError"));
        } finally {
            setActionLoading(false);
        }
    };

    const amenityName = (nameEn: string, nameAr: string | null) =>
        locale === "ar" && nameAr ? nameAr : nameEn;

    if (loading) {
        return (
            <div className="p-8 max-w-5xl mx-auto flex flex-col items-center justify-center h-64 gap-3">
                <Loader2 size={24} className="animate-spin text-muted" />
                <p className="text-xs text-muted">{t("loading")}</p>
            </div>
        );
    }

    const amenities = facilities?.amenities ?? [];
    const parkingSpots = facilities?.parkingSpots ?? [];
    const noActiveLease = activeLeases.length === 0;

    return (
        <div className="p-8 max-w-5xl mx-auto">
            <Link
                href="/dashboard/renter-portal"
                className="flex items-center gap-2 text-xs font-bold text-muted hover:text-foreground mb-6 transition-colors cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded w-fit"
            >
                <ArrowLeft size={14} className="rtl:rotate-180" /> {t("backToPortal")}
            </Link>

            <div className="mb-8">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">{t("renterTitle")}</h1>
                <p className="text-xs text-muted font-medium">{t("renterSubtitle")}</p>
            </div>

            {error && (
                <div className="mb-4 px-4 py-3 rounded-xl bg-error/10 text-error border border-error/20 text-xs font-semibold">
                    {error}
                </div>
            )}

            {noActiveLease && (
                <div className="mb-6 px-4 py-3 rounded-xl bg-warning/10 text-warning border border-warning/20 text-xs font-semibold">
                    {t("noActiveLease")}
                </div>
            )}

            {amenities.length === 0 && parkingSpots.length === 0 && (
                <div className="text-center py-16 bg-background border border-dashed border-border rounded-xl text-xs font-medium text-muted">
                    {t("noFacilities")}
                </div>
            )}

            {/* ── Amenities section ─────────────────────────────────── */}
            {amenities.length > 0 && (
                <div className="mb-10">
                    <div className="flex items-center gap-2 mb-4">
                        <Dumbbell size={16} className="text-primary" />
                        <h2 className="text-sm font-bold text-foreground tracking-tight">{t("amenitiesSection")}</h2>
                    </div>
                    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                        {amenities.map(a => (
                            <div key={a.id} className="bg-surface rounded-xl p-4 border border-border hover:shadow-md transition-all duration-200 flex flex-col gap-2">
                                <div className="flex items-start justify-between gap-2">
                                    <p className="text-sm font-bold text-foreground">{amenityName(a.nameEn, a.nameAr)}</p>
                                    {!a.bookable && (
                                        <span className="px-2 py-0.5 text-[9px] font-bold uppercase tracking-widest bg-input text-muted rounded-md shrink-0">
                                            {t("notBookable")}
                                        </span>
                                    )}
                                </div>
                                {a.propertyName && <p className="text-[10px] font-bold text-muted">{a.propertyName}</p>}
                                {a.description && <p className="text-xs text-muted line-clamp-2">{a.description}</p>}
                                {a.bookable && (
                                    <p className="text-[10px] text-muted">{t("pendingHint", { count: a.pendingCount })}</p>
                                )}
                                {a.bookable && (
                                    <button
                                        onClick={() => openRequest({ resourceType: "AMENITY", resourceId: a.id, name: amenityName(a.nameEn, a.nameAr) })}
                                        disabled={noActiveLease}
                                        className="mt-auto self-start px-4 py-2 bg-primary/10 text-primary hover:bg-primary/20 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30 disabled:opacity-50 disabled:cursor-not-allowed"
                                    >
                                        {t("request")}
                                    </button>
                                )}
                            </div>
                        ))}
                    </div>
                </div>
            )}

            {/* ── Parking section ───────────────────────────────────── */}
            {parkingSpots.length > 0 && (
                <div className="mb-10">
                    <div className="flex items-center gap-2 mb-4">
                        <Car size={16} className="text-primary" />
                        <h2 className="text-sm font-bold text-foreground tracking-tight">{t("parkingSection")}</h2>
                    </div>
                    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                        {parkingSpots.map(s => (
                            <div key={s.id} className="bg-surface rounded-xl p-4 border border-border hover:shadow-md transition-all duration-200 flex flex-col gap-2">
                                <div className="flex items-start justify-between gap-2">
                                    <p className="text-sm font-bold text-foreground" dir="ltr">{s.spotNumber}</p>
                                    <span className={cn(
                                        "px-2 py-0.5 text-[9px] font-bold uppercase tracking-widest rounded-md shrink-0",
                                        s.held ? "bg-warning/10 text-warning" : "bg-success/10 text-success"
                                    )}>
                                        {s.held ? t("held") : t("available")}
                                    </span>
                                </div>
                                {s.propertyName && <p className="text-[10px] font-bold text-muted">{s.propertyName}</p>}
                                <p className="text-xs text-muted">
                                    {s.level ? `${t("level")}: ${s.level} · ` : ""}
                                    {s.covered ? t("covered") : t("uncovered")}
                                </p>
                                <p className="text-[10px] text-muted">{t("pendingHint", { count: s.pendingCount })}</p>
                                <button
                                    onClick={() => openRequest({ resourceType: "PARKING_SPOT", resourceId: s.id, name: s.spotNumber })}
                                    disabled={noActiveLease || s.held}
                                    className="mt-auto self-start px-4 py-2 bg-primary/10 text-primary hover:bg-primary/20 rounded-xl text-xs font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-primary/30 disabled:opacity-50 disabled:cursor-not-allowed"
                                >
                                    {t("request")}
                                </button>
                            </div>
                        ))}
                    </div>
                </div>
            )}

            {/* ── My Requests ───────────────────────────────────────── */}
            <div className="mt-10">
                <h2 className="text-sm font-bold text-foreground tracking-tight mb-4">{t("myRequests")}</h2>
                {bookings.length === 0 ? (
                    <div className="text-center py-12 bg-background border border-dashed border-border rounded-xl text-xs font-medium text-muted">
                        {t("noRequests")}
                    </div>
                ) : (
                    <div className="bg-surface rounded-xl border border-border overflow-hidden">
                        <div className="overflow-x-auto">
                            <table className="w-full text-sm">
                                <thead className="bg-background text-muted text-xs font-semibold uppercase tracking-[0.15em]">
                                    <tr>
                                        <th className="px-6 py-4 text-start">{tB("colResource")}</th>
                                        <th className="px-6 py-4 text-start">{tB("colType")}</th>
                                        <th className="px-6 py-4 text-start">{tB("colRequested")}</th>
                                        <th className="px-6 py-4 text-start">{tB("colPreferred")}</th>
                                        <th className="px-6 py-4 text-start">{tB("colStatus")}</th>
                                        <th className="px-6 py-4" />
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border">
                                    {bookings.map(b => (
                                        <tr key={b.id} className="hover:bg-background/50 transition-all duration-200">
                                            <td className="px-6 py-4">
                                                <p className="font-bold text-foreground">{b.resourceName}</p>
                                                {b.adminNote && (
                                                    <p className="text-[10px] text-muted italic mt-0.5">
                                                        {tB("adminNote")}: {b.adminNote}
                                                    </p>
                                                )}
                                            </td>
                                            <td className="px-6 py-4">
                                                <span className="px-2 py-1 text-[10px] font-bold uppercase tracking-widest bg-background rounded-md">
                                                    {tB(`type${b.resourceType}`)}
                                                </span>
                                            </td>
                                            <td className="px-6 py-4 text-muted text-xs tabular-nums">
                                                {new Date(b.createdAt).toLocaleDateString()}
                                            </td>
                                            <td className="px-6 py-4 text-muted text-xs tabular-nums">
                                                {b.preferredDate ? new Date(b.preferredDate).toLocaleDateString() : "—"}
                                            </td>
                                            <td className="px-6 py-4">
                                                <span className={cn(
                                                    "inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold",
                                                    STATUS_CLASSES[b.status]
                                                )}>
                                                    {tB(`status${b.status}`)}
                                                </span>
                                            </td>
                                            <td className="px-6 py-4">
                                                <div className="flex justify-end">
                                                    {b.status === "PENDING" && (
                                                        <button
                                                            onClick={() => setPendingAction({ kind: "cancel", booking: b })}
                                                            className="px-3 py-1.5 bg-error/10 text-error hover:bg-error/20 rounded-lg text-[11px] font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-error/30"
                                                        >
                                                            {t("cancelRequest")}
                                                        </button>
                                                    )}
                                                    {b.status === "APPROVED" && b.resourceType === "PARKING_SPOT" && (
                                                        <button
                                                            onClick={() => setPendingAction({ kind: "release", booking: b })}
                                                            className="px-3 py-1.5 bg-info/10 text-info hover:bg-info/20 rounded-lg text-[11px] font-bold transition-all duration-200 cursor-pointer focus:outline-none focus:ring-2 focus:ring-info/30"
                                                        >
                                                            {t("releaseSpot")}
                                                        </button>
                                                    )}
                                                </div>
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                    </div>
                )}
            </div>

            {/* ── Request dialog ────────────────────────────────────── */}
            {target && (
                <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={() => setTarget(null)}>
                    <div className="bg-surface rounded-xl border border-border shadow-xl w-full max-w-md mx-4 p-6" onClick={(e) => e.stopPropagation()}>
                        <h3 className="text-lg font-bold text-foreground mb-4">
                            {t("requestTitle", { name: target.name })}
                        </h3>
                        <form onSubmit={submitRequest} className="space-y-4">
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("unit")}</label>
                                <select
                                    required
                                    value={unitId}
                                    onChange={e => setUnitId(e.target.value)}
                                    className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                >
                                    {activeLeases.map(l => (
                                        <option key={l.id} value={l.unitId}>
                                            {l.unitIdentifier} — {l.propertyName}
                                        </option>
                                    ))}
                                </select>
                            </div>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("preferredDate")}</label>
                                <input
                                    type="date"
                                    value={preferredDate}
                                    onChange={e => setPreferredDate(e.target.value)}
                                    className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                />
                            </div>
                            <div>
                                <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("note")}</label>
                                <textarea
                                    rows={2}
                                    placeholder={t("notePlaceholder")}
                                    value={note}
                                    onChange={e => setNote(e.target.value)}
                                    className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 resize-none"
                                />
                            </div>
                            {dialogError && (
                                <p className="text-xs font-semibold text-error">{dialogError}</p>
                            )}
                            <div className="flex justify-end gap-2 pt-2">
                                <button
                                    type="button"
                                    onClick={() => setTarget(null)}
                                    className="px-4 py-2 text-xs font-bold text-muted cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg transition-all duration-200"
                                >
                                    {t("cancel")}
                                </button>
                                <button
                                    type="submit"
                                    disabled={submitting || !unitId}
                                    className="px-4 py-2 bg-primary text-white rounded-lg text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
                                >
                                    {submitting ? t("submitting") : t("submitRequest")}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            <ConfirmDialog
                isOpen={pendingAction !== null}
                onClose={() => setPendingAction(null)}
                onConfirm={runPendingAction}
                title={pendingAction?.kind === "release" ? t("releaseSpot") : t("cancelRequest")}
                description={pendingAction?.kind === "release" ? t("releaseSpotConfirm") : t("cancelRequestConfirm")}
                confirmText={pendingAction?.kind === "release" ? t("releaseSpot") : t("cancelRequest")}
                isDestructive={pendingAction?.kind === "cancel"}
                isLoading={actionLoading}
            />
        </div>
    );
}
```

- [ ] **Step 2: Add the entry card to `web/src/app/[locale]/dashboard/renter-portal/page.tsx` (3 hunks)**

Hunk A — replace the lucide import (line 5):

```tsx
import { FileText, Calendar, DollarSign, Home, CheckCircle, XCircle, Download, Clock, AlertCircle, CreditCard, CalendarDays, Plus, Eye, ChevronLeft, ChevronRight, ChevronDown } from "lucide-react";
```
with:
```tsx
import { FileText, Calendar, DollarSign, Home, CheckCircle, XCircle, Download, Clock, AlertCircle, CreditCard, CalendarDays, Plus, Eye, ChevronLeft, ChevronRight, ChevronDown, Dumbbell } from "lucide-react";
```

Hunk B — replace:
```tsx
    const tPayments = useTranslations("OnlinePayments");
```
with:
```tsx
    const tPayments = useTranslations("OnlinePayments");
    const tFacilities = useTranslations("Facilities");
```

Hunk C — replace:
```tsx
            <div className="space-y-6">
                {leases.map(lease => (
```
with:
```tsx
            {/* Facilities & Parking entry card */}
            <Link href="/dashboard/renter-portal/facilities">
                <div className="bg-surface rounded-xl border border-border hover:shadow-md transition-all duration-200 mb-6 cursor-pointer p-5 flex items-center justify-between">
                    <div className="flex items-center gap-3">
                        <div className="w-10 h-10 bg-primary/10 rounded-xl flex items-center justify-center text-primary border border-primary/20">
                            <Dumbbell size={18} />
                        </div>
                        <div>
                            <p className="text-sm font-bold text-foreground">{tFacilities("entryCardTitle")}</p>
                            <p className="text-[10px] text-muted">{tFacilities("entryCardDesc")}</p>
                        </div>
                    </div>
                    <ChevronRight size={16} className="text-primary rtl:rotate-180" />
                </div>
            </Link>

            <div className="space-y-6">
                {leases.map(lease => (
```

- [ ] **Step 3: Verify**

```bash
cd /Users/kunalsharma/datagami/rentaxis/web && npx tsc --noEmit
```
Expected output: nothing, exit 0.

Manual checks (renter session, backend seeded with an active lease + facilities from W3/W4):
- `/en/dashboard/renter-portal` shows the "Facilities & Parking" card between the payment card and the lease list; clicking it opens `/en/dashboard/renter-portal/facilities`.
- Only facilities visible to the renter's unit appear (tower-scoped ones hidden unless the unit's building is in scope); a non-bookable amenity is listed with a "Not bookable" badge and no Request button.
- Held spots show a "Held" badge with a disabled Request button; requesting a free spot then re-requesting it shows the same PENDING row (idempotent — no duplicate in My Requests).
- Request dialog: unit select lists active leases only; submitting refreshes both the pending-count hints and My Requests (oldest-first).
- Cancel appears only on PENDING rows; Release only on APPROVED parking rows; both go through ConfirmDialog and refresh the lists; after admin rejection with a note, the note renders under the resource name.
- `/ar/...`: back-arrow and chevron flip (`rtl:rotate-180`), all strings Arabic, spot numbers stay LTR.

- [ ] **Step 4: Commit**

```bash
cd /Users/kunalsharma/datagami/rentaxis && git add "web/src/app/[locale]/dashboard/renter-portal/facilities/page.tsx" "web/src/app/[locale]/dashboard/renter-portal/page.tsx" && git commit -m "feat(web): renter facilities page with request flow and my-requests list" -m "Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
Expected output: `2 files changed`.


---

## Mobile (Flutter)

**Conventions verified against the repo:** Dio services return raw `Map<String, dynamic>` (`gate_pass_service.dart`), singleton service providers live in `rentaxis_core/lib/providers/auth_provider.dart`, screens registered in GoRouter are parameterless and self-fetching (router rebuilds on `authProvider` and would discard `extra`), every screen carries a private bilingual `_L` class (`context.isAr`), styling via `context.miftah` tokens + `GoldButton`/`StatusBadge`/`EmptyState`/`ErrorState`/`ShimmerLoading`/`AnimatedListItem`, list padding via `AppInsets.bottomNav(context)`.

**Build/verify commands:** `/Users/kunalsharma/datagami/rentaxis/mobile/melos.yaml` defines only a `bootstrap` command (no analyze/test scripts), so the repo's verification commands are per-package `flutter analyze` and `flutter test`. If package resolution ever fails, run `cd /Users/kunalsharma/datagami/rentaxis/mobile && dart run melos bootstrap` once first.

**Test posture:** unit/widget test harnesses exist (`rentaxis_core/test/services/`, `apps/*/test/` with `support/` fakes), so the service layer and the two decision-critical screens are built TDD. Inventory/list screens are covered by `flutter analyze` + the E2E screen-tour harnesses (Task M8), matching the approved spec's Testing section ("Mobile: add new screens to the existing screen-tour E2E harnesses").

---

### Task M1: `rentaxis_core` — FacilityApiService, barrel export, provider

**Files:**
- Test: `mobile/packages/rentaxis_core/test/services/facility_service_test.dart` (new)
- Create: `mobile/packages/rentaxis_core/lib/api/services/facility_service.dart`
- Modify: `mobile/packages/rentaxis_core/lib/rentaxis_core.dart` (line 32, services block)
- Modify: `mobile/packages/rentaxis_core/lib/providers/auth_provider.dart` (imports at top; providers after line 30)

- [ ] **Step 1: Write the failing service test** — create `mobile/packages/rentaxis_core/test/services/facility_service_test.dart` (stub-adapter pattern copied from `gate_pass_service_test.dart` in the same directory):

```dart
import 'dart:convert';
import 'dart:typed_data';

import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/api/services/facility_service.dart';

void main() {
  group('FacilityApiService admin inbox', () {
    test('getBookings sends only the filters that are set', () async {
      final adapter = _StubAdapter(responseBody: {'content': []});
      final service = FacilityApiService(_dio(adapter));

      await service.getBookings(status: 'PENDING');

      final request = adapter.captured.single;
      expect(request.path, '/v1/bookings');
      expect(request.method, 'GET');
      expect(request.queryParameters, {
        'status': 'PENDING',
        'page': 0,
        'size': 20,
      });
    });

    test('approveBooking posts a trimmed adminNote and omits a blank one',
        () async {
      final adapter = _StubAdapter(
        responseBody: {'id': 'bk-1', 'status': 'APPROVED'},
      );
      final service = FacilityApiService(_dio(adapter));

      await service.approveBooking('bk-1', adminNote: '  Enjoy!  ');
      await service.approveBooking('bk-1', adminNote: '   ');
      await service.rejectBooking('bk-1');

      expect(adapter.captured[0].path, '/v1/bookings/bk-1/approve');
      expect(adapter.captured[0].data, {'adminNote': 'Enjoy!'});
      expect(adapter.captured[1].data, isEmpty);
      expect(adapter.captured[2].path, '/v1/bookings/bk-1/reject');
      expect(adapter.captured[2].data, isEmpty);
    });
  });

  group('FacilityApiService inventory', () {
    test('bulkCreateParkingSpots posts to the bulk endpoint and returns a list',
        () async {
      final adapter = _StubAdapter(responseBody: [
        {'id': 's1', 'spotNumber': 'B1-01'},
        {'id': 's2', 'spotNumber': 'B1-02'},
      ]);
      final service = FacilityApiService(_dio(adapter));

      final created = await service.bulkCreateParkingSpots({
        'propertyId': 'prop-1',
        'spotNumbers': ['B1-01', 'B1-02'],
        'covered': true,
        'buildingIds': <String>[],
      });

      final request = adapter.captured.single;
      expect(request.path, '/v1/parking-spots/bulk');
      expect(request.method, 'POST');
      expect(created, hasLength(2));
    });
  });

  group('FacilityApiService renter', () {
    test('createBooking posts the body verbatim', () async {
      final adapter = _StubAdapter(
        responseBody: {'id': 'bk-9', 'status': 'PENDING'},
      );
      final service = FacilityApiService(_dio(adapter));

      await service.createBooking({
        'resourceType': 'PARKING_SPOT',
        'resourceId': 'spot-1',
        'unitId': 'unit-1',
        'preferredDate': '2026-08-20',
        'note': 'Second car',
      });

      final request = adapter.captured.single;
      expect(request.path, '/v1/bookings');
      expect(request.data, {
        'resourceType': 'PARKING_SPOT',
        'resourceId': 'spot-1',
        'unitId': 'unit-1',
        'preferredDate': '2026-08-20',
        'note': 'Second car',
      });
    });

    test('myFacilities hits the renter endpoint', () async {
      final adapter = _StubAdapter(
        responseBody: {'amenities': [], 'parkingSpots': []},
      );
      final service = FacilityApiService(_dio(adapter));

      await service.myFacilities();
      expect(adapter.captured.single.path, '/v1/facilities/my');
    });
  });
}

Dio _dio(_StubAdapter adapter) {
  final dio = Dio(BaseOptions(baseUrl: 'https://api.example/api'));
  dio.httpClientAdapter = adapter;
  return dio;
}

class _StubAdapter implements HttpClientAdapter {
  _StubAdapter({required this.responseBody});

  final Object responseBody;
  final List<RequestOptions> captured = [];

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<Uint8List>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    captured.add(options);
    return ResponseBody.fromBytes(
      utf8.encode(jsonEncode(responseBody)),
      200,
      headers: {
        'content-type': ['application/json'],
      },
    );
  }

  @override
  void close({bool force = false}) {}
}
```

- [ ] **Step 2: Run the test — expect failure** (the service file does not exist yet):
```bash
cd /Users/kunalsharma/datagami/rentaxis/mobile/packages/rentaxis_core && flutter test test/services/facility_service_test.dart
```
Expect a compile error: `Error: Couldn't resolve the package ... facility_service.dart` (or "Target of URI doesn't exist").

- [ ] **Step 3: Create the service** — `mobile/packages/rentaxis_core/lib/api/services/facility_service.dart`:

```dart
import 'package:dio/dio.dart';

/// Thin wrapper over the amenities & parking booking endpoints
/// (`/v1/amenities`, `/v1/parking-spots`, `/v1/bookings`, `/v1/facilities/my`).
///
/// Raw `Map<String, dynamic>` rows, no models — house convention. Two shapes
/// matter to callers:
///  * Admin inventory/inbox reads are **Spring pages**: `{content: [...],
///    totalElements, ...}` — callers unwrap `content`. Ordering is the
///    server's `createdAt` ASC; do not re-sort.
///  * `POST /v1/bookings` is **idempotent for duplicates**: posting while the
///    caller already has a PENDING request for the same resource returns that
///    existing request, not an error. A 409 means a parking spot is already
///    APPROVED for someone else; a 400 means the amenity is not bookable.
class FacilityApiService {
  final Dio _dio;
  FacilityApiService(this._dio);

  // ─── Admin: amenity inventory ──────────────────────────────────────────

  /// GET /v1/amenities — paged AmenityDTO for one property.
  Future<Map<String, dynamic>> getAmenities({
    required String propertyId,
    int page = 0,
    int size = 20,
  }) async {
    final response = await _dio.get(
      '/v1/amenities',
      queryParameters: {'propertyId': propertyId, 'page': page, 'size': size},
    );
    return response.data as Map<String, dynamic>;
  }

  /// POST /v1/amenities — AmenityCreateRequest: `propertyId`, `nameEn`,
  /// `nameAr?`, `description?`, `bookable?`, `buildingIds?`
  /// (empty/absent = visible to all towers).
  Future<Map<String, dynamic>> createAmenity(Map<String, dynamic> body) async {
    final response = await _dio.post('/v1/amenities', data: body);
    return response.data as Map<String, dynamic>;
  }

  /// PUT /v1/amenities/{id} — patch semantics: absent/null = unchanged; a
  /// non-null `buildingIds` REPLACES the scope set (send `[]` to clear).
  Future<Map<String, dynamic>> updateAmenity(
    String id,
    Map<String, dynamic> body,
  ) async {
    final response = await _dio.put('/v1/amenities/$id', data: body);
    return response.data as Map<String, dynamic>;
  }

  /// DELETE /v1/amenities/{id} — soft-deactivate (`active=false`), 204.
  /// Existing booking requests survive; the amenity just leaves renter view.
  Future<void> deactivateAmenity(String id) async {
    await _dio.delete('/v1/amenities/$id');
  }

  // ─── Admin: parking inventory ──────────────────────────────────────────

  Future<Map<String, dynamic>> getParkingSpots({
    required String propertyId,
    int page = 0,
    int size = 20,
  }) async {
    final response = await _dio.get(
      '/v1/parking-spots',
      queryParameters: {'propertyId': propertyId, 'page': page, 'size': size},
    );
    return response.data as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> createParkingSpot(
    Map<String, dynamic> body,
  ) async {
    final response = await _dio.post('/v1/parking-spots', data: body);
    return response.data as Map<String, dynamic>;
  }

  /// POST /v1/parking-spots/bulk — ParkingSpotBulkCreateRequest:
  /// `{propertyId, spotNumbers: [...], level?, covered?, buildingIds?}`.
  /// Returns the created ParkingSpotDTO list.
  Future<List<dynamic>> bulkCreateParkingSpots(
    Map<String, dynamic> body,
  ) async {
    final response = await _dio.post('/v1/parking-spots/bulk', data: body);
    return response.data as List<dynamic>;
  }

  Future<Map<String, dynamic>> updateParkingSpot(
    String id,
    Map<String, dynamic> body,
  ) async {
    final response = await _dio.put('/v1/parking-spots/$id', data: body);
    return response.data as Map<String, dynamic>;
  }

  Future<void> deactivateParkingSpot(String id) async {
    await _dio.delete('/v1/parking-spots/$id');
  }

  // ─── Admin: booking inbox ──────────────────────────────────────────────

  /// GET /v1/bookings — paged BookingRequestDTO. All filters optional;
  /// omitting `propertyId` reads the whole tenant.
  Future<Map<String, dynamic>> getBookings({
    String? propertyId,
    String? status,
    String? resourceType,
    int page = 0,
    int size = 20,
  }) async {
    final response = await _dio.get(
      '/v1/bookings',
      queryParameters: {
        if (propertyId != null) 'propertyId': propertyId,
        if (status != null) 'status': status,
        if (resourceType != null) 'resourceType': resourceType,
        'page': page,
        'size': size,
      },
    );
    return response.data as Map<String, dynamic>;
  }

  /// GET /v1/bookings/{id} — BookingDetailDTO `{request: {...},
  /// otherRequests: [...]}` where `otherRequests` is every other
  /// PENDING/APPROVED request for the same resource.
  Future<Map<String, dynamic>> getBooking(String id) async {
    final response = await _dio.get('/v1/bookings/$id');
    return response.data as Map<String, dynamic>;
  }

  /// POST /v1/bookings/{id}/approve. PENDING only (else 400). For a parking
  /// spot already APPROVED to someone else the server answers 409 — surface
  /// that as "spot already held", not a retryable failure.
  Future<Map<String, dynamic>> approveBooking(
    String id, {
    String? adminNote,
  }) async {
    final response = await _dio.post(
      '/v1/bookings/$id/approve',
      data: {
        if (_trimToNull(adminNote) != null) 'adminNote': adminNote!.trim(),
      },
    );
    return response.data as Map<String, dynamic>;
  }

  Future<Map<String, dynamic>> rejectBooking(
    String id, {
    String? adminNote,
  }) async {
    final response = await _dio.post(
      '/v1/bookings/$id/reject',
      data: {
        if (_trimToNull(adminNote) != null) 'adminNote': adminNote!.trim(),
      },
    );
    return response.data as Map<String, dynamic>;
  }

  /// POST /v1/bookings/{id}/release — APPROVED parking only. Shared endpoint:
  /// an admin releases any spot, a renter only their own (server branches on
  /// role, same as GatePassController.isGuard()).
  Future<Map<String, dynamic>> releaseBooking(String id) async {
    final response = await _dio.post('/v1/bookings/$id/release');
    return response.data as Map<String, dynamic>;
  }

  // ─── Renter ────────────────────────────────────────────────────────────

  /// GET /v1/facilities/my — MyFacilitiesDTO `{amenities: [...],
  /// parkingSpots: [...]}` visible to the caller's active-lease unit(s).
  ///
  /// Non-bookable amenities are still listed (`bookable=false`) — show them,
  /// but offer no Request button; the server 400s the attempt. Renters get
  /// `pendingCount` numbers only, never other applicants' identities — do not
  /// expect renter fields on these rows; they are absent by design.
  Future<Map<String, dynamic>> myFacilities() async {
    final response = await _dio.get('/v1/facilities/my');
    return response.data as Map<String, dynamic>;
  }

  /// POST /v1/bookings — BookingCreateRequest `{resourceType, resourceId,
  /// unitId, preferredDate?, note?}`. `preferredDate` is a LocalDate: send
  /// `yyyy-MM-dd`, never an instant. The property is derived server-side from
  /// the resource; the unit must be on one of the caller's ACTIVE leases —
  /// 404 otherwise (deliberately not 403, so unit ids cannot be probed).
  Future<Map<String, dynamic>> createBooking(Map<String, dynamic> body) async {
    final response = await _dio.post('/v1/bookings', data: body);
    return response.data as Map<String, dynamic>;
  }

  /// GET /v1/bookings/my — the caller's own requests, all statuses,
  /// createdAt ASC (server-ordered; do not re-sort).
  Future<List<dynamic>> myBookings() async {
    final response = await _dio.get('/v1/bookings/my');
    return response.data as List<dynamic>;
  }

  /// POST /v1/bookings/{id}/cancel — own PENDING only.
  Future<Map<String, dynamic>> cancelBooking(String id) async {
    final response = await _dio.post('/v1/bookings/$id/cancel');
    return response.data as Map<String, dynamic>;
  }

  static String? _trimToNull(String? value) {
    if (value == null) return null;
    final trimmed = value.trim();
    return trimmed.isEmpty ? null : trimmed;
  }
}
```

- [ ] **Step 4: Barrel export** — in `mobile/packages/rentaxis_core/lib/rentaxis_core.dart`, edit:

```dart
// old
export 'api/services/gate_pass_service.dart';
// new
export 'api/services/gate_pass_service.dart';
export 'api/services/facility_service.dart';
```

- [ ] **Step 5: Singleton provider** — in `mobile/packages/rentaxis_core/lib/providers/auth_provider.dart`, two edits:

```dart
// old
import '../api/services/gate_pass_service.dart';
// new
import '../api/services/facility_service.dart';
import '../api/services/gate_pass_service.dart';
```

```dart
// old
/// Single shared LocationService (geolocator wrapper).
final locationServiceProvider = Provider<LocationService>(
  (_) => LocationService(),
);
// new
/// Single shared FacilityApiService — amenities, parking spots and booking
/// requests. Used by the manager and renter apps.
final facilityServiceProvider = Provider<FacilityApiService>((ref) {
  final client = ref.watch(apiClientProvider);
  return FacilityApiService(client.dio);
});

/// Single shared LocationService (geolocator wrapper).
final locationServiceProvider = Provider<LocationService>(
  (_) => LocationService(),
);
```

- [ ] **Step 6: Verify**:
```bash
cd /Users/kunalsharma/datagami/rentaxis/mobile/packages/rentaxis_core && flutter test test/services/facility_service_test.dart && flutter analyze
```
Expect: `All tests passed!` then `No issues found!`

- [ ] **Step 7: Commit**:
```bash
cd /Users/kunalsharma/datagami/rentaxis && git add mobile/packages/rentaxis_core && git commit -m "$(cat <<'EOF'
feat(core): FacilityApiService for amenities, parking spots and bookings

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task M2: Manager — facilities inventory screen (property picker, tabs, tower-scoped add/edit sheets, bulk parking, deactivate)

**Files:**
- Create: `mobile/apps/manager/lib/providers/facility_provider.dart`
- Create: `mobile/apps/manager/lib/screens/facilities/facilities_screen.dart`

- [ ] **Step 1: Manager facility providers** — create `mobile/apps/manager/lib/providers/facility_provider.dart`:

```dart
/// Facility (amenities & parking) state for the manager app.
///
/// All `autoDispose`: inventory and the booking inbox change under the manager
/// (renters file requests, other admins decide them), so re-entering a screen
/// re-reads the server instead of showing a kept-alive copy.
library;

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// App-local: core exports [BuildingService] but no provider for it
/// (same situation as PropertyService in gate_pass_provider.dart).
final buildingServiceProvider = Provider<BuildingService>((ref) {
  final client = ref.watch(apiClientProvider);
  return BuildingService(client.dio);
});

/// Towers of one property, for the scope multi-select chips.
final buildingsProvider = FutureProvider.autoDispose
    .family<List<Map<String, dynamic>>, String>((ref, propertyId) async {
  final service = ref.watch(buildingServiceProvider);
  final rows = await service.getBuildingsByProperty(propertyId);
  return _asRows(rows);
});

/// Amenity inventory of one property. The endpoint is paged; one large page
/// keeps the screen simple (a property has tens of facilities, not thousands).
final amenitiesProvider = FutureProvider.autoDispose
    .family<List<Map<String, dynamic>>, String>((ref, propertyId) async {
  final service = ref.watch(facilityServiceProvider);
  final page = await service.getAmenities(propertyId: propertyId, size: 200);
  return _asRows(page['content'] as List? ?? const []);
});

final parkingSpotsProvider = FutureProvider.autoDispose
    .family<List<Map<String, dynamic>>, String>((ref, propertyId) async {
  final service = ref.watch(facilityServiceProvider);
  final page =
      await service.getParkingSpots(propertyId: propertyId, size: 200);
  return _asRows(page['content'] as List? ?? const []);
});

/// Admin booking inbox filter. A record so equal filters resolve to the same
/// family member (records have value equality).
typedef BookingFilter = ({String? propertyId, String? status});

final bookingsProvider = FutureProvider.autoDispose
    .family<List<Map<String, dynamic>>, BookingFilter>((ref, filter) async {
  final service = ref.watch(facilityServiceProvider);
  final page = await service.getBookings(
    propertyId: filter.propertyId,
    status: filter.status,
    size: 200,
  );
  return _asRows(page['content'] as List? ?? const []);
});

/// One request + its competitors, re-fetched on open so the decision sheet
/// never acts on a row that went stale while the list sat on screen.
final bookingDetailProvider = FutureProvider.autoDispose
    .family<Map<String, dynamic>, String>((ref, id) async {
  final service = ref.watch(facilityServiceProvider);
  return service.getBooking(id);
});

List<Map<String, dynamic>> _asRows(List<dynamic> rows) =>
    rows.whereType<Map>().map((r) => Map<String, dynamic>.from(r)).toList();
```

- [ ] **Step 2: Facilities screen — strings, screen state, lists** — create `mobile/apps/manager/lib/screens/facilities/facilities_screen.dart` with this content (the file is completed in Step 3; this step writes the file up to and including `_TabToggle`, Step 3 appends the cards and sheets so the file only compiles after Step 3):

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../providers/facility_provider.dart';
import '../../providers/gate_pass_provider.dart' show propertiesProvider;

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'المرافق ومواقف السيارات' : 'Facilities';
  String get property => ar ? 'العقار' : 'Property';
  String get amenities => ar ? 'المرافق' : 'Amenities';
  String get parking => ar ? 'المواقف' : 'Parking';
  String get loadFailed =>
      ar ? 'فشل تحميل المرافق' : 'Failed to load facilities';
  String get propertiesLoadFailed =>
      ar ? 'فشل تحميل العقارات' : 'Failed to load properties';
  String get noProperties => ar ? 'لا توجد عقارات' : 'No properties';
  String get noAmenities => ar ? 'لا توجد مرافق بعد' : 'No amenities yet';
  String get noAmenitiesSub => ar
      ? 'أضف مسبحًا أو صالة رياضية أو قاعة مناسبات ليتمكن المستأجرون من طلب حجزها.'
      : 'Add a pool, gym or hall so renters can request it.';
  String get noSpots => ar ? 'لا توجد مواقف بعد' : 'No parking spots yet';
  String get noSpotsSub => ar
      ? 'أضف أرقام المواقف — يمكن إضافة عدة مواقف دفعة واحدة.'
      : 'Add numbered spots — bulk entry adds many at once.';
  String get addAmenity => ar ? 'إضافة مرفق' : 'Add amenity';
  String get editAmenity => ar ? 'تعديل المرفق' : 'Edit amenity';
  String get addParking => ar ? 'إضافة مواقف' : 'Add parking';
  String get editParking => ar ? 'تعديل الموقف' : 'Edit spot';
  String get nameEn => ar ? 'الاسم (إنجليزي)' : 'Name (English)';
  String get nameAr => ar ? 'الاسم (عربي)' : 'Name (Arabic)';
  String get nameRequired =>
      ar ? 'أدخل الاسم بالإنجليزية' : 'Enter the English name';
  String get description => ar ? 'الوصف (اختياري)' : 'Description (optional)';
  String get bookable => ar ? 'قابل للحجز' : 'Bookable';
  String get bookableSub => ar
      ? 'يمكن للمستأجرين إرسال طلبات حجز لهذا المرفق'
      : 'Renters can send booking requests for it';
  String get towers => ar ? 'الأبراج' : 'Towers';
  String get allTowers => ar ? 'كل الأبراج' : 'All towers';
  String get towersHint => ar
      ? 'بدون اختيار = ظاهر لكل الأبراج. الاختيار يقصر الظهور على الأبراج المحددة.'
      : 'None selected = visible to all towers. Selecting restricts visibility.';
  String get spotNumber => ar ? 'رقم الموقف' : 'Spot number';
  String get spotNumbers => ar ? 'أرقام المواقف' : 'Spot numbers';
  String get spotNumbersHint => ar
      ? 'مفصولة بفواصل أو أسطر: B1-01, B1-02, B1-03'
      : 'Comma or newline separated: B1-01, B1-02, B1-03';
  String get spotRequired => ar ? 'أدخل رقم الموقف' : 'Enter a spot number';
  String get level => ar ? 'الطابق (اختياري)' : 'Level (optional)';
  String get covered => ar ? 'مسقوف' : 'Covered';
  String get bulkMode => ar ? 'إضافة متعددة' : 'Bulk add';
  String get save => ar ? 'حفظ' : 'Save';
  String get saveFailed =>
      ar ? 'تعذر الحفظ. حاول مرة أخرى.' : 'Could not save. Try again.';
  String get deactivate => ar ? 'إيقاف' : 'Deactivate';
  String get cancel => ar ? 'إلغاء' : 'Cancel';
  String get deactivated => ar ? 'تم الإيقاف' : 'Deactivated';
  String get deactivateFailed => ar
      ? 'تعذر الإيقاف. حاول مرة أخرى.'
      : 'Could not deactivate. Try again.';
  String get inactive => ar ? 'موقوف' : 'Inactive';
  String get held => ar ? 'محجوز' : 'Held';
  String get notBookable => ar ? 'غير قابل للحجز' : 'Not bookable';
  String deactivateConfirm(String name) => ar
      ? 'إيقاف "$name"؟ سيختفي عن المستأجرين وتبقى الطلبات الحالية كما هي.'
      : 'Deactivate "$name"? Renters stop seeing it; existing requests are kept.';
  String pending(int n) => ar ? '$n قيد الانتظار' : '$n pending';
  String towersCount(int n) => ar ? 'الأبراج: $n' : '$n towers';
}

String _displayName(Map<String, dynamic> row, bool ar) {
  final nameAr = row['nameAr']?.toString();
  if (ar && nameAr != null && nameAr.isNotEmpty) return nameAr;
  return row['nameEn']?.toString() ?? '—';
}

/// Splits comma/semicolon/newline-separated spot numbers, trimming blanks and
/// de-duplicating while preserving order.
List<String> _parseSpotNumbers(String raw) {
  final seen = <String>{};
  final result = <String>[];
  for (final part in raw.split(RegExp(r'[,\n;]+'))) {
    final trimmed = part.trim();
    if (trimmed.isEmpty || !seen.add(trimmed)) continue;
    result.add(trimmed);
  }
  return result;
}

/// Inventory management: free-form amenities and numbered parking spots per
/// property, each optionally scoped to specific towers.
///
/// Parameterless and self-fetching — this router rebuilds on `authProvider`
/// and would discard `extra` (see the gate-pass screens' comment in router.dart).
class FacilitiesScreen extends ConsumerStatefulWidget {
  const FacilitiesScreen({super.key});

  @override
  ConsumerState<FacilitiesScreen> createState() => _FacilitiesScreenState();
}

class _FacilitiesScreenState extends ConsumerState<FacilitiesScreen> {
  String? _propertyId;
  bool _parkingTab = false;

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final properties = ref.watch(propertiesProvider);

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        title: Text(
          l.title,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 17,
                  fontWeight: FontWeight.w600,
                  color: Colors.white,
                )
              : null,
        ),
        actions: [
          if (_propertyId != null)
            IconButton(
              key: const Key('facility-add'),
              icon: const Icon(Icons.add),
              onPressed: () =>
                  _parkingTab ? _openParkingSheet() : _openAmenitySheet(),
            ),
        ],
      ),
      body: properties.when(
        loading: () => const Center(
          child: CircularProgressIndicator(color: AppColors.accent),
        ),
        error: (error, _) => ErrorState(
          message: l.propertiesLoadFailed,
          onRetry: () => ref.invalidate(propertiesProvider),
        ),
        data: (rows) {
          if (rows.isEmpty) {
            return EmptyState(
              icon: Icons.apartment_outlined,
              title: l.noProperties,
            );
          }
          // One property is the overwhelmingly common case; select it
          // silently rather than making the manager pick between one option.
          _propertyId ??= rows.first['id']?.toString();
          final propertyId = _propertyId!;
          return Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 12, 16, 0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    DropdownButtonFormField<String>(
                      initialValue: propertyId,
                      decoration: InputDecoration(labelText: l.property),
                      items: [
                        for (final p in rows)
                          DropdownMenuItem(
                            value: p['id']?.toString(),
                            child: Text(
                              p['name']?.toString() ?? '—',
                              overflow: TextOverflow.ellipsis,
                            ),
                          ),
                      ],
                      onChanged: (id) => setState(() => _propertyId = id),
                    ),
                    const SizedBox(height: 12),
                    _TabToggle(
                      parking: _parkingTab,
                      l: l,
                      onChanged: (v) => setState(() => _parkingTab = v),
                    ),
                    const SizedBox(height: 4),
                  ],
                ),
              ),
              Expanded(
                child: _parkingTab
                    ? _spotList(propertyId, l)
                    : _amenityList(propertyId, l),
              ),
            ],
          );
        },
      ),
    );
  }

  Widget _amenityList(String propertyId, _L l) {
    final async = ref.watch(amenitiesProvider(propertyId));
    return RefreshIndicator(
      color: AppColors.accent,
      onRefresh: () => ref.refresh(amenitiesProvider(propertyId).future),
      child: async.when(
        loading: () => const ListShimmer(itemCount: 4),
        error: (error, _) => _Scrollable(
          child: ErrorState(
            message: l.loadFailed,
            onRetry: () => ref.invalidate(amenitiesProvider(propertyId)),
          ),
        ),
        data: (items) {
          if (items.isEmpty) {
            return _Scrollable(
              child: EmptyState(
                icon: Icons.pool_outlined,
                title: l.noAmenities,
                subtitle: l.noAmenitiesSub,
                actionLabel: l.addAmenity,
                onAction: _openAmenitySheet,
              ),
            );
          }
          return ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding:
                EdgeInsets.fromLTRB(16, 8, 16, AppInsets.bottomNav(context)),
            itemCount: items.length,
            itemBuilder: (context, i) => AnimatedListItem(
              index: i,
              child: _FacilityCard(
                row: items[i],
                parking: false,
                l: l,
                onEdit: () => _openAmenitySheet(existing: items[i]),
                onDeactivate: () => _deactivate(items[i], parking: false),
              ),
            ),
          );
        },
      ),
    );
  }

  Widget _spotList(String propertyId, _L l) {
    final async = ref.watch(parkingSpotsProvider(propertyId));
    return RefreshIndicator(
      color: AppColors.accent,
      onRefresh: () => ref.refresh(parkingSpotsProvider(propertyId).future),
      child: async.when(
        loading: () => const ListShimmer(itemCount: 4),
        error: (error, _) => _Scrollable(
          child: ErrorState(
            message: l.loadFailed,
            onRetry: () => ref.invalidate(parkingSpotsProvider(propertyId)),
          ),
        ),
        data: (items) {
          if (items.isEmpty) {
            return _Scrollable(
              child: EmptyState(
                icon: Icons.local_parking_outlined,
                title: l.noSpots,
                subtitle: l.noSpotsSub,
                actionLabel: l.addParking,
                onAction: _openParkingSheet,
              ),
            );
          }
          return ListView.builder(
            physics: const AlwaysScrollableScrollPhysics(),
            padding:
                EdgeInsets.fromLTRB(16, 8, 16, AppInsets.bottomNav(context)),
            itemCount: items.length,
            itemBuilder: (context, i) => AnimatedListItem(
              index: i,
              child: _FacilityCard(
                row: items[i],
                parking: true,
                l: l,
                onEdit: () => _openParkingSheet(existing: items[i]),
                onDeactivate: () => _deactivate(items[i], parking: true),
              ),
            ),
          );
        },
      ),
    );
  }

  Future<void> _openAmenitySheet({Map<String, dynamic>? existing}) async {
    final propertyId = _propertyId;
    if (propertyId == null) return;
    final changed = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: context.miftah.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => _AmenitySheet(propertyId: propertyId, existing: existing),
    );
    if (changed == true) ref.invalidate(amenitiesProvider(propertyId));
  }

  Future<void> _openParkingSheet({Map<String, dynamic>? existing}) async {
    final propertyId = _propertyId;
    if (propertyId == null) return;
    final changed = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: context.miftah.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => _ParkingSheet(propertyId: propertyId, existing: existing),
    );
    if (changed == true) ref.invalidate(parkingSpotsProvider(propertyId));
  }

  Future<void> _deactivate(
    Map<String, dynamic> row, {
    required bool parking,
  }) async {
    final l = _L(context.isAr);
    final name = parking
        ? (row['spotNumber']?.toString() ?? '—')
        : _displayName(row, l.ar);
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        content: Text(l.deactivateConfirm(name)),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.cancel),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: TextButton.styleFrom(foregroundColor: AppColors.danger),
            child: Text(l.deactivate),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    final id = row['id']?.toString();
    final propertyId = _propertyId;
    if (id == null || propertyId == null) return;
    try {
      if (parking) {
        await ref.read(facilityServiceProvider).deactivateParkingSpot(id);
        ref.invalidate(parkingSpotsProvider(propertyId));
      } else {
        await ref.read(facilityServiceProvider).deactivateAmenity(id);
        ref.invalidate(amenitiesProvider(propertyId));
      }
      if (mounted) _toast(l.deactivated);
    } catch (_) {
      if (mounted) _toast(l.deactivateFailed);
    }
  }

  void _toast(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), behavior: SnackBarBehavior.floating),
    );
  }
}

/// Amenities/Parking segmented toggle (same pattern as the gate-pass create
/// screen's `_TypeToggle`).
class _TabToggle extends StatelessWidget {
  final bool parking;
  final _L l;
  final ValueChanged<bool> onChanged;

  const _TabToggle({
    required this.parking,
    required this.l,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return Container(
      padding: const EdgeInsets.all(4),
      decoration: BoxDecoration(
        color: m.surfaceAlt,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        children: [
          _segment(m, label: l.amenities, selected: !parking, value: false),
          _segment(m, label: l.parking, selected: parking, value: true),
        ],
      ),
    );
  }

  Widget _segment(
    MiftahColors m, {
    required String label,
    required bool selected,
    required bool value,
  }) {
    return Expanded(
      child: GestureDetector(
        onTap: () => onChanged(value),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 160),
          padding: const EdgeInsets.symmetric(vertical: 11),
          decoration: BoxDecoration(
            color: selected ? m.surface : Colors.transparent,
            borderRadius: BorderRadius.circular(9),
            border: selected ? Border.all(color: m.border) : null,
          ),
          child: Text(
            label,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: selected ? m.textPrimary : m.textMuted,
            ),
          ),
        ),
      ),
    );
  }
}
```

- [ ] **Step 3: Append the card, sheets and scroll helper** to the same file (`mobile/apps/manager/lib/screens/facilities/facilities_screen.dart`), completing it:

```dart
/// One inventory row: amenity or spot, with tower/bookable/held/pending pills.
class _FacilityCard extends StatelessWidget {
  final Map<String, dynamic> row;
  final bool parking;
  final _L l;
  final VoidCallback onEdit;
  final VoidCallback onDeactivate;

  const _FacilityCard({
    required this.row,
    required this.parking,
    required this.l,
    required this.onEdit,
    required this.onDeactivate,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final active = row['active'] != false;
    final pendingCount = (row['pendingCount'] as num?)?.toInt() ?? 0;
    final buildingIds = (row['buildingIds'] as List?) ?? const [];
    final title = parking
        ? (row['spotNumber']?.toString() ?? '—')
        : _displayName(row, l.ar);
    final level = row['level']?.toString();
    final description = row['description']?.toString();

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: active ? m.surface : m.surfaceDim,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: onEdit,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 10, 14, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    parking
                        ? Icons.local_parking_outlined
                        : Icons.pool_outlined,
                    size: 18,
                    color: AppColors.accentDark,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      title,
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 15,
                              fontWeight: FontWeight.w600,
                              color: m.textPrimary,
                            )
                          : GoogleFonts.josefinSans(
                              fontSize: 14.5,
                              fontWeight: FontWeight.w600,
                              color: m.textPrimary,
                            ),
                    ),
                  ),
                  IconButton(
                    key: Key('deactivate-${row['id']}'),
                    visualDensity: VisualDensity.compact,
                    icon: Icon(
                      Icons.delete_outline,
                      size: 19,
                      color: active ? m.textMuted : m.border,
                    ),
                    onPressed: active ? onDeactivate : null,
                  ),
                ],
              ),
              if (!parking && description != null && description.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: Text(
                    description,
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                    style: (l.ar
                        ? GoogleFonts.notoNaskhArabic
                        : GoogleFonts.josefinSans)(
                      fontSize: 12.5,
                      color: m.textSecondary,
                      height: 1.4,
                    ),
                  ),
                ),
              Wrap(
                spacing: 6,
                runSpacing: 6,
                children: [
                  StatusBadge(
                    label: buildingIds.isEmpty
                        ? l.allTowers
                        : l.towersCount(buildingIds.length),
                    color: AppColors.accentDark,
                  ),
                  if (parking && level != null && level.isNotEmpty)
                    StatusBadge(label: level, color: m.textSecondary),
                  if (parking && row['covered'] == true)
                    StatusBadge(label: l.covered, color: m.textSecondary),
                  if (parking && row['held'] == true)
                    StatusBadge(label: l.held, color: m.warning),
                  if (!parking && row['bookable'] == false)
                    StatusBadge(label: l.notBookable, color: m.textMuted),
                  if (!active) StatusBadge(label: l.inactive, color: m.textMuted),
                  if (pendingCount > 0)
                    StatusBadge(label: l.pending(pendingCount), color: m.warning),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Tower multi-select. No selection = visible to every tower (the server keeps
/// zero scope rows); selecting towers restricts visibility to them.
class _TowerChips extends ConsumerWidget {
  final String propertyId;
  final Set<String> selected;
  final ValueChanged<Set<String>> onChanged;
  final _L l;

  const _TowerChips({
    required this.propertyId,
    required this.selected,
    required this.onChanged,
    required this.l,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final buildings = ref.watch(buildingsProvider(propertyId));
    return buildings.when(
      loading: () => const ShimmerLoading(height: 36),
      // A property with no towers has nothing to scope; treat a failed tower
      // lookup the same as "no towers" instead of blocking the whole sheet.
      error: (error, _) => Text(
        l.allTowers,
        style: GoogleFonts.josefinSans(fontSize: 12.5, color: m.textMuted),
      ),
      data: (rows) {
        if (rows.isEmpty) {
          return Text(
            l.allTowers,
            style: GoogleFonts.josefinSans(fontSize: 12.5, color: m.textMuted),
          );
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                for (final b in rows)
                  FilterChip(
                    label: Text((b['nameEn'] ?? b['name'] ?? '—').toString()),
                    selected: selected.contains(b['id']?.toString()),
                    selectedColor: AppColors.accent.withValues(alpha: 0.25),
                    checkmarkColor: AppColors.accentDark,
                    onSelected: (on) {
                      final id = b['id']?.toString();
                      if (id == null) return;
                      final next = {...selected};
                      on ? next.add(id) : next.remove(id);
                      onChanged(next);
                    },
                  ),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              l.towersHint,
              style: (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts.josefinSans)(
                fontSize: 11.5,
                color: m.textMuted,
                height: 1.4,
              ),
            ),
          ],
        );
      },
    );
  }
}

class _AmenitySheet extends ConsumerStatefulWidget {
  final String propertyId;
  final Map<String, dynamic>? existing;
  const _AmenitySheet({required this.propertyId, this.existing});

  @override
  ConsumerState<_AmenitySheet> createState() => _AmenitySheetState();
}

class _AmenitySheetState extends ConsumerState<_AmenitySheet> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _nameEnCtrl;
  late final TextEditingController _nameArCtrl;
  late final TextEditingController _descCtrl;
  late bool _bookable;
  late Set<String> _towerIds;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _nameEnCtrl = TextEditingController(text: e?['nameEn']?.toString() ?? '');
    _nameArCtrl = TextEditingController(text: e?['nameAr']?.toString() ?? '');
    _descCtrl =
        TextEditingController(text: e?['description']?.toString() ?? '');
    _bookable = e == null || e['bookable'] == true;
    _towerIds = {
      for (final id in (e?['buildingIds'] as List? ?? const [])) id.toString(),
    };
  }

  @override
  void dispose() {
    _nameEnCtrl.dispose();
    _nameArCtrl.dispose();
    _descCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final l = _L(context.isAr);
    if (!_formKey.currentState!.validate()) return;
    setState(() => _saving = true);
    final service = ref.read(facilityServiceProvider);
    final nameAr = _nameArCtrl.text.trim();
    final description = _descCtrl.text.trim();
    try {
      final existing = widget.existing;
      if (existing == null) {
        await service.createAmenity({
          'propertyId': widget.propertyId,
          'nameEn': _nameEnCtrl.text.trim(),
          if (nameAr.isNotEmpty) 'nameAr': nameAr,
          if (description.isNotEmpty) 'description': description,
          'bookable': _bookable,
          'buildingIds': _towerIds.toList(),
        });
      } else {
        // Patch semantics: absent = unchanged. A non-null buildingIds
        // REPLACES the scope set, which is exactly what the chips represent.
        await service.updateAmenity(existing['id'].toString(), {
          'nameEn': _nameEnCtrl.text.trim(),
          if (nameAr.isNotEmpty) 'nameAr': nameAr,
          if (description.isNotEmpty) 'description': description,
          'bookable': _bookable,
          'buildingIds': _towerIds.toList(),
        });
      }
      if (mounted) Navigator.pop(context, true);
    } catch (_) {
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(l.saveFailed),
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(context).height * 0.85,
          ),
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
            child: Form(
              key: _formKey,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    widget.existing == null ? l.addAmenity : l.editAmenity,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 17,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          )
                        : GoogleFonts.cinzel(
                            fontSize: 16,
                            letterSpacing: 1.6,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          ),
                  ),
                  const SizedBox(height: 16),
                  TextFormField(
                    key: const Key('amenity-name-en'),
                    controller: _nameEnCtrl,
                    maxLength: 160,
                    decoration:
                        InputDecoration(labelText: l.nameEn, counterText: ''),
                    validator: (v) =>
                        (v == null || v.trim().isEmpty) ? l.nameRequired : null,
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _nameArCtrl,
                    maxLength: 160,
                    textDirection: TextDirection.rtl,
                    decoration:
                        InputDecoration(labelText: l.nameAr, counterText: ''),
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _descCtrl,
                    maxLength: 500,
                    maxLines: 3,
                    decoration: InputDecoration(
                      labelText: l.description,
                      counterText: '',
                    ),
                  ),
                  const SizedBox(height: 4),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    value: _bookable,
                    title: Text(
                      l.bookable,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: m.textPrimary,
                      ),
                    ),
                    subtitle: Text(
                      l.bookableSub,
                      style: TextStyle(fontSize: 12, color: m.textSecondary),
                    ),
                    onChanged: (v) => setState(() => _bookable = v),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    l.towers,
                    style: TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w700,
                      color: m.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 8),
                  _TowerChips(
                    propertyId: widget.propertyId,
                    selected: _towerIds,
                    l: l,
                    onChanged: (next) => setState(() => _towerIds = next),
                  ),
                  const SizedBox(height: 20),
                  GoldButton(
                    key: const Key('amenity-save'),
                    label: l.save,
                    height: 48,
                    onPressed: _saving ? null : _save,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _ParkingSheet extends ConsumerStatefulWidget {
  final String propertyId;
  final Map<String, dynamic>? existing;
  const _ParkingSheet({required this.propertyId, this.existing});

  @override
  ConsumerState<_ParkingSheet> createState() => _ParkingSheetState();
}

class _ParkingSheetState extends ConsumerState<_ParkingSheet> {
  final _formKey = GlobalKey<FormState>();
  late final TextEditingController _spotCtrl;
  late final TextEditingController _levelCtrl;
  late bool _covered;
  late Set<String> _towerIds;
  bool _bulk = false;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    final e = widget.existing;
    _spotCtrl = TextEditingController(text: e?['spotNumber']?.toString() ?? '');
    _levelCtrl = TextEditingController(text: e?['level']?.toString() ?? '');
    _covered = e == null || e['covered'] == true;
    _towerIds = {
      for (final id in (e?['buildingIds'] as List? ?? const [])) id.toString(),
    };
  }

  @override
  void dispose() {
    _spotCtrl.dispose();
    _levelCtrl.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    final l = _L(context.isAr);
    if (!_formKey.currentState!.validate()) return;
    setState(() => _saving = true);
    final service = ref.read(facilityServiceProvider);
    final level = _levelCtrl.text.trim();
    try {
      final existing = widget.existing;
      if (existing != null) {
        await service.updateParkingSpot(existing['id'].toString(), {
          'spotNumber': _spotCtrl.text.trim(),
          if (level.isNotEmpty) 'level': level,
          'covered': _covered,
          'buildingIds': _towerIds.toList(),
        });
      } else if (_bulk) {
        await service.bulkCreateParkingSpots({
          'propertyId': widget.propertyId,
          'spotNumbers': _parseSpotNumbers(_spotCtrl.text),
          if (level.isNotEmpty) 'level': level,
          'covered': _covered,
          'buildingIds': _towerIds.toList(),
        });
      } else {
        await service.createParkingSpot({
          'propertyId': widget.propertyId,
          'spotNumber': _spotCtrl.text.trim(),
          if (level.isNotEmpty) 'level': level,
          'covered': _covered,
          'buildingIds': _towerIds.toList(),
        });
      }
      if (mounted) Navigator.pop(context, true);
    } catch (_) {
      if (!mounted) return;
      setState(() => _saving = false);
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(l.saveFailed),
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final creating = widget.existing == null;
    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(context).height * 0.85,
          ),
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
            child: Form(
              key: _formKey,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    creating ? l.addParking : l.editParking,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 17,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          )
                        : GoogleFonts.cinzel(
                            fontSize: 16,
                            letterSpacing: 1.6,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          ),
                  ),
                  const SizedBox(height: 8),
                  if (creating)
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      value: _bulk,
                      title: Text(
                        l.bulkMode,
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        ),
                      ),
                      onChanged: (v) => setState(() => _bulk = v),
                    ),
                  const SizedBox(height: 4),
                  TextFormField(
                    key: const Key('spot-numbers'),
                    controller: _spotCtrl,
                    maxLines: _bulk ? 3 : 1,
                    decoration: InputDecoration(
                      labelText: _bulk ? l.spotNumbers : l.spotNumber,
                      hintText: _bulk ? l.spotNumbersHint : 'B1-01',
                      counterText: '',
                    ),
                    validator: (v) {
                      if (_bulk) {
                        return _parseSpotNumbers(v ?? '').isEmpty
                            ? l.spotRequired
                            : null;
                      }
                      return (v == null || v.trim().isEmpty)
                          ? l.spotRequired
                          : null;
                    },
                  ),
                  const SizedBox(height: 12),
                  TextFormField(
                    controller: _levelCtrl,
                    maxLength: 32,
                    decoration: InputDecoration(
                      labelText: l.level,
                      hintText: 'B1',
                      counterText: '',
                    ),
                  ),
                  const SizedBox(height: 4),
                  SwitchListTile(
                    contentPadding: EdgeInsets.zero,
                    value: _covered,
                    title: Text(
                      l.covered,
                      style: TextStyle(
                        fontSize: 14,
                        fontWeight: FontWeight.w600,
                        color: m.textPrimary,
                      ),
                    ),
                    onChanged: (v) => setState(() => _covered = v),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    l.towers,
                    style: TextStyle(
                      fontSize: 12.5,
                      fontWeight: FontWeight.w700,
                      color: m.textSecondary,
                    ),
                  ),
                  const SizedBox(height: 8),
                  _TowerChips(
                    propertyId: widget.propertyId,
                    selected: _towerIds,
                    l: l,
                    onChanged: (next) => setState(() => _towerIds = next),
                  ),
                  const SizedBox(height: 20),
                  GoldButton(
                    key: const Key('parking-save'),
                    label: l.save,
                    height: 48,
                    onPressed: _saving ? null : _save,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// A [RefreshIndicator] over a non-scrolling child cannot be pulled, so the
/// empty and error states are given something to scroll.
class _Scrollable extends StatelessWidget {
  const _Scrollable({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: child,
        ),
      ),
    );
  }
}
```

- [ ] **Step 4: Verify**:
```bash
cd /Users/kunalsharma/datagami/rentaxis/mobile/apps/manager && flutter analyze
```
Expect: `No issues found!`

- [ ] **Step 5: Commit**:
```bash
cd /Users/kunalsharma/datagami/rentaxis && git add mobile/apps/manager && git commit -m "$(cat <<'EOF'
feat(manager): facilities inventory screen with tower scoping and bulk parking add

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task M3: Manager — booking approvals screen (TDD)

**Files:**
- Test: `mobile/apps/manager/test/support/fake_facility_service.dart` (new), `mobile/apps/manager/test/booking_approvals_screen_test.dart` (new)
- Create: `mobile/apps/manager/lib/screens/facilities/booking_approvals_screen.dart`

- [ ] **Step 1: Write the fake service** — create `mobile/apps/manager/test/support/fake_facility_service.dart`:

```dart
import 'package:rentaxis_core/rentaxis_core.dart';

/// Canned [FacilityApiService] for widget tests: serves fixed rows and records
/// mutating calls. Defaults are benign empties so a screen can touch any
/// endpoint without blowing up the test.
class FakeFacilityService implements FacilityApiService {
  FakeFacilityService({
    this.bookings = const [],
    Map<String, dynamic>? detail,
    this.amenities = const [],
    this.parkingSpots = const [],
  }) : detail = detail ??
            {'request': <String, dynamic>{}, 'otherRequests': <dynamic>[]};

  final List<Map<String, dynamic>> bookings;
  final Map<String, dynamic> detail;
  final List<Map<String, dynamic>> amenities;
  final List<Map<String, dynamic>> parkingSpots;

  int bookingsReads = 0;
  final List<(String, String?)> approveCalls = [];
  final List<(String, String?)> rejectCalls = [];
  final List<String> releaseCalls = [];
  final List<Map<String, dynamic>> createCalls = [];

  Map<String, dynamic> _page(List<Map<String, dynamic>> rows) =>
      {'content': rows, 'totalElements': rows.length};

  @override
  Future<Map<String, dynamic>> getAmenities({
    required String propertyId,
    int page = 0,
    int size = 20,
  }) async =>
      _page(amenities);

  @override
  Future<Map<String, dynamic>> createAmenity(Map<String, dynamic> body) async =>
      {'id': 'am-new', ...body};

  @override
  Future<Map<String, dynamic>> updateAmenity(
    String id,
    Map<String, dynamic> body,
  ) async =>
      {'id': id, ...body};

  @override
  Future<void> deactivateAmenity(String id) async {}

  @override
  Future<Map<String, dynamic>> getParkingSpots({
    required String propertyId,
    int page = 0,
    int size = 20,
  }) async =>
      _page(parkingSpots);

  @override
  Future<Map<String, dynamic>> createParkingSpot(
    Map<String, dynamic> body,
  ) async =>
      {'id': 'spot-new', ...body};

  @override
  Future<List<dynamic>> bulkCreateParkingSpots(
    Map<String, dynamic> body,
  ) async =>
      const [];

  @override
  Future<Map<String, dynamic>> updateParkingSpot(
    String id,
    Map<String, dynamic> body,
  ) async =>
      {'id': id, ...body};

  @override
  Future<void> deactivateParkingSpot(String id) async {}

  @override
  Future<Map<String, dynamic>> getBookings({
    String? propertyId,
    String? status,
    String? resourceType,
    int page = 0,
    int size = 20,
  }) async {
    bookingsReads++;
    final rows = status == null
        ? bookings
        : bookings.where((b) => b['status'] == status).toList();
    return _page(rows);
  }

  @override
  Future<Map<String, dynamic>> getBooking(String id) async => detail;

  @override
  Future<Map<String, dynamic>> approveBooking(
    String id, {
    String? adminNote,
  }) async {
    approveCalls.add((id, adminNote));
    return {'id': id, 'status': 'APPROVED'};
  }

  @override
  Future<Map<String, dynamic>> rejectBooking(
    String id, {
    String? adminNote,
  }) async {
    rejectCalls.add((id, adminNote));
    return {'id': id, 'status': 'REJECTED'};
  }

  @override
  Future<Map<String, dynamic>> releaseBooking(String id) async {
    releaseCalls.add(id);
    return {'id': id, 'status': 'RELEASED'};
  }

  @override
  Future<Map<String, dynamic>> myFacilities() async =>
      {'amenities': amenities, 'parkingSpots': parkingSpots};

  @override
  Future<Map<String, dynamic>> createBooking(Map<String, dynamic> body) async {
    createCalls.add(body);
    return {'id': 'bk-new', 'status': 'PENDING', ...body};
  }

  @override
  Future<List<dynamic>> myBookings() async => bookings;

  @override
  Future<Map<String, dynamic>> cancelBooking(String id) async =>
      {'id': id, 'status': 'CANCELLED'};
}
```

- [ ] **Step 2: Write the failing widget test** — create `mobile/apps/manager/test/booking_approvals_screen_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:manager/screens/facilities/booking_approvals_screen.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import 'support/fake_facility_service.dart';

void main() {
  final booking = <String, dynamic>{
    'id': 'bk-1',
    'resourceType': 'AMENITY',
    'amenityId': 'am-1',
    'parkingSpotId': null,
    'resourceName': 'Community Hall',
    'propertyId': 'prop-1',
    'unitId': 'unit-1',
    'unitNumber': '1204',
    'renterUserId': 'user-9',
    'renterName': 'Aisha Rahman',
    'renterEmail': 'aisha@example.com',
    'renterPhone': '+971501234567',
    'note': 'Birthday party',
    'preferredDate': '2026-08-20',
    'status': 'PENDING',
    'adminNote': null,
    'decidedByUserId': null,
    'decidedAt': null,
    'createdAt': '2026-08-01T10:00:00Z',
  };

  testWidgets('approve posts the admin note and refreshes the queue',
      (tester) async {
    final fake = FakeFacilityService(
      bookings: [booking],
      detail: {'request': booking, 'otherRequests': <dynamic>[]},
    );

    await tester.pumpWidget(
      ProviderScope(
        overrides: [facilityServiceProvider.overrideWithValue(fake)],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const BookingApprovalsScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Community Hall'), findsOneWidget);

    await tester.tap(find.text('Community Hall'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('admin-note')), 'Enjoy!');
    await tester.tap(find.text('APPROVE'));
    await tester.pumpAndSettle();

    expect(fake.approveCalls, [('bk-1', 'Enjoy!')]);
    // The sheet closed and the queue was re-read after the decision.
    expect(fake.bookingsReads, greaterThanOrEqualTo(2));
  });
}
```

- [ ] **Step 3: Run — expect failure** (screen file missing):
```bash
cd /Users/kunalsharma/datagami/rentaxis/mobile/apps/manager && flutter test test/booking_approvals_screen_test.dart
```
Expect a compile error on the missing `booking_approvals_screen.dart` import.

- [ ] **Step 4: Create the screen** — `mobile/apps/manager/lib/screens/facilities/booking_approvals_screen.dart`:

```dart
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../providers/facility_provider.dart';

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'طلبات الحجز' : 'Booking Requests';
  String get loadFailed =>
      ar ? 'فشل تحميل طلبات الحجز' : 'Failed to load booking requests';
  String get pendingFilter => ar ? 'قيد الانتظار' : 'Pending';
  String get approvedFilter => ar ? 'مقبولة' : 'Approved';
  String get allFilter => ar ? 'الكل' : 'All';
  String get nothingHere => ar ? 'لا توجد طلبات' : 'No requests here';
  String get nothingHereSub => ar
      ? 'تظهر هنا طلبات حجز المرافق والمواقف التي يرسلها المستأجرون.'
      : 'Facility and parking requests raised by renters appear here.';
  String get renter => ar ? 'مستأجر' : 'Renter';
  String get unit => ar ? 'وحدة' : 'Unit';
  String get preferred => ar ? 'التاريخ المفضل:' : 'Preferred:';
  String get call => ar ? 'اتصال' : 'Call';
  String get email => ar ? 'بريد' : 'Email';
  String get otherRequests =>
      ar ? 'طلبات أخرى لنفس المرفق' : 'Other requests for this resource';
  String get noOtherRequests => ar ? 'لا توجد طلبات أخرى' : 'No other requests';
  String get adminNote =>
      ar ? 'ملاحظة للمستأجر (اختياري)' : 'Note to renter (optional)';
  String get approve => ar ? 'اعتماد' : 'Approve';
  String get reject => ar ? 'رفض' : 'Reject';
  String get release => ar ? 'تحرير الموقف' : 'Release spot';
  String get approvedToast => ar ? 'تم اعتماد الطلب' : 'Request approved';
  String get rejectedToast => ar ? 'تم رفض الطلب' : 'Request rejected';
  String get releasedToast => ar ? 'تم تحرير الموقف' : 'Spot released';
  String get alreadyDecided => ar
      ? 'تم البت في هذا الطلب مسبقًا. جارٍ تحديث القائمة.'
      : 'This request was already decided. Refreshing the list.';
  String get spotHeld => ar
      ? 'هذا الموقف محجوز بالفعل لمستأجر آخر.'
      : 'This spot is already held by another renter.';
  String get actionFailed => ar
      ? 'تعذر تنفيذ الإجراء. حاول مرة أخرى.'
      : 'Could not complete the action. Try again.';

  String status(String value) => switch (value) {
    'PENDING' => ar ? 'قيد الانتظار' : 'PENDING',
    'APPROVED' => ar ? 'مقبول' : 'APPROVED',
    'REJECTED' => ar ? 'مرفوض' : 'REJECTED',
    'CANCELLED' => ar ? 'ملغي' : 'CANCELLED',
    'RELEASED' => ar ? 'محرَّر' : 'RELEASED',
    _ => value.replaceAll('_', ' '),
  };

  String type(String value) => switch (value) {
    'AMENITY' => ar ? 'مرفق' : 'Amenity',
    'PARKING_SPOT' => ar ? 'موقف سيارة' : 'Parking spot',
    _ => value.replaceAll('_', ' '),
  };
}

Color _statusColor(String? status, MiftahColors m) => switch (status) {
  'PENDING' => m.warning,
  'APPROVED' => m.success,
  'REJECTED' => m.danger,
  'RELEASED' => AppColors.accentDark,
  _ => m.textMuted,
};

Future<void> _launch(String url) async {
  final uri = Uri.parse(url);
  if (await canLaunchUrl(uri)) await launchUrl(uri);
}

/// The tenant's booking-request inbox, pending first.
///
/// Tenant-wide (no propertyId filter is sent), like the gate-pass approvals
/// queue: scope is decided server-side by role, so every card names what and
/// who rather than assuming one property.
class BookingApprovalsScreen extends ConsumerStatefulWidget {
  const BookingApprovalsScreen({super.key});

  @override
  ConsumerState<BookingApprovalsScreen> createState() =>
      _BookingApprovalsScreenState();
}

class _BookingApprovalsScreenState
    extends ConsumerState<BookingApprovalsScreen> {
  String? _status = 'PENDING';

  BookingFilter get _filter => (propertyId: null, status: _status);

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final bookings = ref.watch(bookingsProvider(_filter));

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        title: Text(
          l.title,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 17,
                  fontWeight: FontWeight.w600,
                  color: Colors.white,
                )
              : null,
        ),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 4),
            child: Row(
              children: [
                _FilterChip(
                  label: l.pendingFilter,
                  selected: _status == 'PENDING',
                  onTap: () => setState(() => _status = 'PENDING'),
                ),
                const SizedBox(width: 8),
                _FilterChip(
                  label: l.approvedFilter,
                  selected: _status == 'APPROVED',
                  onTap: () => setState(() => _status = 'APPROVED'),
                ),
                const SizedBox(width: 8),
                _FilterChip(
                  label: l.allFilter,
                  selected: _status == null,
                  onTap: () => setState(() => _status = null),
                ),
              ],
            ),
          ),
          Expanded(
            child: RefreshIndicator(
              color: AppColors.accent,
              onRefresh: () => ref.refresh(bookingsProvider(_filter).future),
              child: bookings.when(
                loading: () => const ListShimmer(itemCount: 4),
                error: (error, _) => _Scrollable(
                  child: ErrorState(
                    message: l.loadFailed,
                    onRetry: () => ref.invalidate(bookingsProvider(_filter)),
                  ),
                ),
                data: (rows) {
                  if (rows.isEmpty) {
                    return _Scrollable(
                      child: EmptyState(
                        icon: Icons.event_available_outlined,
                        title: l.nothingHere,
                        subtitle: l.nothingHereSub,
                      ),
                    );
                  }
                  return ListView.builder(
                    physics: const AlwaysScrollableScrollPhysics(),
                    padding: EdgeInsets.fromLTRB(
                      16,
                      8,
                      16,
                      AppInsets.bottomNav(context),
                    ),
                    itemCount: rows.length,
                    itemBuilder: (context, i) => AnimatedListItem(
                      index: i,
                      child: _BookingCard(
                        booking: rows[i],
                        l: l,
                        onTap: () => _openDetail(rows[i]),
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _openDetail(Map<String, dynamic> booking) async {
    final id = booking['id']?.toString();
    if (id == null) return;
    final changed = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      backgroundColor: context.miftah.surface,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (_) => _BookingDetailSheet(bookingId: id),
    );
    // Invalidate the whole family: a decision changes every filtered view.
    if (changed == true) ref.invalidate(bookingsProvider);
  }
}

class _FilterChip extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback onTap;

  const _FilterChip({
    required this.label,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return GestureDetector(
      onTap: onTap,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 160),
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(999),
          gradient: selected ? MiftahGradients.gold : null,
          border: selected ? null : Border.all(color: m.borderStrong),
        ),
        child: Text(
          context.isAr ? label : label.toUpperCase(),
          style: context.isAr
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: selected ? AppColors.primary : m.textSecondary,
                )
              : GoogleFonts.josefinSans(
                  fontSize: 10.5,
                  letterSpacing: 1.4,
                  fontWeight: FontWeight.w600,
                  color: selected ? AppColors.primary : m.textSecondary,
                ),
        ),
      ),
    );
  }
}

class _BookingCard extends StatelessWidget {
  final Map<String, dynamic> booking;
  final _L l;
  final VoidCallback onTap;

  const _BookingCard({
    required this.booking,
    required this.l,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final status = booking['status']?.toString() ?? '';
    final parking = booking['resourceType'] == 'PARKING_SPOT';

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(
                    parking
                        ? Icons.local_parking_outlined
                        : Icons.pool_outlined,
                    size: 17,
                    color: AppColors.accentDark,
                  ),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      booking['resourceName']?.toString() ?? '—',
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 15,
                              fontWeight: FontWeight.w600,
                              color: m.textPrimary,
                            )
                          : GoogleFonts.josefinSans(
                              fontSize: 14.5,
                              fontWeight: FontWeight.w600,
                              color: m.textPrimary,
                            ),
                    ),
                  ),
                  StatusBadge(
                    label: l.status(status),
                    color: _statusColor(status, m),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                [
                  booking['renterName']?.toString() ?? l.renter,
                  if (booking['unitNumber'] != null)
                    '${l.unit} ${booking['unitNumber']}',
                  Formatters.timeAgo(
                    booking['createdAt']?.toString(),
                    ar: l.ar,
                  ),
                ].join(' · '),
                style: (l.ar
                    ? GoogleFonts.notoNaskhArabic
                    : GoogleFonts.josefinSans)(
                  fontSize: 12.5,
                  color: m.textSecondary,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Decision sheet: the request, the renter's contact details, every competing
/// PENDING/APPROVED request for the same resource, and the actions.
class _BookingDetailSheet extends ConsumerStatefulWidget {
  final String bookingId;
  const _BookingDetailSheet({required this.bookingId});

  @override
  ConsumerState<_BookingDetailSheet> createState() =>
      _BookingDetailSheetState();
}

class _BookingDetailSheetState extends ConsumerState<_BookingDetailSheet> {
  final _noteCtrl = TextEditingController();
  bool _deciding = false;

  @override
  void dispose() {
    _noteCtrl.dispose();
    super.dispose();
  }

  Future<void> _decide(String action) async {
    if (_deciding) return;
    final l = _L(context.isAr);
    setState(() => _deciding = true);
    final service = ref.read(facilityServiceProvider);
    final note = _noteCtrl.text.trim();
    try {
      switch (action) {
        case 'approve':
          await service.approveBooking(
            widget.bookingId,
            adminNote: note.isEmpty ? null : note,
          );
        case 'reject':
          await service.rejectBooking(
            widget.bookingId,
            adminNote: note.isEmpty ? null : note,
          );
        default:
          await service.releaseBooking(widget.bookingId);
      }
      if (!mounted) return;
      _toast(
        switch (action) {
          'approve' => l.approvedToast,
          'reject' => l.rejectedToast,
          _ => l.releasedToast,
        },
        AppColors.success,
      );
      Navigator.pop(context, true);
    } on DioException catch (error) {
      if (!mounted) return;
      final code = error.response?.statusCode;
      if (code == 409) {
        // The spot is APPROVED to someone else. Retrying cannot fix it, but
        // the manager may still want to reject this request — keep the sheet.
        setState(() => _deciding = false);
        _toast(l.spotHeld, AppColors.warning);
      } else if (code == 400) {
        // Someone else decided first; the sheet is stale — close and refresh.
        _toast(l.alreadyDecided, AppColors.warning);
        Navigator.pop(context, true);
      } else {
        setState(() => _deciding = false);
        _toast(l.actionFailed, AppColors.danger);
      }
    } catch (_) {
      if (!mounted) return;
      setState(() => _deciding = false);
      _toast(l.actionFailed, AppColors.danger);
    }
  }

  void _toast(String message, Color background) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: background,
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final detail = ref.watch(bookingDetailProvider(widget.bookingId));

    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(context).height * 0.85,
          ),
          child: detail.when(
            loading: () => const SizedBox(
              height: 220,
              child: Center(
                child: CircularProgressIndicator(color: AppColors.accent),
              ),
            ),
            error: (error, _) => Padding(
              padding: const EdgeInsets.all(24),
              child: ErrorState(
                message: l.loadFailed,
                onRetry: () =>
                    ref.invalidate(bookingDetailProvider(widget.bookingId)),
              ),
            ),
            data: (data) {
              final request =
                  Map<String, dynamic>.from(data['request'] as Map? ?? {});
              final others = (data['otherRequests'] as List? ?? const [])
                  .whereType<Map>()
                  .map((r) => Map<String, dynamic>.from(r))
                  .toList();
              final status = request['status']?.toString() ?? '';
              final parking = request['resourceType'] == 'PARKING_SPOT';
              final phone = request['renterPhone']?.toString();
              final email = request['renterEmail']?.toString();
              final note = request['note']?.toString();

              return SingleChildScrollView(
                padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
                child: Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Expanded(
                          child: Text(
                            request['resourceName']?.toString() ?? '—',
                            style: l.ar
                                ? GoogleFonts.notoNaskhArabic(
                                    fontSize: 17,
                                    fontWeight: FontWeight.w600,
                                    color: m.textPrimary,
                                  )
                                : GoogleFonts.josefinSans(
                                    fontSize: 16,
                                    fontWeight: FontWeight.w600,
                                    color: m.textPrimary,
                                  ),
                          ),
                        ),
                        StatusBadge(
                          label: l.status(status),
                          color: _statusColor(status, m),
                        ),
                      ],
                    ),
                    const SizedBox(height: 2),
                    Text(
                      l.type(request['resourceType']?.toString() ?? ''),
                      style: GoogleFonts.josefinSans(
                        fontSize: 11,
                        letterSpacing: 1.4,
                        color: m.textMuted,
                      ),
                    ),
                    const SizedBox(height: 12),
                    _DetailRow(
                      icon: Icons.person_outline,
                      value: request['renterName']?.toString() ?? l.renter,
                      m: m,
                    ),
                    if (request['unitNumber'] != null)
                      _DetailRow(
                        icon: Icons.home_outlined,
                        value: '${l.unit} ${request['unitNumber']}',
                        m: m,
                      ),
                    if (request['preferredDate'] != null)
                      _DetailRow(
                        icon: Icons.event_outlined,
                        value:
                            '${l.preferred} ${Formatters.date(request['preferredDate']?.toString(), ar: l.ar)}',
                        m: m,
                      ),
                    if (request['createdAt'] != null)
                      _DetailRow(
                        icon: Icons.schedule,
                        value: Formatters.timeAgo(
                          request['createdAt']?.toString(),
                          ar: l.ar,
                        ),
                        m: m,
                      ),
                    if (note != null && note.isNotEmpty) ...[
                      const SizedBox(height: 6),
                      Container(
                        width: double.infinity,
                        padding: const EdgeInsets.all(10),
                        decoration: BoxDecoration(
                          color: m.surfaceAlt,
                          borderRadius: BorderRadius.circular(10),
                        ),
                        child: Text(
                          note,
                          style: (l.ar
                              ? GoogleFonts.notoNaskhArabic
                              : GoogleFonts.josefinSans)(
                            fontSize: 13,
                            color: m.textSecondary,
                            height: 1.5,
                          ),
                        ),
                      ),
                    ],
                    if (phone != null || email != null) ...[
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          if (phone != null)
                            Expanded(
                              child: _ContactBtn(
                                icon: Icons.phone_outlined,
                                label: l.call,
                                ar: l.ar,
                                onTap: () => _launch('tel:$phone'),
                              ),
                            ),
                          if (phone != null && email != null)
                            const SizedBox(width: 10),
                          if (email != null)
                            Expanded(
                              child: _ContactBtn(
                                icon: Icons.email_outlined,
                                label: l.email,
                                ar: l.ar,
                                onTap: () => _launch('mailto:$email'),
                              ),
                            ),
                        ],
                      ),
                    ],
                    const SizedBox(height: 16),
                    Text(
                      l.ar ? l.otherRequests : l.otherRequests.toUpperCase(),
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 12,
                              color: AppColors.accentDark,
                            )
                          : GoogleFonts.josefinSans(
                              fontSize: 10,
                              letterSpacing: 2.2,
                              color: AppColors.accentDark,
                            ),
                    ),
                    const SizedBox(height: 8),
                    if (others.isEmpty)
                      Text(
                        l.noOtherRequests,
                        style: (l.ar
                            ? GoogleFonts.notoNaskhArabic
                            : GoogleFonts.josefinSans)(
                          fontSize: 12.5,
                          color: m.textMuted,
                        ),
                      )
                    else
                      for (final other in others)
                        _OtherRequestRow(row: other, l: l),
                    const SizedBox(height: 16),
                    if (status == 'PENDING') ...[
                      TextField(
                        key: const Key('admin-note'),
                        controller: _noteCtrl,
                        maxLength: 500,
                        decoration: InputDecoration(
                          labelText: l.adminNote,
                          counterText: '',
                        ),
                      ),
                      const SizedBox(height: 12),
                      Row(
                        children: [
                          Expanded(
                            child: GoldButton.outlined(
                              key: const Key('booking-reject'),
                              label: l.reject,
                              height: 46,
                              onPressed:
                                  _deciding ? null : () => _decide('reject'),
                            ),
                          ),
                          const SizedBox(width: 10),
                          Expanded(
                            child: GoldButton(
                              key: const Key('booking-approve'),
                              label: l.approve,
                              height: 46,
                              onPressed:
                                  _deciding ? null : () => _decide('approve'),
                            ),
                          ),
                        ],
                      ),
                    ] else if (status == 'APPROVED' && parking)
                      GoldButton.outlined(
                        key: const Key('booking-release'),
                        label: l.release,
                        height: 46,
                        onPressed: _deciding ? null : () => _decide('release'),
                      ),
                  ],
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}

class _OtherRequestRow extends StatelessWidget {
  final Map<String, dynamic> row;
  final _L l;
  const _OtherRequestRow({required this.row, required this.l});

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final status = row['status']?.toString() ?? '';
    return Container(
      margin: const EdgeInsets.only(bottom: 6),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
      decoration: BoxDecoration(
        color: m.surfaceAlt,
        borderRadius: BorderRadius.circular(10),
      ),
      child: Row(
        children: [
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  row['renterName']?.toString() ?? l.renter,
                  style: (l.ar
                      ? GoogleFonts.notoNaskhArabic
                      : GoogleFonts.josefinSans)(
                    fontSize: 13,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
                ),
                Text(
                  [
                    if (row['unitNumber'] != null)
                      '${l.unit} ${row['unitNumber']}',
                    Formatters.timeAgo(row['createdAt']?.toString(), ar: l.ar),
                  ].join(' · '),
                  style: GoogleFonts.josefinSans(
                    fontSize: 11,
                    color: m.textMuted,
                  ),
                ),
              ],
            ),
          ),
          StatusBadge(label: l.status(status), color: _statusColor(status, m)),
        ],
      ),
    );
  }
}

class _DetailRow extends StatelessWidget {
  const _DetailRow({required this.icon, required this.value, required this.m});

  final IconData icon;
  final String value;
  final MiftahColors m;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsetsDirectional.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 15, color: m.textMuted),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              value,
              style: context.isAr
                  ? GoogleFonts.notoNaskhArabic(
                      fontSize: 13,
                      color: m.textSecondary,
                    )
                  : GoogleFonts.josefinSans(
                      fontSize: 13,
                      color: m.textSecondary,
                    ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ContactBtn extends StatelessWidget {
  final IconData icon;
  final String label;
  final bool ar;
  final VoidCallback onTap;

  const _ContactBtn({
    required this.icon,
    required this.label,
    required this.ar,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 10),
        decoration: BoxDecoration(
          color: AppColors.accent.withValues(alpha: 0.08),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: AppColors.accent.withValues(alpha: 0.3)),
        ),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Icon(icon, size: 16, color: AppColors.accentDark),
            const SizedBox(width: 6),
            Text(
              label,
              style:
                  (ar ? GoogleFonts.notoNaskhArabic : GoogleFonts.josefinSans)(
                fontSize: 12,
                color: m.isDark ? AppColors.accent : AppColors.accentDark,
                fontWeight: FontWeight.w600,
                letterSpacing: ar ? 0 : 0.4,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// A [RefreshIndicator] over a non-scrolling child cannot be pulled, so the
/// empty and error states are given something to scroll.
class _Scrollable extends StatelessWidget {
  const _Scrollable({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: child,
        ),
      ),
    );
  }
}
```

- [ ] **Step 5: Run the test — expect pass**, then analyze:
```bash
cd /Users/kunalsharma/datagami/rentaxis/mobile/apps/manager && flutter test test/booking_approvals_screen_test.dart && flutter analyze
```
Expect: `All tests passed!` then `No issues found!`

- [ ] **Step 6: Commit**:
```bash
cd /Users/kunalsharma/datagami/rentaxis && git add mobile/apps/manager && git commit -m "$(cat <<'EOF'
feat(manager): booking approvals screen with competing-request detail sheet

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task M4: Manager — router entries and More-menu entry points

**Files:**
- Modify: `mobile/apps/manager/lib/router.dart` (imports ~line 43; routes after the `/gate-passes/vendors` block ~line 247)
- Modify: `mobile/apps/manager/lib/screens/more_screen.dart` (menu after the Gate section ~line 78; `_L` strings ~line 625)

Entry-point note: the dashboard `_QuickActions` grid is a deliberate 4-across layout (a 5th tile strands one on a second row — see the grid comments in `dashboard_screen.dart`), so the manager entry points go in the More menu, matching the precedent of Listings and all four Gate features.

- [ ] **Step 1: Router imports** — in `mobile/apps/manager/lib/router.dart`:

```dart
// old
import 'screens/gatepass/register_gate_vendor_screen.dart';
// new
import 'screens/gatepass/register_gate_vendor_screen.dart';
import 'screens/facilities/facilities_screen.dart';
import 'screens/facilities/booking_approvals_screen.dart';
```

- [ ] **Step 2: Router routes** — same file:

```dart
// old
          GoRoute(
            path: '/gate-passes/vendors',
            pageBuilder: (context, state) =>
                fadeTransition(const RegisterGateVendorScreen(), state),
          ),
// new
          GoRoute(
            path: '/gate-passes/vendors',
            pageBuilder: (context, state) =>
                fadeTransition(const RegisterGateVendorScreen(), state),
          ),
          // Facility screens are parameterless and self-fetching for the same
          // reason as the gate-pass screens above: this router rebuilds on
          // authProvider and would discard `extra`.
          GoRoute(
            path: '/facilities',
            pageBuilder: (context, state) =>
                fadeTransition(const FacilitiesScreen(), state),
          ),
          GoRoute(
            path: '/bookings',
            pageBuilder: (context, state) =>
                fadeTransition(const BookingApprovalsScreen(), state),
          ),
```

- [ ] **Step 3: More-menu section** — in `mobile/apps/manager/lib/screens/more_screen.dart`:

```dart
// old
                _sectionLabel(l.finance, l.ar, m),
// new
                _sectionLabel(l.community, l.ar, m),
                _MenuCard(
                  items: [
                    _MenuRow(
                      icon: Icons.pool_outlined,
                      label: l.facilities,
                      onTap: () => context.push('/facilities'),
                    ),
                    _MenuRow(
                      icon: Icons.event_available_outlined,
                      label: l.bookingRequests,
                      onTap: () => context.push('/bookings'),
                    ),
                  ],
                ),
                _sectionLabel(l.finance, l.ar, m),
```

- [ ] **Step 4: More-menu strings** — same file, in the `_L` class:

```dart
// old
  String get registerUnitVendor =>
      ar ? 'تسجيل مورّد للوحدة' : 'Register Unit Vendor';
// new
  String get registerUnitVendor =>
      ar ? 'تسجيل مورّد للوحدة' : 'Register Unit Vendor';
  String get community => ar ? 'المجتمع' : 'Community';
  String get facilities =>
      ar ? 'المرافق ومواقف السيارات' : 'Amenities & Parking';
  String get bookingRequests => ar ? 'طلبات الحجز' : 'Booking Requests';
```

- [ ] **Step 5: Verify**:
```bash
cd /Users/kunalsharma/datagami/rentaxis/mobile/apps/manager && flutter analyze
```
Expect: `No issues found!`

- [ ] **Step 6: Commit**:
```bash
cd /Users/kunalsharma/datagami/rentaxis && git add mobile/apps/manager && git commit -m "$(cat <<'EOF'
feat(manager): facilities and bookings routes plus More-menu entries

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task M5: Renter — facilities browse screen and booking-request sheet (TDD)

**Files:**
- Create: `mobile/apps/renter/lib/providers/facility_provider.dart`
- Test: `mobile/apps/renter/test/support/fake_facility_service.dart` (new), `mobile/apps/renter/test/facilities_screen_test.dart` (new)
- Create: `mobile/apps/renter/lib/screens/facilities/facilities_screen.dart`

- [ ] **Step 1: Renter facility providers** — create `mobile/apps/renter/lib/providers/facility_provider.dart`:

```dart
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

/// Renter-side facility state.
///
/// All `autoDispose`: pending counts, spot holds and decisions change
/// server-side while the renter is elsewhere, so re-entering a screen
/// re-reads the server rather than showing a kept-alive copy.

/// GET /v1/facilities/my — amenities + parking spots visible to the caller's
/// active-lease unit(s). Counts only; no other applicants' identities.
final myFacilitiesProvider =
    FutureProvider.autoDispose<Map<String, dynamic>>((ref) async {
  final service = ref.watch(facilityServiceProvider);
  return service.myFacilities();
});

/// The caller's own booking requests, all statuses, createdAt ASC
/// (server-ordered; kept as-is per the project's created-ascending standard).
final myBookingRequestsProvider =
    FutureProvider.autoDispose<List<Map<String, dynamic>>>((ref) async {
  final service = ref.watch(facilityServiceProvider);
  final rows = await service.myBookings();
  return rows.whereType<Map>().map((r) => Map<String, dynamic>.from(r)).toList();
});
```

- [ ] **Step 2: Copy the fake service** — create `mobile/apps/renter/test/support/fake_facility_service.dart` with **exactly the same content** as `mobile/apps/manager/test/support/fake_facility_service.dart` from Task M3 Step 1 (the class is app-agnostic; each app package keeps its own copy, matching the existing `test/support` convention):
```bash
cp /Users/kunalsharma/datagami/rentaxis/mobile/apps/manager/test/support/fake_facility_service.dart /Users/kunalsharma/datagami/rentaxis/mobile/apps/renter/test/support/fake_facility_service.dart
```

- [ ] **Step 3: Write the failing widget test** — create `mobile/apps/renter/test/facilities_screen_test.dart`:

```dart
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:rentaxis_core/rentaxis_core.dart';
import 'package:renter/providers/gate_pass_provider.dart';
import 'package:renter/screens/facilities/facilities_screen.dart';

import 'support/fake_facility_service.dart';

void main() {
  testWidgets('request sheet posts resourceType, resourceId, unitId and note',
      (tester) async {
    final fake = FakeFacilityService(amenities: [
      {
        'id': 'am-1',
        'propertyId': 'prop-1',
        'propertyName': 'Marina Heights',
        'nameEn': 'Pool',
        'nameAr': 'المسبح',
        'description': 'Rooftop pool',
        'bookable': true,
        'pendingCount': 2,
      },
    ]);

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          facilityServiceProvider.overrideWithValue(fake),
          activeLeasesProvider.overrideWith((ref) async => [
                {
                  'id': 'lease-1',
                  'unitId': 'unit-1',
                  'unitIdentifier': '1204',
                  'propertyId': 'prop-1',
                  'status': 'ACTIVE',
                },
              ]),
        ],
        child: MaterialApp(
          theme: AppTheme.lightTheme,
          home: const FacilitiesScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Pool'), findsOneWidget);

    await tester.tap(find.text('REQUEST'));
    await tester.pumpAndSettle();

    await tester.enterText(find.byKey(const Key('booking-note')), 'Birthday');
    await tester.tap(find.text('SEND REQUEST'));
    await tester.pumpAndSettle();

    expect(fake.createCalls.single, {
      'resourceType': 'AMENITY',
      'resourceId': 'am-1',
      'unitId': 'unit-1',
      'note': 'Birthday',
    });
  });
}
```

- [ ] **Step 4: Run — expect failure** (screen file missing):
```bash
cd /Users/kunalsharma/datagami/rentaxis/mobile/apps/renter && flutter test test/facilities_screen_test.dart
```
Expect a compile error on the missing `facilities_screen.dart` import.

- [ ] **Step 5: Create the screen** — `mobile/apps/renter/lib/screens/facilities/facilities_screen.dart`:

```dart
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../providers/facility_provider.dart';
import '../../providers/gate_pass_provider.dart' show activeLeasesProvider;

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'المرافق' : 'Facilities';
  String get amenitiesSection => ar ? 'المرافق' : 'Amenities';
  String get parkingSection => ar ? 'مواقف السيارات' : 'Parking';
  String get loadFailed =>
      ar ? 'فشل تحميل المرافق' : 'Failed to load facilities';
  String get nothingHere => ar ? 'لا توجد مرافق' : 'No facilities yet';
  String get nothingHereSub => ar
      ? 'عندما يضيف مالك العقار مرافق أو مواقف لوحدتك، تظهر هنا.'
      : 'When your landlord adds amenities or parking for your unit, '
            'they appear here.';
  String get request => ar ? 'طلب حجز' : 'Request';
  String get held => ar ? 'محجوز' : 'Held';
  String get notBookable => ar ? 'غير قابل للحجز' : 'Not bookable';
  String get unit => ar ? 'الوحدة' : 'Unit';
  String get preferredDate =>
      ar ? 'التاريخ المفضل (اختياري)' : 'Preferred date (optional)';
  String get pickADate => ar ? 'اختر تاريخًا' : 'Pick a date';
  String get noteOptional => ar ? 'ملاحظة (اختياري)' : 'Note (optional)';
  String get noteHint =>
      ar ? 'مثال: حفلة عائلية يوم الجمعة' : 'e.g. family gathering on Friday';
  String get sendRequest => ar ? 'إرسال الطلب' : 'Send request';
  String get requestSent => ar ? 'تم إرسال طلبك' : 'Your request was sent';
  String get spotTaken => ar
      ? 'هذا الموقف محجوز بالفعل لمستأجر آخر.'
      : 'This spot is already held by another renter.';
  String get cannotBook =>
      ar ? 'هذا المرفق غير قابل للحجز.' : 'This facility is not bookable.';
  String get noLeaseForProperty => ar
      ? 'لا يوجد لديك عقد إيجار نشط في هذا العقار.'
      : 'You have no active lease at this property.';
  String get requestFailed => ar
      ? 'تعذر إرسال الطلب. حاول مرة أخرى.'
      : 'Could not send the request. Try again.';
  String get leasesLoadFailed =>
      ar ? 'تعذر التحقق من عقد إيجارك.' : 'Could not check your tenancy.';
  String requestTitle(String name) =>
      ar ? 'طلب حجز $name' : 'Request $name';
  String pendingHint(int n) => ar
      ? 'الطلبات المعلقة: $n'
      : n == 1
          ? '1 pending request'
          : '$n pending requests';
  String levelLabel(String level) => ar ? 'الطابق $level' : 'Level $level';
  String get coveredPill => ar ? 'مسقوف' : 'Covered';

  String status(String value) => switch (value) {
    'PENDING' => ar ? 'قيد الانتظار' : 'PENDING',
    'APPROVED' => ar ? 'مقبول' : 'APPROVED',
    'REJECTED' => ar ? 'مرفوض' : 'REJECTED',
    'CANCELLED' => ar ? 'ملغي' : 'CANCELLED',
    'RELEASED' => ar ? 'محرَّر' : 'RELEASED',
    _ => value.replaceAll('_', ' '),
  };
}

Color _statusColor(String? status, MiftahColors m) => switch (status) {
  'PENDING' => m.warning,
  'APPROVED' => m.success,
  'REJECTED' => m.danger,
  'RELEASED' => AppColors.accentDark,
  _ => m.textMuted,
};

String _displayName(Map<String, dynamic> row, bool ar) {
  final nameAr = row['nameAr']?.toString();
  if (ar && nameAr != null && nameAr.isNotEmpty) return nameAr;
  return row['nameEn']?.toString() ?? '—';
}

List<Map<String, dynamic>> _rows(dynamic value) => (value as List? ?? const [])
    .whereType<Map>()
    .map((r) => Map<String, dynamic>.from(r))
    .toList();

/// The renter's own open request per resource id. PENDING/APPROVED only —
/// terminal statuses do not block a new request. `/v1/bookings/my` is
/// createdAt ASC, so the last write wins (the newest open request).
Map<String, Map<String, dynamic>> _openByResource(
  List<Map<String, dynamic>> mine,
) {
  final result = <String, Map<String, dynamic>>{};
  for (final row in mine) {
    final status = row['status']?.toString();
    if (status != 'PENDING' && status != 'APPROVED') continue;
    final resourceId = (row['amenityId'] ?? row['parkingSpotId'])?.toString();
    if (resourceId != null) result[resourceId] = row;
  }
  return result;
}

/// `preferredDate` is a LocalDate server-side — send `yyyy-MM-dd`, never an
/// instant (an instant would shift by the device's UTC offset).
String _localDate(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-'
    '${d.month.toString().padLeft(2, '0')}-'
    '${d.day.toString().padLeft(2, '0')}';

/// Browse the amenities and parking spots visible to my unit(s), and raise
/// booking requests. Parameterless and self-fetching (router discards `extra`
/// on auth rebuilds — see router.dart).
class FacilitiesScreen extends ConsumerWidget {
  const FacilitiesScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final facilities = ref.watch(myFacilitiesProvider);
    final mine = ref.watch(myBookingRequestsProvider);

    Future<void> refresh() async {
      ref.invalidate(myFacilitiesProvider);
      ref.invalidate(myBookingRequestsProvider);
      await ref.read(myFacilitiesProvider.future);
    }

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        title: Text(
          l.title,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 17,
                  fontWeight: FontWeight.w600,
                  color: Colors.white,
                )
              : null,
        ),
        actions: [
          IconButton(
            key: const Key('my-requests'),
            icon: const Icon(Icons.history),
            onPressed: () => context.push('/facilities/requests'),
          ),
        ],
      ),
      body: RefreshIndicator(
        color: AppColors.accent,
        onRefresh: refresh,
        child: facilities.when(
          loading: () => const ListShimmer(itemCount: 4),
          error: (error, _) => _Scrollable(
            child: ErrorState(message: l.loadFailed, onRetry: refresh),
          ),
          data: (data) {
            final amenities = _rows(data['amenities']);
            final spots = _rows(data['parkingSpots']);
            if (amenities.isEmpty && spots.isEmpty) {
              return _Scrollable(
                child: EmptyState(
                  icon: Icons.pool_outlined,
                  title: l.nothingHere,
                  subtitle: l.nothingHereSub,
                ),
              );
            }
            final open = _openByResource(mine.valueOrNull ?? const []);
            return ListView(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: EdgeInsets.fromLTRB(
                16,
                12,
                16,
                AppInsets.bottomNav(context),
              ),
              children: [
                if (amenities.isNotEmpty) ...[
                  _SectionLabel(text: l.amenitiesSection),
                  for (final (i, row) in amenities.indexed)
                    AnimatedListItem(
                      index: i,
                      child: _AmenityCard(
                        row: row,
                        myOpen: open[row['id']?.toString()],
                        l: l,
                      ),
                    ),
                ],
                if (spots.isNotEmpty) ...[
                  _SectionLabel(text: l.parkingSection),
                  for (final (i, row) in spots.indexed)
                    AnimatedListItem(
                      index: i,
                      child: _SpotCard(
                        row: row,
                        myOpen: open[row['id']?.toString()],
                        l: l,
                      ),
                    ),
                ],
              ],
            );
          },
        ),
      ),
    );
  }
}

class _SectionLabel extends StatelessWidget {
  final String text;
  const _SectionLabel({required this.text});

  @override
  Widget build(BuildContext context) {
    final ar = context.isAr;
    return Padding(
      padding: const EdgeInsetsDirectional.only(start: 2, top: 8, bottom: 10),
      child: Text(
        ar ? text : text.toUpperCase(),
        style: ar
            ? GoogleFonts.notoNaskhArabic(
                fontSize: 12.5,
                color: AppColors.accentDark,
              )
            : GoogleFonts.josefinSans(
                fontSize: 10.5,
                letterSpacing: 2.4,
                color: AppColors.accentDark,
              ),
      ),
    );
  }
}

class _AmenityCard extends ConsumerWidget {
  final Map<String, dynamic> row;
  final Map<String, dynamic>? myOpen;
  final _L l;
  const _AmenityCard({required this.row, required this.myOpen, required this.l});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final bookable = row['bookable'] == true;
    final pendingCount = (row['pendingCount'] as num?)?.toInt() ?? 0;
    final property = row['propertyName']?.toString();
    final description = row['description']?.toString();

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.pool_outlined, size: 18, color: AppColors.accentDark),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  _displayName(row, l.ar),
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 14.5,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        ),
                ),
              ),
              if (myOpen != null)
                StatusBadge(
                  label: l.status(myOpen!['status']?.toString() ?? ''),
                  color: _statusColor(myOpen!['status']?.toString(), m),
                )
              else if (!bookable)
                StatusBadge(label: l.notBookable, color: m.textMuted),
            ],
          ),
          if (property != null) ...[
            const SizedBox(height: 4),
            Text(
              property,
              style: GoogleFonts.josefinSans(
                fontSize: 11.5,
                color: m.textMuted,
              ),
            ),
          ],
          if (description != null && description.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(
              description,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts.josefinSans)(
                fontSize: 12.5,
                color: m.textSecondary,
                height: 1.4,
              ),
            ),
          ],
          if (pendingCount > 0) ...[
            const SizedBox(height: 6),
            Text(
              l.pendingHint(pendingCount),
              style: (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts.josefinSans)(fontSize: 12, color: m.warning),
            ),
          ],
          if (bookable && myOpen == null) ...[
            const SizedBox(height: 12),
            GoldButton(
              label: l.request,
              height: 42,
              onPressed: () => _openRequestSheet(
                context,
                ref,
                resourceType: 'AMENITY',
                row: row,
                l: l,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

class _SpotCard extends ConsumerWidget {
  final Map<String, dynamic> row;
  final Map<String, dynamic>? myOpen;
  final _L l;
  const _SpotCard({required this.row, required this.myOpen, required this.l});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final held = row['held'] == true;
    final pendingCount = (row['pendingCount'] as num?)?.toInt() ?? 0;
    final level = row['level']?.toString();
    final property = row['propertyName']?.toString();
    final meta = [
      if (property != null) property,
      if (level != null && level.isNotEmpty) l.levelLabel(level),
      if (row['covered'] == true) l.coveredPill,
    ].join(' · ');

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                Icons.local_parking_outlined,
                size: 18,
                color: AppColors.accentDark,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  row['spotNumber']?.toString() ?? '—',
                  style: GoogleFonts.josefinSans(
                    fontSize: 14.5,
                    fontWeight: FontWeight.w600,
                    color: m.textPrimary,
                  ),
                ),
              ),
              if (myOpen != null)
                StatusBadge(
                  label: l.status(myOpen!['status']?.toString() ?? ''),
                  color: _statusColor(myOpen!['status']?.toString(), m),
                )
              else if (held)
                StatusBadge(label: l.held, color: m.warning),
            ],
          ),
          if (meta.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(
              meta,
              style: GoogleFonts.josefinSans(
                fontSize: 11.5,
                color: m.textMuted,
              ),
            ),
          ],
          if (pendingCount > 0) ...[
            const SizedBox(height: 6),
            Text(
              l.pendingHint(pendingCount),
              style: (l.ar
                  ? GoogleFonts.notoNaskhArabic
                  : GoogleFonts.josefinSans)(fontSize: 12, color: m.warning),
            ),
          ],
          if (!held && myOpen == null) ...[
            const SizedBox(height: 12),
            GoldButton(
              label: l.request,
              height: 42,
              onPressed: () => _openRequestSheet(
                context,
                ref,
                resourceType: 'PARKING_SPOT',
                row: row,
                l: l,
              ),
            ),
          ],
        ],
      ),
    );
  }
}

Future<void> _openRequestSheet(
  BuildContext context,
  WidgetRef ref, {
  required String resourceType,
  required Map<String, dynamic> row,
  required _L l,
}) async {
  final resourceId = row['id']?.toString();
  final propertyId = row['propertyId']?.toString();
  if (resourceId == null || propertyId == null) return;
  final name = resourceType == 'AMENITY'
      ? _displayName(row, l.ar)
      : (row['spotNumber']?.toString() ?? '—');
  final created = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    backgroundColor: context.miftah.surface,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
    ),
    builder: (_) => _RequestSheet(
      resourceType: resourceType,
      resourceId: resourceId,
      propertyId: propertyId,
      resourceName: name,
    ),
  );
  if (created == true) {
    ref.invalidate(myFacilitiesProvider);
    ref.invalidate(myBookingRequestsProvider);
    if (context.mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(l.requestSent),
          behavior: SnackBarBehavior.floating,
        ),
      );
    }
  }
}

/// Booking-request sheet: preferred date + note, unit resolved from the
/// caller's ACTIVE leases at the resource's property.
///
/// The unit comes from an ACTIVE lease and only an ACTIVE one — the backend
/// 404s a unit that is not on the caller's active lease (deliberately 404,
/// not 403), same rule as the gate-pass create screen.
class _RequestSheet extends ConsumerStatefulWidget {
  final String resourceType;
  final String resourceId;
  final String propertyId;
  final String resourceName;

  const _RequestSheet({
    required this.resourceType,
    required this.resourceId,
    required this.propertyId,
    required this.resourceName,
  });

  @override
  ConsumerState<_RequestSheet> createState() => _RequestSheetState();
}

class _RequestSheetState extends ConsumerState<_RequestSheet> {
  final _noteCtrl = TextEditingController();
  DateTime? _preferredDate;
  String? _selectedUnitId;
  bool _submitting = false;

  @override
  void dispose() {
    _noteCtrl.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    final l = _L(context.isAr);
    final unitId = _selectedUnitId;
    if (unitId == null) return;
    setState(() => _submitting = true);
    try {
      await ref.read(facilityServiceProvider).createBooking({
        'resourceType': widget.resourceType,
        'resourceId': widget.resourceId,
        'unitId': unitId,
        if (_preferredDate != null)
          'preferredDate': _localDate(_preferredDate!),
        if (_noteCtrl.text.trim().isNotEmpty) 'note': _noteCtrl.text.trim(),
      });
      if (!mounted) return;
      Navigator.pop(context, true);
    } on DioException catch (error) {
      if (!mounted) return;
      setState(() => _submitting = false);
      _toast(_describe(error, l));
    } catch (_) {
      if (!mounted) return;
      setState(() => _submitting = false);
      _toast(l.requestFailed);
    }
  }

  String _describe(DioException error, _L l) {
    final status = error.response?.statusCode;
    if (status == 409) return l.spotTaken;
    if (status == 400) return l.cannotBook;
    if (status == 404) return l.noLeaseForProperty;
    return l.requestFailed;
  }

  void _toast(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message), behavior: SnackBarBehavior.floating),
    );
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final leases = ref.watch(activeLeasesProvider);

    return Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.viewInsetsOf(context).bottom),
      child: SafeArea(
        child: ConstrainedBox(
          constraints: BoxConstraints(
            maxHeight: MediaQuery.sizeOf(context).height * 0.85,
          ),
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
            child: leases.when(
              loading: () => const SizedBox(
                height: 180,
                child: Center(
                  child: CircularProgressIndicator(color: AppColors.accent),
                ),
              ),
              error: (error, _) => ErrorState(
                message: l.leasesLoadFailed,
                onRetry: () => ref.invalidate(activeLeasesProvider),
              ),
              data: (rows) {
                final mine = rows
                    .where(
                      (r) => r['propertyId']?.toString() == widget.propertyId,
                    )
                    .toList();
                if (mine.isEmpty) {
                  // Every field below would be filled in for a request the
                  // server will 404; carry the explanation here instead.
                  return Padding(
                    padding: const EdgeInsets.symmetric(vertical: 24),
                    child: Text(
                      l.noLeaseForProperty,
                      style: (l.ar
                          ? GoogleFonts.notoNaskhArabic
                          : GoogleFonts.josefinSans)(
                        fontSize: 13.5,
                        color: m.textSecondary,
                        height: 1.5,
                      ),
                    ),
                  );
                }
                // One lease is the common case; select it silently.
                _selectedUnitId ??= mine.first['unitId']?.toString();
                return Column(
                  mainAxisSize: MainAxisSize.min,
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      l.requestTitle(widget.resourceName),
                      style: l.ar
                          ? GoogleFonts.notoNaskhArabic(
                              fontSize: 17,
                              fontWeight: FontWeight.w600,
                              color: m.textPrimary,
                            )
                          : GoogleFonts.josefinSans(
                              fontSize: 16,
                              fontWeight: FontWeight.w600,
                              color: m.textPrimary,
                            ),
                    ),
                    const SizedBox(height: 16),
                    if (mine.length > 1) ...[
                      DropdownButtonFormField<String>(
                        initialValue: _selectedUnitId,
                        decoration: InputDecoration(labelText: l.unit),
                        items: [
                          for (final lease in mine)
                            DropdownMenuItem(
                              value: lease['unitId']?.toString(),
                              child: Text(
                                lease['unitIdentifier']?.toString() ?? '—',
                              ),
                            ),
                        ],
                        onChanged: (id) =>
                            setState(() => _selectedUnitId = id),
                      ),
                      const SizedBox(height: 12),
                    ],
                    Text(
                      l.preferredDate,
                      style: TextStyle(
                        fontSize: 12.5,
                        fontWeight: FontWeight.w700,
                        color: m.textSecondary,
                      ),
                    ),
                    const SizedBox(height: 6),
                    InkWell(
                      borderRadius: BorderRadius.circular(12),
                      onTap: () async {
                        final now = DateTime.now();
                        final picked = await showDatePicker(
                          context: context,
                          initialDate: _preferredDate ?? now,
                          firstDate: DateTime(now.year, now.month, now.day),
                          lastDate: now.add(const Duration(days: 365)),
                        );
                        if (picked != null) {
                          setState(() => _preferredDate = picked);
                        }
                      },
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 14,
                          vertical: 15,
                        ),
                        decoration: BoxDecoration(
                          color: m.surface,
                          border: Border.all(color: m.border),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Row(
                          children: [
                            Icon(
                              Icons.calendar_today_outlined,
                              size: 17,
                              color: m.textMuted,
                            ),
                            const SizedBox(width: 10),
                            Text(
                              _preferredDate == null
                                  ? l.pickADate
                                  : '${_preferredDate!.day.toString().padLeft(2, '0')}/'
                                      '${_preferredDate!.month.toString().padLeft(2, '0')}/'
                                      '${_preferredDate!.year}',
                              style: TextStyle(
                                fontSize: 14,
                                color: _preferredDate == null
                                    ? m.textMuted
                                    : m.textPrimary,
                                fontWeight: _preferredDate == null
                                    ? FontWeight.w400
                                    : FontWeight.w600,
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                    const SizedBox(height: 12),
                    TextField(
                      key: const Key('booking-note'),
                      controller: _noteCtrl,
                      maxLength: 500,
                      maxLines: 2,
                      decoration: InputDecoration(
                        labelText: l.noteOptional,
                        hintText: l.noteHint,
                        counterText: '',
                      ),
                    ),
                    const SizedBox(height: 16),
                    GoldButton(
                      key: const Key('booking-submit'),
                      label: l.sendRequest,
                      height: 48,
                      onPressed: _submitting ? null : _submit,
                    ),
                  ],
                );
              },
            ),
          ),
        ),
      ),
    );
  }
}

/// A [RefreshIndicator] over a non-scrolling child cannot be pulled, so the
/// empty and error states are given something to scroll.
class _Scrollable extends StatelessWidget {
  const _Scrollable({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: child,
        ),
      ),
    );
  }
}
```

- [ ] **Step 6: Run the test — expect pass**, then analyze:
```bash
cd /Users/kunalsharma/datagami/rentaxis/mobile/apps/renter && flutter test test/facilities_screen_test.dart && flutter analyze
```
Expect: `All tests passed!` then `No issues found!`

- [ ] **Step 7: Commit**:
```bash
cd /Users/kunalsharma/datagami/rentaxis && git add mobile/apps/renter && git commit -m "$(cat <<'EOF'
feat(renter): facilities browse and booking request flow

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task M6: Renter — my requests screen (cancel pending, release approved parking)

**Files:**
- Create: `mobile/apps/renter/lib/screens/facilities/my_requests_screen.dart`

- [ ] **Step 1: Create the screen** — `mobile/apps/renter/lib/screens/facilities/my_requests_screen.dart`:

```dart
import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:rentaxis_core/rentaxis_core.dart';

import '../../providers/facility_provider.dart';

/// Screen strings (EN/AR). Lightweight per-screen pattern — see arabic-brief.
class _L {
  _L(this.ar);
  final bool ar;

  String get title => ar ? 'طلبات الحجز' : 'My Requests';
  String get loadFailed =>
      ar ? 'فشل تحميل الطلبات' : 'Failed to load your requests';
  String get nothingYet => ar ? 'لا توجد طلبات بعد' : 'No requests yet';
  String get nothingYetSub => ar
      ? 'عندما تطلب حجز مرفق أو موقف سيارة، تظهر طلباتك هنا.'
      : 'When you request an amenity or a parking spot, '
            'your requests appear here.';
  String get unit => ar ? 'وحدة' : 'Unit';
  String get preferred => ar ? 'التاريخ المفضل:' : 'Preferred:';
  String get managerNote => ar ? 'ملاحظة الإدارة' : 'Manager note';
  String get cancelRequest => ar ? 'إلغاء الطلب' : 'Cancel request';
  String get releaseSpot => ar ? 'تحرير الموقف' : 'Release spot';
  String get keep => ar ? 'تراجع' : 'Keep';
  String get confirm => ar ? 'تأكيد' : 'Confirm';
  String get cancelConfirm =>
      ar ? 'إلغاء طلب الحجز هذا؟' : 'Cancel this booking request?';
  String get releaseConfirm => ar
      ? 'تحرير هذا الموقف؟ سيصبح متاحًا لغيرك ولن يعود إليك تلقائيًا.'
      : 'Release this spot? It becomes available to others and does not '
            'come back automatically.';
  String get cancelled => ar ? 'تم إلغاء الطلب' : 'Request cancelled';
  String get released => ar ? 'تم تحرير الموقف' : 'Spot released';
  String get actionFailed => ar
      ? 'تعذر تنفيذ الإجراء. حاول مرة أخرى.'
      : 'Could not complete the action. Try again.';
  String get alreadyDecided => ar
      ? 'تغيّرت حالة هذا الطلب. جارٍ تحديث القائمة.'
      : 'This request changed state. Refreshing the list.';

  String status(String value) => switch (value) {
    'PENDING' => ar ? 'قيد الانتظار' : 'PENDING',
    'APPROVED' => ar ? 'مقبول' : 'APPROVED',
    'REJECTED' => ar ? 'مرفوض' : 'REJECTED',
    'CANCELLED' => ar ? 'ملغي' : 'CANCELLED',
    'RELEASED' => ar ? 'محرَّر' : 'RELEASED',
    _ => value.replaceAll('_', ' '),
  };
}

Color _statusColor(String? status, MiftahColors m) => switch (status) {
  'PENDING' => m.warning,
  'APPROVED' => m.success,
  'REJECTED' => m.danger,
  'RELEASED' => AppColors.accentDark,
  _ => m.textMuted,
};

/// The renter's own booking requests, with self-service actions:
/// cancel a PENDING request, release an APPROVED parking spot.
class MyRequestsScreen extends ConsumerWidget {
  const MyRequestsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final m = context.miftah;
    final l = _L(context.isAr);
    final requests = ref.watch(myBookingRequestsProvider);

    return Scaffold(
      backgroundColor: m.background,
      appBar: AppBar(
        title: Text(
          l.title,
          style: l.ar
              ? GoogleFonts.notoNaskhArabic(
                  fontSize: 17,
                  fontWeight: FontWeight.w600,
                  color: Colors.white,
                )
              : null,
        ),
      ),
      body: RefreshIndicator(
        color: AppColors.accent,
        onRefresh: () => ref.refresh(myBookingRequestsProvider.future),
        child: requests.when(
          loading: () => const ListShimmer(itemCount: 4),
          error: (error, _) => _Scrollable(
            child: ErrorState(
              message: l.loadFailed,
              onRetry: () => ref.invalidate(myBookingRequestsProvider),
            ),
          ),
          data: (rows) {
            if (rows.isEmpty) {
              return _Scrollable(
                child: EmptyState(
                  icon: Icons.event_note_outlined,
                  title: l.nothingYet,
                  subtitle: l.nothingYetSub,
                ),
              );
            }
            return ListView.builder(
              physics: const AlwaysScrollableScrollPhysics(),
              padding: EdgeInsets.fromLTRB(
                16,
                12,
                16,
                AppInsets.bottomNav(context),
              ),
              itemCount: rows.length,
              itemBuilder: (context, i) => AnimatedListItem(
                index: i,
                child: _RequestCard(request: rows[i], l: l),
              ),
            );
          },
        ),
      ),
    );
  }
}

/// Stateful for [_busy]: cancel/release are network calls, and a double-tap
/// would post two — the second coming back 400 on a request that did in fact
/// change, turning success into an error message.
class _RequestCard extends ConsumerStatefulWidget {
  final Map<String, dynamic> request;
  final _L l;
  const _RequestCard({required this.request, required this.l});

  @override
  ConsumerState<_RequestCard> createState() => _RequestCardState();
}

class _RequestCardState extends ConsumerState<_RequestCard> {
  bool _busy = false;

  Future<void> _run({required bool release}) async {
    final l = widget.l;
    final id = widget.request['id']?.toString();
    if (id == null || _busy) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        content: Text(release ? l.releaseConfirm : l.cancelConfirm),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(ctx, false),
            child: Text(l.keep),
          ),
          TextButton(
            onPressed: () => Navigator.pop(ctx, true),
            style: TextButton.styleFrom(foregroundColor: AppColors.danger),
            child: Text(l.confirm),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;
    setState(() => _busy = true);
    try {
      final service = ref.read(facilityServiceProvider);
      if (release) {
        await service.releaseBooking(id);
      } else {
        await service.cancelBooking(id);
      }
      if (!mounted) return;
      ref.invalidate(myBookingRequestsProvider);
      ref.invalidate(myFacilitiesProvider); // held state / counts changed
      _toast(release ? l.released : l.cancelled, AppColors.success);
    } on DioException catch (error) {
      if (!mounted) return;
      if (error.response?.statusCode == 400) {
        // The request left the state this action needs (someone decided it,
        // or it was already released). Refresh instead of advising a retry.
        ref.invalidate(myBookingRequestsProvider);
        _toast(l.alreadyDecided, AppColors.warning);
      } else {
        _toast(l.actionFailed, AppColors.danger);
      }
    } catch (_) {
      if (mounted) _toast(l.actionFailed, AppColors.danger);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _toast(String message, Color background) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(message),
        backgroundColor: background,
        behavior: SnackBarBehavior.floating,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = widget.l;
    final request = widget.request;
    final id = request['id']?.toString() ?? '';
    final status = request['status']?.toString() ?? '';
    final parking = request['resourceType'] == 'PARKING_SPOT';
    final note = request['note']?.toString();
    final adminNote = request['adminNote']?.toString();

    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: m.surface,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: m.border),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(
                parking ? Icons.local_parking_outlined : Icons.pool_outlined,
                size: 17,
                color: AppColors.accentDark,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  request['resourceName']?.toString() ?? '—',
                  style: l.ar
                      ? GoogleFonts.notoNaskhArabic(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        )
                      : GoogleFonts.josefinSans(
                          fontSize: 14.5,
                          fontWeight: FontWeight.w600,
                          color: m.textPrimary,
                        ),
                ),
              ),
              StatusBadge(
                label: l.status(status),
                color: _statusColor(status, m),
              ),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            [
              if (request['unitNumber'] != null)
                '${l.unit} ${request['unitNumber']}',
              if (request['preferredDate'] != null)
                '${l.preferred} ${Formatters.date(request['preferredDate']?.toString(), ar: l.ar)}',
              Formatters.timeAgo(request['createdAt']?.toString(), ar: l.ar),
            ].join(' · '),
            style: (l.ar
                ? GoogleFonts.notoNaskhArabic
                : GoogleFonts.josefinSans)(
              fontSize: 12,
              color: m.textSecondary,
            ),
          ),
          if (note != null && note.isNotEmpty) ...[
            const SizedBox(height: 8),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: m.surfaceAlt,
                borderRadius: BorderRadius.circular(10),
              ),
              child: Text(
                note,
                style: (l.ar
                    ? GoogleFonts.notoNaskhArabic
                    : GoogleFonts.josefinSans)(
                  fontSize: 12.5,
                  color: m.textSecondary,
                  height: 1.4,
                ),
              ),
            ),
          ],
          if (adminNote != null && adminNote.isNotEmpty) ...[
            const SizedBox(height: 8),
            Container(
              width: double.infinity,
              padding: const EdgeInsets.all(10),
              decoration: BoxDecoration(
                color: AppColors.accent.withValues(alpha: 0.08),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(
                  color: AppColors.accent.withValues(alpha: 0.3),
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    l.managerNote,
                    style: GoogleFonts.josefinSans(
                      fontSize: 10,
                      letterSpacing: 1.8,
                      fontWeight: FontWeight.w600,
                      color: AppColors.accentDark,
                    ),
                  ),
                  const SizedBox(height: 3),
                  Text(
                    adminNote,
                    style: (l.ar
                        ? GoogleFonts.notoNaskhArabic
                        : GoogleFonts.josefinSans)(
                      fontSize: 12.5,
                      color: m.textSecondary,
                      height: 1.4,
                    ),
                  ),
                ],
              ),
            ),
          ],
          if (status == 'PENDING') ...[
            const SizedBox(height: 12),
            GoldButton.outlined(
              key: Key('cancel-$id'),
              label: l.cancelRequest,
              height: 42,
              onPressed: _busy ? null : () => _run(release: false),
            ),
          ] else if (status == 'APPROVED' && parking) ...[
            const SizedBox(height: 12),
            GoldButton.outlined(
              key: Key('release-$id'),
              label: l.releaseSpot,
              height: 42,
              onPressed: _busy ? null : () => _run(release: true),
            ),
          ],
        ],
      ),
    );
  }
}

/// A [RefreshIndicator] over a non-scrolling child cannot be pulled, so the
/// empty and error states are given something to scroll.
class _Scrollable extends StatelessWidget {
  const _Scrollable({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: child,
        ),
      ),
    );
  }
}
```

- [ ] **Step 2: Verify**:
```bash
cd /Users/kunalsharma/datagami/rentaxis/mobile/apps/renter && flutter analyze
```
Expect: `No issues found!`

- [ ] **Step 3: Commit**:
```bash
cd /Users/kunalsharma/datagami/rentaxis && git add mobile/apps/renter && git commit -m "$(cat <<'EOF'
feat(renter): my booking requests screen with cancel and release

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task M7: Renter — router entries and home-screen entry card

**Files:**
- Modify: `mobile/apps/renter/lib/router.dart` (imports ~line 25; routes after the `/gatepass` block ~line 182)
- Modify: `mobile/apps/renter/lib/screens/home_screen.dart` (insert card after `_QuickActions` ~line 94; new widget before the Quick-actions section ~line 758; `_L` strings ~line 1251)

- [ ] **Step 1: Router imports** — in `mobile/apps/renter/lib/router.dart`:

```dart
// old
import 'screens/gatepass/resident_approvals_screen.dart';
// new
import 'screens/gatepass/resident_approvals_screen.dart';
import 'screens/facilities/facilities_screen.dart';
import 'screens/facilities/my_requests_screen.dart';
```

- [ ] **Step 2: Router routes** — same file, append after the `/gatepass` route's detail child:

```dart
// old
              GoRoute(
                path: ':id',
                pageBuilder: (context, state) => slideUpTransition(
                  GatePassDetailScreen(passId: state.pathParameters['id']!),
                  state,
                ),
              ),
            ],
          ),
// new
              GoRoute(
                path: ':id',
                pageBuilder: (context, state) => slideUpTransition(
                  GatePassDetailScreen(passId: state.pathParameters['id']!),
                  state,
                ),
              ),
            ],
          ),
          // Facility screens are parameterless and self-fetching for the same
          // reason as the gate-pass screens above: this router rebuilds on
          // authProvider and would discard `extra`.
          GoRoute(
            path: '/facilities',
            pageBuilder: (context, state) =>
                fadeTransition(const FacilitiesScreen(), state),
            routes: [
              GoRoute(
                path: 'requests',
                pageBuilder: (context, state) =>
                    slideUpTransition(const MyRequestsScreen(), state),
              ),
            ],
          ),
```

- [ ] **Step 3: Home entry card — insertion** — in `mobile/apps/renter/lib/screens/home_screen.dart`:

```dart
// old
                  _QuickActions(
                    penaltyBadge: ref
                        .watch(_openPenaltyCountProvider)
                        .valueOrNull,
                  ),
                  const SizedBox(height: 22),
// new
                  _QuickActions(
                    penaltyBadge: ref
                        .watch(_openPenaltyCountProvider)
                        .valueOrNull,
                  ),
                  const SizedBox(height: 14),
                  const _FacilitiesCard(),
                  const SizedBox(height: 22),
```

- [ ] **Step 4: Home entry card — widget** — same file, insert the new class immediately before `class _QuickActions`:

```dart
// old
class _QuickActions extends StatelessWidget {
  final int? penaltyBadge;
  const _QuickActions({this.penaltyBadge});
// new
/// Entry card for the amenities & parking booking flow (route /facilities).
/// A full-width card rather than a sixth quick-action tile: the grid above is
/// deliberately five-across so the row stays whole (see its comment), and a
/// sixth tile would strand one on a second row.
class _FacilitiesCard extends StatelessWidget {
  const _FacilitiesCard();

  @override
  Widget build(BuildContext context) {
    final m = context.miftah;
    final l = _L(context.isAr);
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: () => context.push('/facilities'),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: m.surface,
          border: Border.all(color: m.border),
          borderRadius: BorderRadius.circular(14),
        ),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: AppColors.accent.withValues(alpha: 0.1),
                border: Border.all(
                  color: AppColors.accent.withValues(alpha: 0.35),
                ),
              ),
              child: const Icon(
                Icons.pool_outlined,
                size: 20,
                color: AppColors.accentDark,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    l.facilitiesTitle,
                    style: l.ar
                        ? GoogleFonts.notoNaskhArabic(
                            fontSize: 14.5,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          )
                        : GoogleFonts.josefinSans(
                            fontSize: 14,
                            fontWeight: FontWeight.w600,
                            color: m.textPrimary,
                          ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    l.facilitiesSub,
                    style: (l.ar
                        ? GoogleFonts.notoNaskhArabic
                        : GoogleFonts.josefinSans)(
                      fontSize: 12,
                      color: m.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
            Icon(Icons.chevron_right, size: 18, color: m.textMuted),
          ],
        ),
      ),
    );
  }
}

class _QuickActions extends StatelessWidget {
  final int? penaltyBadge;
  const _QuickActions({this.penaltyBadge});
```

- [ ] **Step 5: Home strings** — same file, in the `_L` class:

```dart
// old
  String get visitors => ar ? 'الزوار' : 'Visitors';
// new
  String get visitors => ar ? 'الزوار' : 'Visitors';
  String get facilitiesTitle =>
      ar ? 'المرافق ومواقف السيارات' : 'Amenities & Parking';
  String get facilitiesSub => ar
      ? 'اطلب حجز المسبح أو القاعة أو موقف سيارة'
      : 'Request the pool, hall or a parking spot';
```

- [ ] **Step 6: Verify** (analyze plus the existing renter test suite, which pumps the home screen):
```bash
cd /Users/kunalsharma/datagami/rentaxis/mobile/apps/renter && flutter analyze && flutter test
```
Expect: `No issues found!` then `All tests passed!`

- [ ] **Step 7: Commit**:
```bash
cd /Users/kunalsharma/datagami/rentaxis && git add mobile/apps/renter && git commit -m "$(cat <<'EOF'
feat(renter): facilities routes and home entry card

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task M8: Register the new screens in the E2E screen-tour harnesses

**Files:**
- Modify: `mobile/apps/manager/integration_test/prod_deep_routes_test.dart` (routes list, lines 101–104)
- Modify: `mobile/apps/renter/integration_test/screens_tour_test.dart` (after the penalties shot, lines 108–109)

- [ ] **Step 1: Manager deep-route tour** — in `mobile/apps/manager/integration_test/prod_deep_routes_test.dart`:

```dart
// old
      ('/gate-passes/guards', 'gate-guards'),
      ('/gate-passes/policy', 'gate-policy'),
      ('/gate-passes/vendors', 'gate-vendors'),
    ];
// new
      ('/gate-passes/guards', 'gate-guards'),
      ('/gate-passes/policy', 'gate-policy'),
      ('/gate-passes/vendors', 'gate-vendors'),
      ('/facilities', 'facilities'),
      ('/bookings', 'booking-approvals'),
    ];
```

- [ ] **Step 2: Renter screen tour** — in `mobile/apps/renter/integration_test/screens_tour_test.dart`:

```dart
// old
    await _go(tester, '/penalties');
    await binding.takeScreenshot('09-penalties-light');
// new
    await _go(tester, '/penalties');
    await binding.takeScreenshot('09-penalties-light');

    await _go(tester, '/facilities');
    await binding.takeScreenshot('09b-facilities-light');

    await _go(tester, '/facilities/requests');
    await binding.takeScreenshot('09c-facility-requests-light');
```

- [ ] **Step 3: Verify the harnesses still compile** (integration tests are analyzed with the package):
```bash
cd /Users/kunalsharma/datagami/rentaxis/mobile/apps/manager && flutter analyze && cd /Users/kunalsharma/datagami/rentaxis/mobile/apps/renter && flutter analyze
```
Expect: `No issues found!` twice. (Actually running the tours needs a simulator plus the seeded credentials/`--dart-define`s documented in each file's header and in the local mobile E2E setup notes — run them at integration time, not as a per-commit gate.)

- [ ] **Step 4: Commit**:
```bash
cd /Users/kunalsharma/datagami/rentaxis && git add mobile/apps/manager/integration_test mobile/apps/renter/integration_test && git commit -m "$(cat <<'EOF'
test(mobile): register facility screens in E2E screen-tour harnesses

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

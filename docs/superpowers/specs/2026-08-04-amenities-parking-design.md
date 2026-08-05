# Amenities & Parking Booking — Design

**Date:** 2026-08-04
**Status:** Approved (user sign-off on backend + frontend sections)
**Branch:** feat/miftah-admin (or successor feature branch)

## Summary

Admins define bookable **amenities** (free-form) and **parking spots** (numbered inventory) on a property ("project"), optionally limited to specific buildings ("towers"). Renters with an active lease see the facilities visible to their unit and submit **booking requests**. Admins review requests — seeing all other applicants for the same resource — and approve/reject. **No payments are involved.** Approved parking is held until released (by admin or renter); amenity bookings are one-off requests.

### User decisions

| Question | Decision |
|---|---|
| Booking model | Simple request; admin sees other applicants for the same resource and decides |
| Amenity definition | Fully free-form (name EN/AR, description) |
| Parking model | Individual numbered spots (real inventory) |
| Parking hold | Until released (admin or renter releases); amenities are one-off |
| Tower scope semantics | Hard restriction — only renters whose unit is in a scoped tower can see/request |
| Payments | None, ever, in this flow |
| Surfaces (v1) | Backend + web admin + web renter portal + mobile manager + mobile renter |
| Architecture | Unified booking module: two inventory entities + one shared BookingRequest |

## Data model (Liquibase changeset `69-amenities-parking.yaml`)

Five tables, all following existing conventions (uuid PK app-generated, `tenant_id` uuid not-null as 2nd column with tenant index, `timestamptz` timestamps with `now()` defaults, entities extend `BaseTenantEntity`, raw UUID FK columns in entities, `@Enumerated(EnumType.STRING)`).

### 1. `property_amenities`
- `id` uuid PK, `tenant_id`, `property_id` FK→properties (RESTRICT)
- `name_en` varchar not-null, `name_ar` varchar, `description` text
- `bookable` boolean not-null default true, `active` boolean not-null default true
- `created_at`, `updated_at`
- Index: `idx_property_amenities_tenant` on `(tenant_id, property_id)`

### 2. `amenity_building_scopes`
- `id` uuid PK, `tenant_id`
- `amenity_id` FK→property_amenities (`deleteCascade: true`)
- `building_id` FK→buildings (`deleteCascade: true`)
- Unique `(amenity_id, building_id)`
- **Zero scope rows for an amenity = available to all towers.**

### 3. `parking_spots`
- `id` uuid PK, `tenant_id`, `property_id` FK→properties (RESTRICT)
- `spot_number` varchar not-null, `level` varchar (e.g. "B1"), `covered` boolean not-null default true
- `active` boolean not-null default true, `created_at`, `updated_at`
- Unique `(property_id, spot_number)`; index on `(tenant_id, property_id)`

### 4. `parking_spot_building_scopes`
- Same shape as `amenity_building_scopes` with `parking_spot_id` FK→parking_spots (cascade).

### 5. `booking_requests`
- `id` uuid PK, `tenant_id`
- `property_id` uuid not-null — **derived server-side from the resource, never client-supplied**
- `resource_type` varchar(20) not-null: `AMENITY` | `PARKING_SPOT`
- `amenity_id` FK→property_amenities (RESTRICT, nullable), `parking_spot_id` FK→parking_spots (RESTRICT, nullable) — CHECK exactly one non-null (raw SQL). RESTRICT so request history survives; inventory is soft-deactivated, not deleted.
- `unit_id` FK→units (RESTRICT) — the unit the renter booked against
- `renter_user_id` uuid not-null (users.id of the renter)
- `note` text, `preferred_date` date (both optional)
- `status` varchar(20) not-null default `PENDING`
- `admin_note` text, `decided_by_user_id` uuid, `decided_at` timestamptz
- `created_at`, `updated_at`
- Indexes: `(tenant_id, property_id, status)`, `(tenant_id, renter_user_id)`, `(amenity_id)`, `(parking_spot_id)`
- **Partial unique indexes (raw `sql:` blocks, gate-pass style):**
  - `uq_booking_spot_active`: one `APPROVED` row per `parking_spot_id`
  - `uq_booking_pending_renter_amenity` / `uq_booking_pending_renter_spot`: no duplicate `PENDING` by the same renter for the same resource

### Enums (new, in `domain/entity/enums/`)
- `BookingResourceType`: `AMENITY`, `PARKING_SPOT`
- `BookingRequestStatus`: `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`, `RELEASED`

### Status flow
```
PENDING ──approve──> APPROVED ──release (parking only, admin or renter)──> RELEASED
   │──reject──> REJECTED
   │──cancel (renter, own request)──> CANCELLED
```
Amenity bookings terminate at APPROVED/REJECTED (one-off). Approving a parking request does NOT auto-reject other pending requests for that spot; admins decide each one (they'll see the spot is now held). Deactivating a resource (`active=false`) hides it from renters but leaves existing requests untouched.

## Backend architecture

Modeled on the **gate-pass module**: thin services with no role logic; RBAC via `@PreAuthorize` + hand-written ownership checks in controllers; services take `tenantId` as explicit first parameter; cross-tenant/foreign lookups throw `NotFoundException` (404, not 403) to avoid leaking existence.

### New files
- Entities: `PropertyAmenity`, `AmenityBuildingScope`, `ParkingSpot`, `ParkingSpotBuildingScope`, `BookingRequest`
- Repositories: one per entity (`domain/repository/`), Spring Data derived queries
- Services: `FacilityService` (amenity + spot inventory CRUD, scope resolution, renter visibility), `BookingService` (request lifecycle)
- Controllers: `AmenityController` (`/api/v1/amenities`), `ParkingSpotController` (`/api/v1/parking-spots`), `BookingController` (`/api/v1/bookings` + `/api/v1/facilities/my`)
- DTOs: records in `api/dto/` (`AmenityDTO`, `ParkingSpotDTO`, `BookingRequestDTO`, `BookingDetailDTO` (includes `otherRequests`), `MyFacilitiesDTO`, create/update request records)
- Events: `BookingRequestedEvent`, `BookingDecidedEvent` + `BookingNotificationService` (`@TransactionalEventListener(AFTER_COMMIT)`, `REQUIRES_NEW`, per-recipient try/catch — same as `ListingNotificationService`)

### Admin endpoints — `@PreAuthorize hasAnyRole('SUPER_ADMIN','TENANT_ADMIN','PROPERTY_MANAGER')`
`PROPERTY_MANAGER` additionally checked against `UserPropertyAssignmentRepository.existsByUserIdAndPropertyId` (the `checkPropertyManagerAccess` pattern).

| Endpoint | Behavior |
|---|---|
| `GET /api/v1/amenities?propertyId=` | Paginated, `createdAt` ASC |
| `POST /api/v1/amenities` | Create: propertyId, nameEn, nameAr?, description?, bookable, buildingIds[] (empty = all towers) → 201 |
| `PUT /api/v1/amenities/{id}` | Patch semantics (null = unchanged); buildingIds replaces scope set |
| `DELETE /api/v1/amenities/{id}` | Soft-deactivate (`active=false`) → 204 |
| `GET/POST/PUT/DELETE /api/v1/parking-spots...` | Same shape; spot fields: spotNumber, level?, covered |
| `POST /api/v1/parking-spots/bulk` | Create many spots in one call: propertyId, buildingIds[], covered, level?, spotNumbers[] |
| `GET /api/v1/bookings?propertyId=&status=&resourceType=` | Paginated admin inbox, `createdAt` ASC |
| `GET /api/v1/bookings/{id}` | Detail + renter contact info + **`otherRequests`**: all other PENDING/APPROVED requests for the same resource |
| `POST /api/v1/bookings/{id}/approve` | body `{adminNote?}`; PENDING only; parking: 409 `SlotConflictException`-style if spot already APPROVED (DB partial index is the backstop) |
| `POST /api/v1/bookings/{id}/reject` | body `{adminNote?}`; PENDING only |
| `POST /api/v1/bookings/{id}/release` | APPROVED parking only → RELEASED |

### Renter endpoints — `@PreAuthorize hasRole('RENTER')`

| Endpoint | Behavior |
|---|---|
| `GET /api/v1/facilities/my` | For each active lease of the caller (`renterRepository.findByUserId` → leases → unit → property/building): amenities where `active` and (no scope rows OR unit.building ∈ scope), same for spots. **Non-bookable amenities are still listed** (renters should know the gym exists) but carry `bookable=false` and get no Request button; booking attempts against them → 400. Units with no building see only all-towers facilities. Spots include `held` flag (someone has APPROVED). Every bookable resource includes `pendingCount` — renters see **counts only, never other applicants' identities**. |
| `POST /api/v1/bookings` | body `{resourceType, resourceId, unitId, preferredDate?, note?}`. Ownership via the `requireUnitOnActiveLease` pattern (verify caller leases `unitId` actively; derive property server-side). Verify resource is active+bookable+visible to that unit. Parking already APPROVED → 409. Existing PENDING by caller for same resource → return it (idempotent, like `InterestService.addInterest`). Publishes `BookingRequestedEvent`. |
| `GET /api/v1/bookings/my` | Caller's requests, all statuses, `createdAt` ASC |
| `POST /api/v1/bookings/{id}/cancel` | Own PENDING only → CANCELLED |
| `POST /api/v1/bookings/{id}/release` | Own APPROVED parking → RELEASED (same endpoint as admin; controller branches on role like `GatePassController.isGuard()`) |

### Notifications (in-app rows via `NotificationService.notify`)
- `BOOKING_REQUESTED` → all `TENANT_ADMIN` + `PROPERTY_MANAGER` users of the tenant, referenceType `BOOKING`
- `BOOKING_APPROVED` / `BOOKING_REJECTED` / `BOOKING_RELEASED` → the renter user

No feature flag in v1 — enabled for all tenants. No emails, no push (matches current codebase reality: only in-app rows are delivered).

## Web (Next.js)

All fetches via `/api/proxy/v1/...` (proxy injects auth headers). i18n: new `Facilities` and `Bookings` namespaces in `messages/en.json` + `messages/ar.json`; RTL from root layout. Tables paginated, `createdAt` ASC, table-first layouts per project UI standard.

### Admin
- **`dashboard/properties/[id]`** — two new tabs: **Amenities**, **Parking**. Each: table of inventory (name/spot, towers chips, bookable/covered, active, pending count), add/edit dialog with tower multi-select (defaults "All towers"), deactivate action. Parking add dialog supports comma/range bulk entry → `/parking-spots/bulk`.
- **`dashboard/bookings`** (new page + sidebar nav entry) — paginated requests table with property/status/type filters. Row click opens a drawer (InterestsDrawer pattern): renter contact, unit, note, preferred date, **other applicants for the same resource**, Approve/Reject (with note) and Release actions.

### Renter portal
- **`dashboard/renter-portal/facilities`** (new page + entry card on renter-portal home) — Amenities section + Parking section for the caller's unit(s); each card: name, description, towers, pending-count hint ("2 others have requested this"), spot held state; "Request" button → dialog (preferred date?, note?) → POST. "My requests" list below with status badges, Cancel (pending) / Release (approved parking).

## Mobile (Flutter)

Shared plumbing in `rentaxis_core`:
- `lib/api/services/facility_service.dart` — Dio wrapper, raw `Map<String,dynamic>` (no models), endpoints above
- Export in `rentaxis_core.dart` barrel; singleton `facilityServiceProvider` in `providers/auth_provider.dart`
- Bilingual per-screen `_L` classes (`context.isAr`), Miftah theme tokens (`context.miftah`, gold CTAs, Cinzel/Josefin/NotoNaskhArabic)

### Manager app (`apps/manager`)
- `screens/facilities/facilities_screen.dart` — property picker + Amenities/Parking tabs, inventory list, add/edit sheets with tower multi-select, deactivate
- `screens/facilities/booking_approvals_screen.dart` — mirrors `gate_pass_approvals_screen`: pending list (+ filter), request detail with other-applicants section, Approve/Reject/Release
- Routes `/facilities`, `/bookings` in `router.dart` (ShellRoute, parameterless screens that self-fetch — no `extra`); entries in the More menu (dashboard quick-action grid deliberately left at 4 tiles)

### Renter app (`apps/renter`)
- `screens/facilities/facilities_screen.dart` — browse amenities + parking for my unit; request bottom-sheet (preferred date?, note?) mirroring gate-pass create; refresh-on-success submit (await create, surface server errors in the sheet, invalidate providers on success) — booking creates can fail for business reasons (409 spot conflict, 400 non-bookable), so the optimistic-with-rollback pattern used for wishlist toggles does not fit here (same rationale as gate_pass_approvals_screen)
- `screens/facilities/my_requests_screen.dart` — status list, cancel/release
- Routes `/facilities`, `/facilities/requests`; entry on home screen

## Testing

- **Backend (Mockito/AssertJ unit tests, existing conventions):**
  - `BookingServiceTest`: create/approve/reject/cancel/release transitions, idempotent duplicate pending, spot-conflict 409, invalid transitions
  - `FacilityServiceTest`: renter visibility — tower scoping (scoped/unscoped/unit-without-building), inactive/non-bookable exclusion, tenant isolation (wrong tenant → NotFound)
  - `BookingControllerTest` / `AmenityControllerTest` / `ParkingSpotControllerTest`: RBAC boundaries, PM property-assignment check, ownership (renter can't book a unit they don't lease → 404; can't cancel others' requests), DTO mapping
- **Mobile:** add new screens to the existing screen-tour E2E harnesses (manager + renter)
- **Web:** manual verification via dev server (consistent with current repo posture — no web test suite exists)

## Out of scope (explicitly)

- Payments/charges of any kind
- Time-slot scheduling, capacity rules, availability calendars
- Auto-rejection of competing requests on approval
- Email/push notification delivery (in-app only)
- Feature-flag gating
- Linking these bookable amenities to the marketing `ListingAmenity` tags on unit listings (separate concepts)

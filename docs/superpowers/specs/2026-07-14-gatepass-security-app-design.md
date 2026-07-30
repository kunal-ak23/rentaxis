# Gate Pass Module + Security Guard App — Design Spec

**Date:** 2026-07-14
**Status:** Approved by Kunal (chat, 2026-07-14)
**Source requirements:** GCH SOW "RentAxis Platform — New Feature Modules" v1.0 (9 July 2026), Module 1 — Gate Pass, plus owner decision to build a standalone guard app.

## 1. Summary

A visitor gate-pass module for RentAxis. A renter creates a pass in the Renter app and shares it (QR + numeric code). A security guard — using a **new standalone Flutter app** — logs in with **mobile number + SMS verification through Firebase Phone Authentication**, scans the pass at the gate, sees the renter-entered details, and allows entry. Entry (and exit) are logged; the renter is notified on arrival.

### Scope decisions (owner-confirmed)

| Decision | Choice |
|---|---|
| Guard surface | **Standalone app** `mobile/apps/security` — deviates from SOW §3.2/§3.6 (SOW puts scanning inside the Manager app and lists a standalone security app as out of scope). **Requires a written Change Request with GDH per SOW §8.2 before client delivery.** |
| Phone auth | **Firebase Phone Authentication** on Android and iOS. The app verifies the SMS code with Firebase; RentAxis verifies the resulting Firebase ID token server-side. |
| Guard app v1 scope | QR scan, numeric-code manual entry, exit logging, recurring-pass approval queue, today's expected visitors list. |

## 2. Requirements (from SOW §3.2 + owner additions)

- Renter creates a pass: guest name, contact number, visit date/time window, purpose, vehicle number (optional), unit/destination.
- Shareable as QR code and numeric code (numeric = fallback when scanning fails); share-sheet text/link where straightforward.
- Pass types: **single-use** (valid only in its date/time window, consumed on entry) and **recurring** (e.g. maid/driver, valid until an expiry date, multiple entries).
- Single-use passes valid immediately on creation; **recurring passes require manager/security approval** before becoming valid.
- On scan, guard sees renter-entered details and allows/denies entry; actual entry time logged; exit logging supported.
- Renter optionally notified when guest is scanned in.
- Cross-cutting (SOW §3.1): EN/AR + RTL on all new screens, admin activate/deactivate toggle, audit logging of creation and scans, security role restricted from tenant financial/private data, gate-pass usage report with export.

## 3. Backend design (Spring Boot, `com.datagami.rentaxis`)

### 3.1 Data model — Liquibase changesets `65-*.yaml` onward

> CLAUDE.md's "next: 37" is stale; latest is `64-cheque-deposit-reminder-days.yaml`.

All tables carry standard audit fields and `tenant_id` (BaseTenantEntity pattern).

- **`gate_pass`** — id (UUID), tenant_id, property_id FK, unit_id FK, created_by_user_id FK (renter's User), guest_name, guest_phone, purpose, vehicle_number (nullable), pass_type (`SINGLE_USE|RECURRING`), valid_from, valid_to (single-use window; for recurring: recurrence_expiry_date + optional daily window), status (`PENDING_APPROVAL|ACTIVE|USED|EXPIRED|CANCELLED`), qr_token (random 128-bit opaque, unique, the QR payload), numeric_code (8 digits, unique among non-terminal passes per tenant), approved_by_user_id (nullable), approved_at (nullable).
- **`gate_pass_scan`** — id, tenant_id, gate_pass_id FK, direction (`ENTRY|EXIT`), scanned_by_user_id FK (guard), scanned_at, result (`ALLOWED|REJECTED`), rejection_reason (nullable). Serves as the SOW-required audit log for scans.
- **`guard_property_assignment`** — id, tenant_id, user_id FK (guard), property_id FK. A guard sees/scans only passes for assigned properties.

### 3.2 Roles & RBAC

- Add `SECURITY_GUARD` to `UserRole` enum. Guards are `User` rows (existing `phone_number` column) created by TENANT_ADMIN / PROPERTY_MANAGER via existing user management, plus property assignment.
- `@PreAuthorize` scope: `SECURITY_GUARD` may call **only** gate-pass scan/lookup, today's-visitors, and recurring-approval endpoints. Scan responses expose only pass fields (guest details, unit number, window, vehicle) — no lease, financial, or renter-profile data.
- `phone_number` values must be unique among `SECURITY_GUARD` users (enforced by partial unique index + service check) so Firebase login resolves to exactly one guard. Multi-tenant phone collisions are rejected at guard creation in v1.

### 3.3 Firebase Phone Authentication flow

- The Android/iOS app calls FlutterFire `verifyPhoneNumber`; Firebase sends and verifies the six-digit SMS code, including its native app-verification and abuse controls.
- The app obtains a Firebase ID token and calls `POST /api/v1/auth/firebase` `{idToken}`.
- The backend Firebase Admin SDK verifies signature, project audience, expiry, revocation, disabled-user status, and the signed `phone_number` claim. It then resolves exactly one ACTIVE `SECURITY_GUARD` and returns the same `AuthResponse` shape as `/api/auth/login`.
- No OTP code, phone-auth session, or Firebase service-account secret is stored in the mobile app or RentAxis database.
- Known platform risk (inherited, not worsened): backend trusts `X-User-*` headers (tracked P0). Firebase verification is real; session integrity is only as strong as the header-trust model until that is fixed platform-wide.

### 3.4 Gate pass lifecycle & endpoints

Renter (`RENTER` role):
- `POST /api/v1/gatepass` — create (single-use → status ACTIVE; recurring → PENDING_APPROVAL). Validates unit belongs to renter's active lease.
- `GET /api/v1/gatepass/mine`, `GET /api/v1/gatepass/{id}` (includes qr_token + numeric_code), `POST /api/v1/gatepass/{id}/cancel`.

Guard (`SECURITY_GUARD`):
- `POST /api/v1/gatepass/scan` `{qrToken?|numericCode?, direction}` — resolve pass → validate tenant + property assignment + status + time window + type rules → write `gate_pass_scan` → single-use ENTRY marks pass USED → returns guest details + ALLOWED/REJECTED(+reason). Rejections are also logged.
- `GET /api/v1/gatepass/expected-today` — ACTIVE passes valid today for assigned properties.
- `GET /api/v1/gatepass/approvals` + `POST /api/v1/gatepass/{id}/approve|reject` — recurring queue (shared with manager).

Manager/Admin (`TENANT_ADMIN`, `PROPERTY_MANAGER`):
- Approvals (same endpoints), list/search passes per property, `GET /api/v1/gatepass/report?from&to&propertyId` + CSV export, feature toggle (existing settings mechanism), guard property assignment CRUD.

### 3.5 Notifications

Reuse `NotificationService` (in-app rows + ACS email pipeline where a template exists):
- Guest scanned in → notify pass creator ("guest arrived") — in-app (renter app polls unread count).
- Recurring pass created → notify property managers (approval needed).
- Pass approved/rejected → notify creator.
No FCM push in this phase (device-token registration exists but no sender — platform-wide gap, out of scope).

## 4. Mobile design

### 4.1 New guard app — `mobile/apps/security`

Standard Flutter app, auto-discovered by the Melos `apps/*` glob; depends on `rentaxis_core` (Dio client, auth/tenant interceptors, secure storage, theme) + `flutter_riverpod`, `go_router`, `mobile_scanner`, `intl`. Patterns mirror manager/renter: `ProviderScope` → `MaterialApp.router` → `routerProvider` with `authProvider` redirects; `ConsumerStatefulWidget`; raw `Map<String, dynamic>`.

Screens (EN/AR + RTL):
1. Phone entry → request Firebase SMS verification
2. Code entry (6-digit, resend with cooldown) → Firebase verify → backend token exchange → store identity
3. Home — today's expected visitors (grouped by property), pull-to-refresh
4. Scan — `mobile_scanner` camera view + numeric-code manual entry field
5. Result — guest details, big **Allow entry** / **Log exit** action, rejection state with reason
6. Approvals tab — pending recurring passes, approve/reject

(The manager app's `camera` dependency is cheque-OCR-specific; QR scanning uses `mobile_scanner`.)

### 4.2 Renter app additions

- Create Gate Pass screen (guest name/phone, date+time window, purpose, vehicle optional, type single/recurring with expiry)
- My Passes list (status chips), Pass detail: QR (`qr_flutter`), large numeric code, share button (share sheet text), cancel.

### 4.3 Manager app additions

- Recurring-pass approval queue, passes list per property, guard management (create guard user with phone, assign properties).

## 5. Web (Next.js) additions

Minimal v1: gate-pass usage report page with CSV export, feature activate/deactivate toggle, guard management parity. (Dashboard widgets deferred.)

## 6. Testing

- Backend: unit tests for scan validation matrix (expired/early/wrong-property/reused single-use/unapproved recurring/cancelled), Firebase token-to-guard mapping, RBAC (SECURITY_GUARD blocked from financial endpoints); integration test for create→scan→notify happy path.
- Mobile: widget tests for Firebase phone login flow and scan-result rendering; manual E2E with Firebase test phone numbers.
- RTL check on all new screens in both apps.

## 7. Deferred / out of scope

- Offline scan validation (would need signed QR payloads) — v1 assumes gate connectivity.
- FCM push delivery (platform-wide gap; notifications are in-app + email).
- Shareable deep link for guests (SOW: "where straightforward" — plain share text in v1).
- Payment-related anything (SOW: offline-only phase).
- Fixing the X-User-* header-trust P0 (separate tracked workstream).

## 8. Dependencies / prerequisites

1. **Change Request** to GDH for the standalone guard app (SOW §8.2).
2. Firebase project with Phone provider, SMS regions, Android signing fingerprints, iOS APNs/app verification, and production billing configured. See `docs/runbooks/firebase-phone-auth-setup.md`.
3. Client nominates guard users + provides Android devices with cameras (SOW §7).

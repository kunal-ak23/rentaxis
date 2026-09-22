# Mobile Apps E2E Testing Report — 2026-08-02

Scope: end-to-end screen testing of the three Flutter apps (Renter → Admin/Manager → Security)
against the local backend (`http://localhost:8081`), using the seeded **Miftah Demo Account**
tenant (`1c1ad94e-035e-44c4-9636-1895a984aae7`).

## Executive summary

- **All three apps tested end-to-end** with real API data on device: renter (iOS sim, 2 accounts,
  30 screenshots), admin (iOS sim, 13 routes × 4 EN/AR × light/dark modes, 53 screenshots ×2 runs),
  security (Android emulator — iOS sims are impossible for this app, see S1 — full scan flow
  ALLOWED verdict verified).
- **20 bugs found, 19 fixed** in this session (1 documented as an environment constraint):
  12 renter, 7 admin, 1 security. Highlights: entire renter gatepass cluster had no dark mode and
  no Arabic; Arabic dashboard date read "20 Aug" instead of "02 Aug"; property cards always showed
  MONTHLY AED 0.
- **11 animation enhancements** across the three apps, mostly wiring up utilities that already
  existed in `rentaxis_core` but were never used (page transitions, staggered list entrances),
  plus a wishlist-heart bounce, cheque-wizard step transitions, and a scan-verdict entrance.
- Verified after fixes: re-ran the renter tour, the full admin 4-mode pack, and the guard scan
  flow — all green. `flutter analyze` clean on all apps; security widget tests 93/93.
- Screenshot archives: `docs/testing/shots-renter/`, `shots-renter-fatima/`, `shots-admin/`,
  `shots-security/`.

## Test environment

- Device: iPhone 17 Pro simulator (iOS 26.2)
- Backend: local Spring Boot on port 8081 (note: a stale month-old instance occupies 8080 and returns 502s)
- Seed: `scripts/seed_demo_tenant.py` against localhost — properties, 8 units, 4 renters,
  3 active leases + 1 pending-signature, full cheque lifecycle (cleared / deposited / bounced / overdue)
- Listings + meetings phase of the seed skipped (requires the Next.js web app for NextAuth; not running)

### Test accounts (password `Demo@1234` for all)

| Account | Role | Data profile |
|---|---|---|
| admin@miftahdemo.example | TENANT_ADMIN | full tenant |
| ahmed@miftahdemo.example | RENTER | quarterly lease, cleared + deposited + awaiting-deposit cheques |
| fatima@miftahdemo.example | RENTER | bounced Q2 cheque with auto penalty |
| rajesh@miftahdemo.example | RENTER | monthly lease, June installment overdue |
| sara@miftahdemo.example | RENTER | lease pending signature |

## 1. Renter app

Method: `integration_test/screens_tour_test.dart` via `flutter drive` (signs in, walks all 10
routes, captures light/dark/Arabic screenshots → archived in `docs/testing/shots-renter/`),
plus a static review of all screens, plus targeted API cross-checks.

Tours run: Ahmed (full data), Fatima (bounced cheque + penalty, fresh install). All tours passed.

### Screens verified

| Screen | Light | Dark | Arabic | Notes |
|---|---|---|---|---|
| Splash/Login | ✅ | — | — | brand lockup + "securing your session" |
| Home | ✅ | ✅ | ✅ | next-payment card, progress, quick actions correct vs API |
| Payments | ✅ | ✅ | — | timeline totals match API (49,575/113,575, 43%) |
| Tickets | ✅ | ✅ | — | empty state; copy bug fixed (below) |
| Browse | ✅ | — | — | listings seeded via API; map toggle |
| Saved/Wishlist | ✅ | — | — | empty state correct |
| Meetings | ✅ | — | — | empty state correct |
| Notifications | ✅ | — | — | 20 unread from gate-pass seed data |
| Penalties | ✅ | — | — | Fatima's AED 1,000 bounce penalty |
| Profile | ✅ | ✅ | ✅ | appearance + language switches work |

### Bugs found (renter)

| # | Severity | Bug | Status |
|---|---|---|---|
| R1 | i18n | Home quick-action tile "Visitors" untranslated in Arabic (all siblings translated) | **Fixed** — added `l.visitors` (`الزوار`) |
| R2 | copy | Tickets empty state said "Nothing Else Pending" with zero tickets | **Fixed** — "No Requests Yet" / `لا توجد طلبات بعد` |
| R3 | high | Gatepass cluster (4 screens + pass display) hardcoded the light palette — unreadable in dark mode | **Fixed** — converted to `context.miftah` theme pattern |
| R4 | high | Gatepass cluster had zero Arabic support (hardcoded EN, mirrored layout in RTL) | **Fixed** — added `_L` EN/AR pattern per screen |
| R5 | med | Home: payments/lease fetch errors silently swallowed (`SizedBox.shrink`) → blank page, no retry | **Fixed** — ErrorState with retry |
| R6 | med | Notifications: fetch failure rendered "You're all caught up!" false empty state | **Fixed** — `hasError` on NotificationState + ErrorState |
| R7 | med | Wishlist `_removeItem`: `setState` before `mounted` check → crash if user navigates during remove | **Fixed** |
| R8 | med | Browse map: unguarded `listing['id'] as String` cast could kill the whole map | **Fixed** — null-safe + skip |
| R9 | med | Gatepass detail: snackbar says "pull to check status" but screen had no RefreshIndicator | **Fixed** |
| R10 | med | Resident approvals: `_busy` flag never reset on success → card can stay disabled | **Fixed** — reset in finally |
| R11 | low | `Image.network` without `errorBuilder` in wishlist/map/ticket viewer → red error boxes on broken URLs | **Fixed** — graceful fallbacks |
| R12 | low | 5 Rows with unbounded text (meeting host, home totals, payment amount+due, penalty reason badge, ticket timeline labels) → RenderFlex overflow risk with long Arabic strings | **Fixed** — Flexible/Expanded + ellipsis |

### Test-harness finding

The screens tour reuses a persisted session ("already signed in from a previous run"), so switching
`TOUR_EMAIL` between runs silently keeps the previous user. The session lives in
`flutter_secure_storage` (iOS keychain) and survives **both** app uninstall and
`xcrun simctl keychain reset`. The reliable account switch is running
`integration_test/session_seed_test.dart` with `SEED_EMAIL`/`SEED_PASSWORD` first — it overwrites
the stored session; a subsequent tour then starts as that user. (Verified: Fatima's penalties and
bounced-cheque screens captured this way; values match the API.)

## 2. Admin (Manager) app

Method: `integration_test/client_shots_test.dart` via `flutter drive` — walks 13 routes in all
four presentation modes (EN/AR × light/dark) plus both login screens (53 screenshots), as
`admin@miftahdemo.example`. Plus a static review of all screens (incl. cheque_scan/, gatepass/,
listings/) and API cross-checks.

### Screens verified

Dashboard, Properties, Leases, Cheque Ops/Payments, Tickets, Meetings, Renters, Finance
(accounts/transactions/reports), Reports, Listings, Gate Pass Approvals, Notifications,
Settings hub — all four modes each. Data cross-checked against the API (lease amounts,
listing statuses, occupancy, cheque counts all correct).

### Bugs found (admin)

| # | Severity | Bug | Status |
|---|---|---|---|
| M1 | i18n/high | Arabic dashboard date read "٢٠ أغسطس" (20 Aug) on Aug 2 — the `·` separator is visually identical to Arabic-Indic zero `٠`, so "الأحد · ٢" reads as "20" | **Fixed** — Arabic comma separator |
| M2 | data/high | Properties cards always showed "MONTHLY AED 0" — screen read a non-existent `annualRent` unit field (API sends `expectedRent`/`actualRent`) | **Fixed** — actual-then-expected fallback |
| M3 | UX/med | Cheque Ops: stat tiles show all-time counts (6 pending) while the "Upcoming" tab is scoped to the current month → empty list reads as a bug | **Fixed** — empty state now says "Nothing due this month" |
| M4 | crash/med | Listing edit: `a['amenity'] as String` force-cast could crash the form on malformed data | **Fixed** — null-safe + filter |
| M5 | overflow/med | Cheque scan step 2: OCR'd bank/payer values had no Flexible → overflow with long names | **Fixed** |
| M6 | defensive/low | Gate access policy screen: unguarded `results[1] as Map` cast | **Fixed** — fallback to empty map |
| M7 | overflow/low | Cheque scan success screen: amount Text without Flexible | **Fixed** |

### Cosmetic / data observations (not fixed)

- Greeting shows "Hi, Al" for user "Miftah Demo Demo Admin" — `_firstName` takes the first word; fine
  for person names, odd for org-style names. Left as-is.
- Arabic finance screens show chart-of-account names in English — backend seed data is EN-only;
  an app can't translate free-text account names. Data-side gap, not an app bug.
- Backend: `actualRent` is 0 even for OCCUPIED units (leases exist) — worth a backend look;
  the app now falls back to `expectedRent` so the UI is correct either way.

Static review verdict: manager app code discipline is strong — no dark-mode or i18n gaps found
(unlike the renter gatepass cluster), consistent `mounted` guards, real error/empty states
everywhere.

## 3. Security (Guard) app

Method: guard user seeded via API (`guard@miftahdemo.example`, SECURITY_GUARD, assigned to both
properties); session established with `session_seed_test.dart` (SEED_USER_ID/SEED_TENANT_ID —
bypasses Firebase phone auth locally since the backend accepts X-User-* headers); then
`gate_scan_flow_test.dart` keys an active pass's 8-digit code and asserts the verdict screen.
Plus a static review of all screens.

### Bugs found (security)

| # | Severity | Bug | Status |
|---|---|---|---|
| S1 | env/high | App cannot run on Apple Silicon iOS simulators **at all** — `mobile_scanner`'s podspec excludes arm64-simulator (GoogleMLKit ships no arm64-sim slice), and iOS 26 simulators reject x86_64 apps ("needs to be updated"). Initially misdiagnosed as a missing xcconfig override (the renter/manager apps re-allow arm64; doing the same here breaks the scanner module). | **Documented** — explanatory note added to `ios/Flutter/Debug.xcconfig`; iOS-sim testing is a dead end, use Android emulator or physical device (the repo's own E2E docs target Android `10.0.2.2`) |

### E2E result

`integration_test/seeded_scan_flow_test.dart` (new, this session): seeds the guard session and
runs the scan flow in one app process — required on Android because `flutter drive` uninstalls
the app between runs, wiping secure storage (the two-step seed→scan sequence only works on iOS).
Run on a fresh Pixel 7 API 35 emulator (the prior AVD's system services wedged mid-run and it
was recreated): guard board rendered, numeric code `59438953` keyed in, backend returned
**ALLOWED** for "Nanny Recurring" (unit A-101, RECURRING pass), verdict screen rendered with
full pass details. Screenshots archived. Also hardened `gate_scan_flow_test.dart` (poll up to
30s for the board instead of a fixed 6s settle — slow emulators need it).

### Static review verdict

Unusually defensive codebase: raw map access goes through `passString`/`passInstant` helpers,
every provider `.when` handles loading/error/empty, `mounted` guards are consistent. Notes
(accepted, not fixed): login/OTP/scanner screens are intentionally fixed dark "console" chrome
regardless of theme preference (design choice worth confirming); walk-in status polls every 3s
with no backoff cap; unrecognized gate-pass rejection reasons fall back to raw backend English
in Arabic mode (documented as deliberate — nothing to translate from).

## Follow-ups worth considering

- Backend: units report `actualRent: 0` even when OCCUPIED with an active lease — the mobile app
  now falls back to `expectedRent`, but the field looks wrong at the source.
- Backend/seed: chart-of-account names are EN-only, so Arabic finance screens show English data.
- Renter `rentaxis_core` `EmptyState` upper-cases titles unconditionally, which breaks 3
  pre-existing gatepass widget tests that search mixed-case strings (pre-existing, not from this
  session's changes).
- Security app: consider whether login/OTP/scanner staying dark regardless of theme preference is
  intentional; walk-in status polling has no backoff cap.
- `mobile_scanner`'s MLKit dependency blocks arm64-simulator builds — if iOS-sim testing of the
  guard app ever matters, that means migrating the scanner (e.g. to a Vision-API-based package).

## Animation enhancements

Renter app (reusing utilities already exported from `rentaxis_core` — `AnimatedListItem`,
`page_transitions.dart` — which were built but never wired up):

| # | Enhancement | Where |
|---|---|---|
| A1 | Route transitions: cross-fade between tab roots, slide-up for pushed create/detail screens (helpers existed, router never used them) | `renter/lib/router.dart` — all ~20 routes |
| A2 | Staggered fade+slide list entrances added to the 3 lists missing them | meetings, notifications, payments timeline |
| A3 | Wishlist heart scale-bounce (1.0→1.25→1.0, elasticOut) on save — the only feedback the optimistic add gives | `rentaxis_core/widgets/listing_card.dart` |
| A4 | Browse list↔map switch cross-fades via AnimatedSwitcher instead of a hard cut | `renter/lib/screens/browse/browse_screen.dart` |

Manager app:

| # | Enhancement | Where |
|---|---|---|
| A5 | Route transitions wired for all ~30 routes (fade tab roots, slide-up details/wizards) | `manager/lib/router.dart` |
| A6 | Cheque-scan wizard steps cross-fade + slide between steps 1→4 | `cheque_scan_flow_screen.dart` |
| A7 | Step-3 disposition selection animates (AnimatedContainer, 180ms) | `steps/step3_confirm.dart` |
| A8 | Gate-pass approve/reject label↔spinner crossfade | `gate_pass_approvals_screen.dart` |

Security app:

| # | Enhancement | Where |
|---|---|---|
| A9 | Scan verdict band entrance: scale 0.9→1.0 + fade, 300ms easeOutBack (kept fast — guards need the answer instantly) | `result_screen.dart` |
| A10 | Approvals list staggered entrances + approve button crossfade | `approvals_screen.dart` |
| A11 | Walk-in status icon + pill animate on PENDING→ACTIVE→USED poll transitions | `walk_in_status_screen.dart` |

All three apps: `flutter analyze` clean; security app widget tests 93/93 pass.

## Regression check

Full widget-test suites after all changes: renter 48✓/3✗, manager 25✓/5✗, security 93✓/0✗.
All 8 failures verified **pre-existing** (renter's 3 via the gatepass agent's stash comparison;
manager's 5 re-run on a `git stash` baseline in this session — identical failures). Root cause of
most: `rentaxis_core` `EmptyState` upper-cases titles while tests search mixed-case strings.

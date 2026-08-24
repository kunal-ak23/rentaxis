# Mobile capability audit — 24 August 2026

This audit supports tutorials 29–33. It records what was verified locally on the current `origin/main` baseline, what has older production-device evidence, and which integration tests can change production state. No production mobile test was executed during this audit.

## Current baseline verification

Baseline: `origin/main` at `42c398ffab6b569ae07bf42a608ca9c4861a3a8f`.

| Package | Unit/widget result | Analyzer result |
|---|---:|---|
| Shared `rentaxis_core` | 94/94 pass | No errors; one existing unused-import warning and 52 information-level style diagnostics |
| Manager | 100/100 pass | No issues |
| Renter | 224/224 pass | No issues |
| Security | 94/94 pass | No issues |
| **Total** | **512/512 pass** | **No analyzer errors** |

The shared-core warning is non-functional lint debt in `distance_chip.dart`; it did not justify mixing a source cleanup into the tutorial-assets pull request.

## Capability and route inventory

### Manager mobile

- 42 declared routes in `mobile/apps/manager/lib/router.dart`.
- Essentials: authentication, dashboard, properties and property detail, renters, leases and lease detail, cheque operations, tickets and ticket detail, notifications, and profile.
- Operations: meetings and detail, finance, finance reports, staff, vendors and detail, bank accounts, facilities, booking approvals, listings and interests, settlement and penalties, rent/gateway/account-mapping settings, and gate-pass approvals/guards/policy/vendors.
- Five integration entry points cover login presentation, session seeding, a four-mode main-screen capture pack, 20 production-backed detail/configuration routes, and gate-pass approval.

### Renter mobile

- 25 declared routes in `mobile/apps/renter/lib/router.dart`.
- Essentials: authentication/password setup, home and lease context, payments, penalties, maintenance tickets/create/detail, notifications, and profile.
- Services: browse and listing detail, wishlist, meetings/create/detail, facilities and requests, gate-pass list/create/detail, resident approvals, and offers/promotions.
- Seven integration entry points cover login focus, branding, session seeding, the main screen tour, the four-mode capture pack, production-backed deep routes, and resident walk-in approval.
- Promotions are exercised in the unit/widget suite but are not present in the current screenshot tour; tutorial 32 still needs a device capture of `/offers`.

### Security mobile

- Nine declared routes in `mobile/apps/security/lib/router.dart`.
- Authentication: splash, phone login, OTP, and restored guard session.
- Guard operations: assigned-property board, scanner, numeric-code verdict, approvals, walk-in registration, walk-in status, and admission.
- Five integration entry points cover four login presentation modes, session seeding, the numeric-code scan flow, the self-contained seeded scan flow, and walk-in admission.

## Production execution classification

| Integration entry point | Classification | Reason |
|---|---|---|
| Manager `client_shots_test.dart` | Read-oriented capture | Reads live screens; changes only device-local language/theme/session state |
| Manager `prod_deep_routes_test.dart` | Read-oriented capture | Opens 20 live detail/configuration routes and asserts settlement display without saving settings |
| Manager `prod_gatepass_approval_test.dart` | **Mutating** | Approves a pending gate pass |
| Renter `client_shots_test.dart`, `screens_tour_test.dart`, and `prod_deep_routes_test.dart` | Read-oriented capture | Reads live screens and fixture details; changes only device-local preferences/session state |
| Renter `prod_walkin_approval_test.dart` | **Mutating** | Approves a resident walk-in request |
| Security `login_shots_test.dart` | Local presentation capture | Does not authenticate or mutate tenant data |
| Security `gate_scan_flow_test.dart` and `seeded_scan_flow_test.dart` | **Mutating** | Submits a pass code to the live scan endpoint; a valid pass can advance to used state |
| Security `prod_walkin_admit_test.dart` | **Mutating** | Admits an approved walk-in and advances its status |

All production-backed mobile entry points remain approval-gated, including the read-oriented capture packs. The mutating tests additionally require disposable fixtures and deterministic cleanup.

## Existing production-device evidence

The repository's 2 August 2026 production report records successful production-backed Renter journeys, a retested Manager 20-route pass, and Security Android board/numeric-scan coverage. Its screenshots remain useful capture references, but they are historical evidence rather than a current production rerun.

Known hardware/external boundaries remain:

- Physical-camera QR scanning was not automated; numeric-code scanning is the deterministic emulator path.
- Live Firebase SMS OTP was not sent during unattended automation; OTP UI and service behavior have widget coverage.
- No real payment-gateway charge, physical cheque-camera capture, GPS movement, push receipt, email delivery, or external calendar delivery was asserted.
- Security iOS simulator execution is constrained by the scanner dependency; use Android or a physical iOS device.

## Tutorial capture gate

Before recording tutorials 29–33 against production:

1. Deploy the approved safety and workflow fixes listed in the main capability matrix.
2. Obtain explicit approval for the production run.
3. Create disposable fixture records for gate-pass and walk-in state changes.
4. Run read-oriented capture packs first and verify screenshots contain no secrets or unrelated tenant data.
5. Run mutating approval/scan/admission tests only against the disposable fixtures.
6. Capture the missing Renter promotions/offers journey and perform the physical-camera/Firebase checks manually if those claims will appear in narration.

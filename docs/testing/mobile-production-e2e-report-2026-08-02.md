# Mobile production test report — 2 August 2026

## Fix status — completed in the current workspace

All issues found in the original production run have been corrected in the current workspace.

| Finding | Resolution | Verification |
| --- | --- | --- |
| Manager settlement preview displayed a zero security deposit | The preview now retains and renders the API's `depositAmount` | Live iOS production test asserted **AED 22,000.00** for the affected lease |
| Manager shell failed during logout/teardown | Notification polling is cached safely and stopped without reading Riverpod after disposal | Live 20-route iOS production test completed teardown without an exception |
| Security startup depended on Google font downloads | All used font weights are bundled and runtime fetching is disabled in every mobile app | Android production scan completed with runtime font fetching disabled |
| Occupied-unit rent and tenant metrics were not maintained | Lease activation/acceptance/termination now synchronizes the unit; migration 68 repairs existing rows | New lifecycle regression tests and the complete backend suite passed |
| Manager listing summaries omitted property/photo/interest data | Summary mapping now resolves the property name, cover media, and active-interest count | Controller and service regression tests passed |
| Stale widget tests and analyzer findings | Expectations, stable widget keys, deprecated APIs, and lint findings were updated | Renter 51/51, Manager 30/30, Security 93/93, shared core 33/33; all three app analyzers clean |

The Manager iOS and Security Android fixes were retested directly against the live production API. The backend corrections and data backfill are verified locally but are **not yet reflected by the live production API**; deployment of this backend revision and Liquibase migration 68 is required before production revenue and Manager listing summaries change.

## Offline-state follow-up

Manager, Renter, and Security now share a persistent offline banner at the application root. It is visible on authentication and signed-in routes, preserves the current screen beneath it, supports English and Arabic, reacts to connectivity changes while the app is open, and disappears automatically when a network interface returns. API calls continue to handle their own failures because a Wi-Fi or mobile connection does not by itself guarantee internet reachability.

Verification completed:

- Automated startup-offline, disconnect/reconnect, and Arabic right-to-left widget cases passed.
- All application suites remain green: Manager 30/30, Renter 51/51, Security 93/93, and shared core 33/33.
- Manager and Renter iOS simulator builds and the Security Android debug build completed successfully.
- On the Android 15/API 35 emulator, disabling Wi-Fi and mobile data displayed the banner on the running Security app; restoring connectivity removed it.

Evidence: [offline banner](mobile-offline-2026-08-02/security-android-offline-live.png) and [screen after connectivity was restored](mobile-offline-2026-08-02/security-android-online-restored.png).

The sections below preserve the observations and evidence from the original production run.

## Original production result

All three Flutter applications were run on local simulators against the production API at `https://rentaxis.uaenorth.cloudapp.azure.com/api`.

The Renter application completed its production journeys successfully. The Security application completed an approved gate-pass scan on Android, but its walk-in UI run was later blocked by runtime font downloads. Manager feature operations and all targeted screens loaded, but every original Manager integration run ended with the same reproducible app-shell disposal exception. Those findings are resolved and retested as recorded above.

| Application | Simulator | Production result | Visual evidence |
| --- | --- | --- | --- |
| Renter | iPhone 17 Pro, iOS 26.2 simulator | PASS for authentication, 25 screen states, create/detail routes, marketplace, gate passes, and resident approval | 27 screenshots |
| Manager | iPhone 17 Pro, iOS 26.2 simulator | Feature operations succeeded and 73 application states loaded; FAIL on shared-shell teardown/logout | 75 screenshots |
| Security | Android 15 / API 35 emulator | PASS for assigned-property board and numeric gate scan; walk-in admission UI blocked by runtime font fetches | 2 screenshots |

Production health returned HTTP 200 before the run. API responses used by the successful journeys were live production responses, not mocks.

## Release findings

### Resolved P1 — Settlement preview dropped the security deposit in Manager

For lease `d429e789-a4d5-4722-b682-8257331a1dc7`, production returned this settlement preview:

- `depositAmount: 22000.0`
- `unpaidRentTotal: 22000.0`
- `suggestedRefund: 0.0`

The Manager UI displayed **Security Deposit AED 0.00** while displaying the AED 22,000 unpaid-rent deduction. The preview branch builds the deductions but does not store `preview.depositAmount`; the deposit getter therefore falls back to zero. This can display an incorrect refund calculation before a settlement draft exists.

Evidence: [settlement screen](mobile-production-2026-08-02/manager/prod-manager-lease-settlement.png) and `mobile/apps/manager/lib/screens/lease_settlement_screen.dart` around the preview-loading branch.

### Resolved P1 — Manager crashed whenever its shared shell was disposed

The problem reproduced independently in the full screen tour, gate-pass approval test, and 20-route deep pass:

```text
Bad state: Cannot use "ref" after the widget was disposed.
_ShellScreenState.dispose — manager/screens/shell_screen.dart:27
```

The shell calls `ref.read(notificationProvider.notifier).stopPolling()` during `dispose`. This affects logout and any application teardown path that unmounts the shell. The gate-pass approval itself and all route requests had already succeeded before the exception.

### Resolved P1 reliability risk — Security fonts depended on live Google font downloads

The Security Android scan journey passed once, but repeated walk-in admission runs stopped during application startup when `fonts.gstatic.com` connections closed while loading Cinzel and Josefin Sans. These fonts are not declared as bundled application assets. A gate/security application must be able to start under restricted or unreliable internet conditions.

This is a confirmed debug/integration failure and a production reliability risk; a release-build crash was not independently demonstrated. Bundle the required fonts and disable runtime fetching for deterministic startup.

### Resolved in backend P2 — Production property revenue fields disagree with collected rent

Production returned `actualRent: 0.0` for occupied units and `actualRevenue: 0.0` for both properties, while the dashboard reported AED 154,575 collected and three active leases. The current Manager UI has an expected-rent fallback, which prevents blank cards but does not correct the production aggregation data.

Affected responses:

- `GET /v1/units`
- `GET /v1/units/property/{propertyId}`
- `GET /v1/properties`

### Resolved in backend P2 — Manager listing summaries omitted property names and cover photos

The authenticated Manager listing response returned `propertyName: null` and `coverPhotoUrl: null`, so Manager cards show generic building placeholders. The public marketplace response for the same listings returned both the property name and working blob-photo URLs, and the Renter listing rendered the images correctly.

Evidence: [Manager listings](mobile-production-2026-08-02/manager/en-light-listings.png) and [Renter listing detail](mobile-production-2026-08-02/renter/prod-renter-listing-detail.png).

### Resolved P3 — Automated suite and analyzer cleanup

The existing widget suites are not all green:

| Application | Passed | Failed | Notes |
| --- | ---: | ---: | --- |
| Renter | 48 | 3 | Mixed-case empty-state expectations no longer match the shared uppercase UI copy |
| Manager | 25 | 5 | Stale approval/empty-state copy plus two tests looking for the removed `save-assignments` key |
| Security | 93 | 0 | Green |

Static analysis:

- Renter: no warnings/errors; 19 information-level deprecation/style findings.
- Manager: 2 warnings and 23 information-level findings. The warnings are an unused report-detail import and unused `_extendDate` field.
- Security: clean.

These failures appear to be test-maintenance or lint debt rather than failures in the live journeys, but they keep the repository's default test signal red.

## Production journeys completed

### Renter — iOS

- Restored and validated a production session, then verified direct credential login.
- Loaded home, payments, maintenance tickets, a live ticket detail, browse, wishlist, meetings, notifications, penalties, and profile.
- Loaded light and dark modes and an Arabic home state.
- Loaded ticket creation, meeting creation, listing detail, meeting detail, gate-pass list, gate-pass creation, gate-pass detail/QR, and resident approvals.
- Verified two different renter accounts to avoid relying on one cached identity.
- Approved a production walk-in from the Resident Approvals UI; the card disappeared and the success message was shown.
- Rendered the active recurring pass, QR token, numeric entry code, validity window, guest, vehicle, and unit data.

Representative evidence:

- [Renter home](mobile-production-2026-08-02/renter/02-home-light.png)
- [Marketplace property](mobile-production-2026-08-02/renter/prod-renter-listing-detail.png)
- [Gate-pass QR](mobile-production-2026-08-02/renter/prod-renter-gatepass-detail.png)
- [Walk-in before approval](mobile-production-2026-08-02/renter/prod-walkin-before-resident-approval.png)
- [Walk-in after approval](mobile-production-2026-08-02/renter/prod-walkin-after-resident-approval.png)

### Manager — iOS

- Validated the production session and dashboard totals.
- Loaded 13 main areas in English/Arabic and light/dark presentation modes: dashboard, properties, leases, cheque operations, tickets, meetings, renters, finance, reports, listings, gate-pass approvals, notifications, and settings hub.
- Loaded 20 additional detail/configuration routes: property detail, lease detail, penalties, settlement, ticket detail, meeting detail, staff, vendors, vendor detail, bank accounts, profile, settings, rent settings, payment gateway, account mappings, listing edit, listing interests, guard management, gate policy, and gate vendors.
- Approved a recurring gate pass from the Manager UI; production returned ACTIVE and the item left the pending queue.
- Verified live listing interest, account-mapping, vendor, Razorpay availability, guard-assignment, ticket attachment, and settlement-preview responses.
- Reproduced the shared-shell disposal crash on each independent run.

Representative evidence:

- [Dashboard](mobile-production-2026-08-02/manager/en-light-dashboard.png)
- [Arabic dark gate-pass queue](mobile-production-2026-08-02/manager/ar-dark-gate-passes.png)
- [Gate pass after approval](mobile-production-2026-08-02/manager/prod-gatepass-after-approval.png)
- [Account mappings](mobile-production-2026-08-02/manager/prod-manager-account-mappings.png)
- [Security guards](mobile-production-2026-08-02/manager/prod-manager-gate-guards.png)

### Security — Android

- Loaded the guard board with two assigned production properties.
- Loaded the expected-visitors endpoint.
- Entered the production numeric code `00472478` and submitted it to the live scan endpoint.
- Received HTTP 200 and an `ALLOWED` verdict with the expected guest, phone, vehicle, purpose, unit A-101, and recurring validity.
- Uploaded a photographed walk-in fixture through the production API and confirmed the resident approval in the Renter app.
- Repeated Security UI admission attempts were blocked by the font-download issue described above.
- Completed a second walk-in state machine through the production APIs as a fallback: created, resident-approved, guard-admitted, final status USED.

Evidence: [guard board](mobile-production-2026-08-02/security/01-guard-board.png) and [ALLOWED verdict](mobile-production-2026-08-02/security/02-scan-verdict.png).

## Persistent production test data

Testing used the existing isolated production tenant **Al Ashram Demo Account** (`5432aca2-cd9c-4431-a131-22e67a5b72b0`). The seed process refreshed its demo properties, units, renters, leases, payments/cheques, tickets, listings, meetings, vendors, finance transactions, and notifications. The following new records remain intentionally for repeatable mobile testing:

| Record | Identifier | Final state |
| --- | --- | --- |
| Security guard `guard@alashramdemo.com` | `f59ea258-d0fb-4462-8977-ed885f42c069` | ACTIVE; assigned to two properties |
| Recurring pass for `TEST Mobile Production Visitor` | `c59bd945-70ed-4287-868e-4afaaf291b53` | ACTIVE; Manager-approved and scan-allowed |
| UI-tested walk-in `TEST Walk-In Production Visitor` | `870645a8-3c28-43d9-823a-2c1ab37e9c22` | EXPIRED after resident approval; guard UI admit was blocked |
| API fallback walk-in `TEST Walk-In API Fallback` | `5fea1a19-3c36-4ad9-9b2a-d55537ec006a` | USED |

The `GATEPASS` feature was enabled for this demo tenant. No production customer tenant was used.

## Boundaries and untested external effects

The request covered all three applications and their main mobile routes, but simulator automation cannot safely prove every hardware or third-party effect:

- Security was tested on Android because the current `mobile_scanner` iOS dependency does not build for the local Apple-silicon simulator architecture.
- The approved pass was scanned by numeric code. A physical camera scan of the on-screen QR was not used.
- Firebase SMS OTP was not sent during unattended automation; an established production guard session was used. OTP UI/unit coverage is included in the 93 passing Security tests.
- No real Razorpay charge was made; this tenant has no configured payment gateway. Available-gateway rendering was verified.
- No physical cheque-camera capture, GPS movement, push-delivery receipt, email delivery, or external calendar delivery was asserted.
- High-risk financial/configuration forms were loaded and validated with live data but were not saved over seeded production settings.

## Test artifacts added

- `mobile/apps/renter/integration_test/prod_deep_routes_test.dart`
- `mobile/apps/renter/integration_test/prod_walkin_approval_test.dart`
- `mobile/apps/manager/integration_test/prod_deep_routes_test.dart`
- `mobile/apps/manager/integration_test/prod_gatepass_approval_test.dart`
- `mobile/apps/security/integration_test/seeded_scan_flow_test.dart`
- `mobile/apps/security/integration_test/prod_walkin_admit_test.dart`
- Selected production screenshots referenced by this report under `docs/testing/mobile-production-2026-08-02/`

# Production E2E Release Validation

Serial end-to-end suite that exercises the mapped web capability inventory
against **production** with one disposable `TEST-E2E` tenant. Real-file
subflows that are not yet safe are called out explicitly below. The suite lives
beside the local `web/e2e/` suite but is intentionally separate: it uses
production authentication, creates real database records, and must finish by
deleting the exact tenant it created.

This suite currently contains **33 tests across 32 Playwright files** (31 specs
plus the authentication setup). It is a release/recording gate, not a harmless
smoke command. Do not run it until all items in [Safety gates](#safety-gates)
are satisfied.

## How it works

The web application authenticates with NextAuth. Its proxy strips forged
identity headers, derives identity and tenant context from the validated
session, and forwards the request to the backend. When configured, the proxy
also adds the server-only `X-Internal-Auth` proof. Bearer-token authentication
exists for the mobile rollout, but this browser suite deliberately exercises
the same session/proxy path as web users. Therefore:

1. The suite logs in via NextAuth's credentials callback at `/api/auth/callback/credentials`.
2. Playwright saves the `__Secure-next-auth.session-token` cookie to
   `.auth/superadmin.json`.
3. Every API call hits `/api/proxy/*` so the suite cannot bypass the web
   authentication and tenant-switching boundary.
4. A production-target lock prevents concurrent local/worktree runs from
   overwriting the cumulative context and deleting each other's tenant. Stale
   locks are recovered only after their owning process no longer exists.

Backend port 8080 is (assumed to be) not externally reachable on prod; all
traffic goes through Caddy → Next.js → backend.

## Setup

```bash
cd web
npm install   # if not already
cp e2e-prod/.env.local.example e2e-prod/.env.local
# Fill in PROD_SUPERADMIN_EMAIL and PROD_SUPERADMIN_PASSWORD in e2e-prod/.env.local
```

`.env.local` is gitignored.

## Run

```bash
cd web
npx playwright test --config=e2e-prod/playwright.config.ts
```

Single spec:

```bash
npx playwright test --config=e2e-prod/playwright.config.ts e2e-prod/tests/00-smoke-auth.spec.ts
```

Keep the test tenant around after a run (for manual inspection):

```bash
SKIP_CLEANUP=1 npx playwright test --config=e2e-prod/playwright.config.ts
```

Report:

```bash
npx playwright show-report e2e-prod/playwright-report
```

Verify that every web tutorial remains mapped to existing functional specs and
that the README inventory has neither missing nor stale spec files:

```bash
npm run test:e2e:prod:coverage
```

The machine-readable mapping is `e2e-prod/tutorial-coverage.json`. A structural
pass proves inventory integrity only; the command deliberately reports how many
tutorials still lack an exact-current production pass or retain artifact/manual
gates.

## What's covered

| Spec | Surface |
| --- | --- |
| `00-smoke-auth` | Login + `/api/proxy/admin/tenants` (proves cookie + role check) |
| `01-provision` | Tenant, manager, project/property/unit, renter, lease, activation, and feature setup |
| `01a-feature-and-user-admin` | Feature round-trip restoration; user CRUD, assignments, and role boundaries |
| `01b-profile-navigation-and-password` | Locale, profile, password, logout, and re-login browser journey |
| `01c-search-help-and-superadmin-dashboard` | Command-palette navigation, guard help, and super-admin follow-ups |
| `02-cheque-lifecycle` | Collect/deposit lifecycle and financial-transaction emission |
| `03-vendor-and-interactions` | Vendor CRUD and lease interaction history |
| `04-reports` | Dashboard KPIs and finance reports in the browser |
| `05-renter-portal-ui` | Renter payment widgets through a separate renter session |
| `06-renter-create-ui` | Renter creation and portal access through the dashboard |
| `07-bulk-portfolio-import` | Template upload, status polling, and imported-entity verification |
| `08-cheque-upload` | Real cheque-image upload and extraction response |
| `09-notifications` | Tenant-provisioned notification delivery and browser rendering |
| `10-lease-create-ui` | Complete draft-lease wizard and payment-plan preview |
| `10a-contract-signature-lifecycle` | Rejection, clean regeneration, and renter acceptance |
| `10b-draft-lease-administration` | Metadata/payment-plan edits, synthetic bulk cheque attachment, and deletion |
| `11-ticket-lifecycle` | Renter submission through assignment, OTP closure, rating, and reporting |
| `12-property-operations` | Project/property detail plus building, unit, contact, amenity, and parking lifecycle |
| `13-finance-and-settings` | Accounts, mappings, transactions, staff, vendors, and bank accounts |
| `13a-cheques-and-penalties` | Cheque clear/failure plus open, paid, waived, receipt, and history states |
| `13b-facilities-and-bookings` | Amenity/parking requests, release, approval, and rejection |
| `13c-gatepass-lifecycle` | Policy, guard assignment, visitor registration, resident pass, manager approval, report access, and cancellation |
| `13d-promotions` | Business/ad/coupon lifecycle, renter engagement, and analytics |
| `13e-notification-ownership` | Cross-user mark-read denial |
| `13f-lease-renewals` | Reminder, renter intent, scan, closure, extension, and captured state |
| `13g-payment-gateway-config` | Test-mode gateway lifecycle with retained-secret assertions |
| `14-lease-lifecycle-extended` | Contract preview, extension, settlement, and termination |
| `15-listings-and-marketplace` | Publish/unpublish, anonymous browsing, wishlist, interest, and withdrawal |
| `16-meeting-lifecycle` | Renter request, manager approval, calendar, and completion |
| `17-public-account-and-legal-pages` | Public registration controls plus privacy, terms, and deletion pages |
| `99-cleanup` | Confirm-name guarded hard deletion and post-delete 404 verification |

## Test data conventions

Every entity name is prefixed `TEST-` so prod dashboards / reports can
exclude the fixture data with `name NOT LIKE 'TEST-%'`. Tenant names look like
`TEST-E2E 2026-05-20 abc1de2f`.

## Backend changes that landed with this suite

- **Migration 59** (`59-user-name-unique-per-tenant.yaml`) — UNIQUE INDEX on
  `users(tenant_id, name)`. Within a tenant, no two users can share a name.
  SUPER_ADMINs (tenant_id IS NULL) are exempt.
- **`RenterService.createRenter`** — `createPortalAccount` now defaults to
  true and portal-creation failures abort the whole tx (previously swallowed).
  Portal password is returned on `RenterDTO.portalPassword`.
- **`DELETE /api/admin/tenants/{id}?confirmName=...`** — SUPER_ADMIN-only
  hard-delete with confirmName guard. Cascades through every tenant-scoped
  table via information_schema discovery + savepointed multi-pass deletion.
  Used by 99-cleanup.

## Safety gates

- **Deployment gate.** Public registration #111, exact tenant artifact cleanup
  #116, and authenticated contract download #117 are deployed on production
  commit `d666d3be` (run 32855388356).
- **Uploaded-artifact status.** `08-cheque-upload` created a real production blob
  in the final 2026-08-25 run, and `99-cleanup` removed the disposable tenant and its
  captured artifact successfully. Other upload families remain explicit coverage
  gaps in `tutorial-coverage.json`; they are no longer blocked by missing cleanup
  implementation, but still need their own real upload/download production pass.
- **External/manual boundaries.** Live payment-provider charging, physical-camera
  QR capture, Firebase guard authentication, and guard entry/exit scans require
  controlled sandbox/device checks. Guards intentionally cannot use the web
  password login; the automated suite validates guard provisioning/assignment
  and the surrounding resident/manager states without weakening that boundary.
- **Mobile validation.** Manager, Renter, and Security production-device journeys
  are tracked separately in `tutorials/mobile-capability-audit-2026-08-24.md`.
- **Execution status.** The corrected expanded suite ran against exact production
  commit `d666d3be` on 2026-08-25: **33/33 passed in 1.7 minutes**, including
  confirm-name hard deletion and post-delete verification. All 28 mapped web
  tutorials are marked production-passed; retained artifact and controlled
  manual-device gaps remain explicit in `tutorial-coverage.json`.
- **CI integration.** Not wired into GitHub Actions yet — intentional, since
  running on every PR would spam prod with TEST tenants. Recommend a manual
  workflow_dispatch trigger or a nightly cron with prefixed cleanup.

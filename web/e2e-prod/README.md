# Prod E2E Smoke

End-to-end smoke suite that exercises every major controller and the renter
portal UI against **prod**. Lives alongside the local `web/e2e/` suite but is
intentionally separate — different auth model, different runner config, real
data created in real DB.

## How it works

The backend trusts headers (`X-User-Id` / `X-User-Role` / `X-Tenant-Id`)
injected by the Next.js middleware after NextAuth validates the session
cookie. There is **no JWT validation in the backend**. Therefore:

1. The suite logs in via NextAuth's credentials callback at `/api/auth/callback/credentials`.
2. Playwright saves the `__Secure-next-auth.session-token` cookie to
   `.auth/superadmin.json`.
3. Every API call hits `/api/proxy/*` — the middleware reads the cookie,
   injects trusted headers, and rewrites to the backend.

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

## What's covered

| Spec | Surface |
| --- | --- |
| `00-smoke-auth` | Login + `/api/proxy/admin/tenants` (proves cookie + role check) |
| `01-provision` | Tenant, user, property, unit, renter, lease, lease activation |
| `02-cheque-lifecycle` | Collect → deposit → clear AND collect → deposit → bounce; FinancialTransaction emission |
| `03-vendor-and-interactions` | Vendor CRUD; lease interaction log + list |
| `04-reports` | Finance + payments reports respond non-5xx scoped to the test tenant |
| `05-renter-portal-ui` | Real browser: renter portal `/payments` widgets (skipped until portal-password capture is solved) |
| `99-cleanup` | Best-effort tenant deactivate; tolerates missing endpoint |

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

## Known gaps

- **Coverage gaps.** Not yet covered: penalty calculation, renewals end-to-end
  (renter accepts opportunity), online payments (Razorpay sandbox), portfolio
  bulk import, dashboard aggregates, marketplace listing publish, settlement
  finalize. Add specs as needed.
- **CI integration.** Not wired into GitHub Actions yet — intentional, since
  running on every PR would spam prod with TEST tenants. Recommend a manual
  workflow_dispatch trigger or a nightly cron with prefixed cleanup.

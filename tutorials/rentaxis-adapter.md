# RentAxis walkthrough adapter

Hand-written for the accounting v2 cut-over; regenerate with `walkthrough-adapter` only on request.

## Environments

| Env | Base URLs | Default |
|---|---|---|
| local | web `http://localhost:3000`, backend `http://localhost:8081` (a stale instance squats on 8080) | yes |
| staging | none — this product has no staging tier | |
| production | `https://rentaxis.uaenorth.cloudapp.azure.com` | gated — needs per-run confirmation |

## Product surfaces

- **web** — Next.js 16 dashboard, renter portal, superadmin and public marketplace. `web/src/app/[locale]/…`. Every operator role and the renter self-service portal live here. This is the only surface accounting v2 changes.
- **manager** — Flutter admin app, `mobile/apps/manager`. Property managers and tenant admins on the move.
- **renter** — Flutter resident app, `mobile/apps/renter`.
- **security** — Flutter guard app, `mobile/apps/security`. Gate passes only.
- **backend** — Spring Boot modular monolith, `backend/`, 27 controllers under `com.datagami.rentaxis.api`.

## Roles

| Role | Can | Must not |
|---|---|---|
| `SUPER_ADMIN` | provision organisations, flip tenant features, pivot into any tenant | be used for a tenant-level tutorial — it hides per-tenant permission behaviour |
| `TENANT_ADMIN` | everything inside one organisation, including finance | see another organisation |
| `ACCOUNTANT` | post and reverse journals, approve penalties, opening balances, period lock, reverse import batches | create leases, renters or properties |
| `PROPERTY_MANAGER` | draft leases, register and deposit cheques, propose penalties | post a lease, post or reverse a journal, open the chart of accounts |
| `TENANT_USER` | read-only operational views | anything financial |
| `RENTER` | own portal: amounts due, approved penalties, pay, read-only tenant ledger | see another renter's ledger |
| `SECURITY_GUARD` | scan and approve gate passes | everything else |

Provisioning: `SUPER_ADMIN` from `DataInitializer` locally (`admin@rentaxis.com` / `admin123`); every other role from `scripts/seed_demo_tenant.py`, which creates the users and sets their passwords through `/api/admin/users`.

## Routes by surface

`tutorials/capability-route-map.json` is the machine-checked list; `node tutorials/verify-capability-routes.mjs` fails if it drifts from the filesystem. Read the map, not a copy of it.

Accounting v2 route groups, for orientation:
- Setup — `/dashboard/finance/accounts`, `/dashboard/settings/account-template`, `/dashboard/settings/fiscal`, property → **Accounts** tab
- Documents — `/dashboard/leases/[id]` (lines, cheque grid, Post, Recognition schedule, Journals), `/dashboard/finance/journals` (+`[id]`, `new`), `/dashboard/finance/vouchers` (+`purchase-invoice`, `payment`)
- Register — `/dashboard/finance/cheques` and its `collection` / `return-replace` / `post-dated` views, `/dashboard/finance/penalties`
- Close — `/dashboard/finance/recognition`
- Control views — `/dashboard/finance/general-ledger`, `/tenant-ledger`, `/trial-balance` (there is no `/vendor-ledger` route — do not record it)
- Cut-over — `/dashboard/finance/opening-balances`, `/reconciliation`, `/import-batches`
- Vendors and banking — `/dashboard/finance/vendors`, `/dashboard/finance/bank-accounts`

## Seed contract

`scripts/seed_demo_tenant.py`, API-driven and idempotent. Order matters:

1. organisation (`POST /api/admin/tenants`) + `TENANT_ADMIN`
2. chart of accounts, property-account template, tenant default mappings (`POST /api/v1/finance/accounts/seed`)
3. fiscal year + period lock (`PUT /api/v1/finance/fiscal-settings`)
4. properties — each generates its own account set on creation (`POST /properties/{id}/accounts/generate`)
5. units, renters (with portal logins)
6. leases as DRAFT contracts with `lines[]`, then cheques via `PUT /leases/{id}/cheques`, then **Post** (`POST /leases/{id}/post`)
7. cheque lifecycle: deposit / clear / bounce / replace
8. month-end recognition to the end of last month
9. vendor + purchase invoice + payment voucher
10. listings, meetings, tickets, bookings, gate passes, promotions

Naming: organisations `TUTORIAL-…` or `VERIFY-…` plus a date and a random suffix; emails on `example.invalid`; renters prefixed `TEST-`. Never reuse the demo tenant's four named renters (Ahmed, Fatima, Rajesh, Sara) for a destructive proof — they are also the App Store reviewer's data.

Output: `scripts/seed_demo_tenant.out.json` (gitignored). The manifest keys the seed script writes today (`out[...]` in `scripts/seed_demo_tenant.py`): `tenant`, `baseUrl`, `adminLogin`, `adminUserId`, `properties`, `units`, `renterLogins`, `renterIds`, `leases`, `leaseStatus`, `cheques`, `recognitionRunTo`, `accounts`, `fiscal`, `vouchers`. The tutorial recorder reads it via `TUTORIAL_SEED_MANIFEST`.

## API boundaries

| Service | Path prefixes |
|---|---|
| backend, tenant-scoped | `/api/v1/**` |
| backend, superadmin | `/api/admin/**` |
| Next.js proxy (injects `X-User-*` from the NextAuth cookie) | `/api/proxy/**` |
| NextAuth | `/api/auth/**` |

Caddy routes `/api/v1`, `/api/admin` and part of `/api/auth` straight to the backend; everything else must go through `/api/proxy`.

## Auth

- Web: NextAuth credentials. CSRF → `POST /api/auth/callback/credentials` (form-encoded) → session cookie `__Secure-next-auth.session-token` (HTTPS) or `next-auth.session-token` (HTTP). Saved to `web/e2e-prod/.auth/<role>.json`. **That file holds live session tokens; it is a secret and never goes in a commit, a log or a frame.**
- `SUPER_ADMIN` pivots tenant with the `active_tenant_id` cookie, which the proxy forwards as `X-Tenant-Id`.
- Mobile: `POST /api/auth/login`, then `X-User-Id` / `X-User-Role` / `X-Tenant-Id` headers on every call.

## Capture policy

- Web viewport 1440x900, `tutorials/capture/record-tutorial.mjs`, one browser context per tutorial.
- Mobile: iOS Simulator, one `flutter drive` at a time — two concurrent builds flake on destination resolution.
- Never on screen: any real renter's full name or phone number, any bank account number, the contents of `.auth/*.json`, any password field with visible text, the superadmin's email.
- Every recording runs against the seeded demo tenant, never against a customer organisation.

## Test commands

| Scope | Command |
|---|---|
| backend | `cd backend && ./gradlew test` |
| backend, golden ledger only | `cd backend && ./gradlew test -PincludeTags=golden` |
| web unit | `cd web && npx vitest run` |
| web types + lint | `cd web && npx tsc --noEmit && npm run lint` |
| web e2e (dev) | `cd web && npx playwright test e2e` |
| web e2e (production) | `cd web && npx playwright test --config=e2e-prod/playwright.config.ts` |
| route + tutorial verifiers | `node tutorials/verify-capability-routes.mjs && node tutorials/verify-tutorial-library.mjs` |
| mobile | `cd mobile && melos run test` |

## Known hazards

- **Period lock.** A journal dated on or before `books_locked_through` is rejected. Set the fiscal window before any back-dated demo contract, or every post fails with the same unhelpful message.
- **Cheque grid total.** `POST /api/v1/leases` accepts a grid that does not add up; `POST …/post` refuses it. Arithmetic errors surface one step later than you expect.
- **Recognition is precomputed.** Rows exist from the moment a lease posts, but nothing is in the ledger until the job or a manual run posts them. A ledger that looks empty is usually an unrun recognition.
- **Journals are immutable.** There is no edit and no delete, only `reverse`. A bad take that posted a journal cannot be cleaned up by deleting it — reverse it, or delete the whole disposable organisation.
- **Deleting an organisation** (`DELETE /api/admin/tenants/{id}?confirmName=…`) requires the name to match exactly. That is the cleanup path; use it, and account for every ID in the run manifest.
- `flutter build ios --simulator` without `-d <udid>` produces an x86_64 build that will not launch on an arm64 simulator and poisons the cache; `flutter clean` fixes it.

# CLAUDE.md - RentAxis Project Guide

## Project Overview
RentAxis is a **multi-tenant property management SaaS** for UAE landlords. It handles properties, units, leases, rent collection, financial accounting, and renter self-service — with full Arabic/English bilingual support.

## Tech Stack
- **Backend:** Java 21 + Spring Boot 4.0.3 + Spring Data JPA + Liquibase + PostgreSQL 16
- **Frontend:** Next.js 16 + TypeScript 5 + Tailwind CSS 4 + NextAuth + next-intl (AR/EN)
- **Deployment:** Docker Compose + Caddy (reverse proxy) + GitHub Actions CI/CD → Azure VM
- **PDF Generation:** OpenHTMLtoPDF (Arabic font support)
- **Payments:** Razorpay integration with webhook verification
- **Storage:** Azure Blob Storage (optional), local filesystem fallback

## Architecture
- **Modular monolith** — single backend, single frontend, shared PostgreSQL
- **Multi-tenant isolation** — every table has `tenant_id` (`landlord_org_id`), enforced via Spring AOP `TenantAspect` + `TenantContextHolder` (ThreadLocal)
- **Stateless JWT auth** — `ApiSecurityFilter` extracts token, sets SecurityContext
- **RBAC roles:** `SUPER_ADMIN`, `TENANT_ADMIN`, `PROPERTY_MANAGER`, `TENANT_USER`, `RENTER`
- **Frontend portals:** Super Admin, Landlord/Admin Dashboard, Renter Portal — all in one Next.js app via route guards

## Key Directories
```
backend/                    # Spring Boot API
  src/main/java/com/rentaxis/
    config/                 # Security, CORS, app config
    controller/             # REST controllers (17 total)
    service/                # Business logic
    repository/             # JPA repositories
    entity/                 # JPA entities + enums
    dto/                    # Request/response DTOs
    aspect/                 # TenantAspect (multi-tenant AOP)
    filter/                 # ApiSecurityFilter (JWT)
  src/main/resources/
    db/changelog/changesets/ # Liquibase migrations (16 changesets)
    application*.yml        # Spring config profiles

web/                        # Next.js frontend
  src/
    app/[locale]/           # Locale-based routing (en, ar)
      auth/                 # Login, register pages
      dashboard/            # Main portal pages
      superadmin/           # Super admin pages
    components/             # Shared React components
    lib/                    # Utils, RBAC helpers
    types/                  # TypeScript type definitions
  messages/                 # i18n translation files (en.json, ar.json)
  e2e/                      # Playwright E2E tests (19 test files)

infra/                      # Azure provisioning, Caddy config, env templates
docker-compose.yml          # Local dev stack
docker-compose.prod.yml     # Production stack
```

## Database
- **PostgreSQL 16** with **Liquibase** migrations (auto-run on startup)
- **37 domain tables** — all use UUIDs as primary keys, snake_case columns
- **Key entities:** `landlord_org`, `users`, `properties`, `units`, `buildings`, `renters`, `leases`, `lease_events`, `lease_documents`, `payment_schedules`, `online_payments`, `accounts`, `financial_transactions`, `account_mappings`
- Migration files: `backend/src/main/resources/db/changelog/changesets/01-*.yaml` through `16-*.yaml`

## Important Enums
- **LeaseStatus:** DRAFT → PENDING_SIGNATURE → ACTIVE → NOTICE_GIVEN → TERMINATED/EXPIRED → CLOSED
- **PaymentStatus:** PENDING → COLLECTED → DEPOSITED → CLEARED (or OVERDUE/REJECTED/BOUNCED)
- **PaymentMethod:** CHEQUE, ONLINE, CASH, BANK_TRANSFER
- **UnitStatus:** VACANT, OCCUPIED
- **AccountType:** ASSET, LIABILITY, EQUITY, INCOME, EXPENSE

## API Structure
- Base path: `/api` (some routes under `/api/v1`)
- Auth: `POST /api/auth/login`, `POST /api/auth/register`, `GET /api/auth/me/tenants`
- CRUD: `/api/properties`, `/api/units`, `/api/buildings`, `/api/renters`, `/api/leases`
- Finance: `/api/accounts`, `/api/transactions`, `/api/account-mappings`
- Payments: `/api/payment-schedules`, `/api/online-payments`, `/api/webhooks/razorpay`
- Reports: `/api/transactions/reports/organisation`, `/reports/property/{id}`, `/reports/unit/{id}`
- Health: `GET /actuator/health`

## Running Locally
```bash
# Full stack via Docker
docker compose up -d

# Backend only
cd backend && ./gradlew bootRun

# Frontend only
cd web && npm install && npm run dev

# E2E tests
cd web && npm run test:e2e
```

## Environment Variables
- **Backend:** `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_PROFILES_ACTIVE`, `BACKEND_URL`, `ENCRYPTION_KEY` (optional), `AZURE_STORAGE_*` (optional)
- **Frontend:** `NEXT_PUBLIC_API_URL`, `NEXTAUTH_URL`, `NEXTAUTH_SECRET`, `BACKEND_URL`
- **Database:** `POSTGRES_USER`, `POSTGRES_PASSWORD`, `POSTGRES_DB`
- Env files: `.env.backend`, `.env.web`, `.env.postgres` (at project root)

## Code Conventions
- **Git commits:** Conventional format — `feat:`, `fix:`, `docs:`, `enhance:`, `test:`, `ci:`, `chore:`
- **Backend naming:** Singular entity classes (`Lease`), `*Repository`, `*Service`, `*Controller`, `*DTO` suffixes
- **Database:** UUIDs for PKs, snake_case columns, `tenant_id` on all tenant-scoped tables
- **Frontend:** TypeScript strict, React hooks, Tailwind utility classes, `use client` directives where needed
- **i18n:** All user-facing strings in `messages/{locale}.json`, use `useTranslations()` hook

## Critical Rules
1. **Never bypass tenant isolation** — all queries must scope by `tenant_id`. The `TenantAspect` handles this for annotated services, but always verify.
2. **Never store secrets in code** — use environment variables. `.env.*` files are gitignored.
3. **Liquibase migrations are append-only** — never modify existing changesets; always add new ones (next: `17-*.yaml`).
4. **Multi-tenant data leaks are P0 bugs** — always test that Tenant A cannot see Tenant B's data.
5. **Arabic RTL support** — when adding UI, ensure it works in both LTR (English) and RTL (Arabic) layouts.
6. **Frontend API calls** — use the proxy rewrite (`/api/*` → backend) in Next.js config; never hardcode backend URLs in client components.

## Deployment
- **Production:** Azure VM at `rentaxis.uaenorth.cloudapp.azure.com`
- **CI/CD:** GitHub Actions on push to `main` — builds Docker images, deploys via SSH
- **Reverse proxy:** Caddy with automatic HTTPS/Let's Encrypt
- **Health checks:** Backend `/actuator/health`, Web `/api/health`

## Testing
- **E2E:** Playwright with 7 role-based test projects (super-admin, tenant-admin, property-manager, tenant-user, renter, anonymous)
- **Backend:** Minimal — only smoke test exists. No unit/integration test suite yet.
- **Test auth:** Role-based storage states cached in `.auth/{role}.json`

## Known Limitations
- Backend unit/integration tests are minimal
- No AI features implemented yet (optional per spec)
- Single Azure VM — no HA/load balancing
- Flutter mobile app not yet built

# CLAUDE.md - RentAxis Project Guide

## Project Overview
RentAxis is a **multi-tenant property management SaaS** for UAE landlords. Properties, units, leases, rent collection, financial accounting, renter self-service — with Arabic/English bilingual support.

## Tech Stack
- **Backend:** Java 21 + Spring Boot 4.0.3 + Spring Data JPA + Liquibase + PostgreSQL 16
- **Frontend:** Next.js 16 + TypeScript 5 + Tailwind CSS 4 + NextAuth + next-intl (AR/EN)
- **Mobile:** Flutter monorepo (Melos) — Admin app + Renter app + shared `rentaxis_core` package
- **Deployment:** Docker Compose + Caddy → Azure VM, GitHub Actions CI/CD
- **Payments:** Razorpay integration with webhook verification

## Architecture
- **Modular monolith** — single backend, single frontend, shared PostgreSQL
- **Multi-tenant isolation** — `tenant_id` on all tables, enforced via `TenantAspect` AOP
- **Stateless JWT auth** — `ApiSecurityFilter` extracts token, sets SecurityContext
- **RBAC roles:** `SUPER_ADMIN`, `TENANT_ADMIN`, `PROPERTY_MANAGER`, `TENANT_USER`, `RENTER`

## Key Directories
- `backend/` — Spring Boot API (26 controllers, `com.datagami.rentaxis.api.*`)
- `web/` — Next.js frontend (dashboard, renter portal, superadmin)
- `mobile/apps/manager/` — Flutter Admin app (28 screens)
- `mobile/apps/renter/` — Flutter Renter app (9 screens)
- `mobile/packages/rentaxis_core/` — Shared Flutter package (theme, API services, widgets, providers)

## Critical Rules
1. **Never bypass tenant isolation** — all queries must scope by `tenant_id`
2. **Never store secrets in code** — use environment variables (`.env.*` files are gitignored)
3. **Liquibase migrations are append-only** — never modify existing changesets (next: `17-*.yaml`)
4. **Multi-tenant data leaks are P0 bugs**
5. **Arabic RTL support** — ensure UI works in both LTR and RTL
6. **Frontend API calls** — use Next.js proxy rewrite; never hardcode backend URLs in client components
7. **Mobile: adb install -r** — don't uninstall before installing, preserves login state

## Code Conventions
- **Git:** Conventional commits — `feat:`, `fix:`, `docs:`, `enhance:`, `test:`, `ci:`, `chore:`
- **Backend:** Singular entities, `*Repository/*Service/*Controller/*DTO` suffixes, UUIDs for PKs
- **Frontend:** TypeScript strict, Tailwind utilities, `useTranslations()` for i18n
- **Mobile:** Riverpod for state, GoRouter for navigation, `ConsumerStatefulWidget` pattern, raw `Map<String, dynamic>` (no models)

## Running Locally
```bash
docker compose up -d          # Full stack
cd backend && ./gradlew bootRun  # Backend only
cd web && npm run dev            # Frontend only
cd mobile/apps/manager && flutter run  # Admin app
cd mobile/apps/renter && flutter run   # Renter app
```

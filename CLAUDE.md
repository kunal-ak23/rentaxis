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
- `backend/` — Spring Boot API (27 controllers, `com.datagami.rentaxis.api.*`)
- `web/` — Next.js frontend (dashboard, renter portal, superadmin)
- `mobile/apps/manager/` — Flutter Admin app (28 screens)
- `mobile/apps/renter/` — Flutter Renter app (9 screens)
- `mobile/packages/rentaxis_core/` — Shared Flutter package (theme, API services, widgets, providers)

## Critical Rules
1. **Never bypass tenant isolation** — all queries must scope by `tenant_id`
2. **Never store secrets in code** — use environment variables (`.env.*` files are gitignored)
3. **Liquibase migrations are append-only** — never modify existing changesets (next: `89-*.yaml`; plan 4 owns 87/88)
4. **Multi-tenant data leaks are P0 bugs**
5. **Arabic RTL support** — ensure UI works in both LTR and RTL
6. **Frontend API calls** — use Next.js proxy rewrite; never hardcode backend URLs in client components
7. **Mobile: adb install -r** — don't uninstall before installing, preserves login state

## Code Conventions
- **Git:** Conventional commits — `feat:`, `fix:`, `docs:`, `enhance:`, `test:`, `ci:`, `chore:`
- **Backend:** Singular entities, `*Repository/*Service/*Controller/*DTO` suffixes, UUIDs for PKs
- **Frontend:** TypeScript strict, Tailwind utilities, `useTranslations()` for i18n
- **Mobile:** Riverpod for state, GoRouter for navigation, `ConsumerStatefulWidget` pattern, raw `Map<String, dynamic>` (no models)

## Accounting v2 (PACT replacement)
Spec `docs/superpowers/specs/2026-09-17-accounting-v2-design.md`; five stacked plans under `docs/superpowers/plans/2026-09-17-accounting-v2-plan{1..5}-*.md` (PRs #263 ← #288 ← #298 ← #319 ← plan 5). Ledger rules: `PostingService` is the only journal writer, reverse is the only mutation; per-property accounts by `AccountRole`; per-day recognition (rent ÷ term days). **Acceptance gate:** `cd backend && ./gradlew test -PincludeTags=golden` replays two anonymised PACT ledgers row by row — keep it green; never commit real client names or the PACT exports (they stay in ~/Downloads). Demo seed on v2: `scripts/seed_demo_tenant.py` (idempotent; manifest keys feed the tutorial recorder). Mobile finance screens are hidden behind `TenantFeature.MOBILE_FINANCE` (default OFF) until rebuilt.

## Running Locally
```bash
docker compose up -d          # Full stack
cd backend && ./gradlew bootRun  # Backend only
cd web && npm run dev            # Frontend only
cd mobile/apps/manager && flutter run  # Admin app
cd mobile/apps/renter && flutter run   # Renter app
```

## graphify (knowledge graph)

This repo has a graphify-built knowledge graph at `graphify-out/`. **Before answering codebase questions or doing wide file searches, check the graph first** — it summarizes 4,000+ nodes / 8,500+ edges across backend (Java) + web (Next.js) + mobile (Flutter) + design docs into 353 communities and explicit cross-file relationships.

- `graphify-out/GRAPH_REPORT.md` — god nodes, community structure, surprising connections, suggested questions. Read this for high-level orientation.
- `graphify-out/graph.json` — raw graph data, queryable via `/graphify query "<question>"`, `/graphify path "A" "B"`, `/graphify explain "Concept"`.
- `graphify-out/graph.html` — interactive visualization, open in browser.

**When to use the graph:**
- Onboarding to a new module → `/graphify explain "<concept>"`.
- Tracing dependencies → `/graphify path "A" "B"`.
- Open-ended "how does X work" → `/graphify query "<question>"`.
- Before reading >3 files for the same question, check the graph for the right entry point.

**Auto-update:** Git hooks (`.git/hooks/post-commit` and `post-checkout`) re-run AST extraction on code changes — no LLM needed, runs in ~5 seconds. For doc/image changes, run `/graphify --update` manually (uses LLM).

When the user types `/graphify`, invoke the Skill tool with `skill: "graphify"` before doing anything else.

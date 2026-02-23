You are my senior full-stack engineer + solution architect. Design and build a production-ready application for “RentAxis” (multi-landlord rental & lease management) with:

- Backend: Java 25 (use a stable LTS-grade approach; if any library requires Java 21, call it out and keep compatibility strategy)
- Framework: Spring Boot (latest compatible with Java 25)
- Database: PostgreSQL
- Web Frontend: Next.js (TypeScript) + Tailwind
- Authentication: Auth.js (NextAuth) for web, with shared token model for mobile
- Mobile: Flutter (later phase; must be supported by backend APIs now)
- Builds & Deployment: Docker (docker-compose for single-server deployment)
- Hosting: Either (A) single VM on Azure OR (B) an Azure-recommended low-cost setup that still stays simple. Provide both options, and recommend one with rationale given low concurrency (50–100 users, few concurrent users).

Write the output as if delivering to a CTO: architecture decisions, module coverage, data model, API spec, UI flows, deployment plan (Azure), security, and runbooks.

------------------------------------------------------------
1) Constraints & Principles (Non-negotiable)
------------------------------------------------------------
- Users: ~50–100 total; low concurrency.
- Cost-conscious: prefer single-server deployment and minimal managed services initially.
- Multi-tenant:
  - Tenant = Landlord Organization
  - Strict tenant isolation for all data
  - Super Admin cross-tenant visibility must be audited.
- Portals:
  - Landlord Portal
  - Tenant Portal
  - Vendor Portal
  - Super Admin Portal
- Payments:
  - Integrate with client-selected payment gateway via API + webhooks
  - Client owns procurement/merchant onboarding/acquirer relationships
  - We implement integration only.
- MVP: 30 days (usable and demonstrable)
- Production-ready: 45–60 days (bugs addressed + hardening + limited additional feature requests)
- Optional AI capabilities:
  - Must be feature-flagged and NOT required for core platform operation
  - May require additional AI cost and can be enabled later.

------------------------------------------------------------
2) Recommended Architecture (modular monolith)
------------------------------------------------------------
Build a modular monolith to keep cost and complexity low:
- One Spring Boot backend exposing REST APIs.
- One Next.js web app (multiple portals via RBAC and route guards).
- One PostgreSQL DB.
- One object storage abstraction for documents:
  - Start with local storage volume on server
  - Must be switchable to Azure Blob Storage later without code rewrite.
- Background jobs:
  - Simple: Spring Scheduler + DB-based job table
  - Optional: Redis + queue only if needed.

Tenant isolation strategy:
- Every table includes tenant_id (landlord_org_id)
- Enforce tenant scoping at:
  - Service layer
  - Repository layer
  - Optional: Postgres Row-Level Security (RLS) recommended if feasible.

------------------------------------------------------------
3) Authentication & Identity (Auth.js + Spring Boot + Mobile)
------------------------------------------------------------
Web (Next.js + Auth.js):
- Use Auth.js Credentials provider initially (email/password).
- Auth.js manages browser session.
- Next.js obtains backend JWT (access + refresh) via token-exchange.
- All API calls from web attach backend access token.

Mobile (Flutter):
- Uses backend auth endpoints directly:
  - POST /api/auth/login
  - POST /api/auth/refresh
  - POST /api/auth/logout
- Same JWT format as web.

Backend (Spring Boot):
- JWT access token + refresh token
- Role/tenant claims embedded or resolved server-side
- RBAC enforced on every endpoint
- Audit sensitive events

------------------------------------------------------------
4) Product Modules (must cover all)
------------------------------------------------------------
A) Multi-Tenant SaaS Foundation
- Tenant = Landlord Org
- Tenant config: currency, reminders, penalty defaults, templates
- Super Admin cross-tenant monitoring (audited)

B) User Management & RBAC
- Roles: Owner, Accountant, Property Manager, Ops, Viewer (configurable)
- Property-level assignments
- Invite/Deactivate/Reassign users

C) Master Data Model
- LandlordOrg → Property → (Optional) Building → Unit → Lease
- Multi-currency (configurable; default AED per tenant)

D) Lease Lifecycle
- Draft → Pending Signature → Active → Notice Given → Terminated/Early Move-out → Expired → Closed
- Renewal reminders
- Unit transfers
- Early move-out penalty config
- Lease docs + notes

E) Rent Schedules, Collections, Penalties
- Generate schedule (monthly/quarterly/semiannual/annual/custom)
- Track due/paid/overdue/partial
- Penalty rules (flat/day, %/day, one-time after grace)
- Partial payment allocation rules
- Manual payments (cash/bank transfer/cheque) with audit
- Receipts & ledger

F) Tenant KYC & Document Vault
- Configurable required docs
- Upload + approve/reject workflow
- Expiry tracking + reminders
- Secure storage + signed URLs
- Tagging & classification

G) Maintenance & SLA
- Ticket creation with attachments
- Assignment to vendor/internal staff
- SLA states: opened/assigned/in-progress/resolved/closed
- Comments, activity logs, notifications
- Costs and vendor invoice linking

H) Vendor & Expense Management
- Vendor master
- Expense capture with invoice metadata
- Optional approvals above threshold
- Reports: expenses by category/property/unit; net income

I) Payment Gateway Integration (API Only)
- Tenant pay-now initiation
- Webhook verification + idempotency
- Status update + receipt generation
- Gateway config per tenant (keys, secrets, env)
- Document boundary: procurement is client responsibility

J) Reporting & Statements
- Due vs collected dashboard
- Overdue aging
- Tenant ledger
- Landlord statements (income/expense/net)
- CSV/Excel exports with filters

------------------------------------------------------------
5) Optional AI Capabilities (feature-flagged; additional AI cost)
------------------------------------------------------------
Must NOT be required for core use.

AI 1: Document Intelligence (OCR + metadata extraction)
- Cheque scan fields: cheque no, amount, date, bank, payee (if possible)
- KYC scan fields: name, doc id, expiry, nationality, etc.
- Review dialog for extracted data before saving
- Store structured metadata for search and reporting
- Implement provider interface:
  - ExtractionProvider: DisabledProvider (default), AzureAIProvider (optional), LocalOCRProvider (optional)

AI 2: Conversational Reporting
- Natural language queries mapped ONLY to safe whitelisted analytics queries
- Respect tenant/RBAC always
- Feature flag: AI Enabled (On/Off)

------------------------------------------------------------
6) Data Model (PostgreSQL)
------------------------------------------------------------
Provide an ERD-style schema with:
- tenant scoping via landlord_org_id
- core tables:
  - landlord_org, org_settings
  - users, roles, user_roles, user_property_assignments
  - properties, buildings, units
  - tenants (renters), leases, lease_events, lease_documents
  - rent_schedules, payments, payment_items, penalty_rules
  - kyc_documents, document_metadata
  - maintenance_requests, maintenance_activity, maintenance_attachments
  - vendors, vendor_jobs
  - expenses, expense_approvals
  - audit_logs, notifications, templates
Include indexes and constraints.

------------------------------------------------------------
7) API Spec (REST + OpenAPI)
------------------------------------------------------------
Deliver:
- Route map by module
- OpenAPI outline
- Pagination/filter/sort standards
- Webhooks:
  - /api/payments/webhooks/{provider}
  - signature verification + idempotency keys

Auth endpoints:
- POST /api/auth/login
- POST /api/auth/refresh
- POST /api/auth/logout
- POST /api/auth/exchange (used by Auth.js callback to obtain backend token)

------------------------------------------------------------
8) Web UI (Next.js)
------------------------------------------------------------
Provide sitemap and key screens for:
- Landlord Portal (dashboard, properties, leases, collections, KYC approvals, maintenance, vendors, expenses, reports, settings)
- Tenant Portal (rent schedule, pay now, KYC upload, maintenance)
- Vendor Portal (assigned jobs, status updates, attachments)
- Super Admin (tenant provisioning, audits, monitoring)

------------------------------------------------------------
9) Flutter Mobile Plan (later phase; backend-ready now)
------------------------------------------------------------
Define app modules:
- Auth (JWT)
- Tenant: pay now, schedule, KYC upload, maintenance
- Landlord: approvals, dashboard, maintenance assignment
- Vendor: job updates
Define DTOs and API contract for Flutter.

Publishing policy:
- Default: publish under our accounts.
- If client wants their name, they must provide Apple/Play Store credentials.

------------------------------------------------------------
10) Deployment on Azure (Docker)
------------------------------------------------------------
Provide two deployment options and recommend one:

Option A (simplest single-server):
- Azure VM (Ubuntu) + Docker + docker-compose
- Services:
  - nginx
  - nextjs
  - springboot
  - postgres
  - (optional) redis
- Use volumes for postgres + documents
- Let’s Encrypt TLS (or Azure Front Door later)

Option B (Azure recommended low-cost but still simple):
- Azure Container Apps or Azure App Service for containers (if cost acceptable)
- Azure Database for PostgreSQL (flexible server) (optional)
- Azure Blob Storage for documents (optional)
Explain cost/benefit and recommend based on low concurrency.

Include:
- docker-compose.yaml (services, env vars, volumes, healthchecks)
- nginx.conf
- CI build steps (docker build, tag, push)
- Backup plan:
  - nightly pg_dump to encrypted storage
  - restore runbook

------------------------------------------------------------
11) Delivery Plan
------------------------------------------------------------
MVP (T0–T+30 days)
- Must deliver:
  - tenant isolation + RBAC
  - properties/units/leases
  - rent schedules + due tracking + manual payments
  - KYC upload + approval workflow (no AI)
  - maintenance basic
  - reporting basic + exports
  - payment gateway sandbox integration
  - hosted on our infra for UAT

Production Ready (T+30 to T+45–60 days)
- Must deliver:
  - bug fixes (Sev-1 = 0 open)
  - performance + security hardening
  - UX refinements
  - production gateway integration
  - deploy on client servers (Azure VM or recommended option)
  - limited additional feature requests from client (minor enhancements only)
- Any major changes require a Change Request.

Define severity policy and acceptance criteria.

------------------------------------------------------------
12) Output Requirements
------------------------------------------------------------
Produce:
1) Architecture diagram (ASCII/Mermaid)
2) Data model schema (tables + key fields)
3) API routes list by module
4) Auth flows (Auth.js + Spring Boot + Flutter)
5) UI sitemap
6) Docker deployment guide (Azure)
7) Security checklist + audit logging plan
8) Testing plan (unit, integration, tenant isolation, webhook idempotency)
9) 30-day MVP sprint plan + 45–60 day hardening plan
10) Repo structure:
   - /backend (Spring Boot)
   - /web (Next.js)
   - /mobile (Flutter placeholder)
   - /contracts (OpenAPI + shared DTOs)

Now generate the complete blueprint and implementation plan. Keep it simple, low-cost, but engineered correctly.
# Module A: Multi-Tenant SaaS Foundation

## 1. Overview
The Multi-Tenant SaaS Foundation is the core architectural pillar of RentAxis. It ensures that a single deployed instance of the application can securely serve multiple Landlord Organizations (Tenants) while maintaining strict data isolation. It natively supports UAE localization, including English/Arabic dual-language capabilities and Right-to-Left (RTL) reading direction.

## 2. Architecture & Technical Decisions
- **Tenant Context:** Spring Boot will use a `TenantInterceptor` to parse the `landlord_org_id` from the JWT token and store it in a ThreadLocal `TenantContextHolder`.
- **Data Isolation Model:** 
  - Shared Database, Shared Schema approach for low cost and simplicity.
  - Every operational table will include a `tenant_id` column.
  - Enforcement via Hibernate `@Filter` on entities or Postgres Row-Level Security (RLS) to append `WHERE tenant_id = :tenantId` automatically.
- **Cross-Tenant Visibility:** A `SUPER_ADMIN` flag in the JWT will bypass the tenant filter for system administrators, with all cross-tenant data requests strictly logged in the `audit_logs` table.
- **Internationalization (i18n) & RTL:** 
  - Next.js will utilize `next-intl` for dictionary management (English & Arabic).
  - Tailwind directives (e.g., `ltr:ml-4 rtl:mr-4` or native logical properties `ms-4`) will ensure seamless Bi-Directional (BiDi) UI layouts for UAE users.
  - Spring Boot will resolve the `Accept-Language` header to return localized error messages.

## 3. Data Model
### Core Tables:
- `landlord_org`: 
  - `id` (UUID, Primary Key)
  - `name` (String, Organization Name)
  - `status` (Enum: ACTIVE, SUSPENDED)
  - `created_at`, `updated_at` (Timestamps)
- `org_settings`:
  - `id` (UUID)
  - `org_id` (UUID, Foreign Key)
  - `default_currency` (String, e.g., AED)
  - `default_locale` (Enum: EN, AR)
  - `timezone` (String, default 'Asia/Dubai')
  - `reminder_days` (Integer)
  - `penalty_defaults` (JSONB)
  - `document_templates` (JSONB)

## 4. API Specification
- `POST /api/admin/tenants` (Super Admin only: Provision new Landlord Org)
- `GET /api/admin/tenants` (Super Admin only: List all Orgs)
- `GET /api/v1/settings` (Tenant Admin: Get Org Settings)
- `PUT /api/v1/settings` (Tenant Admin: Update Currency, Locale, Timezone, Reminders)

## 5. UI Flows & Interfaces
- **Super Admin Dashboard:** List of all provisioned landlord organizations, subscription/status toggle, and an audit trail viewer for cross-tenant actions.
- **Landlord Portal (Settings):** Interface for Landlord Admins to configure global settings such as currency (AED), default language preference, timezone, and template customizations. Language toggle in the navigation bar to switch between LTR English and RTL Arabic immediately.

## 6. Security Constraints
- All APIs (except Super Admin routes) must strictly extract `tenant_id` from the authenticated JWT.
- Any attempt to access a resource (like a Property or Lease) that belongs to a different `tenant_id` must result in a `403 Forbidden` or `404 Not Found`.

## 7. Execution Plan (MVP Phase)
- Implement `TenantContextHolder` and interceptor.
- Configure Hibernate filters across base entities.
- Setup `next-intl` configuration and RTL utility classes in Tailwind.

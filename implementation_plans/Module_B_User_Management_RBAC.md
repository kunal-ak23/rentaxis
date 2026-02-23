# Module B: User Management & RBAC

## 1. Overview
The User Management & RBAC (Role-Based Access Control) module governs identity securely within the limits of a Landlord Organization. It manages various staff profiles (Owners, Accountants, Property Managers) and dictates what data and actions they can access.

## 2. Architecture & Technical Decisions
- **Authentication:** Auth.js (NextAuth) on Next.js handling the browser session. Spring Boot handles API security using JWT.
- **JWT Content:** The payload will include standard claims (`sub`), custom claims for `tenant_id` (UUID), `role` (String or Array), and `property_ids` (for scope limits).
- **Mobile Support:** Mobile clients will hit Spring Boot directly (`/api/auth/login`) to receive the identical JWT format, ensuring a unified security layer.

## 3. Data Model
### Core Tables:
- `users`:
  - `id` (UUID, Primary Key)
  - `tenant_id` (UUID, Foreign Key to `landlord_org`, nullable for Super Admins)
  - `email` (String, Unique)
  - `password_hash` (String, BCrypt)
  - `status` (Enum: ACTIVE, INACTIVE)
- `roles`:
  - `id` (UUID)
  - `name` (Enum: OWNER, ACCOUNTANT, PROPERTY_MANAGER, OPS, VIEWER)
- `user_roles`:
  - `user_id`, `role_id` (Composite PK)
- `user_property_assignments`:
  - `user_id`, `property_id` (For users who manage specific buildings only)

## 4. API Specification
- `POST /api/auth/login` (Standard credentials exchange)
- `POST /api/auth/refresh` (Obtain new access token)
- `POST /api/auth/exchange` (Auth.js session handoff)
- `POST /api/v1/users/invite` (Owner/Admin: Send invite email)
- `GET /api/v1/users` (List organization users)
- `PUT /api/v1/users/{id}/roles` (Assign roles and property scopes)
- `DELETE /api/v1/users/{id}` (Soft delete / deactivate)

## 5. UI Flows & Interfaces
- **Landlord Portal (User Directory):** Datatable displaying all staff members.
- **Invite Modal:** Form to input email, select the RBAC role from a dropdown, and conditionally assign the user to specific Properties using a multi-select component.
- **Route Guards:** Next.js middleware to bounce users attempting to route to unauthorized pages (e.g., VIEWER accessing `/settings`).

## 6. Security Constraints
- Passwords must be hashed using BCrypt.
- Method-level security in Spring Boot (`@PreAuthorize("hasRole('OWNER')")`) to prevent unauthorized API execution, even if the UI element is somehow exposed.
- User management requests must belong to the active tenant execution context.

## 7. Execution Plan (MVP Phase)
- Setup Spring Security JWT filter and Auth.js integration.
- Implement User and Role repositories.
- Build directory views and invite workflows in the MVP timeframe.

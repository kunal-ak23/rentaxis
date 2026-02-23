# Module B: User Management & RBAC

## 1. Overview
The User Management & RBAC (Role-Based Access Control) module governs identity securely within the limits of a Landlord Organization. It manages various staff profiles and dictates what data and actions they can access. It handles individual user language preferences for seamless operations across multilingual teams in the UAE.

## 2. Architecture & Technical Decisions
- **Authentication:** Auth.js (NextAuth) on Next.js handling the browser session. Spring Boot handles API security using JWT.
- **JWT Content:** The payload will include standard claims (`sub`), custom claims for `tenant_id` (UUID), `role` (String or Array), `property_ids` (for scope limits), and `preferred_locale`.
- **Mobile Support:** Mobile clients will hit Spring Boot directly (`/api/auth/login`) to receive the identical JWT format, ensuring a unified security layer.

## 3. Data Model
### Core Tables:
- `users`:
  - `id` (UUID, Primary Key)
  - `tenant_id` (UUID, Foreign Key to `landlord_org`, nullable for Super Admins)
  - `email` (String, Unique)
  - `password_hash` (String, BCrypt)
  - `preferred_locale` (Enum: EN, AR - overrides org default)
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
- `POST /api/v1/users/invite` (Owner/Admin: Send invite email, localized to the user's preferred language)
- `GET /api/v1/users` (List organization users)
- `PUT /api/v1/users/{id}/roles` (Assign roles and property scopes)
- `PUT /api/v1/users/me/locale` (Update the active user's language preference)
- `DELETE /api/v1/users/{id}` (Soft delete / deactivate)

## 5. UI Flows & Interfaces
- **Landlord Portal (User Directory):** Datatable displaying all staff members.
- **Invite Modal:** Form to input email, select the RBAC role, assign properties, and set an initial language preference (English/Arabic) so the welcome email is translated correctly.
- **User Profile Drawer:** Interface allowing the individual user to override the UI language (changes to Arabic, flipping the layout RTL instantly).

## 6. Security Constraints
- Passwords must be hashed using BCrypt.
- Method-level security in Spring Boot (`@PreAuthorize("hasRole('OWNER')")`) to prevent unauthorized API execution.
- User management requests must belong to the active tenant execution context.

## 7. Execution Plan (MVP Phase)
- Setup Spring Security JWT filter and Auth.js integration.
- Implement User, Role, and translation-preference mechanisms.
- Build directory views and invite workflows in the MVP timeframe.

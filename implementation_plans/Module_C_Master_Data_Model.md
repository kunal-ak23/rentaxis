# Module C: Master Data Model

## 1. Overview
The Master Data Model represents the hierarchical real estate structure within a Landlord Organization: `LandlordOrg -> Property -> (Optional Building) -> Unit -> Lease`. This dataset serves as the backbone for linking financial, operational, and maintenance records.

## 2. Architecture & Technical Decisions
- **Flexible Hierarchy:** Buildings are optional. Units can belong directly to a Property or a Building within a Property.
- **Multi-Currency:** Rent amounts are defined per Unit/Lease, but UI will uniformly display values using `tenant_id` context currency unless overridden at the lease level.
- **Tenant Scoping:** All reads and writes to Property, Building, and Unit entities evaluate the current context `tenant_id` before committing or returning models.

## 3. Data Model
### Core Tables:
- `properties`:
  - `id` (UUID, Primary Key)
  - `tenant_id` (UUID, Foreign Key)
  - `name` (String, e.g., "Sunset Villas")
  - `address` (Text)
  - `type` (Enum: COMMERCIAL, RESIDENTIAL, MIXED)
- `buildings`:
  - `id` (UUID)
  - `property_id` (UUID, Foreign Key)
  - `name` (String, e.g., "Tower A")
  - `floors` (Integer)
- `units`:
  - `id` (UUID)
  - `property_id` (UUID, Foreign Key)
  - `building_id` (UUID, Nullable Foreign Key)
  - `unit_number` (String)
  - `type` (Enum: 1BHK, 2BHK, RETAIL, OFFICE)
  - `size_sqft` (Numeric)
  - `status` (Enum: VACANT, OCCUPIED, MAINTENANCE)

## 4. API Specification
- `GET /api/v1/properties` (List all properties for the tenant)
- `POST /api/v1/properties` (Create a property)
- `GET /api/v1/properties/{id}/buildings` (List buildings in a property)
- `GET /api/v1/properties/{id}/units` (List units in a property)
- `POST /api/v1/units/bulk` (Bulk upload units via CSV payload)

## 5. UI Flows & Interfaces
- **Properties Dashboard:** Visual card layout or list view of all owned properties showing summary stats (Total Units, Occupancy %).
- **Property Detail View:** Tabbed interface separating "Overview", "Buildings", "Units", and "Active Leases".
- **Unit Management Wizard:** Form to add units individually, alongside a bulk CSV import utility for onboarding large complexes efficiently.

## 6. Security Constraints
- Users with property-level restrictions (e.g., Property Manager for "Tower A") should structurally only be capable of fetching units strictly related to their assigned `property_ids` via the API.

## 7. Execution Plan (MVP Phase)
- Construct database entities mapped with JPA and Hibernate tenant filters.
- Develop CRUD endpoints for Properties and Units.
- Implement the Next.js visual hierarchy and management views.

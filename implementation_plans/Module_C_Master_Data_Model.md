# Module C: Master Data Model

> **STATUS: 🟢 COMPLETE**
> 
> | Item | Status |
> |------|--------|
> | `Property` entity with `tenant_id`, enums | ✅ Done (`Property.java`, `PropertyType.java`, `Emirate.java`) |
> | `Building` entity | ✅ Done (`Building.java` with BaseTenantEntity) |
> | `Unit` entity with status/type enums | ✅ Done (`Unit.java`, `UnitStatus.java`, `UnitType.java`) |
> | `PropertyController` + `PropertyService` | ✅ Done |
> | `UnitController` + `UnitService` | ✅ Done (Includes Bulk CSV upload) |
> | Property/Unit repositories | ✅ Done (`PropertyRepository`, `UnitRepository`, `BuildingRepository`) |
> | Properties Dashboard (flip cards UI) | ✅ Done (`/dashboard/properties/page.tsx`) |
> | Property Stats DTO | ✅ Done (`PropertyStatsDTO.java`) |
> | `GET /api/v1/properties` | ✅ Done |
> | `POST /api/v1/properties` | ✅ Done |
> | Arabic naming fields (`name_en`, `name_ar`) | ✅ Done (on Property and Building entities, UI support) |
> | Makani Number / Plot Number fields | ✅ Done (on Property entity) |
> | Property Detail View (tabbed: Overview, Buildings, Units, Leases) | ✅ Done (`/dashboard/properties/[id]/page.tsx`) |
> | Bulk CSV unit upload (`POST /api/v1/units/bulk`) | ✅ Done |
> | Building CRUD endpoints | ✅ Done (`BuildingController.java`, `BuildingService.java`) |
> | BiDi form support (RTL/LTR) | ✅ Done (Using `dir="rtl"` in UI for AR fields) |
> 
> *Last reviewed: 2026-02-26*

## 1. Overview
The Master Data Model represents the hierarchical real estate structure within a Landlord Organization: `LandlordOrg -> Property -> (Optional Building) -> Unit -> Lease`. This dataset serves as the backbone for linking financial, operational, and maintenance records, incorporating UAE-specific address structures.

## 2. Architecture & Technical Decisions
- **Flexible Hierarchy:** Buildings are optional. Units can belong directly to a Property or a Building within a Property.
- **UAE Region Localization:** The address structure will capture Emirate and Makani Number/Plot Number, aligning with typical UAE real estate registry formats.
- **Tenant Scoping:** All reads and writes to Property, Building, and Unit entities evaluate the current context `tenant_id` before committing or returning models.

## 3. Data Model
### Core Tables:
- `properties`:
  - `id` (UUID, Primary Key)
  - `tenant_id` (UUID, Foreign Key)
  - `name_en` (String, e.g., "Sunset Villas")
  - `name_ar` (String, Optional Arabic name)
  - `emirate` (Enum: DUBAI, ABU_DHABI, SHARJAH, AJMAN, UMM_AL_QUWAIN, RAS_AL_KHAIMAH, FUJAIRAH)
  - `address` (Text)
  - `makani_number` (String, UAE specific geolocation id)
  - `type` (Enum: COMMERCIAL, RESIDENTIAL, MIXED)
- `buildings`:
  - `id` (UUID)
  - `property_id` (UUID, Foreign Key)
  - `name_en`, `name_ar` (Strings)
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
- **Properties Dashboard:** Visual card layout displaying properties. The UI will toggle between `name_en` and `name_ar` based on the user's active locale.
- **Property Detail View:** Tabbed interface separating "Overview", "Buildings", "Units", and "Active Leases".
- **Unit Management Wizard:** Form to add units individually, integrating structured address fields (Emirate, Makani).

## 6. Security Constraints
- Users with property-level restrictions (e.g., Property Manager for a specific building) should structurally only be capable of fetching units strictly related to their assigned `property_ids` via the API.

## 7. Execution Plan (MVP Phase)
- Construct database entities mapped with JPA and Hibernate tenant filters, including Arabic naming fields and Emirate lookups.
- Develop CRUD endpoints for Properties and Units.
- Implement the Next.js visual hierarchy enforcing bi-directional language support on forms.

-- ============================================================================
-- RentAxis Demo Tenant Seed Script
-- Tenant: "XYZ Developers" -- Business Bay, Dubai, UAE
--
-- This script is idempotent-safe: uses ON CONFLICT (id) DO NOTHING
-- and fixed UUIDs so it can be re-run without creating duplicates.
--
-- Usage: psql -U rentaxis -d rentaxis -f seed-demo-tenant.sql
-- ============================================================================

BEGIN;

DO $$
DECLARE
    -- ========================================================================
    -- FIXED UUIDs -- deterministic so the script is re-runnable
    -- ========================================================================

    -- Tenant
    v_tenant_id UUID := 'a0000000-0000-4000-8000-000000000001';

    -- Users
    v_user_superadmin UUID := 'b0000000-0000-4000-8000-000000000000';
    v_user_admin      UUID := 'b0000000-0000-4000-8000-000000000001';
    v_user_pm1        UUID := 'b0000000-0000-4000-8000-000000000002';
    v_user_pm2        UUID := 'b0000000-0000-4000-8000-000000000003';
    v_user_staff      UUID := 'b0000000-0000-4000-8000-000000000004';
    v_user_renter1    UUID := 'b0000000-0000-4000-8000-000000000005';
    v_user_renter2    UUID := 'b0000000-0000-4000-8000-000000000006';
    v_user_renter3    UUID := 'b0000000-0000-4000-8000-000000000007';
    v_user_renter4    UUID := 'b0000000-0000-4000-8000-000000000008';

    -- Properties
    v_prop_marina UUID := 'c0000000-0000-4000-8000-000000000001';
    v_prop_biz    UUID := 'c0000000-0000-4000-8000-000000000002';
    v_prop_palm   UUID := 'c0000000-0000-4000-8000-000000000003';

    -- Buildings
    v_bld_tower_a  UUID := 'c1000000-0000-4000-8000-000000000001';  -- Marina Heights Tower A
    v_bld_block_a  UUID := 'c1000000-0000-4000-8000-000000000002';  -- Business Central Block A
    v_bld_block_b  UUID := 'c1000000-0000-4000-8000-000000000003';  -- Business Central Block B

    -- Units -- Marina Heights Tower A
    v_unit_m101 UUID := 'd0000000-0000-4000-8000-000000000001';
    v_unit_m102 UUID := 'd0000000-0000-4000-8000-000000000002';
    v_unit_m201 UUID := 'd0000000-0000-4000-8000-000000000003';
    v_unit_m202 UUID := 'd0000000-0000-4000-8000-000000000004';
    v_unit_m301 UUID := 'd0000000-0000-4000-8000-000000000005';
    -- Units -- Business Central Block A
    v_unit_a01  UUID := 'd0000000-0000-4000-8000-000000000006';
    v_unit_a02  UUID := 'd0000000-0000-4000-8000-000000000007';
    -- Units -- Business Central Block B
    v_unit_b01  UUID := 'd0000000-0000-4000-8000-000000000008';
    v_unit_b02  UUID := 'd0000000-0000-4000-8000-000000000009';
    -- Units -- Palm Residences (no building)
    v_unit_v01  UUID := 'd0000000-0000-4000-8000-000000000010';
    v_unit_v02  UUID := 'd0000000-0000-4000-8000-000000000011';
    v_unit_v03  UUID := 'd0000000-0000-4000-8000-000000000012';

    -- Renters
    v_renter1 UUID := 'e0000000-0000-4000-8000-000000000001';  -- John Smith
    v_renter2 UUID := 'e0000000-0000-4000-8000-000000000002';  -- Maria Garcia
    v_renter3 UUID := 'e0000000-0000-4000-8000-000000000003';  -- Rajesh Kumar
    v_renter4 UUID := 'e0000000-0000-4000-8000-000000000004';  -- Li Wei

    -- Leases
    v_lease1 UUID := 'f0000000-0000-4000-8000-000000000001';  -- John  -> 101 Marina   ACTIVE
    v_lease2 UUID := 'f0000000-0000-4000-8000-000000000002';  -- Maria -> 102 Marina   ACTIVE
    v_lease3 UUID := 'f0000000-0000-4000-8000-000000000003';  -- Rajesh-> A01 Biz      ACTIVE
    v_lease4 UUID := 'f0000000-0000-4000-8000-000000000004';  -- Li Wei-> V01 Palm     ACTIVE
    v_lease5 UUID := 'f0000000-0000-4000-8000-000000000005';  -- John  -> V02 Palm     ACTIVE
    v_lease6 UUID := 'f0000000-0000-4000-8000-000000000006';  -- Maria -> 201 Marina   EXPIRED
    v_lease7 UUID := 'f0000000-0000-4000-8000-000000000007';  -- Li Wei-> B01 Biz      DRAFT
    v_lease8 UUID := 'f0000000-0000-4000-8000-000000000008';  -- Rajesh-> 201 Marina   PENDING_SIGNATURE

    -- Chart of Accounts
    v_acct_bank       UUID := 'a1000000-0000-4000-8000-000000000001';  -- A-01-01 Bank/Cash
    v_acct_receivable UUID := 'a1000000-0000-4000-8000-000000000002';  -- A-02-01 Accounts Receivable
    v_acct_deposit    UUID := 'a1000000-0000-4000-8000-000000000003';  -- B-01-01 Security Deposits
    v_acct_advance    UUID := 'a1000000-0000-4000-8000-000000000004';  -- B-02-01 Advance Rent
    v_acct_rental     UUID := 'a1000000-0000-4000-8000-000000000005';  -- C-01-01 Rental Income
    v_acct_service    UUID := 'a1000000-0000-4000-8000-000000000006';  -- C-02-01 Service Charges
    v_acct_maint      UUID := 'a1000000-0000-4000-8000-000000000007';  -- D-01-01 Maintenance
    v_acct_mgmt       UUID := 'a1000000-0000-4000-8000-000000000008';  -- D-02-01 Management Fees
    v_acct_equity     UUID := 'a1000000-0000-4000-8000-000000000009';  -- E-01-01 Owner's Equity

    -- Account Mapping
    v_mapping1 UUID := 'a2000000-0000-4000-8000-000000000001';

    -- Rent Collection Settings
    v_rcs_marina UUID := 'a3000000-0000-4000-8000-000000000001';
    v_rcs_biz    UUID := 'a3000000-0000-4000-8000-000000000002';
    v_rcs_palm   UUID := 'a3000000-0000-4000-8000-000000000003';

    -- Tickets
    v_ticket1 UUID := 'ee000000-0000-4000-8000-000000000001';
    v_ticket2 UUID := 'ee000000-0000-4000-8000-000000000002';
    v_ticket3 UUID := 'ee000000-0000-4000-8000-000000000003';
    v_ticket4 UUID := 'ee000000-0000-4000-8000-000000000004';
    v_ticket5 UUID := 'ee000000-0000-4000-8000-000000000005';
    v_ticket6 UUID := 'ee000000-0000-4000-8000-000000000006';

    -- Ticket Replies
    v_reply1a UUID := 'f1000000-0000-4000-8000-000000000001';
    v_reply1b UUID := 'f1000000-0000-4000-8000-000000000002';
    v_reply1c UUID := 'f1000000-0000-4000-8000-000000000003';
    v_reply2a UUID := 'f1000000-0000-4000-8000-000000000004';
    v_reply2b UUID := 'f1000000-0000-4000-8000-000000000005';
    v_reply3a UUID := 'f1000000-0000-4000-8000-000000000006';

    -- Ticket History
    v_thist1a UUID := 'f2000000-0000-4000-8000-000000000001';
    v_thist1b UUID := 'f2000000-0000-4000-8000-000000000002';
    v_thist1c UUID := 'f2000000-0000-4000-8000-000000000003';
    v_thist1d UUID := 'f2000000-0000-4000-8000-000000000004';
    v_thist2a UUID := 'f2000000-0000-4000-8000-000000000005';
    v_thist2b UUID := 'f2000000-0000-4000-8000-000000000006';
    v_thist2c UUID := 'f2000000-0000-4000-8000-000000000007';
    v_thist3a UUID := 'f2000000-0000-4000-8000-000000000008';
    v_thist3b UUID := 'f2000000-0000-4000-8000-000000000009';
    v_thist4a UUID := 'f2000000-0000-4000-8000-000000000010';
    v_thist4b UUID := 'f2000000-0000-4000-8000-000000000011';

    -- Staff
    v_staff1 UUID := 'f3000000-0000-4000-8000-000000000001';
    v_staff2 UUID := 'f3000000-0000-4000-8000-000000000002';
    v_staff3 UUID := 'f3000000-0000-4000-8000-000000000003';

    -- Vendors
    v_vendor1 UUID := 'f4000000-0000-4000-8000-000000000001';
    v_vendor2 UUID := 'f4000000-0000-4000-8000-000000000002';
    v_vendor3 UUID := 'f4000000-0000-4000-8000-000000000003';

    -- Org Settings
    v_org_settings UUID := 'a4000000-0000-4000-8000-000000000001';

    -- Password hash for "pass123"
    v_pw TEXT := '$2b$10$22S1QaJew0rmKLO0cAsp2eLqU.U/.2qMgRPqbiFIwDcp6KI02wG4u';

    -- Payment schedule counter (used in loops)
    v_ps_id UUID;
    v_txn_id UUID;
    i INT;

BEGIN

    -- ========================================================================
    -- 1. TENANT (landlord_org)
    -- ========================================================================
    INSERT INTO landlord_org (id, name, status, address, trn, ticket_otp_required, created_at, updated_at)
    VALUES (v_tenant_id, 'XYZ Developers', 'ACTIVE', 'Business Bay, Dubai, UAE', '100234567890003', true, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- Org Settings
    INSERT INTO org_settings (id, landlord_org_id, tenant_id, default_currency, default_locale, timezone, reminder_days)
    VALUES (v_org_settings, v_tenant_id, v_tenant_id, 'AED', 'en', 'Asia/Dubai', 30)
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================================
    -- 2. USERS
    -- ========================================================================
    -- SUPER_ADMIN (no tenant_id)
    INSERT INTO users (id, tenant_id, email, password_hash, name, role, created_at, updated_at)
    VALUES (v_user_superadmin, NULL, 'superadmin@xyz.com', v_pw, 'Super Admin', 'SUPER_ADMIN', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- TENANT_ADMIN
    INSERT INTO users (id, tenant_id, email, password_hash, name, role, created_at, updated_at)
    VALUES (v_user_admin, v_tenant_id, 'admin@xyz.com', v_pw, 'Ahmed Al Maktoum', 'TENANT_ADMIN', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- PROPERTY_MANAGERs
    INSERT INTO users (id, tenant_id, email, password_hash, name, role, created_at, updated_at)
    VALUES (v_user_pm1, v_tenant_id, 'pm1@xyz.com', v_pw, 'Fatima Hassan', 'PROPERTY_MANAGER', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO users (id, tenant_id, email, password_hash, name, role, created_at, updated_at)
    VALUES (v_user_pm2, v_tenant_id, 'pm2@xyz.com', v_pw, 'Omar Khalid', 'PROPERTY_MANAGER', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- TENANT_USER (staff role)
    INSERT INTO users (id, tenant_id, email, password_hash, name, role, created_at, updated_at)
    VALUES (v_user_staff, v_tenant_id, 'staff@xyz.com', v_pw, 'Sara Ali', 'TENANT_USER', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- RENTERs
    INSERT INTO users (id, tenant_id, email, password_hash, name, role, created_at, updated_at)
    VALUES (v_user_renter1, v_tenant_id, 'renter1@xyz.com', v_pw, 'John Smith', 'RENTER', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO users (id, tenant_id, email, password_hash, name, role, created_at, updated_at)
    VALUES (v_user_renter2, v_tenant_id, 'renter2@xyz.com', v_pw, 'Maria Garcia', 'RENTER', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO users (id, tenant_id, email, password_hash, name, role, created_at, updated_at)
    VALUES (v_user_renter3, v_tenant_id, 'renter3@xyz.com', v_pw, 'Rajesh Kumar', 'RENTER', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO users (id, tenant_id, email, password_hash, name, role, created_at, updated_at)
    VALUES (v_user_renter4, v_tenant_id, 'renter4@xyz.com', v_pw, 'Li Wei', 'RENTER', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================================
    -- 3. PROPERTIES
    -- ========================================================================
    INSERT INTO properties (id, tenant_id, name_en, name_ar, emirate, address, type, created_at, updated_at)
    VALUES (v_prop_marina, v_tenant_id, 'Marina Heights', 'مرتفعات المارينا', 'Dubai', 'Dubai Marina, Dubai, UAE', 'RESIDENTIAL', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO properties (id, tenant_id, name_en, name_ar, emirate, address, type, created_at, updated_at)
    VALUES (v_prop_biz, v_tenant_id, 'Business Central', 'بزنس سنترال', 'Dubai', 'DIFC, Dubai, UAE', 'COMMERCIAL', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO properties (id, tenant_id, name_en, name_ar, emirate, address, type, created_at, updated_at)
    VALUES (v_prop_palm, v_tenant_id, 'Palm Residences', 'بالم ريزيدنسز', 'Dubai', 'Palm Jumeirah, Dubai, UAE', 'RESIDENTIAL', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================================
    -- 4. BUILDINGS
    -- ========================================================================
    -- Marina Heights: 1 building (Tower A)
    INSERT INTO buildings (id, tenant_id, property_id, name_en, name_ar, floors)
    VALUES (v_bld_tower_a, v_tenant_id, v_prop_marina, 'Tower A', 'البرج أ', 3)
    ON CONFLICT (id) DO NOTHING;

    -- Business Central: 2 buildings (Block A, Block B)
    INSERT INTO buildings (id, tenant_id, property_id, name_en, name_ar, floors)
    VALUES (v_bld_block_a, v_tenant_id, v_prop_biz, 'Block A', 'بلوك أ', 2)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO buildings (id, tenant_id, property_id, name_en, name_ar, floors)
    VALUES (v_bld_block_b, v_tenant_id, v_prop_biz, 'Block B', 'بلوك ب', 2)
    ON CONFLICT (id) DO NOTHING;

    -- Palm Residences: no buildings (villas directly under property)

    -- ========================================================================
    -- 5. UNITS (14 total)
    -- ========================================================================
    -- Marina Heights Tower A
    INSERT INTO units (id, tenant_id, property_id, building_id, unit_number, type, size_sqft, status, expected_rent, actual_rent)
    VALUES (v_unit_m101, v_tenant_id, v_prop_marina, v_bld_tower_a, '101', '1BHK',   750, 'OCCUPIED', 60000, 60000)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO units (id, tenant_id, property_id, building_id, unit_number, type, size_sqft, status, expected_rent, actual_rent)
    VALUES (v_unit_m102, v_tenant_id, v_prop_marina, v_bld_tower_a, '102', '2BHK',  1050, 'OCCUPIED', 85000, 85000)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO units (id, tenant_id, property_id, building_id, unit_number, type, size_sqft, status, expected_rent, actual_rent)
    VALUES (v_unit_m201, v_tenant_id, v_prop_marina, v_bld_tower_a, '201', '3BHK',  1400, 'OCCUPIED', 120000, 120000)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO units (id, tenant_id, property_id, building_id, unit_number, type, size_sqft, status, expected_rent, actual_rent)
    VALUES (v_unit_m202, v_tenant_id, v_prop_marina, v_bld_tower_a, '202', 'STUDIO',  450, 'VACANT', 40000, 0)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO units (id, tenant_id, property_id, building_id, unit_number, type, size_sqft, status, expected_rent, actual_rent)
    VALUES (v_unit_m301, v_tenant_id, v_prop_marina, v_bld_tower_a, '301', 'PENTHOUSE', 2500, 'VACANT', 250000, 0)
    ON CONFLICT (id) DO NOTHING;

    -- Business Central Block A
    INSERT INTO units (id, tenant_id, property_id, building_id, unit_number, type, size_sqft, status, expected_rent, actual_rent)
    VALUES (v_unit_a01, v_tenant_id, v_prop_biz, v_bld_block_a, 'A01', 'OFFICE', 1200, 'OCCUPIED', 150000, 150000)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO units (id, tenant_id, property_id, building_id, unit_number, type, size_sqft, status, expected_rent, actual_rent)
    VALUES (v_unit_a02, v_tenant_id, v_prop_biz, v_bld_block_a, 'A02', 'OFFICE',  900, 'VACANT', 120000, 0)
    ON CONFLICT (id) DO NOTHING;

    -- Business Central Block B
    INSERT INTO units (id, tenant_id, property_id, building_id, unit_number, type, size_sqft, status, expected_rent, actual_rent)
    VALUES (v_unit_b01, v_tenant_id, v_prop_biz, v_bld_block_b, 'B01', 'RETAIL', 800, 'OCCUPIED', 95000, 95000)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO units (id, tenant_id, property_id, building_id, unit_number, type, size_sqft, status, expected_rent, actual_rent)
    VALUES (v_unit_b02, v_tenant_id, v_prop_biz, v_bld_block_b, 'B02', 'RETAIL', 800, 'VACANT', 95000, 0)
    ON CONFLICT (id) DO NOTHING;

    -- Palm Residences (no building_id)
    INSERT INTO units (id, tenant_id, property_id, building_id, unit_number, type, size_sqft, status, expected_rent, actual_rent)
    VALUES (v_unit_v01, v_tenant_id, v_prop_palm, NULL, 'V01', '3BHK', 3500, 'OCCUPIED', 300000, 300000)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO units (id, tenant_id, property_id, building_id, unit_number, type, size_sqft, status, expected_rent, actual_rent)
    VALUES (v_unit_v02, v_tenant_id, v_prop_palm, NULL, 'V02', '3BHK', 3500, 'OCCUPIED', 220000, 220000)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO units (id, tenant_id, property_id, building_id, unit_number, type, size_sqft, status, expected_rent, actual_rent)
    VALUES (v_unit_v03, v_tenant_id, v_prop_palm, NULL, 'V03', '3BHK', 3500, 'VACANT', 250000, 0)
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================================
    -- 6. RENTERS (linked to renter users)
    -- ========================================================================
    INSERT INTO renters (id, tenant_id, name_en, email, phone, primary_language, user_id)
    VALUES (v_renter1, v_tenant_id, 'John Smith',   'renter1@xyz.com', '+971501111111', 'en', v_user_renter1)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO renters (id, tenant_id, name_en, email, phone, primary_language, user_id)
    VALUES (v_renter2, v_tenant_id, 'Maria Garcia', 'renter2@xyz.com', '+971502222222', 'en', v_user_renter2)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO renters (id, tenant_id, name_en, email, phone, primary_language, user_id)
    VALUES (v_renter3, v_tenant_id, 'Rajesh Kumar', 'renter3@xyz.com', '+971503333333', 'en', v_user_renter3)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO renters (id, tenant_id, name_en, email, phone, primary_language, user_id)
    VALUES (v_renter4, v_tenant_id, 'Li Wei',       'renter4@xyz.com', '+971504444444', 'en', v_user_renter4)
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================================
    -- 7. LEASES
    -- ========================================================================
    -- Lease 1: John Smith -> 101 Marina | 60,000/yr | 5 Jan 2026 - 4 Jan 2027 | 12 cheques | CHEQUE | ACTIVE
    INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date, status, rent_amount, deposit_amount, payment_terms, payment_method, monthly_rent)
    VALUES (v_lease1, v_tenant_id, v_unit_m101, v_renter1, '2026-01-05', '2027-01-04', 'ACTIVE', 60000, 5000, 12, 'CHEQUE', 5000)
    ON CONFLICT (id) DO NOTHING;

    -- Lease 2: Maria Garcia -> 102 Marina | 85,000/yr | 5 Feb 2026 - 4 Feb 2027 | 4 cheques | CHEQUE | ACTIVE
    INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date, status, rent_amount, deposit_amount, payment_terms, payment_method, monthly_rent)
    VALUES (v_lease2, v_tenant_id, v_unit_m102, v_renter2, '2026-02-05', '2027-02-04', 'ACTIVE', 85000, 7000, 4, 'CHEQUE', 7083.33)
    ON CONFLICT (id) DO NOTHING;

    -- Lease 3: Rajesh Kumar -> A01 Business Central | 150,000/yr | 5 Mar 2026 - 4 Mar 2027 | 2 cheques | BANK_TRANSFER | ACTIVE
    INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date, status, rent_amount, deposit_amount, payment_terms, payment_method, monthly_rent)
    VALUES (v_lease3, v_tenant_id, v_unit_a01, v_renter3, '2026-03-05', '2027-03-04', 'ACTIVE', 150000, 12500, 2, 'BANK_TRANSFER', 12500)
    ON CONFLICT (id) DO NOTHING;

    -- Lease 4: Li Wei -> V01 Palm | 300,000/yr | 5 Jan 2026 - 4 Jan 2027 | 4 cheques | CHEQUE | ACTIVE
    INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date, status, rent_amount, deposit_amount, payment_terms, payment_method, monthly_rent)
    VALUES (v_lease4, v_tenant_id, v_unit_v01, v_renter4, '2026-01-05', '2027-01-04', 'ACTIVE', 300000, 25000, 4, 'CHEQUE', 25000)
    ON CONFLICT (id) DO NOTHING;

    -- Lease 5: John Smith -> V02 Palm | 220,000/yr | 5 Apr 2026 - 4 Apr 2027 | 1 cheque | ONLINE | ACTIVE
    INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date, status, rent_amount, deposit_amount, payment_terms, payment_method, monthly_rent)
    VALUES (v_lease5, v_tenant_id, v_unit_v02, v_renter1, '2026-04-05', '2027-04-04', 'ACTIVE', 220000, 18000, 1, 'ONLINE', 18333.33)
    ON CONFLICT (id) DO NOTHING;

    -- Lease 6: Maria Garcia -> 201 Marina | 120,000/yr | 5 Jan 2025 - 4 Jan 2026 | 12 cheques | CHEQUE | EXPIRED
    INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date, status, rent_amount, deposit_amount, payment_terms, payment_method, monthly_rent)
    VALUES (v_lease6, v_tenant_id, v_unit_m201, v_renter2, '2025-01-05', '2026-01-04', 'EXPIRED', 120000, 10000, 12, 'CHEQUE', 10000)
    ON CONFLICT (id) DO NOTHING;

    -- Lease 7: Li Wei -> B01 Business Central | 95,000/yr | 5 Jun 2026 - 4 Jun 2027 | 1 cheque | CHEQUE | DRAFT
    INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date, status, rent_amount, deposit_amount, payment_terms, payment_method, monthly_rent)
    VALUES (v_lease7, v_tenant_id, v_unit_b01, v_renter4, '2026-06-05', '2027-06-04', 'DRAFT', 95000, 8000, 1, 'CHEQUE', 7916.67)
    ON CONFLICT (id) DO NOTHING;

    -- Lease 8: Rajesh Kumar -> 201 Marina | 120,000/yr | 5 Jul 2026 - 4 Jul 2027 | 6 cheques | CHEQUE | PENDING_SIGNATURE
    INSERT INTO leases (id, tenant_id, unit_id, renter_id, start_date, end_date, status, rent_amount, deposit_amount, payment_terms, payment_method, monthly_rent)
    VALUES (v_lease8, v_tenant_id, v_unit_m201, v_renter3, '2026-07-05', '2027-07-04', 'PENDING_SIGNATURE', 120000, 10000, 6, 'CHEQUE', 10000)
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================================
    -- 8. PAYMENT SCHEDULES
    -- ========================================================================

    -- -----------------------------------------------------------------------
    -- Lease 1: 60,000/yr / 12 cheques = 5,000/mo -- due 5th of each month
    -- Start 5 Jan 2026. First 2 CLEARED, #3 DEPOSITED, rest PENDING
    -- -----------------------------------------------------------------------
    FOR i IN 1..12 LOOP
        v_ps_id := ('f5000001-0000-4000-8000-0000000000' || LPAD(i::TEXT, 2, '0'))::UUID;
        INSERT INTO payment_schedules (id, tenant_id, lease_id, unit_id, property_id, installment_number, due_date, amount, status, payment_method, cheque_date, payer_name)
        VALUES (
            v_ps_id, v_tenant_id, v_lease1, v_unit_m101, v_prop_marina, i,
            ('2026-01-05'::DATE + ((i-1) * INTERVAL '1 month'))::DATE,
            5000.00,
            CASE
                WHEN i <= 2 THEN 'CLEARED'
                WHEN i = 3 THEN 'DEPOSITED'
                ELSE 'PENDING'
            END,
            'CHEQUE',
            ('2026-01-05'::DATE + ((i-1) * INTERVAL '1 month'))::DATE,
            'John Smith'
        )
        ON CONFLICT (id) DO NOTHING;
    END LOOP;

    -- -----------------------------------------------------------------------
    -- Lease 2: 85,000/yr / 4 cheques = 21,250/quarter -- due 5th every 3 months
    -- Start 5 Feb 2026. First 1 CLEARED, rest PENDING
    -- -----------------------------------------------------------------------
    FOR i IN 1..4 LOOP
        v_ps_id := ('f5000002-0000-4000-8000-0000000000' || LPAD(i::TEXT, 2, '0'))::UUID;
        INSERT INTO payment_schedules (id, tenant_id, lease_id, unit_id, property_id, installment_number, due_date, amount, status, payment_method, cheque_date, payer_name)
        VALUES (
            v_ps_id, v_tenant_id, v_lease2, v_unit_m102, v_prop_marina, i,
            ('2026-02-05'::DATE + ((i-1) * 3 * INTERVAL '1 month'))::DATE,
            21250.00,
            CASE
                WHEN i = 1 THEN 'CLEARED'
                WHEN i = 2 THEN 'COLLECTED'
                ELSE 'PENDING'
            END,
            'CHEQUE',
            ('2026-02-05'::DATE + ((i-1) * 3 * INTERVAL '1 month'))::DATE,
            'Maria Garcia'
        )
        ON CONFLICT (id) DO NOTHING;
    END LOOP;

    -- -----------------------------------------------------------------------
    -- Lease 3: 150,000/yr / 2 cheques = 75,000 each -- due 5th every 6 months
    -- Start 5 Mar 2026. First 1 CLEARED, rest PENDING
    -- -----------------------------------------------------------------------
    FOR i IN 1..2 LOOP
        v_ps_id := ('f5000003-0000-4000-8000-0000000000' || LPAD(i::TEXT, 2, '0'))::UUID;
        INSERT INTO payment_schedules (id, tenant_id, lease_id, unit_id, property_id, installment_number, due_date, amount, status, payment_method, cheque_date, payer_name)
        VALUES (
            v_ps_id, v_tenant_id, v_lease3, v_unit_a01, v_prop_biz, i,
            ('2026-03-05'::DATE + ((i-1) * 6 * INTERVAL '1 month'))::DATE,
            75000.00,
            CASE WHEN i = 1 THEN 'CLEARED' ELSE 'PENDING' END,
            'BANK_TRANSFER',
            ('2026-03-05'::DATE + ((i-1) * 6 * INTERVAL '1 month'))::DATE,
            'Rajesh Kumar'
        )
        ON CONFLICT (id) DO NOTHING;
    END LOOP;

    -- -----------------------------------------------------------------------
    -- Lease 4: 300,000/yr / 4 cheques = 75,000 each -- due 5th every 3 months
    -- Start 5 Jan 2026. First 1 CLEARED, rest PENDING
    -- -----------------------------------------------------------------------
    FOR i IN 1..4 LOOP
        v_ps_id := ('f5000004-0000-4000-8000-0000000000' || LPAD(i::TEXT, 2, '0'))::UUID;
        INSERT INTO payment_schedules (id, tenant_id, lease_id, unit_id, property_id, installment_number, due_date, amount, status, payment_method, cheque_date, payer_name)
        VALUES (
            v_ps_id, v_tenant_id, v_lease4, v_unit_v01, v_prop_palm, i,
            ('2026-01-05'::DATE + ((i-1) * 3 * INTERVAL '1 month'))::DATE,
            75000.00,
            CASE
                WHEN i = 1 THEN 'CLEARED'
                WHEN i = 2 THEN 'DEPOSITED'
                ELSE 'PENDING'
            END,
            'CHEQUE',
            ('2026-01-05'::DATE + ((i-1) * 3 * INTERVAL '1 month'))::DATE,
            'Li Wei'
        )
        ON CONFLICT (id) DO NOTHING;
    END LOOP;

    -- -----------------------------------------------------------------------
    -- Lease 5: 220,000/yr / 1 payment = 220,000 -- due 5 Apr 2026
    -- PENDING (future lease)
    -- -----------------------------------------------------------------------
    v_ps_id := 'f5000005-0000-4000-8000-000000000001'::UUID;
    INSERT INTO payment_schedules (id, tenant_id, lease_id, unit_id, property_id, installment_number, due_date, amount, status, payment_method, payer_name)
    VALUES (v_ps_id, v_tenant_id, v_lease5, v_unit_v02, v_prop_palm, 1, '2026-04-05', 220000.00, 'PENDING', 'ONLINE', 'John Smith')
    ON CONFLICT (id) DO NOTHING;

    -- -----------------------------------------------------------------------
    -- Lease 6 (EXPIRED): 120,000/yr / 12 cheques = 10,000/mo -- ALL CLEARED (2025 dates)
    -- -----------------------------------------------------------------------
    FOR i IN 1..12 LOOP
        v_ps_id := ('f5000006-0000-4000-8000-0000000000' || LPAD(i::TEXT, 2, '0'))::UUID;
        INSERT INTO payment_schedules (id, tenant_id, lease_id, unit_id, property_id, installment_number, due_date, amount, status, payment_method, cheque_date, payer_name, status_changed_at)
        VALUES (
            v_ps_id, v_tenant_id, v_lease6, v_unit_m201, v_prop_marina, i,
            ('2025-01-05'::DATE + ((i-1) * INTERVAL '1 month'))::DATE,
            10000.00,
            'CLEARED',
            'CHEQUE',
            ('2025-01-05'::DATE + ((i-1) * INTERVAL '1 month'))::DATE,
            'Maria Garcia',
            ('2025-01-05'::DATE + ((i-1) * INTERVAL '1 month') + INTERVAL '2 days')::TIMESTAMP
        )
        ON CONFLICT (id) DO NOTHING;
    END LOOP;

    -- -----------------------------------------------------------------------
    -- Lease 7 (DRAFT): 95,000/yr / 1 cheque = 95,000 -- ALL PENDING
    -- -----------------------------------------------------------------------
    v_ps_id := 'f5000007-0000-4000-8000-000000000001'::UUID;
    INSERT INTO payment_schedules (id, tenant_id, lease_id, unit_id, property_id, installment_number, due_date, amount, status, payment_method, payer_name)
    VALUES (v_ps_id, v_tenant_id, v_lease7, v_unit_b01, v_prop_biz, 1, '2026-06-05', 95000.00, 'PENDING', 'CHEQUE', 'Li Wei')
    ON CONFLICT (id) DO NOTHING;

    -- -----------------------------------------------------------------------
    -- Lease 8 (PENDING_SIGNATURE): 120,000/yr / 6 cheques = 20,000 each -- ALL PENDING
    -- -----------------------------------------------------------------------
    FOR i IN 1..6 LOOP
        v_ps_id := ('f5000008-0000-4000-8000-0000000000' || LPAD(i::TEXT, 2, '0'))::UUID;
        INSERT INTO payment_schedules (id, tenant_id, lease_id, unit_id, property_id, installment_number, due_date, amount, status, payment_method, cheque_date, payer_name)
        VALUES (
            v_ps_id, v_tenant_id, v_lease8, v_unit_m201, v_prop_marina, i,
            ('2026-07-05'::DATE + ((i-1) * 2 * INTERVAL '1 month'))::DATE,
            20000.00,
            'PENDING',
            'CHEQUE',
            ('2026-07-05'::DATE + ((i-1) * 2 * INTERVAL '1 month'))::DATE,
            'Rajesh Kumar'
        )
        ON CONFLICT (id) DO NOTHING;
    END LOOP;

    -- ========================================================================
    -- 9. CHART OF ACCOUNTS
    -- ========================================================================
    INSERT INTO accounts (id, tenant_id, code, name, name_en, account_type, account_sub_type, description, is_system, hierarchy_level, is_group, is_active, display_order, created_at, updated_at)
    VALUES
        (v_acct_bank,       v_tenant_id, 'A-01-01', 'Bank/Cash',            'Bank/Cash',            'ASSET',     'CURRENT_ASSET',    'Main bank and petty cash',      true, 1, false, true, 1, NOW(), NOW()),
        (v_acct_receivable, v_tenant_id, 'A-02-01', 'Accounts Receivable',  'Accounts Receivable',  'ASSET',     'CURRENT_ASSET',    'Rent and charges owed by tenants', true, 1, false, true, 2, NOW(), NOW()),
        (v_acct_deposit,    v_tenant_id, 'B-01-01', 'Security Deposits',    'Security Deposits',    'LIABILITY', 'CURRENT_LIABILITY','Refundable security deposits held', true, 1, false, true, 3, NOW(), NOW()),
        (v_acct_advance,    v_tenant_id, 'B-02-01', 'Advance Rent',         'Advance Rent',         'LIABILITY', 'CURRENT_LIABILITY','Rent collected in advance',      true, 1, false, true, 4, NOW(), NOW()),
        (v_acct_rental,     v_tenant_id, 'C-01-01', 'Rental Income',        'Rental Income',        'INCOME',    NULL,               'Monthly rental income',          true, 1, false, true, 5, NOW(), NOW()),
        (v_acct_service,    v_tenant_id, 'C-02-01', 'Service Charges',      'Service Charges',      'INCOME',    NULL,               'Service charge collections',     true, 1, false, true, 6, NOW(), NOW()),
        (v_acct_maint,      v_tenant_id, 'D-01-01', 'Maintenance',          'Maintenance',          'EXPENSE',   NULL,               'Maintenance and repair costs',   true, 1, false, true, 7, NOW(), NOW()),
        (v_acct_mgmt,       v_tenant_id, 'D-02-01', 'Management Fees',      'Management Fees',      'EXPENSE',   NULL,               'Property management fees',       true, 1, false, true, 8, NOW(), NOW()),
        (v_acct_equity,     v_tenant_id, 'E-01-01', 'Owner''s Equity',      'Owner''s Equity',      'EQUITY',    NULL,               'Owner capital and retained earnings', true, 1, false, true, 9, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================================
    -- 10. ACCOUNT MAPPINGS
    -- ========================================================================
    INSERT INTO account_mappings (id, tenant_id, transaction_nature, debit_account_id, credit_account_id, created_at, updated_at)
    VALUES (v_mapping1, v_tenant_id, 'RENT_PAYMENT_CLEARED', v_acct_bank, v_acct_rental, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================================
    -- 11. FINANCIAL TRANSACTIONS (for all CLEARED payments)
    -- Paired: debit A-01-01 (Bank/Cash) + credit C-01-01 (Rental Income)
    -- ========================================================================

    -- Lease 1: installments 1-2 CLEARED (Jan 5, Feb 5 2026)
    FOR i IN 1..2 LOOP
        -- Debit entry (Bank/Cash)
        v_txn_id := ('f6000001-0001-4000-8000-0000000000' || LPAD(i::TEXT, 2, '0'))::UUID;
        INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, account_code, account_type, debit, credit, property_id, unit_id, created_at, updated_at)
        VALUES (
            v_txn_id, v_tenant_id,
            ('2026-01-05'::DATE + ((i-1) * INTERVAL '1 month'))::DATE,
            'Rent payment - Lease #1 Inst ' || i || ' - John Smith - Unit 101',
            v_acct_bank, 'A-01-01', 'ASSET', 5000.00, 0, v_prop_marina, v_unit_m101, NOW(), NOW()
        )
        ON CONFLICT (id) DO NOTHING;

        -- Credit entry (Rental Income)
        v_txn_id := ('f6000001-0002-4000-8000-0000000000' || LPAD(i::TEXT, 2, '0'))::UUID;
        INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, account_code, account_type, debit, credit, property_id, unit_id, created_at, updated_at)
        VALUES (
            v_txn_id, v_tenant_id,
            ('2026-01-05'::DATE + ((i-1) * INTERVAL '1 month'))::DATE,
            'Rent payment - Lease #1 Inst ' || i || ' - John Smith - Unit 101',
            v_acct_rental, 'C-01-01', 'INCOME', 0, 5000.00, v_prop_marina, v_unit_m101, NOW(), NOW()
        )
        ON CONFLICT (id) DO NOTHING;
    END LOOP;

    -- Lease 2: installment 1 CLEARED (Feb 5 2026)
    -- Debit
    INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, account_code, account_type, debit, credit, property_id, unit_id, created_at, updated_at)
    VALUES ('f6000002-0001-4000-8000-000000000001'::UUID, v_tenant_id, '2026-02-05',
            'Rent payment - Lease #2 Inst 1 - Maria Garcia - Unit 102',
            v_acct_bank, 'A-01-01', 'ASSET', 21250.00, 0, v_prop_marina, v_unit_m102, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;
    -- Credit
    INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, account_code, account_type, debit, credit, property_id, unit_id, created_at, updated_at)
    VALUES ('f6000002-0002-4000-8000-000000000001'::UUID, v_tenant_id, '2026-02-05',
            'Rent payment - Lease #2 Inst 1 - Maria Garcia - Unit 102',
            v_acct_rental, 'C-01-01', 'INCOME', 0, 21250.00, v_prop_marina, v_unit_m102, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- Lease 3: installment 1 CLEARED (Mar 5 2026)
    INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, account_code, account_type, debit, credit, property_id, unit_id, created_at, updated_at)
    VALUES ('f6000003-0001-4000-8000-000000000001'::UUID, v_tenant_id, '2026-03-05',
            'Rent payment - Lease #3 Inst 1 - Rajesh Kumar - Unit A01',
            v_acct_bank, 'A-01-01', 'ASSET', 75000.00, 0, v_prop_biz, v_unit_a01, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;
    INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, account_code, account_type, debit, credit, property_id, unit_id, created_at, updated_at)
    VALUES ('f6000003-0002-4000-8000-000000000001'::UUID, v_tenant_id, '2026-03-05',
            'Rent payment - Lease #3 Inst 1 - Rajesh Kumar - Unit A01',
            v_acct_rental, 'C-01-01', 'INCOME', 0, 75000.00, v_prop_biz, v_unit_a01, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- Lease 4: installment 1 CLEARED (Jan 5 2026)
    INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, account_code, account_type, debit, credit, property_id, unit_id, created_at, updated_at)
    VALUES ('f6000004-0001-4000-8000-000000000001'::UUID, v_tenant_id, '2026-01-05',
            'Rent payment - Lease #4 Inst 1 - Li Wei - Unit V01',
            v_acct_bank, 'A-01-01', 'ASSET', 75000.00, 0, v_prop_palm, v_unit_v01, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;
    INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, account_code, account_type, debit, credit, property_id, unit_id, created_at, updated_at)
    VALUES ('f6000004-0002-4000-8000-000000000001'::UUID, v_tenant_id, '2026-01-05',
            'Rent payment - Lease #4 Inst 1 - Li Wei - Unit V01',
            v_acct_rental, 'C-01-01', 'INCOME', 0, 75000.00, v_prop_palm, v_unit_v01, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- Lease 6 (EXPIRED): ALL 12 installments CLEARED (2025 dates)
    FOR i IN 1..12 LOOP
        -- Debit
        v_txn_id := ('f6000006-0001-4000-8000-0000000000' || LPAD(i::TEXT, 2, '0'))::UUID;
        INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, account_code, account_type, debit, credit, property_id, unit_id, created_at, updated_at)
        VALUES (
            v_txn_id, v_tenant_id,
            ('2025-01-05'::DATE + ((i-1) * INTERVAL '1 month'))::DATE,
            'Rent payment - Lease #6 Inst ' || i || ' - Maria Garcia - Unit 201',
            v_acct_bank, 'A-01-01', 'ASSET', 10000.00, 0, v_prop_marina, v_unit_m201, NOW(), NOW()
        )
        ON CONFLICT (id) DO NOTHING;

        -- Credit
        v_txn_id := ('f6000006-0002-4000-8000-0000000000' || LPAD(i::TEXT, 2, '0'))::UUID;
        INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, account_code, account_type, debit, credit, property_id, unit_id, created_at, updated_at)
        VALUES (
            v_txn_id, v_tenant_id,
            ('2025-01-05'::DATE + ((i-1) * INTERVAL '1 month'))::DATE,
            'Rent payment - Lease #6 Inst ' || i || ' - Maria Garcia - Unit 201',
            v_acct_rental, 'C-01-01', 'INCOME', 0, 10000.00, v_prop_marina, v_unit_m201, NOW(), NOW()
        )
        ON CONFLICT (id) DO NOTHING;
    END LOOP;

    -- ========================================================================
    -- 12. RENT COLLECTION SETTINGS
    -- ========================================================================
    -- Marina Heights: due day 5, grace 5 days, penalty 5%/month, reminders "7,3,1"
    INSERT INTO rent_collection_settings (id, tenant_id, property_id, due_day_of_month, grace_period_days, penalty_type, penalty_amount, online_payment_enabled, payment_reminder_days, created_at, updated_at)
    VALUES (v_rcs_marina, v_tenant_id, v_prop_marina, 5, 5, 'PERCENTAGE', 5.00, false, '7,3,1', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- Business Central: due day 5, grace 10 days, penalty 2%/month, reminders "14,7,1"
    INSERT INTO rent_collection_settings (id, tenant_id, property_id, due_day_of_month, grace_period_days, penalty_type, penalty_amount, online_payment_enabled, payment_reminder_days, created_at, updated_at)
    VALUES (v_rcs_biz, v_tenant_id, v_prop_biz, 5, 10, 'PERCENTAGE', 2.00, false, '14,7,1', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- Palm Residences: due day 5, grace 7 days, penalty 3%/month, reminders "7,3"
    INSERT INTO rent_collection_settings (id, tenant_id, property_id, due_day_of_month, grace_period_days, penalty_type, penalty_amount, online_payment_enabled, payment_reminder_days, created_at, updated_at)
    VALUES (v_rcs_palm, v_tenant_id, v_prop_palm, 5, 7, 'PERCENTAGE', 3.00, false, '7,3', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================================
    -- 13. MAINTENANCE TICKETS
    -- ========================================================================

    -- Ticket 1: "AC not cooling properly" -- 101 Marina -- HVAC -- HIGH -- CLOSED
    --   reported by John Smith, assigned to Fatima, rating 5
    INSERT INTO maintenance_tickets (id, tenant_id, property_id, unit_id, lease_id, reported_by, assigned_to, title, description, category, priority, status, satisfaction_rating, satisfaction_comment, resolved_at, closed_at, created_at, updated_at)
    VALUES (v_ticket1, v_tenant_id, v_prop_marina, v_unit_m101, v_lease1, v_user_renter1, v_user_pm1,
            'AC not cooling properly',
            'The AC in the living room is running but not cooling. Temperature stays above 28C even at the lowest setting.',
            'HVAC', 'HIGH', 'CLOSED', 5, 'Fixed quickly, thank you!',
            '2026-02-12 14:00:00'::TIMESTAMP, '2026-02-12 16:00:00'::TIMESTAMP,
            '2026-02-10 09:00:00'::TIMESTAMP, '2026-02-12 16:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    -- Ticket 2: "Water leak in bathroom" -- 102 Marina -- PLUMBING -- URGENT -- RESOLVED
    --   reported by Maria, assigned to Fatima, OTP 234567
    INSERT INTO maintenance_tickets (id, tenant_id, property_id, unit_id, lease_id, reported_by, assigned_to, title, description, category, priority, status, closure_otp, resolved_at, created_at, updated_at)
    VALUES (v_ticket2, v_tenant_id, v_prop_marina, v_unit_m102, v_lease2, v_user_renter2, v_user_pm1,
            'Water leak in bathroom',
            'There is a constant drip from the pipe under the bathroom sink. Water is pooling on the floor.',
            'PLUMBING', 'URGENT', 'RESOLVED', '234567',
            '2026-03-06 11:00:00'::TIMESTAMP,
            '2026-03-05 08:30:00'::TIMESTAMP, '2026-03-06 11:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    -- Ticket 3: "Power outlet not working" -- A01 Biz -- ELECTRICAL -- HIGH -- IN_PROGRESS
    --   reported by Rajesh, assigned to Omar
    INSERT INTO maintenance_tickets (id, tenant_id, property_id, unit_id, lease_id, reported_by, assigned_to, title, description, category, priority, status, created_at, updated_at)
    VALUES (v_ticket3, v_tenant_id, v_prop_biz, v_unit_a01, v_lease3, v_user_renter3, v_user_pm2,
            'Power outlet not working',
            'Two power outlets on the east wall of the office have stopped working. No visible damage.',
            'ELECTRICAL', 'HIGH', 'IN_PROGRESS',
            '2026-03-10 10:00:00'::TIMESTAMP, '2026-03-11 09:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    -- Ticket 4: "Door lock broken" -- V01 Palm -- SECURITY -- MEDIUM -- ASSIGNED
    --   reported by Li Wei, assigned to Fatima
    INSERT INTO maintenance_tickets (id, tenant_id, property_id, unit_id, lease_id, reported_by, assigned_to, title, description, category, priority, status, created_at, updated_at)
    VALUES (v_ticket4, v_tenant_id, v_prop_palm, v_unit_v01, v_lease4, v_user_renter4, v_user_pm1,
            'Door lock broken',
            'The main entrance door lock is jammed. Cannot lock the door properly from outside.',
            'SECURITY', 'MEDIUM', 'ASSIGNED',
            '2026-03-12 14:00:00'::TIMESTAMP, '2026-03-12 15:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    -- Ticket 5: "Pest issue in kitchen" -- V02 Palm -- PEST_CONTROL -- LOW -- OPEN
    --   reported by John Smith (no assignee)
    INSERT INTO maintenance_tickets (id, tenant_id, property_id, unit_id, lease_id, reported_by, title, description, category, priority, status, created_at, updated_at)
    VALUES (v_ticket5, v_tenant_id, v_prop_palm, v_unit_v02, v_lease5, v_user_renter1,
            'Pest issue in kitchen',
            'Noticed some ants near the kitchen sink area. They appear to be coming from under the counter.',
            'PEST_CONTROL', 'LOW', 'OPEN',
            '2026-03-14 11:00:00'::TIMESTAMP, '2026-03-14 11:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    -- Ticket 6: "Parking lot lights out" -- Marina Heights (no unit) -- STRUCTURAL -- MEDIUM -- OPEN
    --   reported by Fatima (PM ticket, no unit or lease)
    INSERT INTO maintenance_tickets (id, tenant_id, property_id, reported_by, title, description, category, priority, status, created_at, updated_at)
    VALUES (v_ticket6, v_tenant_id, v_prop_marina, v_user_pm1,
            'Parking lot lights out',
            'Several lights in the basement parking lot (P2 level) are not functioning. Safety concern for residents.',
            'STRUCTURAL', 'MEDIUM', 'OPEN',
            '2026-03-15 16:00:00'::TIMESTAMP, '2026-03-15 16:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================================
    -- 14. TICKET REPLIES
    -- ========================================================================

    -- Ticket 1 replies (3): renter report -> PM acknowledgment -> PM resolution
    INSERT INTO ticket_replies (id, tenant_id, ticket_id, user_id, user_name, message, created_at)
    VALUES (v_reply1a, v_tenant_id, v_ticket1, v_user_renter1, 'John Smith',
            'The AC has been making a strange noise and barely cools the room. It has been like this for two days now.',
            '2026-02-10 09:05:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO ticket_replies (id, tenant_id, ticket_id, user_id, user_name, message, created_at)
    VALUES (v_reply1b, v_tenant_id, v_ticket1, v_user_pm1, 'Fatima Hassan',
            'Thank you for reporting this. I have scheduled a technician visit for tomorrow between 10 AM and 12 PM. Please ensure someone is available to provide access.',
            '2026-02-10 10:30:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO ticket_replies (id, tenant_id, ticket_id, user_id, user_name, message, created_at)
    VALUES (v_reply1c, v_tenant_id, v_ticket1, v_user_pm1, 'Fatima Hassan',
            'The technician has replaced the compressor and recharged the refrigerant. AC is now cooling normally. Please let us know if you face any further issues.',
            '2026-02-12 14:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    -- Ticket 2 replies (2): renter detail -> PM update
    INSERT INTO ticket_replies (id, tenant_id, ticket_id, user_id, user_name, message, created_at)
    VALUES (v_reply2a, v_tenant_id, v_ticket2, v_user_renter2, 'Maria Garcia',
            'The leak is getting worse. Water is now spreading to the hallway. Please send someone urgently.',
            '2026-03-05 09:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO ticket_replies (id, tenant_id, ticket_id, user_id, user_name, message, created_at)
    VALUES (v_reply2b, v_tenant_id, v_ticket2, v_user_pm1, 'Fatima Hassan',
            'Emergency plumber has been dispatched and is on the way. ETA 30 minutes. In the meantime, please turn off the water valve under the sink if accessible.',
            '2026-03-05 09:15:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    -- Ticket 3 replies (1): PM starts work
    INSERT INTO ticket_replies (id, tenant_id, ticket_id, user_id, user_name, message, created_at)
    VALUES (v_reply3a, v_tenant_id, v_ticket3, v_user_pm2, 'Omar Khalid',
            'Electrician has inspected the outlets. The issue is with the circuit breaker. We have ordered a replacement part and will fix it within 2 business days.',
            '2026-03-11 09:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================================
    -- 15. TICKET HISTORY (status changes for tickets 1-4)
    -- ========================================================================

    -- Ticket 1: OPEN -> ASSIGNED -> IN_PROGRESS -> RESOLVED -> CLOSED
    INSERT INTO ticket_history (id, tenant_id, ticket_id, action, from_status, to_status, performed_by, performed_by_name, notes, created_at)
    VALUES (v_thist1a, v_tenant_id, v_ticket1, 'CREATED', NULL, 'OPEN', v_user_renter1, 'John Smith', 'Ticket created by renter', '2026-02-10 09:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO ticket_history (id, tenant_id, ticket_id, action, from_status, to_status, assigned_to, performed_by, performed_by_name, notes, created_at)
    VALUES (v_thist1b, v_tenant_id, v_ticket1, 'ASSIGNED', 'OPEN', 'ASSIGNED', v_user_pm1, v_user_admin, 'Ahmed Al Maktoum', 'Assigned to Fatima Hassan', '2026-02-10 09:30:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO ticket_history (id, tenant_id, ticket_id, action, from_status, to_status, performed_by, performed_by_name, notes, created_at)
    VALUES (v_thist1c, v_tenant_id, v_ticket1, 'STATUS_CHANGED', 'ASSIGNED', 'RESOLVED', v_user_pm1, 'Fatima Hassan', 'AC repaired - compressor replaced', '2026-02-12 14:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO ticket_history (id, tenant_id, ticket_id, action, from_status, to_status, performed_by, performed_by_name, notes, created_at)
    VALUES (v_thist1d, v_tenant_id, v_ticket1, 'STATUS_CHANGED', 'RESOLVED', 'CLOSED', v_user_renter1, 'John Smith', 'Renter confirmed fix, rated 5 stars', '2026-02-12 16:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    -- Ticket 2: OPEN -> ASSIGNED -> IN_PROGRESS -> RESOLVED
    INSERT INTO ticket_history (id, tenant_id, ticket_id, action, from_status, to_status, performed_by, performed_by_name, notes, created_at)
    VALUES (v_thist2a, v_tenant_id, v_ticket2, 'CREATED', NULL, 'OPEN', v_user_renter2, 'Maria Garcia', 'Ticket created by renter', '2026-03-05 08:30:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO ticket_history (id, tenant_id, ticket_id, action, from_status, to_status, assigned_to, performed_by, performed_by_name, notes, created_at)
    VALUES (v_thist2b, v_tenant_id, v_ticket2, 'ASSIGNED', 'OPEN', 'ASSIGNED', v_user_pm1, v_user_admin, 'Ahmed Al Maktoum', 'Urgent - assigned to Fatima', '2026-03-05 08:45:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO ticket_history (id, tenant_id, ticket_id, action, from_status, to_status, performed_by, performed_by_name, notes, created_at)
    VALUES (v_thist2c, v_tenant_id, v_ticket2, 'STATUS_CHANGED', 'ASSIGNED', 'RESOLVED', v_user_pm1, 'Fatima Hassan', 'Pipe replaced, leak fixed', '2026-03-06 11:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    -- Ticket 3: OPEN -> ASSIGNED -> IN_PROGRESS
    INSERT INTO ticket_history (id, tenant_id, ticket_id, action, from_status, to_status, performed_by, performed_by_name, notes, created_at)
    VALUES (v_thist3a, v_tenant_id, v_ticket3, 'CREATED', NULL, 'OPEN', v_user_renter3, 'Rajesh Kumar', 'Ticket created by renter', '2026-03-10 10:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO ticket_history (id, tenant_id, ticket_id, action, from_status, to_status, assigned_to, performed_by, performed_by_name, notes, created_at)
    VALUES (v_thist3b, v_tenant_id, v_ticket3, 'STATUS_CHANGED', 'OPEN', 'IN_PROGRESS', v_user_pm2, v_user_pm2, 'Omar Khalid', 'Assigned and investigation started', '2026-03-11 09:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    -- Ticket 4: OPEN -> ASSIGNED
    INSERT INTO ticket_history (id, tenant_id, ticket_id, action, from_status, to_status, performed_by, performed_by_name, notes, created_at)
    VALUES (v_thist4a, v_tenant_id, v_ticket4, 'CREATED', NULL, 'OPEN', v_user_renter4, 'Li Wei', 'Ticket created by renter', '2026-03-12 14:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO ticket_history (id, tenant_id, ticket_id, action, from_status, to_status, assigned_to, performed_by, performed_by_name, notes, created_at)
    VALUES (v_thist4b, v_tenant_id, v_ticket4, 'ASSIGNED', 'OPEN', 'ASSIGNED', v_user_pm1, v_user_admin, 'Ahmed Al Maktoum', 'Assigned to Fatima', '2026-03-12 15:00:00'::TIMESTAMP)
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================================
    -- 16. STAFF
    -- ========================================================================
    INSERT INTO staff (id, tenant_id, name_en, employee_id, designation, department, monthly_salary, join_date, phone, property_id, is_active, created_at, updated_at)
    VALUES (v_staff1, v_tenant_id, 'Abdullah Mohammed', 'EMP001', 'Maintenance Supervisor', 'Maintenance', 8000.00, '2024-03-01', '+971551111111', v_prop_marina, true, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO staff (id, tenant_id, name_en, employee_id, designation, department, monthly_salary, join_date, phone, property_id, is_active, created_at, updated_at)
    VALUES (v_staff2, v_tenant_id, 'Priya Patel', 'EMP002', 'Receptionist', 'Administration', 5000.00, '2024-06-15', '+971552222222', v_prop_biz, true, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO staff (id, tenant_id, name_en, employee_id, designation, department, monthly_salary, join_date, phone, property_id, is_active, created_at, updated_at)
    VALUES (v_staff3, v_tenant_id, 'Carlos Santos', 'EMP003', 'Security Guard', 'Security', 4500.00, '2024-09-01', '+971553333333', v_prop_palm, true, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================================
    -- 17. VENDORS
    -- ========================================================================
    INSERT INTO vendors (id, tenant_id, name_en, email, phone, contact_person, address, is_active, created_at, updated_at)
    VALUES (v_vendor1, v_tenant_id, 'Dubai Plumbing Services', 'plumbing@dubaiservices.ae', '+97141111111', 'Khalid Ibrahim', 'Al Quoz Industrial Area, Dubai', true, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO vendors (id, tenant_id, name_en, email, phone, contact_person, address, is_active, created_at, updated_at)
    VALUES (v_vendor2, v_tenant_id, 'Cool Air HVAC', 'info@coolairhvac.ae', '+97142222222', 'Mohammed Hassan', 'Ras Al Khor, Dubai', true, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO vendors (id, tenant_id, name_en, email, phone, contact_person, address, is_active, created_at, updated_at)
    VALUES (v_vendor3, v_tenant_id, 'SecureGuard LLC', 'contact@secureguard.ae', '+97143333333', 'James Wilson', 'JLT, Dubai', true, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- ========================================================================
    -- User-Tenant Memberships (required for tenant access)
    -- ========================================================================
    INSERT INTO user_tenant_memberships (user_id, tenant_id, created_at) VALUES
        (v_user_superadmin, v_tenant_id, NOW()),
        (v_user_admin, v_tenant_id, NOW()),
        (v_user_pm1, v_tenant_id, NOW()),
        (v_user_pm2, v_tenant_id, NOW()),
        (v_user_staff, v_tenant_id, NOW()),
        (v_user_renter1, v_tenant_id, NOW()),
        (v_user_renter2, v_tenant_id, NOW()),
        (v_user_renter3, v_tenant_id, NOW()),
        (v_user_renter4, v_tenant_id, NOW())
    ON CONFLICT DO NOTHING;

    -- User-Property Assignments (PMs to properties)
    INSERT INTO user_property_assignments (user_id, property_id) VALUES
        (v_user_pm1, v_prop_marina),
        (v_user_pm1, v_prop_palm),
        (v_user_pm2, v_prop_biz)
    ON CONFLICT DO NOTHING;

    -- ========================================================================
    -- Done!
    -- ========================================================================
    RAISE NOTICE 'Demo tenant "XYZ Developers" seeded successfully.';
    RAISE NOTICE 'Users: superadmin@xyz.com, admin@xyz.com, pm1@xyz.com, pm2@xyz.com, staff@xyz.com, renter1-4@xyz.com';
    RAISE NOTICE 'Password for all users: pass123';

END;
$$;

COMMIT;

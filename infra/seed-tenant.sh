#!/bin/bash
# ============================================================
# RentAxis — Seed Demo Tenant
# Usage: ./infra/seed-tenant.sh <company_name> <domain> [db_host]
# Example: ./infra/seed-tenant.sh "JCB Developers" "jcb.com"
# ============================================================
set -euo pipefail

COMPANY="${1:?Usage: $0 <company_name> <domain> [db_host]}"
DOMAIN="${2:?Usage: $0 <company_name> <domain> [db_host]}"
DB_HOST="${3:-localhost}"
DB_USER="${4:-rentaxis}"
DB_NAME="${5:-rentaxis}"
PASSWORD_HASH='$2b$10$22S1QaJew0rmKLO0cAsp2eLqU.U/.2qMgRPqbiFIwDcp6KI02wG4u'

echo "================================================"
echo "  RentAxis — Seed Demo Tenant"
echo "================================================"
echo "  Company:  $COMPANY"
echo "  Domain:   $DOMAIN"
echo "  DB Host:  $DB_HOST"
echo "  Password: pass123 (for all users)"
echo ""

# Generate deterministic UUIDs based on domain hash
# Using md5 of domain to create unique but reproducible UUIDs
HASH=$(echo -n "$DOMAIN" | md5sum | cut -c1-8)

psql -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 << EOSQL
BEGIN;

DO \$\$
DECLARE
    v_tenant_id UUID := '${HASH}0000-0000-4000-8000-000000000001';
    v_user_admin UUID := '${HASH}0000-0000-4000-8000-000000000010';
    v_user_pm1 UUID := '${HASH}0000-0000-4000-8000-000000000011';
    v_user_pm2 UUID := '${HASH}0000-0000-4000-8000-000000000012';
    v_user_staff UUID := '${HASH}0000-0000-4000-8000-000000000013';
    v_user_renter1 UUID := '${HASH}0000-0000-4000-8000-000000000021';
    v_user_renter2 UUID := '${HASH}0000-0000-4000-8000-000000000022';
    v_user_renter3 UUID := '${HASH}0000-0000-4000-8000-000000000023';
    v_user_renter4 UUID := '${HASH}0000-0000-4000-8000-000000000024';

    v_prop1 UUID := '${HASH}0000-0000-4000-8000-000000000101';
    v_prop2 UUID := '${HASH}0000-0000-4000-8000-000000000102';
    v_prop3 UUID := '${HASH}0000-0000-4000-8000-000000000103';

    v_bldg1 UUID := '${HASH}0000-0000-4000-8000-000000000201';
    v_bldg2a UUID := '${HASH}0000-0000-4000-8000-000000000202';
    v_bldg2b UUID := '${HASH}0000-0000-4000-8000-000000000203';

    v_unit1 UUID := '${HASH}0000-0000-4000-8000-000000000301';
    v_unit2 UUID := '${HASH}0000-0000-4000-8000-000000000302';
    v_unit3 UUID := '${HASH}0000-0000-4000-8000-000000000303';
    v_unit4 UUID := '${HASH}0000-0000-4000-8000-000000000304';
    v_unit5 UUID := '${HASH}0000-0000-4000-8000-000000000305';
    v_unit6 UUID := '${HASH}0000-0000-4000-8000-000000000306';
    v_unit7 UUID := '${HASH}0000-0000-4000-8000-000000000307';
    v_unit8 UUID := '${HASH}0000-0000-4000-8000-000000000308';
    v_unit9 UUID := '${HASH}0000-0000-4000-8000-000000000309';
    v_unit10 UUID := '${HASH}0000-0000-4000-8000-000000000310';
    v_unit11 UUID := '${HASH}0000-0000-4000-8000-000000000311';
    v_unit12 UUID := '${HASH}0000-0000-4000-8000-000000000312';

    v_renter1 UUID := '${HASH}0000-0000-4000-8000-000000000401';
    v_renter2 UUID := '${HASH}0000-0000-4000-8000-000000000402';
    v_renter3 UUID := '${HASH}0000-0000-4000-8000-000000000403';
    v_renter4 UUID := '${HASH}0000-0000-4000-8000-000000000404';

    v_lease1 UUID := '${HASH}0000-0000-4000-8000-000000000501';
    v_lease2 UUID := '${HASH}0000-0000-4000-8000-000000000502';
    v_lease3 UUID := '${HASH}0000-0000-4000-8000-000000000503';
    v_lease4 UUID := '${HASH}0000-0000-4000-8000-000000000504';
    v_lease5 UUID := '${HASH}0000-0000-4000-8000-000000000505';
    v_lease6 UUID := '${HASH}0000-0000-4000-8000-000000000506';

    v_acct_bank UUID := '${HASH}0000-0000-4000-8000-000000000601';
    v_acct_recv UUID := '${HASH}0000-0000-4000-8000-000000000602';
    v_acct_dep UUID := '${HASH}0000-0000-4000-8000-000000000603';
    v_acct_adv UUID := '${HASH}0000-0000-4000-8000-000000000604';
    v_acct_rent UUID := '${HASH}0000-0000-4000-8000-000000000605';
    v_acct_svc UUID := '${HASH}0000-0000-4000-8000-000000000606';
    v_acct_maint UUID := '${HASH}0000-0000-4000-8000-000000000607';
    v_acct_mgmt UUID := '${HASH}0000-0000-4000-8000-000000000608';
    v_acct_eq UUID := '${HASH}0000-0000-4000-8000-000000000609';

    v_mapping UUID := '${HASH}0000-0000-4000-8000-000000000701';

    v_rcs1 UUID := '${HASH}0000-0000-4000-8000-000000000801';
    v_rcs2 UUID := '${HASH}0000-0000-4000-8000-000000000802';
    v_rcs3 UUID := '${HASH}0000-0000-4000-8000-000000000803';

    v_ticket1 UUID := '${HASH}0000-0000-4000-8000-000000000901';
    v_ticket2 UUID := '${HASH}0000-0000-4000-8000-000000000902';
    v_ticket3 UUID := '${HASH}0000-0000-4000-8000-000000000903';
    v_ticket4 UUID := '${HASH}0000-0000-4000-8000-000000000904';

    v_staff1 UUID := '${HASH}0000-0000-4000-8000-000000000a01';
    v_staff2 UUID := '${HASH}0000-0000-4000-8000-000000000a02';
    v_staff3 UUID := '${HASH}0000-0000-4000-8000-000000000a03';

    v_vendor1 UUID := '${HASH}0000-0000-4000-8000-000000000b01';
    v_vendor2 UUID := '${HASH}0000-0000-4000-8000-000000000b02';
    v_vendor3 UUID := '${HASH}0000-0000-4000-8000-000000000b03';

    v_pw TEXT := '${PASSWORD_HASH}';
    i INT;
    v_ps_id UUID;
    v_ft_id UUID;

BEGIN

    -- 1. Tenant
    INSERT INTO landlord_org (id, name, address, trn, status, ticket_otp_required, created_at, updated_at)
    VALUES (v_tenant_id, '${COMPANY}', 'Business Bay, Dubai, UAE', '100234567890003', 'ACTIVE', true, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- 2. Users
    INSERT INTO users (id, tenant_id, email, password_hash, name, role, status, created_at, updated_at) VALUES
        (v_user_admin, v_tenant_id, 'admin@${DOMAIN}', v_pw, 'Ahmed Al Maktoum', 'TENANT_ADMIN', 'ACTIVE', NOW(), NOW()),
        (v_user_pm1, v_tenant_id, 'pm1@${DOMAIN}', v_pw, 'Fatima Hassan', 'PROPERTY_MANAGER', 'ACTIVE', NOW(), NOW()),
        (v_user_pm2, v_tenant_id, 'pm2@${DOMAIN}', v_pw, 'Omar Khalid', 'PROPERTY_MANAGER', 'ACTIVE', NOW(), NOW()),
        (v_user_staff, v_tenant_id, 'staff@${DOMAIN}', v_pw, 'Sara Ali', 'TENANT_USER', 'ACTIVE', NOW(), NOW()),
        (v_user_renter1, v_tenant_id, 'renter1@${DOMAIN}', v_pw, 'John Smith', 'RENTER', 'ACTIVE', NOW(), NOW()),
        (v_user_renter2, v_tenant_id, 'renter2@${DOMAIN}', v_pw, 'Maria Garcia', 'RENTER', 'ACTIVE', NOW(), NOW()),
        (v_user_renter3, v_tenant_id, 'renter3@${DOMAIN}', v_pw, 'Rajesh Kumar', 'RENTER', 'ACTIVE', NOW(), NOW()),
        (v_user_renter4, v_tenant_id, 'renter4@${DOMAIN}', v_pw, 'Li Wei', 'RENTER', 'ACTIVE', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- 3. User-Tenant Memberships
    INSERT INTO user_tenant_memberships (user_id, tenant_id, created_at) VALUES
        (v_user_admin, v_tenant_id, NOW()),
        (v_user_pm1, v_tenant_id, NOW()),
        (v_user_pm2, v_tenant_id, NOW()),
        (v_user_staff, v_tenant_id, NOW()),
        (v_user_renter1, v_tenant_id, NOW()),
        (v_user_renter2, v_tenant_id, NOW()),
        (v_user_renter3, v_tenant_id, NOW()),
        (v_user_renter4, v_tenant_id, NOW())
    ON CONFLICT DO NOTHING;

    -- 4. Properties
    INSERT INTO properties (id, tenant_id, name_en, name_ar, type, emirate, address, makani_number, created_at, updated_at) VALUES
        (v_prop1, v_tenant_id, 'Marina Heights', 'مارينا هايتس', 'RESIDENTIAL', 'DUBAI', 'Dubai Marina, Tower Road', '12345-67890', NOW(), NOW()),
        (v_prop2, v_tenant_id, 'Business Central', 'بيزنس سنترال', 'COMMERCIAL', 'DUBAI', 'DIFC, Gate Village', '98765-43210', NOW(), NOW()),
        (v_prop3, v_tenant_id, 'Palm Residences', 'بالم ريزيدنسز', 'RESIDENTIAL', 'DUBAI', 'Palm Jumeirah, Crescent Road', NULL, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- 5. Buildings
    INSERT INTO buildings (id, tenant_id, property_id, name, floors, created_at, updated_at) VALUES
        (v_bldg1, v_tenant_id, v_prop1, 'Tower A', 20, NOW(), NOW()),
        (v_bldg2a, v_tenant_id, v_prop2, 'Block A', 10, NOW(), NOW()),
        (v_bldg2b, v_tenant_id, v_prop2, 'Block B', 8, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- 6. Units
    INSERT INTO units (id, tenant_id, property_id, building_id, unit_number, type, size_sqft, expected_rent, status, created_at, updated_at) VALUES
        (v_unit1, v_tenant_id, v_prop1, v_bldg1, '101', 'BHK1', 850, 60000, 'OCCUPIED', NOW(), NOW()),
        (v_unit2, v_tenant_id, v_prop1, v_bldg1, '102', 'BHK2', 1200, 85000, 'OCCUPIED', NOW(), NOW()),
        (v_unit3, v_tenant_id, v_prop1, v_bldg1, '201', 'BHK3', 1800, 120000, 'OCCUPIED', NOW(), NOW()),
        (v_unit4, v_tenant_id, v_prop1, v_bldg1, '202', 'STUDIO', 450, 35000, 'VACANT', NOW(), NOW()),
        (v_unit5, v_tenant_id, v_prop1, v_bldg1, '301', 'PENTHOUSE', 3500, 250000, 'VACANT', NOW(), NOW()),
        (v_unit6, v_tenant_id, v_prop2, v_bldg2a, 'A01', 'OFFICE', 2000, 150000, 'OCCUPIED', NOW(), NOW()),
        (v_unit7, v_tenant_id, v_prop2, v_bldg2a, 'A02', 'OFFICE', 1500, 120000, 'VACANT', NOW(), NOW()),
        (v_unit8, v_tenant_id, v_prop2, v_bldg2b, 'B01', 'RETAIL', 800, 95000, 'OCCUPIED', NOW(), NOW()),
        (v_unit9, v_tenant_id, v_prop2, v_bldg2b, 'B02', 'RETAIL', 600, 75000, 'VACANT', NOW(), NOW()),
        (v_unit10, v_tenant_id, v_prop3, NULL, 'V01', 'BHK3', 4000, 300000, 'OCCUPIED', NOW(), NOW()),
        (v_unit11, v_tenant_id, v_prop3, NULL, 'V02', 'BHK3', 3000, 220000, 'OCCUPIED', NOW(), NOW()),
        (v_unit12, v_tenant_id, v_prop3, NULL, 'V03', 'BHK3', 3200, 240000, 'VACANT', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- 7. Renters
    INSERT INTO renters (id, tenant_id, name_en, name_ar, email, phone, primary_language, user_id) VALUES
        (v_renter1, v_tenant_id, 'John Smith', 'جون سميث', 'renter1@${DOMAIN}', '+971501234567', 'EN', v_user_renter1),
        (v_renter2, v_tenant_id, 'Maria Garcia', 'ماريا غارسيا', 'renter2@${DOMAIN}', '+971502345678', 'EN', v_user_renter2),
        (v_renter3, v_tenant_id, 'Rajesh Kumar', 'راجيش كومار', 'renter3@${DOMAIN}', '+971503456789', 'EN', v_user_renter3),
        (v_renter4, v_tenant_id, 'Li Wei', 'لي وي', 'renter4@${DOMAIN}', '+971504567890', 'EN', v_user_renter4)
    ON CONFLICT (id) DO NOTHING;

    -- 8. User-Property Assignments
    INSERT INTO user_property_assignments (user_id, property_id) VALUES
        (v_user_pm1, v_prop1), (v_user_pm1, v_prop3), (v_user_pm2, v_prop2)
    ON CONFLICT DO NOTHING;

    -- 9. Leases (5 ACTIVE + 1 EXPIRED)
    INSERT INTO leases (id, tenant_id, unit_id, renter_id, property_id, start_date, end_date, rent_amount, monthly_rent, deposit_amount, payment_terms, payment_method, status, ejari_number, created_at, updated_at) VALUES
        (v_lease1, v_tenant_id, v_unit1, v_renter1, v_prop1, '2026-01-05', '2027-01-04', 60000, 5000, 5000, 12, 'CHEQUE', 'ACTIVE', 'EJ-001', NOW(), NOW()),
        (v_lease2, v_tenant_id, v_unit2, v_renter2, v_prop1, '2026-02-05', '2027-02-04', 85000, 7083.33, 8500, 4, 'CHEQUE', 'ACTIVE', 'EJ-002', NOW(), NOW()),
        (v_lease3, v_tenant_id, v_unit6, v_renter3, v_prop2, '2026-03-05', '2027-03-04', 150000, 12500, 15000, 2, 'BANK_TRANSFER', 'ACTIVE', 'EJ-003', NOW(), NOW()),
        (v_lease4, v_tenant_id, v_unit10, v_renter4, v_prop3, '2026-01-05', '2027-01-04', 300000, 25000, 30000, 4, 'CHEQUE', 'ACTIVE', 'EJ-004', NOW(), NOW()),
        (v_lease5, v_tenant_id, v_unit11, v_renter1, v_prop3, '2026-04-05', '2027-04-04', 220000, 18333.33, 22000, 1, 'ONLINE', 'ACTIVE', 'EJ-005', NOW(), NOW()),
        (v_lease6, v_tenant_id, v_unit3, v_renter2, v_prop1, '2025-01-05', '2026-01-04', 120000, 10000, 12000, 12, 'CHEQUE', 'EXPIRED', 'EJ-006', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- 10. Payment Schedules
    -- Lease 1: 12 cheques, 5000/month. First 3 CLEARED, 1 COLLECTED, rest PENDING
    FOR i IN 1..12 LOOP
        v_ps_id := gen_random_uuid();
        INSERT INTO payment_schedules (id, tenant_id, lease_id, property_id, unit_id, installment_number, due_date, amount, status, payment_method, status_changed_at, created_at, updated_at)
        VALUES (v_ps_id, v_tenant_id, v_lease1, v_prop1, v_unit1, i,
            ('2026-01-05'::DATE + ((i-1) * INTERVAL '1 month'))::DATE,
            5000, CASE WHEN i <= 3 THEN 'CLEARED' WHEN i = 4 THEN 'COLLECTED' ELSE 'PENDING' END,
            'CHEQUE', NOW(), NOW(), NOW());
        -- Financial transactions for cleared
        IF i <= 3 THEN
            v_ft_id := gen_random_uuid();
            INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, debit, credit, property_id, unit_id, created_at, updated_at)
            VALUES (v_ft_id, v_tenant_id, ('2026-01-05'::DATE + ((i-1) * INTERVAL '1 month'))::DATE,
                'Rent cleared - Lease 1 Inst #' || i, v_acct_bank, 5000, 0, v_prop1, v_unit1, NOW(), NOW());
            v_ft_id := gen_random_uuid();
            INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, debit, credit, property_id, unit_id, created_at, updated_at)
            VALUES (v_ft_id, v_tenant_id, ('2026-01-05'::DATE + ((i-1) * INTERVAL '1 month'))::DATE,
                'Rental income - Lease 1 Inst #' || i, v_acct_rent, 0, 5000, v_prop1, v_unit1, NOW(), NOW());
        END IF;
    END LOOP;

    -- Lease 2: 4 cheques, 21250/quarter. First 1 CLEARED, 1 DEPOSITED, rest PENDING
    FOR i IN 1..4 LOOP
        v_ps_id := gen_random_uuid();
        INSERT INTO payment_schedules (id, tenant_id, lease_id, property_id, unit_id, installment_number, due_date, amount, status, payment_method, status_changed_at, created_at, updated_at)
        VALUES (v_ps_id, v_tenant_id, v_lease2, v_prop1, v_unit2, i,
            ('2026-02-05'::DATE + ((i-1) * 3 * INTERVAL '1 month'))::DATE,
            21250, CASE WHEN i = 1 THEN 'CLEARED' WHEN i = 2 THEN 'DEPOSITED' ELSE 'PENDING' END,
            'CHEQUE', NOW(), NOW(), NOW());
        IF i = 1 THEN
            v_ft_id := gen_random_uuid();
            INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, debit, credit, property_id, unit_id, created_at, updated_at)
            VALUES (v_ft_id, v_tenant_id, '2026-02-05', 'Rent cleared - Lease 2 Inst #1', v_acct_bank, 21250, 0, v_prop1, v_unit2, NOW(), NOW());
            v_ft_id := gen_random_uuid();
            INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, debit, credit, property_id, unit_id, created_at, updated_at)
            VALUES (v_ft_id, v_tenant_id, '2026-02-05', 'Rental income - Lease 2 Inst #1', v_acct_rent, 0, 21250, v_prop1, v_unit2, NOW(), NOW());
        END IF;
    END LOOP;

    -- Lease 3: 2 cheques, 75000/half. First 1 CLEARED, rest PENDING
    FOR i IN 1..2 LOOP
        v_ps_id := gen_random_uuid();
        INSERT INTO payment_schedules (id, tenant_id, lease_id, property_id, unit_id, installment_number, due_date, amount, status, payment_method, status_changed_at, created_at, updated_at)
        VALUES (v_ps_id, v_tenant_id, v_lease3, v_prop2, v_unit6, i,
            ('2026-03-05'::DATE + ((i-1) * 6 * INTERVAL '1 month'))::DATE,
            75000, CASE WHEN i = 1 THEN 'CLEARED' ELSE 'PENDING' END,
            'BANK_TRANSFER', NOW(), NOW(), NOW());
        IF i = 1 THEN
            v_ft_id := gen_random_uuid();
            INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, debit, credit, property_id, unit_id, created_at, updated_at)
            VALUES (v_ft_id, v_tenant_id, '2026-03-05', 'Rent cleared - Lease 3 Inst #1', v_acct_bank, 75000, 0, v_prop2, v_unit6, NOW(), NOW());
            v_ft_id := gen_random_uuid();
            INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, debit, credit, property_id, unit_id, created_at, updated_at)
            VALUES (v_ft_id, v_tenant_id, '2026-03-05', 'Rental income - Lease 3 Inst #1', v_acct_rent, 0, 75000, v_prop2, v_unit6, NOW(), NOW());
        END IF;
    END LOOP;

    -- Lease 4: 4 cheques, 75000/quarter. First 1 CLEARED, rest PENDING
    FOR i IN 1..4 LOOP
        v_ps_id := gen_random_uuid();
        INSERT INTO payment_schedules (id, tenant_id, lease_id, property_id, unit_id, installment_number, due_date, amount, status, payment_method, status_changed_at, created_at, updated_at)
        VALUES (v_ps_id, v_tenant_id, v_lease4, v_prop3, v_unit10, i,
            ('2026-01-05'::DATE + ((i-1) * 3 * INTERVAL '1 month'))::DATE,
            75000, CASE WHEN i = 1 THEN 'CLEARED' ELSE 'PENDING' END,
            'CHEQUE', NOW(), NOW(), NOW());
        IF i = 1 THEN
            v_ft_id := gen_random_uuid();
            INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, debit, credit, property_id, unit_id, created_at, updated_at)
            VALUES (v_ft_id, v_tenant_id, '2026-01-05', 'Rent cleared - Lease 4 Inst #1', v_acct_bank, 75000, 0, v_prop3, v_unit10, NOW(), NOW());
            v_ft_id := gen_random_uuid();
            INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, debit, credit, property_id, unit_id, created_at, updated_at)
            VALUES (v_ft_id, v_tenant_id, '2026-01-05', 'Rental income - Lease 4 Inst #1', v_acct_rent, 0, 75000, v_prop3, v_unit10, NOW(), NOW());
        END IF;
    END LOOP;

    -- Lease 5: 1 cheque, 220000. PENDING
    v_ps_id := gen_random_uuid();
    INSERT INTO payment_schedules (id, tenant_id, lease_id, property_id, unit_id, installment_number, due_date, amount, status, payment_method, status_changed_at, created_at, updated_at)
    VALUES (v_ps_id, v_tenant_id, v_lease5, v_prop3, v_unit11, 1, '2026-04-05', 220000, 'PENDING', 'ONLINE', NOW(), NOW(), NOW());

    -- Lease 6 (expired): 12 cheques all CLEARED
    FOR i IN 1..12 LOOP
        v_ps_id := gen_random_uuid();
        INSERT INTO payment_schedules (id, tenant_id, lease_id, property_id, unit_id, installment_number, due_date, amount, status, payment_method, status_changed_at, created_at, updated_at)
        VALUES (v_ps_id, v_tenant_id, v_lease6, v_prop1, v_unit3, i,
            ('2025-01-05'::DATE + ((i-1) * INTERVAL '1 month'))::DATE,
            10000, 'CLEARED', 'CHEQUE', NOW(), NOW(), NOW());
        v_ft_id := gen_random_uuid();
        INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, debit, credit, property_id, unit_id, created_at, updated_at)
        VALUES (v_ft_id, v_tenant_id, ('2025-01-05'::DATE + ((i-1) * INTERVAL '1 month'))::DATE,
            'Rent cleared - Lease 6 Inst #' || i, v_acct_bank, 10000, 0, v_prop1, v_unit3, NOW(), NOW());
        v_ft_id := gen_random_uuid();
        INSERT INTO financial_transactions (id, tenant_id, date, description, account_id, debit, credit, property_id, unit_id, created_at, updated_at)
        VALUES (v_ft_id, v_tenant_id, ('2025-01-05'::DATE + ((i-1) * INTERVAL '1 month'))::DATE,
            'Rental income - Lease 6 Inst #' || i, v_acct_rent, 0, 10000, v_prop1, v_unit3, NOW(), NOW());
    END LOOP;

    -- 11. Chart of Accounts
    INSERT INTO accounts (id, tenant_id, code, name, type, sub_type, is_system, created_at, updated_at) VALUES
        (v_acct_bank, v_tenant_id, 'A-01-01', 'Bank/Cash Account', 'ASSET', 'CURRENT_ASSET', true, NOW(), NOW()),
        (v_acct_recv, v_tenant_id, 'A-02-01', 'Accounts Receivable', 'ASSET', 'CURRENT_ASSET', true, NOW(), NOW()),
        (v_acct_dep, v_tenant_id, 'B-01-01', 'Security Deposits', 'LIABILITY', 'CURRENT_LIABILITY', true, NOW(), NOW()),
        (v_acct_adv, v_tenant_id, 'B-02-01', 'Advance Rent', 'LIABILITY', 'CURRENT_LIABILITY', true, NOW(), NOW()),
        (v_acct_rent, v_tenant_id, 'C-01-01', 'Rental Income', 'INCOME', 'OPERATING_INCOME', true, NOW(), NOW()),
        (v_acct_svc, v_tenant_id, 'C-02-01', 'Service Charges', 'INCOME', 'OPERATING_INCOME', true, NOW(), NOW()),
        (v_acct_maint, v_tenant_id, 'D-01-01', 'Maintenance Expenses', 'EXPENSE', 'OPERATING_EXPENSE', true, NOW(), NOW()),
        (v_acct_mgmt, v_tenant_id, 'D-02-01', 'Management Fees', 'EXPENSE', 'OPERATING_EXPENSE', true, NOW(), NOW()),
        (v_acct_eq, v_tenant_id, 'E-01-01', 'Owner Equity', 'EQUITY', NULL, true, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- 12. Account Mapping
    INSERT INTO account_mappings (id, tenant_id, transaction_nature, debit_account_id, credit_account_id, created_at, updated_at)
    VALUES (v_mapping, v_tenant_id, 'RENT_PAYMENT_CLEARED', v_acct_bank, v_acct_rent, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- 13. Rent Collection Settings (due day 5)
    INSERT INTO rent_collection_settings (id, tenant_id, property_id, due_day_of_month, grace_period_days, late_penalty_percentage, payment_reminder_days, created_at, updated_at) VALUES
        (v_rcs1, v_tenant_id, v_prop1, 5, 5, 5.0, '7,3,1', NOW(), NOW()),
        (v_rcs2, v_tenant_id, v_prop2, 5, 10, 2.0, '14,7,1', NOW(), NOW()),
        (v_rcs3, v_tenant_id, v_prop3, 5, 7, 3.0, '7,3', NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- 14. Maintenance Tickets
    INSERT INTO maintenance_tickets (id, tenant_id, property_id, unit_id, lease_id, reported_by, assigned_to, title, description, category, priority, status, satisfaction_rating, satisfaction_comment, closure_otp, resolved_at, closed_at, created_at, updated_at) VALUES
        (v_ticket1, v_tenant_id, v_prop1, v_unit1, v_lease1, v_user_renter1, v_user_pm1, 'AC not cooling properly', 'The AC in the living room is not cooling. Temperature stays at 28C even after running for hours.', 'HVAC', 'HIGH', 'CLOSED', 5, 'Fixed quickly, great service!', NULL, '2026-02-12 14:00:00', '2026-02-12 16:00:00', '2026-02-10 09:00:00', NOW()),
        (v_ticket2, v_tenant_id, v_prop1, v_unit2, v_lease2, v_user_renter2, v_user_pm1, 'Water leak in bathroom', 'There is a constant drip from the bathroom ceiling. Getting worse.', 'PLUMBING', 'URGENT', 'RESOLVED', NULL, NULL, '234567', '2026-03-06 11:00:00', NULL, '2026-03-05 08:30:00', NOW()),
        (v_ticket3, v_tenant_id, v_prop2, v_unit6, v_lease3, v_user_renter3, v_user_pm2, 'Power outlet not working', 'The outlet near my desk stopped working. Tried different devices.', 'ELECTRICAL', 'HIGH', 'IN_PROGRESS', NULL, NULL, NULL, NULL, NULL, '2026-03-10 10:00:00', NOW()),
        (v_ticket4, v_tenant_id, v_prop3, v_unit10, v_lease4, v_user_renter4, v_user_pm1, 'Door lock malfunction', 'The smart lock on the front door is not responding to the key card.', 'SECURITY', 'MEDIUM', 'ASSIGNED', NULL, NULL, NULL, NULL, NULL, '2026-03-12 14:00:00', NOW())
    ON CONFLICT (id) DO NOTHING;

    -- 15. Ticket Replies
    INSERT INTO ticket_replies (id, tenant_id, ticket_id, user_id, user_name, message, created_at) VALUES
        (gen_random_uuid(), v_tenant_id, v_ticket1, v_user_renter1, 'John Smith', 'The AC has been making a loud noise too. Please check ASAP.', '2026-02-10 10:00:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket1, v_user_pm1, 'Fatima Hassan', 'Technician scheduled for tomorrow morning. Will replace the compressor if needed.', '2026-02-11 09:00:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket1, v_user_pm1, 'Fatima Hassan', 'Compressor replaced. AC is now cooling properly. Please confirm.', '2026-02-12 14:30:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket2, v_user_renter2, 'Maria Garcia', 'The leak is getting worse. Water is now dripping onto the floor.', '2026-03-05 09:00:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket2, v_user_pm1, 'Fatima Hassan', 'Emergency plumber dispatched. Pipe replacement in progress.', '2026-03-06 10:00:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket3, v_user_pm2, 'Omar Khalid', 'Electrician inspecting the outlet today. Will update once diagnosed.', '2026-03-11 09:30:00');

    -- 16. Ticket History
    INSERT INTO ticket_history (id, tenant_id, ticket_id, action, old_status, new_status, performed_by, performed_by_name, notes, created_at) VALUES
        (gen_random_uuid(), v_tenant_id, v_ticket1, 'CREATED', NULL, 'OPEN', v_user_renter1, 'John Smith', 'Ticket created', '2026-02-10 09:00:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket1, 'ASSIGNED', 'OPEN', 'ASSIGNED', v_user_admin, 'Ahmed Al Maktoum', 'Assigned to Fatima', '2026-02-10 09:30:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket1, 'STATUS_CHANGED', 'ASSIGNED', 'RESOLVED', v_user_pm1, 'Fatima Hassan', 'AC repaired', '2026-02-12 14:00:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket1, 'STATUS_CHANGED', 'RESOLVED', 'CLOSED', v_user_renter1, 'John Smith', 'Confirmed fix', '2026-02-12 16:00:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket2, 'CREATED', NULL, 'OPEN', v_user_renter2, 'Maria Garcia', 'Ticket created', '2026-03-05 08:30:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket2, 'ASSIGNED', 'OPEN', 'ASSIGNED', v_user_admin, 'Ahmed Al Maktoum', 'Urgent - assigned to Fatima', '2026-03-05 08:45:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket2, 'STATUS_CHANGED', 'ASSIGNED', 'RESOLVED', v_user_pm1, 'Fatima Hassan', 'Pipe replaced', '2026-03-06 11:00:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket3, 'CREATED', NULL, 'OPEN', v_user_renter3, 'Rajesh Kumar', 'Ticket created', '2026-03-10 10:00:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket3, 'STATUS_CHANGED', 'OPEN', 'IN_PROGRESS', v_user_pm2, 'Omar Khalid', 'Investigation started', '2026-03-11 09:00:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket4, 'CREATED', NULL, 'OPEN', v_user_renter4, 'Li Wei', 'Ticket created', '2026-03-12 14:00:00'),
        (gen_random_uuid(), v_tenant_id, v_ticket4, 'ASSIGNED', 'OPEN', 'ASSIGNED', v_user_admin, 'Ahmed Al Maktoum', 'Assigned to Fatima', '2026-03-12 15:00:00');

    -- 17. Staff
    INSERT INTO staff (id, tenant_id, name_en, employee_id, designation, department, monthly_salary, join_date, phone, property_id, is_active, created_at, updated_at) VALUES
        (v_staff1, v_tenant_id, 'Abdullah Mohammed', 'EMP001', 'Maintenance Supervisor', 'Maintenance', 8000, '2024-03-01', '+971551111111', v_prop1, true, NOW(), NOW()),
        (v_staff2, v_tenant_id, 'Priya Patel', 'EMP002', 'Receptionist', 'Administration', 5000, '2024-06-15', '+971552222222', v_prop2, true, NOW(), NOW()),
        (v_staff3, v_tenant_id, 'Carlos Santos', 'EMP003', 'Security Guard', 'Security', 4500, '2024-09-01', '+971553333333', v_prop3, true, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    -- 18. Vendors
    INSERT INTO vendors (id, tenant_id, name, email, phone, contact_person, address, is_active, created_at, updated_at) VALUES
        (v_vendor1, v_tenant_id, 'Dubai Plumbing Services', 'plumbing@dubaiservices.ae', '+97141111111', 'Khalid Ibrahim', 'Al Quoz, Dubai', true, NOW(), NOW()),
        (v_vendor2, v_tenant_id, 'Cool Air HVAC', 'info@coolairhvac.ae', '+97142222222', 'Mohammed Hassan', 'Ras Al Khor, Dubai', true, NOW(), NOW()),
        (v_vendor3, v_tenant_id, 'SecureGuard LLC', 'contact@secureguard.ae', '+97143333333', 'James Wilson', 'JLT, Dubai', true, NOW(), NOW())
    ON CONFLICT (id) DO NOTHING;

    RAISE NOTICE 'Tenant "%" seeded with domain @%', '${COMPANY}', '${DOMAIN}';
    RAISE NOTICE 'Users: admin@%, pm1@%, pm2@%, staff@%, renter1-4@%', '${DOMAIN}', '${DOMAIN}', '${DOMAIN}', '${DOMAIN}', '${DOMAIN}';
    RAISE NOTICE 'Password for all: pass123';

END;
\$\$;

COMMIT;
EOSQL

echo ""
echo "✓ Tenant '${COMPANY}' created with @${DOMAIN} users"
echo "  Login: admin@${DOMAIN} / pass123"

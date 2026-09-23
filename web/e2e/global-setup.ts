import { test as setup } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { login, register, createTenant, listTenants, createUser, createProperty, createUnit, createRenter, createLease, seedChartOfAccounts } from './helpers/api-client';

const CONTEXT_PATH = path.join(__dirname, '.test-context.json');

setup('seed test data', async () => {
  console.log('--- Global Setup: Seeding test data ---');

  // Unique suffix per run to avoid collisions
  const suffix = Date.now().toString(36);

  // 1. Try to login as default super admin, or register a new tenant admin
  let adminId: string;
  let adminRole: string;
  let adminEmail: string;
  let adminPassword: string;
  let testTenantId: string;

  try {
    const admin = await login('admin@rentaxis.com', 'admin123');
    adminId = admin.id;
    adminRole = admin.role;
    adminEmail = 'admin@rentaxis.com';
    adminPassword = 'admin123';
    console.log(`Logged in as ${admin.email} (${admin.role}), tenantId=${admin.tenantId}`);

    // SUPER_ADMIN: create a tenant for test data
    try {
      const tenant = await createTenant(adminId, adminRole, null, `E2E Test Org ${suffix}`);
      testTenantId = tenant.id;
      console.log(`Created test tenant: ${testTenantId}`);
    } catch {
      const tenants = await listTenants(adminId, adminRole);
      testTenantId = tenants[0]?.id;
      console.log(`Using existing tenant: ${testTenantId}`);
    }
  } catch {
    // Default admin not available — register a new org via /register
    console.log('Default admin login failed, registering new org...');
    adminEmail = `e2e-admin-${suffix}@test.com`;
    adminPassword = 'admin123';
    const reg = await register('E2E Admin', `E2E Corp ${suffix}`, adminEmail, adminPassword);
    adminId = reg.id;
    adminRole = reg.role; // TENANT_ADMIN
    testTenantId = reg.tenantId;
    console.log(`Registered new org: ${reg.email} (${reg.role}), tenantId=${testTenantId}`);
  }

  if (!testTenantId) {
    console.error('FATAL: No tenant available');
    fs.writeFileSync(CONTEXT_PATH, JSON.stringify({ error: 'No tenant available' }));
    return;
  }

  // 2. Create test users for each role
  const usersToCreate = [
    { name: 'E2E Tenant Admin', email: `e2e-ta-${suffix}@test.com`, password: 'test1234', role: 'TENANT_ADMIN' },
    { name: 'E2E Property Manager', email: `e2e-pm-${suffix}@test.com`, password: 'test1234', role: 'PROPERTY_MANAGER' },
    { name: 'E2E Tenant User', email: `e2e-tu-${suffix}@test.com`, password: 'test1234', role: 'TENANT_USER' },
  ];

  for (const u of usersToCreate) {
    try {
      const user = await createUser(adminId, adminRole, testTenantId, u);
      console.log(`Created ${u.role}: ${u.email} (${user.id})`);
    } catch (e: any) {
      console.log(`User ${u.email} creation failed: ${e.message}`);
    }
  }

  // 2b. Seed the chart of accounts (+ property account template + charge
  //     types, chained server-side) before any property exists, so the
  //     property created below gets its own generated account set and the
  //     draft lease below has a RENT / SECURITY_DEPOSIT charge type to use.
  //     accounting-v2 plan 2's `lines`-based leases need this; v1's flat
  //     rentAmount/depositAmount body never did.
  try {
    await seedChartOfAccounts(adminId, adminRole, testTenantId);
    console.log('Seeded chart of accounts');
  } catch (e: any) {
    console.log(`Chart of accounts seed failed (may already exist): ${e.message}`);
  }

  // 3. Create test property
  let propertyId: string = '';
  try {
    const property = await createProperty(adminId, adminRole, testTenantId, {
      nameEn: 'E2E Test Tower',
      nameAr: 'برج الاختبار',
      address: '123 Test Blvd, Dubai',
      emirate: 'DUBAI',
    });
    propertyId = property.id;
    console.log(`Created property: ${propertyId}`);
  } catch (e: any) {
    console.log(`Property creation failed: ${e.message}`);
  }

  // 4. Create test unit
  let unitId: string = '';
  if (propertyId) {
    try {
      const unit = await createUnit(adminId, adminRole, testTenantId, {
        propertyId,
        unitNumber: 'E2E-101',
        type: 'BHK1',
        sizeSqft: 120,
        expectedRent: 8000,
      });
      unitId = unit.id;
      console.log(`Created unit: ${unitId}`);
    } catch (e: any) {
      console.log(`Unit creation failed: ${e.message}`);
    }
  }

  // 5. Create test renter with portal account
  let renterId: string = '';
  const renterEmail = `e2e-renter-${suffix}@test.com`;
  try {
    const renter = await createRenter(adminId, adminRole, testTenantId, {
      nameEn: 'E2E Renter',
      nameAr: 'مستأجر اختبار',
      email: renterEmail,
      phone: '+971501234567',
      createPortalAccount: true,
    });
    renterId = renter.id;
    console.log(`Created renter: ${renterId}`);
  } catch (e: any) {
    console.log(`Renter creation failed: ${e.message}`);
  }

  // 6. Create a draft lease
  let leaseId: string = '';
  if (unitId && renterId) {
    try {
      // The term STARTS IN THE PAST on purpose. Recognition only posts periods
      // whose `period_end` has already passed (`RecognitionController
      // #notInTheFuture`), so a contract that starts today has nothing to close
      // and accounting-v2's month-end step is invisible to
      // `finance/accounting-v2.spec.ts`, which walks THIS lease. Two months back
      // leaves at least one closed period whatever day of the month the run is.
      const today = new Date();
      const pad = (n: number) => String(n).padStart(2, '0');
      const isoOf = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
      const start = new Date(today.getFullYear(), today.getMonth() - 2, 1);
      const startDate = isoOf(start);
      // A one-year term: the day before the same date next year.
      const endDate = isoOf(new Date(start.getFullYear() + 1, start.getMonth(), 0));
      const lease = await createLease(adminId, adminRole, testTenantId, {
        unitId,
        renterId,
        startDate,
        endDate,
        rentAmount: 8000,
        depositAmount: 8000,
        paymentTerms: 4,
      });
      leaseId = lease.id;
      console.log(`Created lease: ${leaseId} (status: ${lease.status})`);
    } catch (e: any) {
      console.log(`Lease creation failed: ${e.message}`);
    }
  }

  // 7. Compute renter portal password (auto-generated by backend: "Renter@" + first 6 chars of renter ID)
  const renterPassword = renterId ? `Renter@${renterId.substring(0, 6)}` : 'test1234';
  console.log(`Renter portal password: ${renterPassword}`);

  // 8. Write test context
  const testContext = {
    adminId,
    adminRole,
    testTenantId,
    propertyId,
    unitId,
    renterId,
    leaseId,
    renterEmail,
    users: {
      superAdmin: { email: adminEmail, password: adminPassword },
      tenantAdmin: { email: usersToCreate[0].email, password: 'test1234' },
      propertyManager: { email: usersToCreate[1].email, password: 'test1234' },
      tenantUser: { email: usersToCreate[2].email, password: 'test1234' },
      renter: { email: renterEmail, password: renterPassword },
    },
  };

  fs.writeFileSync(CONTEXT_PATH, JSON.stringify(testContext, null, 2));
  console.log(`Test context written to ${CONTEXT_PATH}`);
  console.log('--- Global Setup Complete ---');
});

/**
 * 01 — Provision a fresh test tenant + property + unit + renter + active lease.
 * Every downstream spec reads .test-context.json to find these IDs.
 *
 * Every entity name is prefixed `TEST-` so prod dashboards / reports can
 * exclude this fixture data with a single filter.
 */
import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, ProdContext, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('provision tenant + property + unit + renter + active lease', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  const suffix = ctx.runSuffix;

  const request = await playwrightRequest.newContext({
    baseURL: ctx.baseURL,
    storageState: path.join(__dirname, '..', '.auth', 'superadmin.json'),
  });
  const pctx: ProdContext = { baseURL: ctx.baseURL, request, user: ctx.user };

  // 1. Tenant.
  const tenant = await api.createTenant(pctx, `TEST-E2E ${new Date().toISOString().slice(0, 10)} ${suffix}`);
  expect(tenant.id).toBeTruthy();

  // 2. Pivot SUPER_ADMIN's effective tenant for subsequent scoped calls.
  await setActiveTenant(pctx, tenant.id);

  // 3. Tenant admin user (so renter-portal-ui can later log in as a non-super
  //    user if needed). Generated email so re-runs don't collide.
  const adminEmail = `test-admin-${suffix}@e2e.rentaxis.test`;
  await api.createUser(pctx, tenant.id, {
    name: `TEST-Admin ${suffix}`,
    email: adminEmail,
    password: 'TestAdmin!23',
    role: 'TENANT_ADMIN',
  });

  // 4. Property + unit.
  const property = await api.createProperty(pctx, { nameEn: `TEST-Tower ${suffix}` });
  const unit = await api.createUnit(pctx, {
    propertyId: property.id,
    unitNumber: `TEST-${suffix}`,
    expectedRent: 50000,
  });

  // 5. Renter — backend now defaults createPortalAccount=true, returning the
  //    generated portal password on the response. The UI spec uses it to log in.
  const renterEmail = `test-renter-${suffix}@e2e.rentaxis.test`;
  const renter = await api.createRenter(pctx, {
    nameEn: `TEST-Renter ${suffix}`,
    email: renterEmail,
  });
  expect(renter.userId, 'renter creation must auto-create a portal User').toBeTruthy();
  expect(renter.portalPassword, 'response must include the generated portal password').toBeTruthy();

  // 6. Lease — 1-year, quarterly schedule (paymentTerms=4) for cheque lifecycle.
  const today = new Date();
  const startDate = today.toISOString().slice(0, 10);
  const endDate = new Date(today.getFullYear() + 1, today.getMonth(), today.getDate())
    .toISOString()
    .slice(0, 10);
  const lease = await api.createLease(pctx, {
    unitId: unit.id,
    renterId: renter.id,
    startDate,
    endDate,
    rentAmount: 50000,
  });

  // 7. Activate.
  const activated = await api.activateLease(pctx, lease.id);
  expect(activated.status).toMatch(/ACTIVE/i);

  // 8. Persist for downstream specs.
  fs.writeFileSync(
    CONTEXT_FILE,
    JSON.stringify(
      {
        ...ctx,
        tenant: { id: tenant.id, name: tenant.name },
        property: { id: property.id },
        unit: { id: unit.id },
        renter: { id: renter.id, email: renterEmail, portalPassword: renter.portalPassword },
        lease: { id: lease.id, status: activated.status },
        adminEmail,
      },
      null,
      2,
    ),
  );

  await request.dispose();
});

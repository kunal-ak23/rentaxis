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
import { api, loginAsNextAuth, ProdContext, setActiveTenant } from '../helpers/prod-client';

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

  // 3. Tenant admin + property manager. In production, SUPER_ADMIN provisions
  //    the org and creates the TENANT_ADMIN; subsequent operations are done
  //    by the TENANT_ADMIN or by a PROPERTY_MANAGER. We capture both so
  //    later specs can authenticate as the role that actually performs each
  //    action in the product — matches how the frontend is used.
  const adminEmail = `test-admin-${suffix}@e2e.rentaxis.test`;
  const adminPassword = 'TestAdmin!23';
  await api.createUser(pctx, tenant.id, {
    name: `TEST-Admin ${suffix}`,
    email: adminEmail,
    password: adminPassword,
    role: 'TENANT_ADMIN',
  });

  const pmEmail = `test-pm-${suffix}@e2e.rentaxis.test`;
  const pmPassword = 'TestPM!23';
  await api.createUser(pctx, tenant.id, {
    name: `TEST-PM ${suffix}`,
    email: pmEmail,
    password: pmPassword,
    role: 'PROPERTY_MANAGER',
  });

  // 4. Switch session to TENANT_ADMIN for the rest of provisioning — that's
  //    the role a real org admin uses to set up properties/units/renters/leases.
  //    Doing this as SUPER_ADMIN (the prior shape) would hide any TA-only
  //    controller restrictions and produce false-pass tests.
  const taCtx = await loginAsNextAuth(ctx.baseURL, adminEmail, adminPassword);

  // 5. Property + unit (TA).
  const property = await api.createProperty(taCtx, { nameEn: `TEST-Tower ${suffix}` });
  const unit = await api.createUnit(taCtx, {
    propertyId: property.id,
    unitNumber: `TEST-${suffix}`,
    expectedRent: 50000,
  });

  // 6. Renter (TA). Backend defaults createPortalAccount=true and returns
  //    the generated portal password on the response.
  //
  //    Gmail +alias so the USER_INVITED email actually delivers to a real
  //    inbox the operator can verify (kunalsharma.ks13@gmail.com). Per-tenant
  //    email uniqueness (migration 59) means re-runs across tenants don't
  //    collide.
  const renterEmail = `kunalsharma.ks13+e2e-renter-${suffix}@gmail.com`;
  const renter = await api.createRenter(taCtx, {
    nameEn: `TEST-Renter ${suffix}`,
    email: renterEmail,
  });
  expect(renter.userId, 'renter creation must auto-create a portal User').toBeTruthy();
  expect(renter.portalPassword, 'response must include the generated portal password').toBeTruthy();

  // 7. Lease (TA) — 1-year, quarterly (paymentTerms=4) for cheque lifecycle.
  const today = new Date();
  const startDate = today.toISOString().slice(0, 10);
  const endDate = new Date(today.getFullYear() + 1, today.getMonth(), today.getDate())
    .toISOString()
    .slice(0, 10);
  const lease = await api.createLease(taCtx, {
    unitId: unit.id,
    renterId: renter.id,
    startDate,
    endDate,
    rentAmount: 50000,
  });

  // 8. Activate (TA).
  const activated = await api.activateLease(taCtx, lease.id);
  expect(activated.status).toMatch(/ACTIVE/i);

  // 9. Persist for downstream specs.
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
        adminPassword,
        pmEmail,
        pmPassword,
      },
      null,
      2,
    ),
  );

  await taCtx.request.dispose();
  await request.dispose();
});

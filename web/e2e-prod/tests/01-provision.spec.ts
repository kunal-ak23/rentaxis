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

  // Persist the tenant immediately so 99-cleanup can remove it even if a
  // later provisioning step fails. Without this checkpoint, a failed seed
  // leaves an orphaned TEST-E2E organization in production.
  fs.writeFileSync(
    CONTEXT_FILE,
    JSON.stringify({ ...ctx, tenant: { id: tenant.id, name: tenant.name } }, null, 2),
  );

  // 2. Enable every gated capability on this disposable tenant so later specs
  //    validate the complete product without changing a real customer's flags.
  for (const feature of [
    'EMAIL_NOTIFICATIONS',
    'LISTINGS',
    'MEETINGS',
    'LEASE_RENEWALS',
    'GATEPASS',
  ] as const) {
    await api.setTenantFeature(pctx, tenant.id, feature, true);
  }

  // 3. Pivot SUPER_ADMIN's effective tenant for subsequent scoped calls.
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
  const pm = await api.createUser(pctx, tenant.id, {
    name: `TEST-PM ${suffix}`,
    email: pmEmail,
    password: pmPassword,
    role: 'PROPERTY_MANAGER',
  });

  const guardEmail = `test-guard-${suffix}@e2e.rentaxis.test`;
  const guardPassword = 'TestGuard!23';
  const guard = await api.createUser(pctx, tenant.id, {
    name: `TEST-Guard ${suffix}`,
    email: guardEmail,
    password: guardPassword,
    role: 'SECURITY_GUARD',
  });

  // 4. Switch session to TENANT_ADMIN for the rest of provisioning — that's
  //    the role a real org admin uses to set up properties/units/renters/leases.
  //    Doing this as SUPER_ADMIN (the prior shape) would hide any TA-only
  //    controller restrictions and produce false-pass tests.
  const taCtx = await loginAsNextAuth(ctx.baseURL, adminEmail, adminPassword);

  // 5. Property + unit (TA).
  const property = await api.createProperty(taCtx, { nameEn: `TEST-Tower ${suffix}` });
  await api.assignUserToProperty(taCtx, pm.id, property.id);
  await api.setGuardProperties(taCtx, guard.id, [property.id]);
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

  // 7. Lease (TA) — ends inside the 90-day renewal window while retaining four
  //    installments for the cheque lifecycle. A tenant-scoped renewal scan later
  //    opens the opportunity without processing any real customer tenant.
  const today = new Date();
  const startDate = today.toISOString().slice(0, 10);
  const endDate = new Date(today.getTime() + 60 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
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
        // nameEn is asserted on by 13f-lease-renewals. Omitting it made that
        // spec build `new RegExp(undefined, 'i')` and hunt for the literal
        // string "undefined" on a page that was rendering correctly.
        property: { id: property.id, nameEn: `TEST-Tower ${suffix}` },
        unit: { id: unit.id },
        renter: {
          id: renter.id,
          userId: renter.userId,
          email: renterEmail,
          portalPassword: renter.portalPassword,
        },
        lease: { id: lease.id, status: activated.status },
        adminEmail,
        adminPassword,
        pmEmail,
        pmPassword,
        pmUserId: pm.id,
        guardEmail,
        guardPassword,
        guardUserId: guard.id,
      },
      null,
      2,
    ),
  );

  await taCtx.request.dispose();
  await request.dispose();
});

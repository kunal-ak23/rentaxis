/**
 * 01a — Disposable-tenant feature access and user administration.
 *
 * Verifies the five feature toggles, SUPER_ADMIN-only boundaries, tenant-admin
 * role hierarchy, property assignments, update/list/delete, and the foreign
 * target read regression fixed in PR #105. Every mutation stays inside the
 * TEST-E2E tenant created by 01-provision.
 */
import { test, expect, type APIResponse } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');
const FEATURES = [
  'EMAIL_NOTIFICATIONS',
  'LISTINGS',
  'MEETINGS',
  'LEASE_RENEWALS',
  'GATEPASS',
  'MOBILE_FINANCE',
] as const;

async function assertOk(response: APIResponse, operation: string): Promise<void> {
  if (!response.ok()) {
    const body = await response.text().catch(() => '');
    throw new Error(`${operation} failed (${response.status()}): ${body.slice(0, 400)}`);
  }
}

test('super admin controls disposable feature access and tenant admin manages users safely', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.tenant?.id, '01-provision must run first').toBeTruthy();

  const superCtx = await loginAsNextAuth(ctx.baseURL, ctx.user.email, process.env.PROD_SUPERADMIN_PASSWORD!);
  await setActiveTenant(superCtx, ctx.tenant.id);

  const tenantUpdate = await superCtx.request.put(`/api/proxy/admin/tenants/${ctx.tenant.id}`, {
    data: {
      // Keep the confirmed cleanup name unchanged while exercising every
      // non-media organization field used by the provisioning form.
      name: ctx.tenant.name,
      address: `TEST-E2E Address ${ctx.runSuffix}`,
      trn: `TEST-TRN-${ctx.runSuffix}`,
      status: 'ACTIVE',
      phone: '+971500000008',
      ticketOtpRequired: true,
    },
    failOnStatusCode: false,
  });
  await assertOk(tenantUpdate, 'tenant organization update');
  const updatedTenant = await tenantUpdate.json();
  expect(updatedTenant.name).toBe(ctx.tenant.name);
  expect(updatedTenant.address).toContain('TEST-E2E Address');
  expect(updatedTenant.ticketOtpRequired).toBe(true);

  const featuresResponse = await superCtx.request.get(
    `/api/proxy/admin/tenants/${ctx.tenant.id}/features`,
    { failOnStatusCode: false },
  );
  await assertOk(featuresResponse, 'feature listing');
  const features = (await featuresResponse.json()) as Array<{ feature: string; enabled: boolean }>;
  expect(features.map((item) => item.feature).sort()).toEqual([...FEATURES].sort());
  expect(features.every((item) => item.enabled)).toBeTruthy();

  // Exercise a real toggle round-trip on the disposable tenant, restoring it
  // immediately so the later gate-pass lifecycle still runs.
  await api.setTenantFeature(superCtx, ctx.tenant.id, 'GATEPASS', false);
  try {
    const disabledResponse = await superCtx.request.get(
      `/api/proxy/admin/tenants/${ctx.tenant.id}/features`,
      { failOnStatusCode: false },
    );
    await assertOk(disabledResponse, 'feature listing after disable');
    const disabledFeatures = (await disabledResponse.json()) as Array<{
      feature: string;
      enabled: boolean;
    }>;
    expect(disabledFeatures.find((item) => item.feature === 'GATEPASS')?.enabled).toBe(false);
  } finally {
    await api.setTenantFeature(superCtx, ctx.tenant.id, 'GATEPASS', true);
  }

  const tenantAdminCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(tenantAdminCtx, ctx.tenant.id);

  const forbiddenFeatureRead = await tenantAdminCtx.request.get(
    `/api/proxy/admin/tenants/${ctx.tenant.id}/features`,
    { failOnStatusCode: false },
  );
  expect(forbiddenFeatureRead.status()).toBe(403);

  const forbiddenSuperAdminCreate = await tenantAdminCtx.request.post('/api/proxy/admin/users', {
    data: {
      email: `test-illegal-super-${ctx.runSuffix}@e2e.rentaxis.test`,
      password: 'TestIllegal!23',
      name: 'TEST-Illegal Super Admin',
      role: 'SUPER_ADMIN',
      tenantId: null,
    },
    failOnStatusCode: false,
  });
  expect(forbiddenSuperAdminCreate.status()).toBe(403);

  // PR #105 regression: a tenant admin must not be able to inspect a global
  // SUPER_ADMIN target through the non-tenanted assignment repository.
  const foreignTargetRead = await tenantAdminCtx.request.get(
    `/api/proxy/admin/users/${ctx.user.id}/properties`,
    { failOnStatusCode: false },
  );
  expect(foreignTargetRead.status()).toBeGreaterThanOrEqual(400);
  expect(foreignTargetRead.status()).toBeLessThan(500);

  const managedEmail = `test-managed-pm-${ctx.runSuffix}@e2e.rentaxis.test`;
  const managedPassword = 'TestManaged!23';
  const managedNewPassword = 'TestManagedNew!24';
  const managed = await api.createUser(tenantAdminCtx, ctx.tenant.id, {
    name: `TEST-Managed PM ${ctx.runSuffix}`,
    email: managedEmail,
    password: managedPassword,
    role: 'PROPERTY_MANAGER',
  });
  await api.assignUserToProperty(tenantAdminCtx, managed.id, ctx.property.id);

  const assignedResponse = await tenantAdminCtx.request.get(
    `/api/proxy/admin/users/${managed.id}/properties`,
    { failOnStatusCode: false },
  );
  await assertOk(assignedResponse, 'managed user property listing');
  expect(await assignedResponse.json()).toEqual([ctx.property.id]);

  const updatedResponse = await tenantAdminCtx.request.put(
    `/api/proxy/admin/users/${managed.id}`,
    {
      data: {
        email: managedEmail,
        password: null,
        name: `TEST-Managed PM Updated ${ctx.runSuffix}`,
        role: 'PROPERTY_MANAGER',
        tenantId: ctx.tenant.id,
        phoneNumber: '+971500000009',
        propertyIds: [ctx.property.id],
      },
      failOnStatusCode: false,
    },
  );
  await assertOk(updatedResponse, 'managed user update');
  expect((await updatedResponse.json()).name).toContain('Updated');

  const usersResponse = await tenantAdminCtx.request.get('/api/proxy/admin/users', {
    failOnStatusCode: false,
  });
  await assertOk(usersResponse, 'tenant user listing');
  const users = (await usersResponse.json()) as Array<{ id: string; tenantId: string }>;
  expect(users.find((user) => user.id === managed.id)?.tenantId).toBe(ctx.tenant.id);

  ctx.managedUser = {
    id: managed.id,
    email: managedEmail,
    password: managedPassword,
    newPassword: managedNewPassword,
  };
  fs.writeFileSync(CONTEXT_FILE, JSON.stringify(ctx, null, 2));

  await tenantAdminCtx.request.dispose();
  await superCtx.request.dispose();
});

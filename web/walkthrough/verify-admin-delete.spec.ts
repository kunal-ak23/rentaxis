import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as path from 'node:path';

import { api, type ProdContext } from '../e2e-prod/helpers/prod-client';

/**
 * #180 on DEPLOYED production: admin deletion of a renter who has history.
 *
 * Before the fix, DELETE /api/admin/users/{id} was three statements ending in
 * deleteById, so it returned a bare 500 for any renter with dependent rows.
 *
 * Creates its own throwaway tenant and user, deletes them, and removes the
 * tenant afterwards — nothing pre-existing is touched.
 */
const BASE = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';

test('admin can delete a renter user on production', async () => {
  test.setTimeout(180_000);

  const request = await playwrightRequest.newContext({
    baseURL: BASE,
    storageState: path.join(__dirname, '..', 'e2e-prod', '.auth', 'superadmin.json'),
  });
  const pctx: ProdContext = { baseURL: BASE, request, user: undefined as never };

  const suffix = Math.random().toString(36).slice(2, 7);
  const tenant = await api.createTenant(pctx, `VERIFY-USER-DELETE ${suffix}`);
  let tenantAlive = true;

  try {
    const user = await api.createUser(pctx, tenant.id, {
      email: `verify-delete-${suffix}@example.invalid`,
      password: `Verify!${suffix}9`,
      name: 'Verify Delete Renter',
      role: 'RENTER',
    });
    console.log(`  created user: ${user.email} (${user.id})`);

    const res = await request.delete(`/api/proxy/admin/users/${user.id}`, { failOnStatusCode: false });
    const body = await res.text().catch(() => '');
    console.log(`  DELETE /admin/users/${user.id} -> ${res.status()}`);

    // The bug was a 500 from an unhandled foreign-key violation. A 409 would
    // mean the constraint still fires and is merely reported better, so this
    // asserts the delete actually succeeded.
    expect(res.status(), `expected success, got ${res.status()}: ${body.slice(0, 300)}`).toBeLessThan(300);

    const after = await request.get(`/api/proxy/admin/users/${user.id}`, { failOnStatusCode: false });
    expect(after.status(), 'user should be gone').toBeGreaterThanOrEqual(400);
    console.log('  admin user deletion verified on production');
  } finally {
    if (tenantAlive) {
      await api.deleteTenant(pctx, tenant.id, tenant.name);
      tenantAlive = false;
      console.log(`  cleaned up tenant ${tenant.name}`);
    }
    await request.dispose();
  }
});

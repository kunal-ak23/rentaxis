/**
 * 99 — Hard-delete the test tenant + all its data.
 *
 * Uses the new DELETE /api/admin/tenants/{id}?confirmName=... endpoint added
 * specifically for E2E cleanup. The `confirmName` safety check prevents a
 * wrong-UUID curl from blowing away a real tenant.
 *
 * Skip with SKIP_CLEANUP=1 to leave the tenant in prod for manual inspection
 * after a failed run.
 */
import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, ProdContext } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('delete test tenant', async () => {
  // Tenant deletion walks every tenant-scoped table and can approach the
  // suite-wide 60s default on production-sized schemas. Keep this bounded but
  // separate from the tighter functional-test timeout.
  test.setTimeout(180_000);
  test.skip(process.env.SKIP_CLEANUP === '1', 'SKIP_CLEANUP set');

  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.tenant?.id, 'no tenant in context — provisioning probably failed').toBeTruthy();

  const request = await playwrightRequest.newContext({
    baseURL: ctx.baseURL,
    storageState: path.join(__dirname, '..', '.auth', 'superadmin.json'),
  });
  const pctx: ProdContext = { baseURL: ctx.baseURL, request, user: ctx.user };

  await api.deleteTenant(pctx, ctx.tenant.id, ctx.tenant.name);

  // Confirm gone. GET features on the deleted tenant should now 404.
  const verify = await pctx.request.get(`/api/proxy/admin/tenants/${ctx.tenant.id}/features`);
  expect(verify.status(), 'tenant should be gone after delete').toBe(404);

  await request.dispose();
});

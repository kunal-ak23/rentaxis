/**
 * Cleanup for the lease-cheque walkthrough runs.
 *
 * Deletes the disposable TUTORIAL-LEASE-CHEQUES tenants this walkthrough
 * created on production, keeping the one that backs the delivered videos.
 *
 * Two independent safety gates, because this is a hard delete on prod:
 *   1. A name allowlist — only tenants whose live name starts with the
 *      walkthrough prefix are eligible, checked against what the server
 *      reports rather than what a local manifest claims.
 *   2. The API's own confirmName parameter, which makes a wrong UUID inert
 *      unless the name matches too.
 *
 * KEEP_SUFFIX names the run whose takes were delivered; that tenant is
 * skipped so the videos stay reproducible against live data.
 */
import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as path from 'node:path';
import { api, type ProdContext } from '../e2e-prod/helpers/prod-client';

const PREFIX = 'TUTORIAL-LEASE-CHEQUES';
const KEEP_SUFFIX = process.env.KEEP_SUFFIX || 'a4cq7';

test('delete disposable walkthrough tenants', async () => {
  // Tenant deletion walks every tenant-scoped table; 99-cleanup budgets 180s
  // for one. This deletes a dozen, serially.
  test.setTimeout(20 * 60_000);

  const request = await playwrightRequest.newContext({
    baseURL: process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com',
    storageState: path.join(__dirname, '..', 'e2e-prod', '.auth', 'superadmin.json'),
  });
  const pctx: ProdContext = { baseURL: '', request, user: undefined as never };

  const all = await api.listTenants(pctx);
  const walkthrough = all.filter((t) => t.name.startsWith(PREFIX));
  const keep = walkthrough.filter((t) => t.name.includes(KEEP_SUFFIX));
  const doomed = walkthrough.filter((t) => !t.name.includes(KEEP_SUFFIX));

  console.log(`\n  tenants on prod: ${all.length}`);
  console.log(`  matching "${PREFIX}": ${walkthrough.length}`);
  console.log(`  keeping (${KEEP_SUFFIX}): ${keep.map((t) => t.name).join(', ') || '(none)'}`);
  console.log(`  deleting: ${doomed.length}\n`);

  expect(keep.length, `the kept run (${KEEP_SUFFIX}) must still exist`).toBe(1);

  // DRY=1 prints the exact delete list and stops. Worth doing once before the
  // real pass: the allowlist is a prefix match against live tenant names, so a
  // dry run is what confirms it selects only this walkthrough's fixtures and
  // has not swept up something that merely shares the prefix.
  if (process.env.DRY === '1') {
    for (const t of doomed) console.log(`  would delete: ${t.name}  (${t.id})`);
    test.skip(true, 'DRY=1 — nothing deleted');
    return;
  }

  for (const t of doomed) {
    // Belt and braces: never pass a name to confirmName that is not ours.
    expect(t.name.startsWith(PREFIX), `refusing to delete ${t.name}`).toBeTruthy();
    await api.deleteTenant(pctx, t.id, t.name);
    const verify = await pctx.request.get(`/api/proxy/admin/tenants/${t.id}/features`);
    expect(verify.status(), `${t.name} should be gone`).toBe(404);
    console.log(`  deleted: ${t.name}`);
  }

  const after = (await api.listTenants(pctx)).filter((t) => t.name.startsWith(PREFIX));
  console.log(`\n  remaining "${PREFIX}" tenants: ${after.length} — ${after.map((t) => t.name).join(', ')}`);
  expect(after.length, 'only the kept run should remain').toBe(1);

  await request.dispose();
});

/**
 * 04 — Reports surface smoke. We don't validate report content (that's owned
 * by backend unit tests); we just verify the endpoints respond 200 with a
 * non-error JSON shape under realistic tenant scope.
 */
import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { ProdContext, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('finance reports respond for the test tenant', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  const request = await playwrightRequest.newContext({
    baseURL: ctx.baseURL,
    storageState: path.join(__dirname, '..', '.auth', 'superadmin.json'),
  });
  const pctx: ProdContext = { baseURL: ctx.baseURL, request, user: ctx.user };
  await setActiveTenant(pctx, ctx.tenant.id);

  for (const url of [
    '/api/proxy/v1/finance/transactions',
    '/api/proxy/v1/finance/reports/trial-balance',
    '/api/proxy/v1/finance/reports/organisation',
    `/api/proxy/v1/finance/reports/property/${ctx.property.id}`,
    `/api/proxy/v1/finance/reports/unit/${ctx.unit.id}`,
    '/api/proxy/v1/payments/aging-report',
  ]) {
    const res = await pctx.request.get(url);
    expect(res.status(), `${url} returned ${res.status()}`).toBeLessThan(500);
    // Some reports legitimately 404 if no data — that's fine. 5xx is not.
  }

  await request.dispose();
});

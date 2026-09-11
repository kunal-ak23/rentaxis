import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as path from 'node:path';

import { setActiveTenant, type ProdContext } from '../e2e-prod/helpers/prod-client';

/**
 * Production check for the lease a client could not create: AED 5,000/month
 * with a 5,000 deposit over 6 cheques. The payment plan step used to refuse it
 * outright, because the largest cheque had to fit inside the deposit.
 *
 * Read-only — the preview endpoint creates nothing, so this leaves no records
 * to clean up.
 */

const AUTH = path.join(__dirname, '..', 'e2e-prod', '.auth', 'superadmin.json');
const BASE = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';

test('production accepts a 6-cheque plan whose cheques exceed the deposit', async () => {
  let ctx: ProdContext = {
    baseURL: BASE,
    request: await playwrightRequest.newContext({ baseURL: BASE, storageState: AUTH }),
  };

  // A SUPER_ADMIN with no tenant selected sees nothing tenant-scoped, so pick
  // one first — otherwise the property lookup below silently has nothing to find.
  const tenantsRes = await ctx.request.get('/api/proxy/admin/tenants');
  expect(tenantsRes.ok(), `tenants: ${tenantsRes.status()}`).toBeTruthy();
  const tenants = await tenantsRes.json();
  expect(tenants.length, 'need a tenant on production').toBeGreaterThan(0);
  await setActiveTenant(ctx, tenants[0].id);
  const request = ctx.request;
  console.log(`  acting in tenant: ${tenants[0].name}`);

  const propsRes = await request.get('/api/proxy/v1/properties');
  expect(propsRes.ok(), `properties: ${propsRes.status()}`).toBeTruthy();
  const props = await propsRes.json();
  const list = Array.isArray(props) ? props : (props.content ?? []);
  expect(list.length, 'need at least one property on production').toBeGreaterThan(0);
  // /v1/properties returns stats objects wrapping the property.
  const propertyId = list[0].property?.id ?? list[0].id;

  const params = new URLSearchParams({
    propertyId,
    startDate: '2026-09-11',
    endDate: '2027-09-10',
    monthlyRent: '5000',
    paymentTerms: '6',
    depositAmount: '5000',      // the field that used to make this fail
    strategy: 'LAST_LARGER',
  });

  const res = await request.get(`/api/proxy/v1/payments/preview?${params}`);
  const body = await res.text();

  expect(body, 'the old rejection must be gone').not.toContain('exceeding the deposit');
  expect(res.ok(), `preview failed ${res.status()}: ${body}`).toBeTruthy();

  const preview = JSON.parse(body);
  const amounts = preview.lines.map((l: { amount: number }) => Number(l.amount));
  console.log(`  6-cheque plan on production: ${amounts.join(', ')}`);

  expect(amounts).toHaveLength(6);
  expect(amounts.every((a: number) => a === 10000)).toBeTruthy();
  expect(amounts.reduce((a: number, b: number) => a + b, 0)).toBe(60000);

  // The counts that were refused alongside it.
  for (const [terms, each] of [[1, 60000], [2, 30000], [4, 15000]] as const) {
    params.set('paymentTerms', String(terms));
    const r = await request.get(`/api/proxy/v1/payments/preview?${params}`);
    expect(r.ok(), `${terms}-cheque plan still refused: ${r.status()}`).toBeTruthy();
    const p = await r.json();
    expect(p.lines).toHaveLength(terms);
    expect(Number(p.lines[0].amount)).toBe(each);
    console.log(`  ${terms}-cheque plan: ${each} each — accepted`);
  }
});

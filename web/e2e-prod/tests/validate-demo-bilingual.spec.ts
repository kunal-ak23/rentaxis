import { test, expect } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { loginAsNextAuth, setActiveTenant, ProdContext } from '../helpers/prod-client';

const BASE_URL = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';
const STATE = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../../tutorials/state/miftah-demo-tutorial-mtbzmkbe.json'), 'utf8'));
const SECRETS = JSON.parse(fs.readFileSync(path.resolve(__dirname, '../../../tutorials/state/miftah-demo-tutorial-mtbzmkbe-secrets.local.json'), 'utf8'));

async function getJson<T>(ctx: ProdContext, endpoint: string): Promise<T> {
  const response = await ctx.request.get(`/api/proxy${endpoint}`);
  expect(response.ok(), `${endpoint} should be readable`).toBeTruthy();
  return response.json() as Promise<T>;
}

test('demo listing and property expose English and Arabic fields', async () => {
  const ctx = await loginAsNextAuth(BASE_URL, SECRETS.users.managerEmail, SECRETS.password);
  await setActiveTenant(ctx, STATE.tenant.id);
  const listing = await getJson<Record<string, unknown>>(ctx, `/listings/${STATE.listing.id}`);
  const property = await getJson<Record<string, unknown>>(ctx, `/v1/properties/${STATE.property.id}`);
  expect(listing.titleEn).toBe('Bright Marina One-Bedroom Retreat');
  expect(listing.titleAr).toBe('شقة مشرقة بغرفة نوم واحدة في المارينا');
  expect(listing.descriptionEn).toContain('sunlit');
  expect(listing.descriptionAr).toContain('مشرق');
  expect(property.nameEn).toBe('Marina Oasis Residences');
  expect(property.nameAr).toBe('مساكن واحة المارينا');
  console.log('Bilingual API fields verified for listing and property.');
  await ctx.request.dispose();
});

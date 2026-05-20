/**
 * 03 — Vendor CRUD + lease interaction logging.
 *
 * Two surfaces in one spec because both are lightweight CRUD smoke and
 * keeping the spec count manageable matters more than 1-spec-per-controller.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('vendor create + interaction log + interaction list', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.lease?.id, '01-provision must run first').toBeTruthy();

  // Both vendor creation (canAccessFinance) and lease interactions
  // (TENANT_ADMIN | PROPERTY_MANAGER) are TA-grade operations in the real
  // product. Logging in as TA exercises the exact role a real org admin
  // would use — and exposed exactly this kind of role-only restriction
  // when an earlier draft used SUPER_ADMIN for interactions and got 403.
  const taCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(taCtx, ctx.tenant.id);

  // --- Vendor --------------------------------------------------------------
  const vendor = await api.createVendor(taCtx, {
    name: `TEST-Vendor ${ctx.runSuffix}`,
  });
  expect(vendor.id).toBeTruthy();

  // --- Interaction ---------------------------------------------------------
  const logged = await api.logInteraction(taCtx, ctx.lease.id, {
    type: 'CALL',
    direction: 'OUTBOUND',
    summary: `e2e interaction smoke ${ctx.runSuffix}`,
  });
  expect(logged.id).toBeTruthy();

  // GET returns Page<InteractionDTO>, not a bare array.
  const listRes = await taCtx.request.get(`/api/proxy/v1/leases/${ctx.lease.id}/interactions`);
  expect(listRes.ok()).toBeTruthy();
  const page = await listRes.json();
  const items: Array<{ id: string }> = page.content ?? page;
  expect(items.some((i) => i.id === logged.id)).toBeTruthy();

  await taCtx.request.dispose();
});

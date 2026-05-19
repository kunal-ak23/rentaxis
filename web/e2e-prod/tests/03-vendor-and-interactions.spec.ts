/**
 * 03 — Vendor CRUD + lease interaction logging.
 *
 * Two surfaces in one spec because both are lightweight CRUD smoke and
 * keeping the spec count manageable matters more than 1-spec-per-controller.
 */
import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, ProdContext, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('vendor create + interaction log + interaction list', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.lease?.id, '01-provision must run first').toBeTruthy();

  const request = await playwrightRequest.newContext({
    baseURL: ctx.baseURL,
    storageState: path.join(__dirname, '..', '.auth', 'superadmin.json'),
  });
  const pctx: ProdContext = { baseURL: ctx.baseURL, request, user: ctx.user };
  await setActiveTenant(pctx, ctx.tenant.id);

  // --- Vendor --------------------------------------------------------------
  const vendor = await api.createVendor(pctx, {
    name: `TEST-Vendor ${ctx.runSuffix}`,
    category: 'MAINTENANCE',
  });
  expect(vendor.id).toBeTruthy();

  // --- Interaction ---------------------------------------------------------
  const logged = await api.logInteraction(pctx, ctx.lease.id, {
    type: 'CALL',
    notes: `e2e interaction smoke ${ctx.runSuffix}`,
  });
  expect(logged.id).toBeTruthy();

  const list = await api.listInteractions(pctx, ctx.lease.id);
  expect(list.some((i) => i.id === logged.id)).toBeTruthy();

  await request.dispose();
});

/**
 * 13f — Tenant-scoped renewal opportunity, reminder, renter intent, and close.
 * Requires PR #99 so the scan cannot process unrelated opted-in tenants.
 */
import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, ProdContext } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('renter responds to a renewal reminder and admin closes the opportunity', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  const superAdminRequest = await playwrightRequest.newContext({
    baseURL: ctx.baseURL,
    storageState: path.join(__dirname, '..', '.auth', 'superadmin.json'),
  });
  const superAdminCtx: ProdContext = {
    baseURL: ctx.baseURL,
    request: superAdminRequest,
    user: ctx.user,
  };
  const renterCtx = await loginAsNextAuth(
    ctx.baseURL,
    ctx.renter.email,
    ctx.renter.portalPassword,
  );
  const taCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);

  // The tenant id is part of the endpoint path. Do not replace this with the
  // unscoped /run-now operation, which processes every opted-in organization.
  await api.triggerRenewalScan(superAdminCtx, ctx.tenant.id);

  const initial = (await api.getMyRenewals(renterCtx)).leases.find(
    (item) => item.leaseId === ctx.lease.id,
  );
  expect(initial).toBeTruthy();
  expect(initial!.daysRemaining).toBeGreaterThan(0);
  expect(initial!.daysRemaining).toBeLessThanOrEqual(60);
  expect(initial!.opportunityId).toBeTruthy();
  expect(initial!.stage).toBe('OPEN');
  expect(initial!.reminders.some((item) => item.slot === 60 && item.status === 'SENT')).toBeTruthy();

  const intent = await api.setRenewalIntent(renterCtx, initial!.opportunityId!, 'RENEW');
  expect(intent).toEqual({ intent: 'RENEW', stage: 'INTENT_CAPTURED' });

  const captured = (await api.getMyRenewals(renterCtx)).leases.find(
    (item) => item.leaseId === ctx.lease.id,
  );
  expect(captured?.intent).toBe('RENEW');
  expect(captured?.stage).toBe('INTENT_CAPTURED');

  const closed = await api.markLeaseRenewed(
    taCtx,
    ctx.lease.id,
    `TEST-E2E renewal acknowledged ${ctx.runSuffix}`,
  );
  expect(closed.stage).toBe('CLOSED_WON');
  expect(closed.outcome).toBe('RENEWED');
  expect(closed.closedAt).toBeTruthy();

  const afterClose = (await api.getMyRenewals(renterCtx)).leases.find(
    (item) => item.leaseId === ctx.lease.id,
  );
  expect(afterClose?.opportunityId).toBeNull();
  expect(afterClose?.stage).toBeNull();

  const interactions = await api.listInteractions(taCtx, ctx.lease.id);
  expect(
    interactions.some((item) => item.summary.includes('TEST-E2E renewal acknowledged')),
  ).toBeTruthy();

  await superAdminRequest.dispose();
  await renterCtx.request.dispose();
  await taCtx.request.dispose();
});

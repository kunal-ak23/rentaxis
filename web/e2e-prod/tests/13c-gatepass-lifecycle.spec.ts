/**
 * 13c — Resident, security guard, and manager gate-pass lifecycle.
 * Runs before settlement while the renter still owns an ACTIVE lease.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('renter issues a pass, guard approves and scans it, manager sees the report', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.guardUserId, '01-provision must persist the security guard').toBeTruthy();

  const renterCtx = await loginAsNextAuth(
    ctx.baseURL,
    ctx.renter.email,
    ctx.renter.portalPassword,
  );
  const guardCtx = await loginAsNextAuth(ctx.baseURL, ctx.guardEmail, ctx.guardPassword);
  const taCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(guardCtx, ctx.tenant.id);
  await setActiveTenant(taCtx, ctx.tenant.id);

  expect((await api.getGuardProperties(guardCtx)).some((item) => item.id === ctx.property.id)).toBeTruthy();

  const validFrom = new Date(Date.now() - 5 * 60 * 1000).toISOString();
  const validTo = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
  const gatePass = await api.createGatePass(renterCtx, {
    unitId: ctx.unit.id,
    validFrom,
    validTo,
    guestName: `TEST-Guest ${ctx.runSuffix}`,
  });
  expect(gatePass.status).toBe('PENDING_APPROVAL');
  expect(gatePass.numericCode).toMatch(/^\d{8}$/);
  expect(gatePass.qrToken).toHaveLength(48);
  expect((await api.getMyGatePasses(renterCtx)).some((item) => item.id === gatePass.id)).toBeTruthy();

  const approvalResponse = await guardCtx.request.get('/api/proxy/v1/gatepass/approvals');
  expect(approvalResponse.ok()).toBeTruthy();
  const approvalPayload: Array<Record<string, unknown>> = await approvalResponse.json();
  const summary = approvalPayload.find((item) => item.id === gatePass.id);
  expect(summary).toBeTruthy();
  expect(summary).not.toHaveProperty('qrToken');
  expect(summary).not.toHaveProperty('numericCode');

  expect((await api.approveGatePass(guardCtx, gatePass.id, true)).status).toBe('ACTIVE');
  expect((await api.getExpectedGatePasses(guardCtx)).some((item) => item.id === gatePass.id)).toBeTruthy();

  const entry = await api.scanGatePass(guardCtx, {
    numericCode: gatePass.numericCode,
    direction: 'ENTRY',
  });
  expect(entry.result).toBe('ALLOWED');
  expect(entry.guestName).toContain('TEST-Guest');

  const exit = await api.scanGatePass(guardCtx, {
    qrToken: gatePass.qrToken,
    direction: 'EXIT',
  });
  expect(exit.result).toBe('ALLOWED');

  const report = await api.getGatePassReport(
    taCtx,
    new Date(Date.now() - 60 * 60 * 1000).toISOString(),
    new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    ctx.property.id,
  );
  expect(report.filter((row) => row.gatePassId === gatePass.id)).toHaveLength(2);

  expect((await api.cancelGatePass(renterCtx, gatePass.id)).status).toBe('CANCELLED');

  await renterCtx.request.dispose();
  await guardCtx.request.dispose();
  await taCtx.request.dispose();
});

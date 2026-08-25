/**
 * 13c — Automated resident and manager gate-pass lifecycle around the guard boundary.
 * Live Firebase SMS authentication and guard scanning stay a controlled device check.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('admin configures gate access while renter and manager approve a pass', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.guardUserId, '01-provision must persist the security guard').toBeTruthy();

  const renterCtx = await loginAsNextAuth(
    ctx.baseURL,
    ctx.renter.email,
    ctx.renter.portalPassword,
  );
  const taCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(taCtx, ctx.tenant.id);

  expect(await api.getAssignedGuardPropertyIds(taCtx, ctx.guardUserId)).toContain(ctx.property.id);

  const initialPolicy = await api.getEffectiveGatePolicy(taCtx, ctx.property.id);
  expect(initialPolicy.propertyId).toBe(ctx.property.id);

  const policy = await api.setGatePolicy(taCtx, ctx.property.id, {
    requireUnregisteredApproval: true,
    requireRegisteredApproval: false,
    notifyRegisteredEntry: true,
    requireFreshPhoto: true,
    approvalTimeoutMinutes: 20,
  });
  expect(policy).toMatchObject({
    propertyId: ctx.property.id,
    inherited: false,
    requireUnregisteredApproval: true,
    requireRegisteredApproval: false,
    notifyRegisteredEntry: true,
    requireFreshPhoto: true,
    approvalTimeoutMinutes: 20,
  });
  expect(await api.getEffectiveGatePolicy(taCtx, ctx.property.id)).toMatchObject({
    requireUnregisteredApproval: true,
    approvalTimeoutMinutes: 20,
  });

  const registeredPhone = '+971500009991';
  const registration = await api.createManagedVisitorRegistration(taCtx, {
    propertyId: ctx.property.id,
    unitId: ctx.unit.id,
    name: `TEST-Registered Vendor ${ctx.runSuffix}`,
    phone: registeredPhone,
    visitorType: 'SERVICE_VENDOR',
    validFrom: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
    validTo: new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString(),
    active: true,
  });
  expect(registration.registeredForSelectedUnit).toBeTruthy();
  expect(registration.visitorType).toBe('SERVICE_VENDOR');

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

  const approvalPayload = await api.getGatePassApprovals(taCtx);
  const summary = approvalPayload.find((item) => item.id === gatePass.id);
  expect(summary).toBeTruthy();
  expect(summary).not.toHaveProperty('qrToken');
  expect(summary).not.toHaveProperty('numericCode');

  expect((await api.approveGatePass(taCtx, gatePass.id, true)).status).toBe('ACTIVE');

  const report = await api.getGatePassReport(
    taCtx,
    new Date(Date.now() - 60 * 60 * 1000).toISOString(),
    new Date(Date.now() + 60 * 60 * 1000).toISOString(),
    ctx.property.id,
  );
  expect(report.filter((row) => row.gatePassId === gatePass.id)).toHaveLength(0);

  expect((await api.cancelGatePass(renterCtx, gatePass.id)).status).toBe('CANCELLED');

  await renterCtx.request.dispose();
  await taCtx.request.dispose();
});

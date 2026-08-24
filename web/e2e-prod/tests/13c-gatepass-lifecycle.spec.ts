/**
 * 13c — Resident, security guard, and manager gate-pass lifecycle.
 * Runs before settlement while the renter still owns an ACTIVE lease.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('admin configures gate access while renter and guard complete a pass lifecycle', async () => {
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

  const destinations = await api.getWalkInDestinations(guardCtx, ctx.property.id);
  expect(destinations.some((item) => item.unitId === ctx.unit.id)).toBeTruthy();

  const registeredPhone = '+971500009991';
  const registration = await api.createManagedVisitorRegistration(taCtx, {
    propertyId: ctx.property.id,
    unitId: ctx.unit.id,
    name: `TEST-Registered Vendor ${ctx.runSuffix}`,
    phone: registeredPhone,
    visitorType: 'VENDOR',
    validFrom: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
    validTo: new Date(Date.now() + 30 * 24 * 60 * 60 * 1000).toISOString(),
    active: true,
  });
  expect(registration.registeredForSelectedUnit).toBeTruthy();
  expect(registration.visitorType).toBe('VENDOR');

  const lookup = await api.lookupWalkInVisitor(
    guardCtx,
    ctx.property.id,
    ctx.unit.id,
    registeredPhone,
  );
  expect(lookup).toMatchObject({
    id: registration.id,
    visitorType: 'VENDOR',
    registeredForSelectedUnit: true,
  });

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

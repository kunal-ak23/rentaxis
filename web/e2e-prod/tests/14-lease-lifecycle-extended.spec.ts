/**
 * 14 — Extended lease lifecycle after all payment-dependent smoke checks.
 * Previews the generated contract without persisting a blob, extends the lease,
 * drafts a settlement with deduction and addition lines, and finalizes it.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('tenant admin previews contract, extends, and settles the active lease', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.lease?.id, '01-provision must run first').toBeTruthy();

  const taCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(taCtx, ctx.tenant.id);

  const previewPdf = await taCtx.request.post(
    `/api/proxy/v1/leases/${ctx.lease.id}/generate-contract/preview`,
    { failOnStatusCode: false },
  );
  expect(previewPdf.ok()).toBeTruthy();
  expect(previewPdf.headers()['content-type']).toContain('application/pdf');
  expect((await previewPdf.body()).subarray(0, 4).toString()).toBe('%PDF');

  const currentEndDate = new Date();
  currentEndDate.setFullYear(currentEndDate.getFullYear() + 1);
  const extendedEndDate = new Date(currentEndDate);
  extendedEndDate.setMonth(extendedEndDate.getMonth() + 2);
  const newEndDate = extendedEndDate.toISOString().slice(0, 10);

  const extended = await api.extendLease(taCtx, ctx.lease.id, newEndDate);
  expect(extended.status).toBe('ACTIVE');
  expect(extended.endDate).toBe(newEndDate);

  const settlementPreview = await api.getSettlementPreview(taCtx, ctx.lease.id);
  expect(settlementPreview.depositAmount).toBeGreaterThan(0);
  expect(settlementPreview.unpaidRentTotal).toBeGreaterThanOrEqual(0);

  const draft = await api.saveSettlementDraft(taCtx, ctx.lease.id, {
    notes: `TEST-E2E settlement ${ctx.runSuffix}`,
    deductions: [
      {
        category: 'CLEANING',
        description: 'TEST-Exit cleaning',
        amount: 50,
        autoCalculated: false,
        type: 'DEDUCTION',
      },
      {
        description: 'TEST-Prepaid utility credit',
        amount: 25,
        autoCalculated: false,
        type: 'ADDITION',
        additionCategory: 'UTILITY_OVERPAYMENT',
      },
    ],
  });
  expect(draft.status).toBe('DRAFT');
  expect(draft.totalDeductions).toBe(50);
  expect(draft.totalAdditions).toBe(25);
  expect(draft.deductions).toHaveLength(2);

  const saved = await api.getSettlement(taCtx, ctx.lease.id);
  expect(saved.id).toBe(draft.id);
  expect(saved.refundAmount).toBe(draft.refundAmount);

  const finalized = await api.finalizeSettlement(taCtx, ctx.lease.id);
  expect(finalized.status).toBe('TERMINATED');

  const events = await api.getLeaseEvents(taCtx, ctx.lease.id);
  expect(events.some((event) => event.notes.includes('Lease extended'))).toBeTruthy();
  expect(events.some((event) => event.newState === 'TERMINATED')).toBeTruthy();

  await taCtx.request.dispose();
});

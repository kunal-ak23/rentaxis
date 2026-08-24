/**
 * 10b — Draft lease administration before financial processing.
 *
 * Covers the non-file parts of the lease detail administration surface:
 * metadata edits, per-row payment-plan edits, bulk cheque attachment, and
 * draft deletion. The cheque image reference is deliberately synthetic and
 * points at no real blob, so this scenario cannot orphan a production file.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('tenant admin edits a draft payment plan, bulk-attaches a cheque, and deletes the draft', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.property?.id, '01-provision must run first').toBeTruthy();

  const adminCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(adminCtx, ctx.tenant.id);

  const unit = await api.createUnit(adminCtx, {
    propertyId: ctx.property.id,
    unitNumber: `TEST-DRAFT-${ctx.runSuffix}`,
    expectedRent: 4_500,
  });
  const renter = await api.createRenter(adminCtx, {
    nameEn: `TEST-Draft Renter ${ctx.runSuffix}`,
    email: `test-draft-renter-${ctx.runSuffix}@e2e.rentaxis.test`,
    createPortalAccount: false,
  });

  const start = new Date();
  start.setDate(start.getDate() + 7);
  const end = new Date(start);
  end.setFullYear(end.getFullYear() + 1);
  const startDate = start.toISOString().slice(0, 10);
  const endDate = end.toISOString().slice(0, 10);

  const created = await api.createLease(adminCtx, {
    unitId: unit.id,
    renterId: renter.id,
    startDate,
    endDate,
    rentAmount: 4_500,
    paymentTerms: 4,
  });
  expect(created.status).toBe('DRAFT');

  const original = await api.getLease(adminCtx, created.id);
  const agreementDate = new Date().toISOString().slice(0, 10);
  const updated = await api.updateDraftLease(adminCtx, created.id, {
    unitId: original.unitId,
    renterId: original.renterId,
    startDate: original.startDate,
    endDate: original.endDate,
    rentAmount: original.rentAmount,
    monthlyRent: original.monthlyRent,
    depositAmount: original.depositAmount,
    ejariNumber: `TEST-EJARI-${ctx.runSuffix}`,
    paymentTerms: original.paymentTerms,
    installmentDistribution: original.installmentDistribution,
    paymentMethod: original.paymentMethod,
    depositPaymentMethod: 'BANK_TRANSFER',
    paymentReferenceNumber: `TEST-REF-${ctx.runSuffix}`,
    agreementDate,
    rentVatApplicable: original.rentVatApplicable,
  });
  expect(updated.status).toBe('DRAFT');
  expect(updated.ejariNumber).toBe(`TEST-EJARI-${ctx.runSuffix}`);
  expect(updated.paymentReferenceNumber).toBe(`TEST-REF-${ctx.runSuffix}`);

  const schedule = await api.getPaymentScheduleForLease(adminCtx, created.id);
  const editableRows = schedule.filter(
    (row) => row.status === 'PENDING' && !row.isBookingDeposit && !row.isSecurityDeposit && !row.isCharge,
  );
  expect(editableRows.length).toBeGreaterThanOrEqual(2);

  const cashRow = editableRows[0];
  const editedSchedule = await api.updateLeasePaymentSchedule(adminCtx, created.id, [
    {
      scheduleId: cashRow.id,
      dueDate: cashRow.dueDate,
      amount: cashRow.amount,
      paymentMethod: 'CASH',
    },
  ]);
  expect(editedSchedule.find((row) => row.id === cashRow.id)).toMatchObject({
    paymentMethod: 'CASH',
    chequeNumber: null,
  });

  const chequeRow = editableRows[1];
  const syntheticBlobPath = `e2e/nonexistent/${ctx.runSuffix}.png`;
  const attached = await api.bulkAttachCheques(adminCtx, created.id, [
    {
      scheduleId: chequeRow.id,
      chequeNumber: `TEST-BULK-${ctx.runSuffix}`,
      chequeDate: chequeRow.dueDate,
      bankName: 'TEST-E2E Bank',
      payerName: `TEST-Draft Renter ${ctx.runSuffix}`,
      imageUrl: `https://example.invalid/${syntheticBlobPath}`,
      imageBlobPath: syntheticBlobPath,
      imageUploadedAt: new Date().toISOString(),
    },
  ]);
  expect(attached.schedules).toHaveLength(1);
  expect(attached.schedules[0]).toMatchObject({
    id: chequeRow.id,
    status: 'COLLECTED',
    chequeNumber: `TEST-BULK-${ctx.runSuffix}`,
    chequeImageBlobPath: syntheticBlobPath,
  });

  await api.deleteDraftLease(adminCtx, created.id);
  const deleted = await adminCtx.request.get(`/api/proxy/v1/leases/${created.id}`, {
    failOnStatusCode: false,
  });
  expect(deleted.status()).toBe(404);

  await adminCtx.request.dispose();
});

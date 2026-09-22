/**
 * 10b — Draft lease administration before financial processing.
 *
 * Covers the non-file parts of the lease detail administration surface:
 * metadata edits, a DRAFT cheque-grid re-save, bulk cheque attachment, and
 * draft deletion. The cheque image reference is deliberately synthetic and
 * points at no real blob, so this scenario cannot orphan a production file.
 *
 * accounting-v2 plan 2: the draft's payment plan is now the cheque grid
 * (`PUT /leases/{id}/cheques`, `POST /leases/{id}/cheques/bulk-attach`), cut
 * from `lines` rather than a flat rentAmount/paymentTerms body. Nothing here
 * posts — bulk attach only writes cheque number/bank/payer/date/image onto
 * existing DRAFT rows (`LeaseController#bulkAttachCheques`'s own doc).
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
    ejariNumber: `TEST-EJARI-${ctx.runSuffix}`,
    paymentTerms: original.paymentTerms,
    installmentDistribution: original.installmentDistribution,
    paymentMethod: original.paymentMethod,
    // v2's DraftPaymentMethod only admits CHEQUE | ONLINE — v1's BANK_TRANSFER
    // is no longer a value the wizard (or this helper) may SAVE a draft as.
    depositPaymentMethod: 'ONLINE',
    paymentReferenceNumber: `TEST-REF-${ctx.runSuffix}`,
    agreementDate,
    rentVatApplicable: original.rentVatApplicable,
    // Re-send the same lines the server handed back — updateDraftLease's
    // `PUT /leases/{id}` re-saves the whole draft, lines included.
    lines: original.lines.map((l) => ({ chargeTypeCode: l.chargeTypeCode, grossAmount: l.grossAmount })),
  });
  expect(updated.status).toBe('DRAFT');
  expect(updated.ejariNumber).toBe(`TEST-EJARI-${ctx.runSuffix}`);
  expect(updated.paymentReferenceNumber).toBe(`TEST-REF-${ctx.runSuffix}`);

  // The cheque grid is generated on the draft — still DRAFT-status rows,
  // since nothing has posted (ChequeGrid/`leaseApi.generateCheques`).
  await api.generateCheques(adminCtx, created.id, { installments: 4 });
  // Read the grid back rather than re-sending the generate response: the save
  // below is the whole table, and the generate response does not carry the
  // dates each row was born with.
  const grid = await api.getLeaseCheques(adminCtx, created.id);
  const editableRows = grid.filter((row) => row.status === 'DRAFT' && row.mode === 'PDC');
  expect(editableRows.length).toBeGreaterThanOrEqual(2);

  // `PUT /leases/{id}/cheques` re-saves the WHOLE grid, not one row — flip
  // just the first row's mode to CASH and resend every row unchanged. Every
  // column has to travel with it: a field the payload omits is cleared, and a
  // CASH row with no date is refused ("a CASH receipt needs the date it is
  // expected on").
  const cashRow = editableRows[0];
  const savedGrid = await api.saveLeaseCheques(
    adminCtx,
    created.id,
    grid.map((row) => ({
      id: row.id,
      seqNo: row.seqNo,
      postingDate: row.postingDate,
      chequeNumber: row.chequeNumber,
      chequeDate: row.chequeDate,
      payeeBank: row.payeeBank,
      narration: row.narration,
      amount: row.amount,
      mode: row.id === cashRow.id ? 'CASH' : (row.mode as 'PDC' | 'CASH' | 'TRANSFER' | 'ONLINE'),
    })),
  );
  expect(savedGrid.find((row) => row.id === cashRow.id)).toMatchObject({ mode: 'CASH' });

  const chequeRow = editableRows[1];
  const syntheticBlobPath = `e2e/nonexistent/${ctx.runSuffix}.png`;
  const attached = await api.bulkAttachCheques(adminCtx, created.id, [
    {
      scheduleId: chequeRow.id,
      chequeNumber: `TEST-BULK-${ctx.runSuffix}`,
      chequeDate: startDate,
      bankName: 'TEST-E2E Bank',
      payerName: `TEST-Draft Renter ${ctx.runSuffix}`,
      imageUrl: `https://example.invalid/${syntheticBlobPath}`,
      imageBlobPath: syntheticBlobPath,
      imageUploadedAt: new Date().toISOString(),
    },
  ]);
  expect(attached.cheques).toHaveLength(1);
  // A bulk attach never posts — status stays whatever it was (DRAFT here),
  // never a v1-style "COLLECTED".
  expect(attached.cheques[0]).toMatchObject({
    id: chequeRow.id,
    chequeNumber: `TEST-BULK-${ctx.runSuffix}`,
  });

  await api.deleteDraftLease(adminCtx, created.id);
  const deleted = await adminCtx.request.get(`/api/proxy/v1/leases/${created.id}`, {
    failOnStatusCode: false,
  });
  expect(deleted.status()).toBe(404);

  await adminCtx.request.dispose();
});

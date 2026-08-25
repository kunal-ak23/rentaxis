/**
 * 10a — Contract generation and renter signature lifecycle.
 *
 * Uses the lease created through the real UI in 10, then verifies PDF storage,
 * authenticated download, renter rejection, clean regeneration, and renter
 * acceptance. Production execution requires the exact-artifact cleanup in
 * PR #104 so deleting the disposable test tenant cannot orphan contract PDFs.
 */
import { test, expect, type APIResponse } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

async function expectOk(response: APIResponse, operation: string): Promise<void> {
  if (!response.ok()) {
    const body = await response.text().catch(() => '');
    throw new Error(`${operation} failed (${response.status()}): ${body.slice(0, 400)}`);
  }
}

test('renter rejects regenerated contract and then accepts the replacement', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.contractLease?.id, '10-lease-create-ui must run first').toBeTruthy();
  expect(ctx.contractLease?.renterPassword).toBeTruthy();

  const adminCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(adminCtx, ctx.tenant.id);

  const firstGenerate = await adminCtx.request.post(
    `/api/proxy/v1/leases/${ctx.contractLease.id}/generate-contract`,
    { failOnStatusCode: false },
  );
  await expectOk(firstGenerate, 'initial contract generation');
  const firstDocument = await firstGenerate.json();
  expect(firstDocument.type).toBe('CONTRACT');

  const firstDownload = await adminCtx.request.get(
    `/api/proxy/v1/leases/documents/${firstDocument.id}/download`,
    { failOnStatusCode: false },
  );
  await expectOk(firstDownload, 'contract download');
  expect(firstDownload.headers()['content-type']).toContain('application/pdf');
  expect((await firstDownload.body()).subarray(0, 4).toString()).toBe('%PDF');

  const renterCtx = await loginAsNextAuth(
    ctx.baseURL,
    ctx.contractLease.renterEmail,
    ctx.contractLease.renterPassword,
  );
  await setActiveTenant(renterCtx, ctx.tenant.id);

  const rejected = await renterCtx.request.put(
    `/api/proxy/v1/leases/${ctx.contractLease.id}/reject`,
    { data: {}, failOnStatusCode: false },
  );
  await expectOk(rejected, 'renter rejection');
  expect((await rejected.json()).status).toBe('DRAFT');

  const replacementGenerate = await adminCtx.request.post(
    `/api/proxy/v1/leases/${ctx.contractLease.id}/generate-contract`,
    { failOnStatusCode: false },
  );
  await expectOk(replacementGenerate, 'replacement contract generation');
  const replacementDocument = await replacementGenerate.json();
  expect(replacementDocument.id).not.toBe(firstDocument.id);

  const documents = await adminCtx.request.get(
    `/api/proxy/v1/leases/${ctx.contractLease.id}/documents`,
    { failOnStatusCode: false },
  );
  await expectOk(documents, 'contract document listing');
  const documentList = await documents.json();
  expect(documentList).toHaveLength(1);
  expect(documentList[0].id).toBe(replacementDocument.id);

  const accepted = await renterCtx.request.put(
    `/api/proxy/v1/leases/${ctx.contractLease.id}/accept`,
    { data: {}, failOnStatusCode: false },
  );
  await expectOk(accepted, 'renter acceptance');
  expect((await accepted.json()).status).toBe('ACTIVE');

  const events = await api.getLeaseEvents(adminCtx, ctx.contractLease.id);
  expect(events.some((event) => event.notes.includes('rejected by renter'))).toBeTruthy();
  expect(events.some((event) => event.notes.includes('accepted by renter'))).toBeTruthy();

  await renterCtx.request.dispose();
  await adminCtx.request.dispose();
});

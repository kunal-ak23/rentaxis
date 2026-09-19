/**
 * 02 — Cheque state transitions on the accounting-v2 register.
 *
 * Covers: deposit on two REGISTERED rows. `clear` and `bounce` are left
 * out — they emit ledger entries (CBR/PDR reversal) and are covered by
 * 13a-cheques-and-penalties once 13-finance-and-settings has seeded the
 * chart of accounts.
 *
 * There is no PENDING/"collect" step any more: 01-provision's
 * `postLeaseFlow` already generated and posted the cheque grid, so every
 * rent cheque on the lease starts REGISTERED. The four lifecycle endpoints
 * are now PUT/POST on /v1/cheques/{id}/{deposit|clear|bounce|replace}.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('cheque state transitions — deposit two REGISTERED rows', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.lease?.id, '01-provision must run first').toBeTruthy();
  expect(ctx.pmEmail, '01-provision must have created a PROPERTY_MANAGER').toBeTruthy();

  // Cheque deposit is performed by a PROPERTY_MANAGER in the real product —
  // see web/src/app/[locale]/dashboard/finance/cheques/collection/page.tsx.
  // Login as PM (a role we'd otherwise never exercise) so any PM-only
  // restriction on ChequeController surfaces here.
  const pctx = await loginAsNextAuth(ctx.baseURL, ctx.pmEmail, ctx.pmPassword);
  await setActiveTenant(pctx, ctx.tenant.id);

  const cheques = await api.getLeaseCheques(pctx, ctx.lease.id);
  expect(cheques.length, 'lease posting should have registered a cheque grid').toBeGreaterThan(0);

  const registered = cheques.filter((c) => c.status === 'REGISTERED' && c.mode === 'PDC');
  expect(registered.length, 'expected at least 2 REGISTERED PDC rows').toBeGreaterThanOrEqual(2);
  const [happyRow, bounceRow] = registered;

  // Row 1: deposit, leave at DEPOSITED.
  const deposited1 = await api.depositCheque(pctx, happyRow.id, { notes: 'TEST-E2E cheque deposited' });
  expect(deposited1.status).toMatch(/DEPOSITED/i);

  // Row 2: deposit too (bounce/clear are 13a's, once the chart of accounts exists).
  const deposited2 = await api.depositCheque(pctx, bounceRow.id, { notes: 'TEST-E2E cheque deposited' });
  expect(deposited2.status).toMatch(/DEPOSITED/i);

  await pctx.request.dispose();
});

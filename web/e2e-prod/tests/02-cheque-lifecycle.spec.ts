/**
 * 02 — A property manager moves cheques through the register.
 *
 * The full accounting lifecycle (post, deposit, clear, bounce, replace,
 * recognise, reconcile) lives in 13h-accounting-v2.spec.ts, on a contract of
 * its own and as the role that owns the books. This one keeps the check that
 * was always its real reason for existing: cheque collection is a
 * PROPERTY_MANAGER job in the product
 * (web/src/app/[locale]/dashboard/finance/cheques/collection/page.tsx), so a
 * PM-only restriction on ChequeController has to surface somewhere, and a
 * spec that banks cheques as an admin would never see it.
 *
 * It deposits two rows rather than one because 13a-cheques-and-penalties
 * clears one of them and bounces the other; that spec's own preconditions say
 * so.
 *
 * There is no PENDING/"collect" step any more: 01-provision's `postLeaseFlow`
 * already generated and posted the cheque grid, so every rent cheque on the
 * lease starts REGISTERED, and the transitions are PUT /v1/cheques/{id}/...
 *
 * The v1 note about a fresh tenant lacking account mappings — which is why
 * this spec used to stop before `clear` — is obsolete: `PropertyService`
 * calls `generateMissing` on create, so a property owns its account set from
 * the moment it exists (spec §5.1).
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('a property manager deposits two registered cheques', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.lease?.id, '01-provision must run first').toBeTruthy();
  expect(ctx.pmEmail, '01-provision must have created a PROPERTY_MANAGER').toBeTruthy();

  const pctx = await loginAsNextAuth(ctx.baseURL, ctx.pmEmail, ctx.pmPassword);
  await setActiveTenant(pctx, ctx.tenant.id);

  const cheques = await api.getLeaseCheques(pctx, ctx.lease.id);
  expect(cheques.length, 'lease posting should have registered a cheque grid').toBeGreaterThan(0);

  const registered = cheques.filter((c) => c.status === 'REGISTERED' && c.mode === 'PDC');
  expect(registered.length, 'expected at least 2 REGISTERED PDC rows').toBeGreaterThanOrEqual(2);
  const [happyRow, bounceRow] = registered;

  const deposited1 = await api.depositCheque(pctx, happyRow.id, { notes: 'TEST-E2E cheque deposited' });
  expect(deposited1.status).toMatch(/DEPOSITED/i);

  const deposited2 = await api.depositCheque(pctx, bounceRow.id, { notes: 'TEST-E2E cheque deposited' });
  expect(deposited2.status).toMatch(/DEPOSITED/i);

  // Read the register back rather than trusting the two responses: what the
  // next spec reaches for is the state of the grid, not the value a PUT
  // happened to echo.
  const afterwards = await api.getLeaseCheques(pctx, ctx.lease.id);
  const depositedIds = afterwards.filter((c) => c.status === 'DEPOSITED').map((c) => c.id).sort();
  expect(depositedIds).toEqual([happyRow.id, bounceRow.id].sort());

  await pctx.request.dispose();
});

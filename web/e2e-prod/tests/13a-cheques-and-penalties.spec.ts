/**
 * 13a — Cheque clear/bounce plus the penalty propose/approve/waive lifecycle.
 * Runs after 13-finance-and-settings has seeded the chart of accounts.
 *
 * accounting-v2 plan 2 replaced the v1 payment-schedule `mark-failed` (which
 * charged a fine automatically) with a worklist: a bounce alone charges
 * nothing — `PenaltyRuleEngine.onBounce` only auto-proposes once a lease has
 * crossed the org's bounce threshold (default 2), which this single-bounce
 * scenario never reaches — so this spec proposes penalties explicitly, the
 * way a property manager does from the lease's Penalties tab. Approving
 * writes a PEN journal and opens a CASH collection row on the register
 * (`PenaltyAssessmentService#approve`); waiving posts nothing. The renter's
 * `/penalties/mine` (`GET /v1/penalties/mine`) is APPROVED-only — a PROPOSED
 * or WAIVED fine never reaches the renter, unlike v1's payment-schedule
 * penalties endpoint, which had an "open" filter a renter could read.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('manager clears and fails cheques while renter sees paid and waived fines', async ({ browser }) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  const pmCtx = await loginAsNextAuth(ctx.baseURL, ctx.pmEmail, ctx.pmPassword);
  const taCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  const renterCtx = await loginAsNextAuth(
    ctx.baseURL,
    ctx.renter.email,
    ctx.renter.portalPassword,
  );

  const adminBrowser = await browser.newContext({ baseURL: ctx.baseURL });
  const adminPage = await adminBrowser.newPage();
  await adminPage.goto('/en/auth/login');
  await adminPage.locator('#login-email').fill(ctx.adminEmail);
  await adminPage.locator('#login-password').fill(ctx.adminPassword);
  await adminPage.getByRole('button', { name: /sign in|log in/i }).click();
  await adminPage.waitForURL(/\/dashboard(?!\/renter-portal)/, { timeout: 15_000 });
  await adminPage.goto('/en/dashboard/settings/fines');
  await expect(adminPage.getByRole('heading', { level: 1, name: 'Cheque-failure fines' })).toBeVisible();
  // VERIFY: Task 9's penalty-module fields (bouncesBeforePenalty,
  // autoProposeChequeReturn, autoProposeLatePayment) added a NumberInput and
  // two checkboxes to this page; if bouncesBeforePenalty renders before the
  // five fine amounts in the DOM, these indices shift by one. Confirmed
  // against the live page in 17b.
  const fineInputs = adminPage.locator('input[type="number"]');
  await expect(fineInputs.nth(0)).toHaveValue('500');
  await expect(fineInputs.nth(1)).toHaveValue('350');
  await expect(fineInputs.nth(2)).toHaveValue('750');
  await expect(fineInputs.nth(3)).toHaveValue('2');
  await expect(fineInputs.nth(4)).toHaveValue('25');

  const cheques = await api.getLeaseCheques(pmCtx, ctx.lease.id);
  const registered = cheques.filter((c) => c.status === 'REGISTERED' && c.mode === 'PDC');
  const deposited0 = cheques.find((c) => c.status === 'DEPOSITED');
  expect(deposited0, '02-cheque-lifecycle must leave DEPOSITED cheques').toBeTruthy();

  const cleared = await api.clearCheque(pmCtx, deposited0!.id, { notes: 'TEST-E2E cheque cleared' });
  expect(cleared.status).toBe('CLEARED');

  const receipt = await pmCtx.request.get(`/api/proxy/v1/cheques/${deposited0!.id}/receipt`, {
    failOnStatusCode: false,
  });
  expect(receipt.ok()).toBeTruthy();
  expect(receipt.headers()['content-type']).toContain('application/pdf');
  expect((await receipt.body()).subarray(0, 4).toString()).toBe('%PDF');

  // The second deposited row bounces. A single bounce never crosses the
  // org's default threshold (2) for PenaltyRuleEngine's own auto-propose, so
  // this proposes the fine by hand — the property manager's own path, from
  // the lease's Penalties tab (canProposePenalties admits PROPERTY_MANAGER).
  const others = cheques.filter((c) => c.status === 'DEPOSITED' && c.id !== deposited0!.id);
  expect(others.length, '02-cheque-lifecycle must leave a second DEPOSITED cheque to bounce').toBeGreaterThan(0);
  const bounceRow = others[0];
  const bounced = await api.bounceCheque(pmCtx, bounceRow.id, 'ACCOUNT_CLOSED', { notes: 'TEST-E2E account closed' });
  expect(bounced.status).toBe('BOUNCED');

  const proposed = await api.proposePenalty(pmCtx, {
    leaseId: ctx.lease.id,
    chequeId: bounceRow.id,
    reason: 'CHEQUE_RETURN',
    amount: 750,
    description: 'TEST-E2E account closed',
  });
  expect(proposed.status).toBe('PROPOSED');

  // A PROPOSED fine is finance still deciding — the renter cannot see it yet
  // (`GET /v1/penalties/mine` is APPROVED-only).
  const beforeApproval = await api.myPenalties(renterCtx);
  expect(beforeApproval.some((item) => item.id === proposed.id)).toBeFalsy();

  const approved = await api.approvePenalty(taCtx, proposed.id);
  expect(approved.status).toBe('APPROVED');
  expect(approved.collectionChequeId, 'approving opens a CASH collection row').toBeTruthy();

  const renterBrowser = await browser.newContext({ baseURL: ctx.baseURL });
  const renterPage = await renterBrowser.newPage();
  await renterPage.goto('/en/auth/login');
  await renterPage.locator('#login-email').fill(ctx.renter.email);
  await renterPage.locator('#login-password').fill(ctx.renter.portalPassword);
  await renterPage.getByRole('button', { name: /sign in|log in/i }).click();
  await renterPage.waitForURL(/\/dashboard\/renter-portal/, { timeout: 15_000 });
  await renterPage.goto('/en/dashboard/renter-portal/penalties');
  await expect(renterPage.getByRole('heading', { level: 1, name: 'Penalties' })).toBeVisible();
  // The badge is the PENALTY's own reason (CHEQUE_RETURN → "Cheque Return"),
  // not the cheque's failureReason — that only shows up in the description
  // column, as the free text `proposePenalty` sent.
  await expect(renterPage.getByText('Cheque Return', { exact: true }).first()).toBeVisible();
  await expect(renterPage.getByText('TEST-E2E account closed')).toBeVisible();
  // collectionStatus renders the cheque status label — REGISTERED, before the
  // collection row is receipted.
  await expect(renterPage.getByText('Registered', { exact: true }).last()).toBeVisible();

  // Collect the fine like any other cheque: the collection row is CASH, so
  // it clears straight from REGISTERED via `receive`, not `deposit`.
  const receivedCollection = await api.receiveCheque(taCtx, approved.collectionChequeId!, { notes: `TEST-PEN-${ctx.runSuffix}` });
  expect(receivedCollection.status).toBe('CLEARED');

  const afterCollection = await api.myPenalties(renterCtx);
  const paidPenalty = afterCollection.find((item) => item.id === proposed.id);
  expect(paidPenalty).toMatchObject({ status: 'APPROVED' });

  await renterPage.reload();
  await expect(renterPage.getByText('TEST-E2E account closed')).toBeVisible();
  await expect(renterPage.getByText('Cleared', { exact: true }).last()).toBeVisible();

  // A second fine, proposed and then WAIVED instead of approved. Deposit a
  // still-REGISTERED cheque first — `bounce` only accepts DEPOSITED/CLEARED
  // rows — then bounce it and propose the fine for it.
  expect(registered.length, 'a four-installment lease must retain a REGISTERED cheque for waiver coverage').toBeGreaterThan(0);
  const waiveRow = registered[0];
  await api.depositCheque(pmCtx, waiveRow.id);
  const bouncedForWaiver = await api.bounceCheque(pmCtx, waiveRow.id, 'SIGNATURE_MISMATCH', { notes: 'TEST-E2E signature mismatch' });
  expect(bouncedForWaiver.status).toBe('BOUNCED');

  const proposedForWaiver = await api.proposePenalty(pmCtx, {
    leaseId: ctx.lease.id,
    chequeId: waiveRow.id,
    reason: 'CHEQUE_RETURN',
    amount: 350,
    description: 'TEST-E2E signature mismatch',
  });
  expect(proposedForWaiver.status).toBe('PROPOSED');

  const waived = await api.waivePenalty(taCtx, proposedForWaiver.id, 'TEST-E2E bank confirmed an operational error');
  expect(waived.status).toBe('WAIVED');

  // Waived nothing posts, and — same as PROPOSED — a renter never sees it:
  // `/mine` stays APPROVED-only.
  const finalList = await api.myPenalties(renterCtx);
  expect(finalList.find((item) => item.id === proposedForWaiver.id)).toBeUndefined();

  const financeList = await api.listPenalties(taCtx, ctx.lease.id, 'WAIVED');
  expect(financeList.content.find((item) => item.id === proposedForWaiver.id)).toMatchObject({ status: 'WAIVED' });

  await adminBrowser.close();
  await renterBrowser.close();
  await pmCtx.request.dispose();
  await taCtx.request.dispose();
  await renterCtx.request.dispose();
});

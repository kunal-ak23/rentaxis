// Tutorial 21 — Tenant payments (Oasis Crest Properties, the Tenant Ahmed
// Hassan). Home: next payment 21,250 due 01/10/2026, in the grace period →
// View all payments; My Payments: amount due 21,250, cheque 5 (200104), its
// row's due date / payable / running total; Payment History: cheques 1–3
// cleared with a Receipt Voucher each, cheque 4 at the bank ("Nothing to pay").
//
// No online payment: the ruling is cheques, cash and bank transfer only, so
// snapshot pre21 (= base1005 plus rent_collection_settings.online_payment_enabled
// = false on Oasis Crest's two lettable properties, set via the rent-settings API
// off camera) shows no Pay Now. Read-only; the proof and capture restore pre21.
// Weights are the seconds of narration each scene covers (tutorials/work/acct/tts.sh).
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { expectCount, expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const historyRow = (page, n) => page.locator('[data-testid^="history-row-"]').filter({ hasText: `Cheque ${n}` }).first();

const scenes = [
  roleRouteScene('renter', '/en/dashboard/renter-portal', 'Next payment',
    'The home page shows the next payment and whether it is still within the grace period.', {
    weight: 13.12,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const next = page.getByText('Next payment', { exact: false }).first();
      await next.waitFor({ state: 'visible', timeout: navTimeoutMs });
      const card = page.locator('main').getByText('AED 21,250').first();
      await card.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(6.4);
      await pointAt(card);
      await at(10.4);
      await pointAt(page.getByText('Due · in grace period'));
    },
  }),
  stepScene('What is due',
    'The cheque the landlord banks next, with its due date, amount and the running total.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByRole('link', { name: /View all payments/ }).click();
      const total = page.getByTestId('amount-due-total');
      await total.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(total, '21,250.00', 'Amount due');
      await expectCount(page.getByRole('button', { name: 'Pay Now' }), 0, 'Pay Now buttons (no online payment)');
      const due = page.locator('[data-testid^="due-row-"]').first();
      await expectText(due, 'Cheque 5', 'Due row');
      await expectText(page.locator('main'), '200104', 'Next cheque number');
      await restPointer(page, 1300, 640);
      await at(4.2);
      await pointAt(total);
      await at(6.4);
      await pointAt(page.getByText(/Cheque 5 · 200104/));
      await at(10.0);
      await pointAt(due.getByText('Cheque 5'));
      await at(15.6);
      await pointAt(due.getByText('01/10/2026'));
      await at(17.4);
      await pointAt(due.getByText('AED 21,250').first());
      await at(19.2);
      await pointAt(page.locator('[data-testid^="due-running-"]').first());
    }, { weight: 21.05 }),
  stepScene('Payment history',
    'Cleared cheques carry a receipt voucher; a cheque at the bank needs nothing from the Tenant.',
    async (page) => {
      const at = sceneClock(page);
      const c4 = historyRow(page, 4);
      await expectText(c4, 'At the bank', 'Cheque 4 at the bank');
      for (const n of [1, 2, 3]) await historyRow(page, n).locator('[data-testid^="receipt-link-"]').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(0.6);
      await c4.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'end' }));
      await at(2.4);
      await pointAt(historyRow(page, 1));
      await at(3.6);
      await pointAt(historyRow(page, 2));
      await at(4.8);
      await pointAt(historyRow(page, 3));
      await at(6.0);
      await pointAt(historyRow(page, 1).locator('[data-testid^="receipt-link-"]'));
      await at(7.6);
      await pointAt(c4.locator('[data-testid^="at-bank-"]'));
      await at(12.4);
      await pointAt(historyRow(page, 3).locator('[data-testid^="receipt-link-"]'));
    }, { weight: 15.88 }),
  stepScene('What a Tenant can see',
    'What is due, what has moved, and the proof of what has been paid.',
    async (page) => {
      const at = sceneClock(page);
      await at(0.4);
      await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'smooth' }));
      await at(1.8);
      await pointAt(page.getByTestId('amount-due-total'));
      await at(4.4);
      await pointAt(page.getByText('Payment History', { exact: false }).first());
    }, { weight: 9.93 }),
];

export default { role: 'renter', anchored: true, scenes };

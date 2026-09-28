// Tutorial 12 — Administer an active tenancy contract. On Sara Mansour's
// M-1501 contract, posted in tutorial 34, the Company Admin reads the header
// actions and the More actions menu, adds a charge (a second parking bay:
// Parking Fee 750 from 1 October with its own Ejari number and one cheque),
// attaches a fictional move-in inspection report (capture/fixtures/) and logs
// an outbound call. The whole flow runs in one page.
//
// Add charge posts journals the app cannot undo, so the proof and the capture
// each start from the database snapshot `pre12` (restored by record-local.sh).
// Tenant email and phone on the overview are hidden (lib/page.mjs).
import path from 'node:path';
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { saraLeaseId } from '../lib/fixtures.mjs';
import { expectCount, expectText, pace } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const report = path.join(import.meta.dirname, '..', 'fixtures', 'm-1501-move-in-inspection.pdf');
const ejari = '0123-2026-000451';
const summary = 'Confirmed the second parking bay from 1 October and collected cheque 500406.';

const scenes = [
  // Weights are the seconds of narration each scene covers (measured from the
  // synthesized cues); paces put each action on its cue.
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${saraLeaseId}`, 'An active contract',
    'Record payment and Renew at the top; More actions holds everything else.', {
    weight: 22,
    afterNavigation: async (page) => {
      await expectText(page.getByTestId('lease-status'), 'Active', 'Contract status');
      await expectText(page.locator('main'), '140,000.00', 'Contract value');
      await pace(page, 8500);
      await pointAt(page.getByTestId('lease-status'));
      await pace(page, 1800);
      await pointAt(page.getByTestId('lease-actions-primary'));
      await pace(page, 2500);
      await page.getByTestId('lease-more-actions').click();
      const menu = page.getByRole('menu');
      await menu.waitFor({ state: 'visible' });
      for (const item of ['Extend Contract', 'Amend Lines', 'Add charge', 'Give notice', 'Terminate', 'Ledger']) {
        await menu.getByRole('menuitem', { name: item, exact: true }).waitFor({ state: 'visible' });
      }
      await pointAt(menu.getByRole('menuitem', { name: 'Extend Contract', exact: true }));
      await pace(page, 1500);
      await pointAt(menu.getByRole('menuitem', { name: 'Ledger', exact: true }));
      await pace(page, 1200);
      await page.keyboard.press('Escape');
      await menu.waitFor({ state: 'hidden' });
      await restPointer(page, 1100, 600);
    },
  }),
  stepScene('Add charge',
    'A second parking bay from 1 October: its own addendum, Ejari number, charge line and cheque.',
    async (page) => {
      await pace(page, 6500);
      await page.getByTestId('lease-more-actions').click();
      await page.getByRole('menuitem', { name: 'Add charge', exact: true }).click();
      await page.getByTestId('add-charge-effective-from').waitFor({ state: 'visible' });
      await pace(page, 1500);
      await page.getByTestId('add-charge-effective-from').fill('2026-10-01');
      await pace(page, 800);
      await page.locator('#add-charge-ejari').fill(ejari);
      await pace(page, 600);
      await page.locator('#add-charge-reason').fill('Second parking bay from October');
      await pace(page, 700);
      await page.getByTestId('lease-line-type-0').selectOption({ label: 'Parking Fee' });
      await page.getByTestId('lease-line-amount-0').fill('750');
      await pace(page, 2500);
      await page.getByLabel('Cheque No 1').fill('500406');
      await page.getByLabel('Date 1', { exact: true }).fill('2026-10-01');
      await page.getByLabel('Payee Bank 1').fill('Emirates NBD');
      await page.getByLabel('Amount 1', { exact: true }).fill('750');
      await expectText(page.getByTestId('add-charge-match'), 'Cheques match the contract value of 750.00', 'Addendum cheques');
      await pointAt(page.getByTestId('add-charge-match'));
    }, { weight: 30.8 }),
  stepScene('Six cheques',
    'The addendum posts its own journals: the contract value is now 140,750.',
    async (page) => {
      await page.getByRole('button', { name: /^add charge$/i }).last().click();
      await page.getByTestId('add-charge-effective-from').waitFor({ state: 'detached', timeout: navTimeoutMs });
      await page.locator('main').getByText('140,750.00', { exact: true }).first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.locator('main'), 'NUMBER OF CHEQUES 6', 'Cheque count');
      await restPointer(page, 1100, 600);
      await pointAt(page.getByText('Number of cheques', { exact: false }).first());
    }, { weight: 4.4 }),
  stepScene('Addenda and supporting documents',
    'The addendum with its journal and Ejari number, and the paperwork attached to the contract.',
    async (page) => {
      await page.getByTestId('lease-tab-documents').click();
      const addenda = page.getByTestId('lease-addenda');
      await addenda.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(addenda, ejari, 'Addendum Ejari');
      await expectText(addenda, 'Second parking bay from October', 'Addendum reason');
      await addenda.scrollIntoViewIfNeeded();
      await pace(page, 1200);
      await pointAt(addenda.getByText('ADD-', { exact: false }).first());
      await pace(page, 5500);
      const name = page.getByPlaceholder('e.g. Emirates ID, Trade License, Agreement');
      await pointAt(name);
      await pace(page, 2500);
      await name.fill('Move-in inspection report');
      await pace(page, 900);
      await pointAt(page.locator('main label').filter({ hasText: 'Attach file' }));
      await page.locator('main input[type=file]').setInputFiles(report);
      const doc = page.locator('main').getByText('Move-in inspection report', { exact: true });
      await doc.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(page.getByTestId('lease-doc-error'), 0, 'upload errors');
      await pace(page, 800);
      await pointAt(doc);
    }, { weight: 17.6 }),
  stepScene('Log interaction',
    'A factual note of the call: who logged it, when, and the outcome.',
    async (page) => {
      await page.getByTestId('lease-tab-activity').click();
      await page.getByRole('button', { name: /Log interaction/i }).click();
      const dialog = page.getByRole('dialog');
      await dialog.waitFor({ state: 'visible' });
      await pace(page, 1500);
      await dialog.locator('#log-type').selectOption('CALL');
      await dialog.getByLabel('Outbound', { exact: true }).check();
      await pace(page, 600);
      await dialog.locator('#log-summary').fill(summary);
      await pace(page, 600);
      await dialog.locator('#log-outcome').selectOption('POSITIVE');
      await pace(page, 600);
      await dialog.getByRole('button', { name: 'Save', exact: true }).click();
      await dialog.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      const note = page.getByText(summary, { exact: true });
      await note.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(note.locator('xpath=..'), 'Noura Al Suwaidi', 'Interaction author');
      await restPointer(page, 1100, 700);
      await pointAt(note);
    }, { weight: 22.5 }),
];

export default { role: 'tenantAdmin', scenes };

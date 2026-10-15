// Tutorial 19 — Vendors and bank accounts (Palm Ridge Properties). As the
// Accountant Rania Khoury: the vendor list (TRN, contact, own payable account,
// open / overdue / advance), a new vendor on camera (Gulf Shield Pest Control
// LLC, TRN, 45-day terms; the payable account is created automatically), and
// BlueWave Cleaning's open items (BW-2608-07, 25/08/2026, due 24/09/2026) and
// statement. As the Company Admin Karim Saleh: the two Emirates Islamic bank
// accounts, one per building, each linked to its property and its BANK ledger
// account, one default; the edit form. Account numbers and IBANs are blurred.
//
// Adding a vendor cannot be undone here: proof and capture start from snapshot pre19.
// Weights are the seconds of narration each scene covers (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';
import { seed } from '../lib/context.mjs';

const row = (page, text) => page.locator('main tr').filter({ hasText: text }).first();
const field = (page, label) => page.locator('form label', { hasText: label }).first()
  .locator('xpath=following-sibling::*[self::input or self::textarea][1]');
const type = (locator, text) => locator.pressSequentially(text, { delay: 30 });

/** Blur bank account numbers and IBANs wherever they are on screen (table cells and form inputs). */
async function blurBankNumbers(page) {
  await page.evaluate(() => {
    const blur = (el) => { el.style.filter = 'blur(6px)'; };
    const heads = [...document.querySelectorAll('main thead th')].map((th) => th.textContent.trim().toLowerCase());
    const cols = heads.map((h, i) => (/account number|iban/.test(h) ? i : -1)).filter((i) => i >= 0);
    for (const tr of document.querySelectorAll('main tbody tr')) for (const i of cols) if (tr.children[i]) blur(tr.children[i]);
    for (const label of document.querySelectorAll('form label')) {
      if (/account number|iban/i.test(label.textContent)) {
        const input = label.parentElement?.querySelector('input');
        if (input) blur(input);
      }
    }
  });
}

const scenes = [
  roleRouteScene('accountant', '/en/dashboard/finance/vendors', 'Suppliers and their ledgers',
    'Each vendor: TRN, contact, its own payable account, and what is open, overdue or paid in advance.', {
    weight: 17.94,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const bw = row(page, 'BlueWave Cleaning LLC');
      await bw.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(bw, '100555777800003', 'BlueWave TRN');
      await expectText(bw, '100044 - BlueWave Cleaning LLC', 'BlueWave payable account');
      await restPointer(page, 1300, 600);
      await at(8.6);
      await pointAt(bw.getByText('100555777800003'));
      await at(11.7);
      await pointAt(bw.getByText('100044 - BlueWave Cleaning LLC'));
      await at(14.9);
      await pointAt(page.locator('main thead th', { hasText: 'Overdue' }).first());
    },
  }),
  stepScene('Add a vendor',
    'Gulf Shield Pest Control LLC: TRN for input VAT, 45-day terms; Miftah creates its payable account.',
    async (page) => {
      const at = sceneClock(page);
      await at(1.9);
      await page.getByRole('button', { name: 'Add Vendor' }).first().click();
      await field(page, 'Name (English)').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await field(page, 'Name (English)').click();
      await type(field(page, 'Name (English)'), 'Gulf Shield Pest Control LLC');
      await at(6.2);
      await field(page, 'TRN').click();
      await type(field(page, 'TRN'), '100555999000003');
      await at(11.6);
      await field(page, 'Payment terms').fill('45');
      await at(14.6);
      await field(page, 'Email').click();
      await type(field(page, 'Email'), 'accounts@gulfshield.example');
      await field(page, 'Phone').fill('+97140000914');
      await field(page, 'Contact Person').fill('Farah Aziz');
      await at(18.4);
      await pointAt(page.locator('form').getByText('Created and maintained automatically'));
      await at(23.8);
      await page.locator('form button[type="submit"]').click();
      const gs = row(page, 'Gulf Shield Pest Control LLC');
      await gs.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(gs, '- Gulf Shield Pest Control LLC', 'New payable account');
      await expectText(gs, '100555999000003', 'New TRN');
      await restPointer(page, 1300, 600);
      await pointAt(gs.getByText('- Gulf Shield Pest Control LLC'));
    }, { weight: 27.98 }),
  stepScene('A vendor at work',
    "BlueWave's open items: every bill with its voucher and due date; then the statement of account as a PDF.",
    async (page) => {
      const at = sceneClock(page);
      await at(0.6);
      await page.getByTestId(`vendor-account-${seed.vendors.bluewave}`).click();
      const items = page.getByTestId('vendor-items');
      await items.waitFor({ state: 'visible', timeout: navTimeoutMs });
      const aug = page.getByTestId('vendor-item-BW-2608-07');
      await expectText(aug, '25/08/2026', 'Invoice date');
      await expectText(aug, '24/09/2026', 'Due date');
      await restPointer(page, 1300, 640);
      await at(3.2);
      await pointAt(page.getByTestId('vendor-name'));
      await at(5.7);
      await pointAt(items.locator('thead'));
      await at(10.6);
      await pointAt(aug);
      await at(14.3);
      await page.getByTestId('tab-statement').click();
      await page.getByTestId('soa-pdf-en').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pointAt(page.getByTestId('soa-from'));
      await at(16.8);
      await pointAt(page.getByTestId('soa-pdf-ar'));
    }, { weight: 19.73 }),
  roleRouteScene('tenantAdmin', '/en/dashboard/finance/bank-accounts', 'Bank accounts',
    'Two Emirates Islamic accounts, one per building, each linked to its property and its bank ledger account.', {
    weight: 24.03,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const res = row(page, 'Palm Ridge Residences');
      await res.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await blurBankNumbers(page);
      await expectText(res, '100005 - Emirates Islamic - Palm Ridge Residences', 'Residences ledger link');
      await expectText(row(page, 'Palm Ridge Business Centre'), 'Default Account', 'Default');
      await restPointer(page, 1300, 600);
      await at(3.3);
      await pointAt(res);
      await at(7.2);
      await pointAt(row(page, 'Palm Ridge Business Centre'));
      await at(10.0);
      await pointAt(res.getByText('Palm Ridge Residences', { exact: true }));
      await at(13.9);
      await pointAt(res.getByText('100005 - Emirates Islamic - Palm Ridge Residences'));
      await at(21.8);
      await pointAt(row(page, 'Palm Ridge Business Centre').getByText('Default Account'));
    },
  }),
  stepScene('Edit a bank account',
    'The linked property, the chart of accounts link and the default flag.',
    async (page) => {
      const at = sceneClock(page);
      await row(page, 'Palm Ridge Residences').getByRole('button', { name: 'Edit Bank Account' }).click();
      const form = page.locator('form').first();
      await form.getByText('Linked Property', { exact: false }).first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await blurBankNumbers(page);
      const selects = form.locator('select');
      await restPointer(page, 1300, 600);
      await at(2.0);
      await pointAt(selects.nth(0));
      await at(4.4);
      await pointAt(selects.nth(1));
      await at(6.6);
      await pointAt(form.getByText('Default Account', { exact: true }).first());
    }, { weight: 11.84, role: 'tenantAdmin' }),
];

export default {
  role: 'accountant',
  anchored: true,
  warmup: [{ role: 'accountant', path: '/en/dashboard/finance/vendors' }, { role: 'tenantAdmin', path: '/en/dashboard/finance/bank-accounts' }],
  scenes,
};

// Tutorial 37 — Tenant ledger, control account and trial balance (Palm Ridge
// Properties, the Accountant Rania Khoury). Omar Haddad's Tenant Ledger to
// 31/08/2026: the TCO debits Rent Receivable 77,000 and the five PDRs credit it
// back to zero. Layla Nasser: cheque 220102's return (CBR-26/1) reopens 15,000,
// the 500 penalty and its cheque, the two 7,500 replacements close it; Advance
// Rent 12,328.76 Cr still to earn; PDC Receivable nets to zero. CBR-26/1's
// journal. The General Ledger on Rent Receivable – Residences for February 2026
// closes at 15,000.00 Dr, Layla's balance that day (the control account). The
// Trial Balance at 31/08/2026: 4,150,891.24 each side, then the Residences alone.
//
// Read-only: nothing is posted, so no snapshot is needed. One page throughout.
// Weights are the seconds of narration each scene covers (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const row = (page, text) => page.locator('main tr').filter({ hasText: text }).first();
const journalLines = (page) => page.locator('main table').first().locator('tbody tr');
const journalLine = (page, account, amount) => journalLines(page).filter({ hasText: account }).filter({ hasText: amount }).first();

async function accountId(page, name) {
  const accounts = await (await page.request.get('/api/proxy/v1/finance/accounts')).json();
  const id = accounts.find((a) => a.name === name)?.id;
  if (!id) throw new Error(`Account not found: ${name}`);
  return id;
}

async function pickTenant(page, name) {
  await page.getByTestId('ledger-renter-filter').click();
  await page.getByRole('option', { name: new RegExp(`^${name}`) }).click();
  await page.getByRole('button', { name: 'Apply', exact: true }).click();
}

const centre = (locator) => locator.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));

const scenes = [
  roleRouteScene('accountant', '/en/dashboard/finance/tenant-ledger', 'One block per account',
    "Omar Haddad: the contract debits Rent Receivable 77,000; his five cheques credit it back to zero.", {
    weight: 40.27,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('ledger-renter-filter').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 500);
      await at(7.0);
      await page.locator('#ledger-from').fill('2025-09-01');
      await page.locator('#ledger-to').fill('2026-08-31');
      await at(9.0);
      await pickTenant(page, 'Omar Haddad');
      const last = row(page, 'PDR-25/13');
      await last.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(row(page, 'Name :: Rent Receivable - Palm Ridge Residences'), '100001', 'Rent Receivable block');
      await expectText(row(page, 'Advance Rent - Palm Ridge Residences'), '77,000.00 Dr', 'TCO balance');
      await expectText(last, '0.00', 'Balance after the cheques');
      await restPointer(page, 1300, 500);
      await at(14.2);
      await pointAt(row(page, 'Name :: Rent Receivable - Palm Ridge Residences'));
      await at(18.6);
      await pointAt(row(page, 'Advance Rent - Palm Ridge Residences').getByText('77,000.00 Dr'));
      await at(24.4);
      await pointAt(row(page, 'Advance Rent - Palm Ridge Residences'));
      await at(28.4);
      await pointAt(row(page, 'PDR-25/10'));
      await at(31.2);
      await pointAt(last);
      await at(35.0);
      await pointAt(row(page, 'Sub Total'));
    },
  }),
  stepScene('A returned cheque',
    'CBR-26/1 debits Rent Receivable 15,000 again; the penalty and two replacement cheques bring it back to zero.',
    async (page) => {
      const at = sceneClock(page);
      await at(0.4);
      await pickTenant(page, 'Layla Nasser');
      const cbr = row(page, 'CBR-26/1');
      await cbr.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(cbr, '15,000.00 Dr', 'Balance after the return');
      await expectText(row(page, 'PDR-26/17'), '0.00', 'Balance after the replacements');
      await restPointer(page, 1300, 560);
      await at(5.9);
      await pointAt(cbr);
      await at(8.9);
      await pointAt(row(page, 'PEN-26/1'));
      await at(11.0);
      await pointAt(row(page, 'PDR-26/15'));
      await at(13.5);
      await pointAt(row(page, 'PDR-26/16'));
      await at(15.4);
      await pointAt(row(page, 'PDR-26/17'));
    }, { weight: 17.86 }),
  stepScene('Earned, held and cleared',
    'Advance Rent 12,328.76 Cr is billed but not yet earned; PDC Receivable is back to zero.',
    async (page) => {
      const at = sceneClock(page);
      const advance = page.getByTestId(`ledger-subtotal-${await accountId(page, 'Advance Rent - Palm Ridge Residences')}`);
      const pdc = page.getByTestId(`ledger-subtotal-${await accountId(page, 'PDC Receivable Palm Ridge Residences')}`);
      await expectText(advance, '12,328.76 Cr', 'Advance Rent still to earn');
      await expectText(pdc, '0.00', 'PDC Receivable');
      await centre(advance);
      await page.waitForTimeout(900);
      await at(1.2);
      await pointAt(advance);
      await at(5.0);
      await centre(pdc);
      await page.waitForTimeout(900);
      await pointAt(row(page, 'Name :: PDC Receivable Palm Ridge Residences'));
      await at(9.2);
      await pointAt(pdc);
    }, { weight: 12.45 }),
  stepScene('The journal behind a row',
    'CBR-26/1: Dr Rent Receivable 15,000, Cr PDC Receivable 15,000. Balanced, never edited, only reversed.',
    async (page) => {
      const at = sceneClock(page);
      const link = row(page, 'CBR-26/1').getByRole('link', { name: 'CBR-26/1', exact: true });
      await centre(link);
      await page.waitForTimeout(800);
      await at(1.2);
      await link.click();
      await page.waitForURL(/\/dashboard\/finance\/journals\/[0-9a-f-]{36}/, { timeout: navTimeoutMs });
      await page.getByText('Journal Voucher CBR-26/1', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(journalLine(page, 'Rent Receivable', '15,000.00'), 'Layla Nasser', 'Debit line');
      await expectText(journalLine(page, 'PDC Receivable', '15,000.00'), 'Layla Nasser', 'Credit line');
      await restPointer(page, 1300, 700);
      await at(3.6);
      await pointAt(journalLine(page, 'Rent Receivable', '15,000.00'));
      await at(6.0);
      await pointAt(journalLine(page, 'PDC Receivable', '15,000.00'));
      await at(8.6);
      await pointAt(row(page, 'Total'));
    }, { weight: 12.46 }),
  stepScene('The control account',
    'Rent Receivable – Residences, February: every tenant together; it closes at 15,000 Dr, Layla’s balance.',
    async (page) => {
      const at = sceneClock(page);
      const id = await accountId(page, 'Rent Receivable - Palm Ridge Residences');
      await page.goto(`/en/dashboard/finance/general-ledger?accountIds=${id}&from=2026-02-01&to=2026-02-28`);
      const sub = row(page, 'Sub Total');
      await sub.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(sub, '15,000.00 Dr', 'Control account at the end of February');
      await restPointer(page, 1300, 600);
      await at(5.4);
      await pointAt(row(page, 'Name :: Rent Receivable - Palm Ridge Residences'));
      await at(7.2);
      await pointAt(row(page, 'TCO-26/2'));
      await at(9.2);
      await pointAt(sub);
      await at(11.6);
      await pointAt(row(page, 'PDR-26/15'));
      await at(14.3);
      await pointAt(sub);
    }, { weight: 17.85 }),
  stepScene('The trial balance',
    'At 31/08/2026: 4,150,891.24 debit and credit. One building alone still balances.',
    async (page) => {
      const at = sceneClock(page);
      await at(0.3);
      await page.getByTestId('sidebar-trial-balance').click();
      await page.locator('#tb-as-of').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.locator('#tb-as-of').fill('2026-08-31');
      await page.getByRole('button', { name: 'Apply', exact: true }).click();
      const grand = row(page, 'Grand Total');
      await expectText(grand, '4,150,891.24', 'Grand total');
      const cells = (await grand.innerText()).match(/[\d,]+\.\d{2}/g) || [];
      if (cells.length < 2 || cells[0] !== cells[1]) throw new Error(`Trial balance does not balance: ${cells.join(' / ')}`);
      await centre(grand);
      await page.waitForTimeout(900);
      await at(4.4);
      await pointAt(grand);
      await at(7.4);
      await page.locator('#tb-property').selectOption({ label: 'Palm Ridge Residences' });
      await page.getByRole('button', { name: 'Apply', exact: true }).click();
      await expectText(grand, '2,670,639.82', 'Residences grand total');
      // Straight back down to the total (no smooth scroll: the take ends here).
      await grand.evaluate((el) => el.scrollIntoView({ block: 'center' }));
      await pointAt(grand);
    }, { weight: 11.21 }),
];

export default {
  role: 'accountant',
  anchored: true,
  warmup: [{ role: 'accountant', path: '/en/dashboard/finance/tenant-ledger' }],
  scenes,
};

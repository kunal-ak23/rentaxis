// Tutorial 49 — A tenancy contract in the books. As Company Admin: the contract tutorial 47
// drafts and posts (Rajesh Kumar, A-202, 42,000 rent + 4,000 deposit + 1,000 admin fee, six
// cheques) seen from the ledger: the posting journal, one PDR per cheque in hand, the receipt
// that cleared the deposit cheque, the contract's ledger account by account, and the per-day
// recognition schedule.
//
// Read-only. The proof and the capture start from the database snapshot `pre49`: the books as
// tutorial 47's take left them (contract posted, deposit cheque cleared, fee cheque banked).
// The contract's id is the snapshot's, so scene 1 opens it from the list and keeps its path.
//
// Weights are the narration's own spans (take 1's cues) and every `at()` lands on its cue.
// The last scene says the nightly run posts each ended month, and points to tutorial 51,
// which shows it on these contracts (owner request 2026-10-29).
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { applyCaptureStyles, goto, waitForApp } from '../lib/page.mjs';
import { expectCount, expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

let contractPath = null;

const centre = (locator) => locator.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
const journalsTab = (page) => page.getByTestId('lease-journals-tab');
/** A journal in the contract's list (the ledger below links the same numbers again). */
const listed = (page, no) => journalsTab(page).getByRole('link', { name: no, exact: true }).first();
/** An account's Sub Total row in the contract's ledger, found by its code; cell 3 is its balance. */
const subtotal = (page, code) => journalsTab(page).locator(
  `xpath=.//tr[td[contains(., ':: ${code}')]]/following-sibling::tr[starts-with(@data-testid, 'ledger-subtotal-')][1]`);
const balance = (page, code) => subtotal(page, code).locator('td').nth(3);
/** A line of the open journal, by account code: cells are line, account, debit, credit. */
const line = (page, code) => page.locator('main table tbody tr').filter({ hasText: code }).first();
const debit = (page, code) => line(page, code).locator('td').nth(2);
const credit = (page, code) => line(page, code).locator('td').nth(3);

async function openJournal(page, no) {
  await listed(page, no).click();
  await page.waitForURL(/\/finance\/journals\/[0-9a-f-]{36}$/, { timeout: navTimeoutMs });
  await waitForApp(page);
  await applyCaptureStyles(page);
  await page.getByText(`Journal Voucher ${no}`, { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
}

const scenes = [
  roleRouteScene('tenantAdmin', '/en/dashboard/leases', 'Rajesh\'s contract',
    'A-202, posted in tutorial 47. View posting journal opens what posting wrote.', {
    weight: 8.8,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const row = page.locator('tr[data-testid^="lease-row-"]').filter({ hasText: 'A-202' }).filter({ hasText: 'Rajesh Kumar' });
      await row.first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(row, 1, 'A-202 contracts for Rajesh Kumar');
      await restPointer(page, 1100, 560);
      await at(4.0);
      await row.click();
      await page.waitForURL(/\/dashboard\/leases\/[0-9a-f-]{36}$/, { timeout: navTimeoutMs });
      contractPath = new URL(page.url()).pathname;
      await waitForApp(page);
      await applyCaptureStyles(page);
      await expectText(page.getByTestId('lease-status'), 'Active', 'A-202 status');
      const journalLink = page.getByTestId('lease-posting-journal');
      await at(6.2);
      await pointAt(journalLink);
      await at(7.6);
      await journalLink.click();
      await page.waitForURL(/\/finance\/journals\/[0-9a-f-]{36}$/, { timeout: navTimeoutMs });
      await waitForApp(page);
      await applyCaptureStyles(page);
      await page.getByText('Journal Voucher TCO-26/7', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
    },
  }),
  stepScene('The posting journal, TCO-26/7',
    'Rent Receivable 47,000, against Advance Rent, Security Deposit and Admin Fee.',
    async (page) => {
      const at = sceneClock(page);
      const total = page.locator('main table tr').filter({ hasText: /^\s*Total/ }).last();
      await expectCount(page.locator('main table tbody tr').filter({ hasText: '100013' }), 3, 'Rent Receivable lines');
      await expectText(credit(page, '100002'), '42,000.00', 'Advance Rent credit');
      await expectText(credit(page, '100015'), '4,000.00', 'Security Deposit credit');
      await expectText(credit(page, '100001'), '1,000.00', 'Admin Fee credit');
      await expectText(total, '47,000.00 47,000.00', 'Journal total');
      await at(0.2);
      await pointAt(page.getByText('Journal Voucher TCO-26/7', { exact: true }));
      await at(1.8);
      await pointAt(debit(page, '100013'));
      await at(5.2);
      await pointAt(total.getByText('47,000.00').first());
      await at(9.4);
      await pointAt(credit(page, '100002'));
      await at(17.1);
      await pointAt(credit(page, '100015'));
      await at(24.3);
      await pointAt(credit(page, '100001'));
    }, { weight: 29.2 }),
  stepScene('Every journal the contract wrote',
    'Cheques tab, Journal Vouchers. One PDR per cheque in hand: Rent Receivable to PDC Receivable.',
    async (page) => {
      const at = sceneClock(page);
      await goto(page, contractPath);
      await page.getByTestId('lease-tab-payments').click();
      await page.getByTestId('lease-section-toggle-journals').click();
      const list = journalsTab(page);
      await listed(page, 'TCO-26/7').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(list.locator('table').first().locator('tbody tr'), 8, 'journals of the A-202 contract');
      await expectCount(list.locator('table').first().locator('tbody tr').filter({ hasText: 'PDR' }), 6, 'PDR journals');
      await centre(listed(page, 'PDR-26/40'));
      await at(3.4);
      await pointAt(listed(page, 'TCO-26/7'));
      await at(6.6);
      await pointAt(listed(page, 'PDR-26/42'));
      await at(8.8);
      await pointAt(listed(page, 'PDR-26/37'));
      await at(11.8);
      await pointAt(listed(page, 'PDR-26/40'));
      await at(17.0);
      await openJournal(page, 'CRT-26/12');
    }, { weight: 18.5 }),
  stepScene('Clearing the deposit cheque',
    'CRT-26/12: the building\'s bank debited 4,000, PDC Receivable credited.',
    async (page) => {
      const at = sceneClock(page);
      await expectText(debit(page, '100003'), '4,000.00', 'Bank debit');
      await expectText(credit(page, '100011'), '4,000.00', 'PDC Receivable credit');
      await at(0.2);
      await pointAt(page.getByText('Journal Voucher CRT-26/12', { exact: true }));
      await at(2.6);
      await pointAt(debit(page, '100003'));
      await at(5.6);
      await pointAt(credit(page, '100011'));
    }, { weight: 11.2 }),
  stepScene('Account by account',
    'Advance Rent 42,000 not yet earned; PDC Receivable 43,000 to clear; Rent Receivable 0.00.',
    async (page) => {
      const at = sceneClock(page);
      await goto(page, `${contractPath}?tab=payments&section=journals`);
      await listed(page, 'TCO-26/7').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(balance(page, '100002'), '42,000.00 Cr', 'Advance Rent');
      await expectText(balance(page, '100003'), '4,000.00 Dr', 'Bank');
      await expectText(balance(page, '100011'), '43,000.00 Dr', 'PDC Receivable');
      await expectText(subtotal(page, '100013'), '47,000.00 47,000.00 0.00', 'Rent Receivable');
      const show = async (code) => { await centre(subtotal(page, code)); await pointAt(balance(page, code)); };
      await at(3.8);
      await show('100002');
      await at(8.3);
      await show('100003');
      await at(10.7);
      await show('100011');
      await at(14.4);
      await show('100013');
    }, { weight: 21.3 }),
  stepScene('Revenue recognition',
    'Twelve monthly slices, by the day. Each month-end moves one from Advance Rent to Rental Income.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('lease-section-toggle-recognition').click();
      const schedule = page.getByTestId('recognition-schedule');
      await schedule.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(schedule.locator('[data-testid^="recognition-row-"]'), 12, 'recognition slices');
      await expectText(page.getByTestId('recognition-amount-0'), '3,452.05', 'November slice');
      await expectText(page.getByTestId('recognition-amount-11'), '3,567.16', 'Last slice');
      await expectText(page.getByTestId('recognition-schedule-total'), '42,000.00', 'Schedule total');
      await centre(schedule);
      await at(3.6);
      await pointAt(page.getByTestId('recognition-row-0').locator('td').first());
      await at(9.0);
      await pointAt(page.getByTestId('recognition-amount-0'));
      await at(15.0);
      await pointAt(page.getByTestId('recognition-amount-11'));
      await at(18.2);
      await pointAt(page.getByTestId('recognition-schedule-total'));
    }, { weight: 20.9 }),
  stepScene('Posted every night',
    'Each ended month posts on its own overnight: Planned turns Posted, with its CIL. Tutorial 51 shows it.',
    async (page) => {
      const at = sceneClock(page);
      const november = page.getByTestId('recognition-row-0');
      await expectText(november.locator('td').nth(3), 'Planned', 'November status');
      await at(7.0);
      await pointAt(november.locator('td').nth(3));
      await at(9.4);
      await pointAt(november.locator('td').nth(4));
      await at(11.6);
      await restPointer(page, 1300, 500);
    }, { weight: 18 }),
];

export default {
  role: 'tenantAdmin',
  anchored: true,
  warmup: [{ role: 'tenantAdmin', path: '/en/dashboard/finance/journals' }],
  scenes,
};

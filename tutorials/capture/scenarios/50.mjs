// Tutorial 50 — An early termination in the books. As Company Admin: tutorial 48's
// termination of Ahmed Hassan's A-101 contract (moved out 31 August) seen from the ledger:
// the entries dated the termination day (the October cheque's PDR reversed, August's
// recognition re-posted, TCR taking off the unearned rent), the settlement journal, the
// refund payment voucher, the contract's ledger closed account by account, and the
// recognition schedule cut at the termination date.
//
// Read-only. The proof and the capture start from the database snapshot `pre50`: the books as
// tutorial 48's actions leave them (July cheque cleared, contract terminated, settlement
// finalized, refund paid — the contract reads Closed).
//
// Weights are the narration's own spans (take 1's cues) and every `at()` lands on its cue.
// The settlement and refund journals open straight from their links in the contract's list
// (read in scene 1), so each is on screen as its sentence starts.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { ahmedLeaseId } from '../lib/fixtures.mjs';
import { applyCaptureStyles, goto, waitForApp } from '../lib/page.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const contractPath = `/en/dashboard/leases/${ahmedLeaseId}`;
const journalPaths = {};

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

async function journalOpened(page, no) {
  await page.waitForURL(/\/finance\/journals\/[0-9a-f-]{36}$/, { timeout: navTimeoutMs });
  await waitForApp(page);
  await applyCaptureStyles(page);
  await page.getByText(`Journal Voucher ${no}`, { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
}

const scenes = [
  roleRouteScene('tenantAdmin', contractPath, 'What the termination wrote',
    'Dated 31 August: the October cheque\'s PDR reversed, August re-posted, and TCR-26/2.', {
    weight: 36.7,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await expectText(page.getByTestId('lease-status'), 'Closed', 'A-101 status');
      await restPointer(page, 1100, 560);
      await at(9.8);
      await pointAt(page.getByTestId('lease-status'));
      await at(12.4);
      await page.getByTestId('lease-tab-payments').click();
      await at(13.8);
      await page.getByTestId('lease-section-toggle-journals').click();
      await listed(page, 'TCR-26/2').waitFor({ state: 'visible', timeout: navTimeoutMs });
      const rows = journalsTab(page).locator('table').first().locator('tbody tr');
      await expectText(rows.filter({ hasText: 'PDR-26/37' }), 'Reversal of PDR-26/5', 'October cheque PDR reversal');
      await expectText(rows.filter({ hasText: 'CIL-26/38' }), '7,219.17', 'August recognition re-posted');
      await expectText(rows.filter({ hasText: 'TCR-26/2' }), '28,410.96', 'Unearned rent reversal');
      for (const no of ['STL-26/2', 'BPV-26/2']) journalPaths[no] = await listed(page, no).getAttribute('href');
      await centre(listed(page, 'CIL-26/38'));
      await at(20.0);
      await pointAt(listed(page, 'PDR-26/37'));
      await at(26.8);
      await pointAt(listed(page, 'CIL-26/38'));
      await at(32.6);
      await pointAt(listed(page, 'TCR-26/2'));
      await at(35.2);
      await listed(page, 'TCR-26/2').click();
      await journalOpened(page, 'TCR-26/2');
    },
  }),
  stepScene('Unearned rent off the books',
    'TCR-26/2: 28,410.96 out of Advance Rent and off Rent Receivable.',
    async (page) => {
      const at = sceneClock(page);
      await expectText(debit(page, '100002'), '28,410.96', 'Advance Rent debit');
      await expectText(credit(page, '100013'), '28,410.96', 'Rent Receivable credit');
      await at(1.0);
      await pointAt(debit(page, '100002'));
      await at(3.2);
      await pointAt(credit(page, '100013'));
    }, { weight: 9.7 }),
  stepScene('The settlement journal',
    'STL-26/2: the deposit released, the balance cleared, cleaning 750, refund owed 28,410.96.',
    async (page) => {
      const at = sceneClock(page);
      await goto(page, journalPaths['STL-26/2']);
      await journalOpened(page, 'STL-26/2');
      await expectText(debit(page, '100015'), '22,000.00', 'Security Deposit released');
      await expectText(debit(page, '100013'), '7,160.96', 'Rent Receivable cleared');
      await expectText(credit(page, '100007'), '750.00', 'Cleaning charge');
      await expectText(credit(page, 'B-01-07'), '28,410.96', 'Refund owed');
      await at(2.6);
      await pointAt(debit(page, '100015'));
      await at(7.0);
      await pointAt(debit(page, '100013'));
      await at(13.3);
      await pointAt(credit(page, '100007'));
      await at(18.2);
      await pointAt(credit(page, 'B-01-07'));
    }, { weight: 23.2 }),
  stepScene('Paying the refund',
    'BPV-26/2: Refunds payable cleared from the building\'s bank, balanced across head office.',
    async (page) => {
      const at = sceneClock(page);
      await goto(page, journalPaths['BPV-26/2']);
      await journalOpened(page, 'BPV-26/2');
      await expectText(debit(page, 'B-01-07'), '28,410.96', 'Refunds payable debit');
      await expectText(credit(page, '100003'), '28,410.96', 'Bank credit');
      await expectText(credit(page, 'A-02-06-001'), '28,410.96', 'Head office clearing');
      await expectText(debit(page, '100006'), '28,410.96', 'Building clearing');
      await at(2.6);
      await pointAt(debit(page, 'B-01-07'));
      await at(5.6);
      await pointAt(credit(page, '100003'));
      await at(10.6);
      await pointAt(credit(page, 'A-02-06-001'));
      await at(13.0);
      await pointAt(debit(page, '100006'));
    }, { weight: 16.9 }),
  stepScene('Account by account',
    'Every holding account at zero. The bank keeps 58,839.04: what was earned.',
    async (page) => {
      const at = sceneClock(page);
      await goto(page, `${contractPath}?tab=payments&section=journals`);
      await listed(page, 'TCR-26/2').waitFor({ state: 'visible', timeout: navTimeoutMs });
      for (const code of ['100002', '100011', '100013', '100015', 'B-01-07']) {
        await expectText(balance(page, code), '0.00', `${code} closed`);
        const text = (await balance(page, code).innerText()).trim();
        if (text !== '0.00') throw new Error(`${code} closed: expected "0.00", found "${text}".`);
      }
      await expectText(balance(page, '100003'), '58,839.04 Dr', 'Bank');
      await expectText(balance(page, '100014'), '56,589.04 Cr', 'Rental Income');
      await expectText(balance(page, '100001'), '1,500.00 Cr', 'Admin Fee');
      await expectText(balance(page, '100007'), '750.00 Cr', 'Cleaning charge');
      const show = async (code) => { await centre(subtotal(page, code)); await pointAt(balance(page, code)); };
      await at(3.9);
      await show('100002');
      await at(6.3);
      await show('100003');
      await at(8.2);
      await show('100011');
      await at(10.3);
      await show('100013');
      await at(12.4);
      await show('100014');
      await at(15.5);
      await show('100015');
      await at(17.3);
      await show('B-01-07');
      await at(19.8);
      await show('100003');
      await at(24.2);
      await show('100001');
      await at(26.6);
      await show('100007');
    }, { weight: 29.5 }),
  stepScene('Recognition stops at the termination',
    'Recognised to 31 August; September to December cancelled. Total 56,589.04.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('lease-section-toggle-recognition').click();
      const schedule = page.getByTestId('recognition-schedule');
      await schedule.waitFor({ state: 'visible', timeout: navTimeoutMs });
      const september = schedule.locator('tr').filter({ hasText: '01/09/2026 – 30/09/2026' }).first();
      const august = schedule.locator('tr').filter({ hasText: 'CIL-26/38' }).first();
      await expectText(september, 'Cancelled', 'September slice');
      await expectText(august, '7,219.17', 'August slice');
      await expectText(page.getByTestId('recognition-schedule-total'), '56,589.04', 'Schedule total');
      await centre(schedule);
      await at(1.4);
      await pointAt(august.getByText('7,219.17', { exact: true }));
      await at(3.8);
      await pointAt(september.getByText('Cancelled', { exact: true }));
      await at(6.6);
      await pointAt(page.getByTestId('recognition-schedule-total'));
    }, { weight: 12.1 }),
];

export default {
  role: 'tenantAdmin',
  anchored: true,
  warmup: [{ role: 'tenantAdmin', path: '/en/dashboard/finance/journals' }],
  scenes,
};

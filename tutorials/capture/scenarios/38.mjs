// Tutorial 38 — How accounting works in Miftah (Palm Ridge Properties, the
// Accountant Rania Khoury). One contract's money from posting to the reports:
// Omar Haddad's R-101 contract, its TCO journal, the five PDRs netting Rent
// Receivable to zero, cheque 110102's CRT, the per-day recognition schedule and
// October's CIL; then Layla Nasser's returned cheque 220102 (CBR) in the Tenant
// Ledger, the Trial Balance and Profit & Loss. Read-only: nothing is posted, so
// no snapshot is needed. The whole tutorial runs in one page (sidebar and back
// navigation), so there is one clip and no reload between scenes.
//
// Weights are the seconds of narration each scene covers (timing audio,
// tutorials/work/acct/tts.sh); `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { palmLease } from '../lib/fixtures.mjs';
import { expectCount, expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const omar = palmLease('omar');

const journalLines = (page) => page.locator('main table').first().locator('tbody tr');
const journalLine = (page, account, amount) => journalLines(page).filter({ hasText: account }).filter({ hasText: amount }).first();

/** Open a journal from a link (or after a click already made) and wait for its lines. */
async function openJournal(page, link, docNo) {
  if (link) await link.click();
  await page.waitForURL(/\/dashboard\/finance\/journals\/[0-9a-f-]{36}/, { timeout: navTimeoutMs });
  await page.getByText(`Journal Voucher ${docNo}`, { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
  await journalLines(page).first().waitFor({ state: 'visible', timeout: navTimeoutMs });
}

/** The contract's Cheques tab, with a collapsible section open. */
async function openSection(page, id) {
  const body = page.getByTestId(id === 'journals' ? 'lease-journals-tab' : 'recognition-schedule');
  if (!(await body.isVisible().catch(() => false))) await page.getByTestId(`lease-section-toggle-${id}`).click();
  await body.waitFor({ state: 'visible', timeout: navTimeoutMs });
  return body;
}

const scenes = [
  roleRouteScene('accountant', `/en/dashboard/leases/${omar}`, 'One contract, start to finish',
    'Omar Haddad, unit R-101: a 5,000 security deposit and 72,000 rent for the year.', {
    weight: 17.0,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await expectText(page.getByTestId('lease-status'), 'Active', 'Contract status');
      await expectText(page.getByTestId('lease-line-row-0'), '5,000.00', 'Deposit line');
      await expectText(page.getByTestId('lease-line-row-1'), '72,000.00', 'Rent line');
      await restPointer(page, 1100, 560);
      await at(7.5);
      await pointAt(page.getByText('Omar Haddad', { exact: true }).first());
      await at(11.0);
      await pointAt(page.getByTestId('lease-line-row-0'));
      await at(14.3);
      await pointAt(page.getByTestId('lease-line-row-1'));
    },
  }),
  stepScene('The tenancy contract journal',
    'TCO: Rent Receivable is debited 77,000; the deposit and the advance rent are credited.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('lease-posting-journal').click();
      await openJournal(page, null, 'TCO-25/3');
      await expectCount(journalLines(page).filter({ hasText: 'Rent Receivable' }), 2, 'Rent Receivable lines');
      await restPointer(page, 1200, 700);
      await at(4.7);
      await pointAt(journalLine(page, 'Rent Receivable', '5,000.00'));
      await at(6.4);
      await pointAt(journalLine(page, 'Rent Receivable', '72,000.00'));
      await at(8.4);
      await pointAt(page.locator('main table').first().locator('tr').filter({ hasText: 'Total' }).filter({ hasText: '77,000.00' }).first());
      await at(11.3);
      await pointAt(journalLine(page, 'Security Deposit Palm Ridge Residences', '5,000.00'));
      await at(18.5);
      await pointAt(journalLine(page, 'Advance Rent', '72,000.00'));
    }, { weight: 25.5 }),
  stepScene('Cheques in hand',
    'Each post-dated cheque journal (PDR) moves the debt from Rent Receivable to PDC Receivable. Omar owes nothing on paper.',
    async (page) => {
      const at = sceneClock(page);
      await page.goBack();
      await page.getByTestId('lease-tab-payments').click();
      const grid = page.getByTestId('cheque-grid');
      await grid.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(page.locator('[data-testid^="cheque-row-"]'), 5, 'cheque rows');
      await at(3.4);
      await pointAt(page.getByTestId('cheque-row-2'));
      await at(6.0);
      const journals = await openSection(page, 'journals');
      await expectCount(journals.locator('table').first().locator('tbody tr').filter({ hasText: 'PDR' }), 5, 'PDR journals');
      await pointAt(journals.getByText('Journal Voucher', { exact: true }).first());
      await at(9.5);
      const list = journals.locator('table').first();
      await pointAt(list.locator('tbody tr').filter({ hasText: 'TCO-25/3' }));
      await at(11.3);
      await pointAt(list.locator('tbody tr').filter({ hasText: 'PDR-25/13' }));
      await at(13.4);
      const ledger = journals.locator('table').nth(1);
      const header = ledger.getByText('Rent Receivable - Palm Ridge Residences', { exact: false }).first();
      await pointAt(header);
      await at(16.5);
      await pointAt(ledger.locator('tr').filter({ hasText: 'PDR-25/10' }).first());
      await at(20.5);
      const subtotal = ledger.locator('tr').filter({ hasText: 'Sub Total' }).first();
      await expectText(subtotal, '77,000.00 77,000.00 0.00', 'Rent Receivable subtotal');
      await pointAt(subtotal);
    }, { weight: 26.0 }),
  stepScene('Clearing: the money arrives',
    'Banking writes nothing. Clearing writes a CRT: debit the bank, credit PDC Receivable.',
    async (page) => {
      const at = sceneClock(page);
      const row = page.getByTestId('cheque-row-2');
      await expectText(row, '110102', 'Cheque number');
      await expectText(row, 'Cleared', 'Cheque status');
      await pointAt(row.getByText('110102', { exact: true }));
      await at(2.4);
      await pointAt(page.getByTestId('cheque-status-2'));
      const ledger = page.getByTestId('lease-journals-tab').locator('table').nth(1);
      const bankDebit = ledger.locator('tr').filter({ hasText: 'CRT-26/1' }).filter({ hasText: 'PDC Receivable' }).first();
      const pdcCredit = ledger.locator('tr').filter({ hasText: 'CRT-26/1' }).filter({ hasText: 'Emirates Islamic' }).first();
      await expectText(bankDebit, '18,000.00', 'Bank debit');
      await expectText(pdcCredit, '18,000.00', 'PDC credit');
      await at(4.3);
      await pointAt(ledger.locator('tr').filter({ hasText: 'Name :: Emirates Islamic - Palm Ridge Residences' }).first());
      await at(6.5);
      await pointAt(bankDebit);
      await at(11.0);
      await pointAt(pdcCredit);
    }, { weight: 17.9 }),
  stepScene('Earned by the day',
    'Month-end recognition moves each month\'s share from Advance Rent into Rental Income.',
    async (page) => {
      const at = sceneClock(page);
      await openSection(page, 'journals');
      const schedule = await openSection(page, 'recognition');
      await expectText(schedule, 'Matches the contract rent.', 'Schedule total');
      const oct = schedule.locator('tbody tr').filter({ hasText: '01/10/2025' });
      const nov = schedule.locator('tbody tr').filter({ hasText: '01/11/2025' });
      await expectText(oct, '6,115.07', 'October');
      await expectText(nov, '5,917.81', 'November');
      await schedule.scrollIntoViewIfNeeded();
      await at(3.0);
      await pointAt(schedule.getByRole('columnheader', { name: 'Days', exact: true }));
      await at(6.4);
      await pointAt(oct);
      await at(8.6);
      await pointAt(nov);
      const cil = page.getByTestId('lease-journals-tab').locator('table').nth(1).locator('tr')
        .filter({ hasText: 'CIL-25/5' }).filter({ hasText: 'Rental Income' }).first();
      await expectText(cil, '6,115.07', 'October recognition in Advance Rent');
      await at(10.6);
      await pointAt(page.getByTestId('lease-journals-tab').getByText('Name :: Advance Rent - Palm Ridge Residences').first());
      await at(12.2);
      await pointAt(cil);
    }, { weight: 19.6 }),
  stepScene('A returned cheque',
    'The return journal (CBR) puts the debt back on the tenant; replacement cheques settle it again.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('rail-accounting').click();
      await at(1.8);
      await page.getByTestId('sidebar-tenant-ledger').click();
      await page.getByTestId('ledger-renter-filter').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(3.0);
      await page.getByTestId('ledger-renter-filter').click();
      await page.getByRole('option', { name: /^Layla Nasser/ }).click();
      await page.getByRole('button', { name: 'Apply', exact: true }).click();
      const rr = page.locator('main table').first();
      const cbr = rr.locator('tr').filter({ hasText: 'CBR-26/1' }).first();
      await cbr.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(cbr, '15,000.00 Dr', 'Balance after the return');
      await at(5.8);
      await pointAt(rr.locator('tr').filter({ hasText: 'PDR-25/28' }).first());
      await at(9.6);
      await pointAt(cbr);
      await at(13.4);
      await pointAt(rr.locator('tr').filter({ hasText: 'PDR-26/17' }).first());
      await at(16.4);
      await openJournal(page, cbr.getByRole('link', { name: 'CBR-26/1', exact: true }).first(), 'CBR-26/1');
      await restPointer(page, 1200, 700);
      await at(17.6);
      await pointAt(journalLine(page, 'Rent Receivable', '15,000.00'));
      await at(20.5);
      await pointAt(journalLine(page, 'PDC Receivable', '15,000.00'));
    }, { weight: 26.4 }),
  stepScene('Trial Balance',
    'Every account, one line each. Total debits equal total credits.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-trial-balance').click();
      const grand = page.locator('main tr').filter({ hasText: 'Grand Total' }).first();
      await grand.waitFor({ state: 'visible', timeout: navTimeoutMs });
      const cells = (await grand.innerText()).match(/[\d,]+\.\d{2}/g) || [];
      if (cells.length < 2 || cells[0] !== cells[1]) throw new Error(`Trial balance does not balance: ${cells.join(' / ')}`);
      await at(1.2);
      await pointAt(grand);
    }, { weight: 5.5 }),
  stepScene('Profit & Loss',
    'Rental income as earned, not as collected. One ledger feeds every report.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-company-pl').click();
      const rental = page.getByTestId('row-RENTAL_INCOME');
      await rental.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('check-badge'), 'Ties to the ledger', 'P&L tie-out');
      await at(1.5);
      await pointAt(rental);
      await at(5.0);
      await pointAt(page.getByTestId('check-badge'));
    }, { weight: 11.3 }),
];

export default { role: 'accountant', scenes };

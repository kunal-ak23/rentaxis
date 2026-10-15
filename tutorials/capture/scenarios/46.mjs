// Tutorial 46 — Period lock and year-end close (Palm Ridge Properties, the
// Accountant Rania Khoury). Snapshot pre46 = base1005: books locked through
// 30/06/2026, recognition posted to 31/08/2026, September still PLANNED.
// Lock through 30/09/2026 is refused (PR #400: planned recognition would be
// stranded) with a link to Month-end recognition; run recognition to
// 30/09/2026; the lock then moves to 30/09/2026. Then FY 2025 (Closed, YEC-25/1),
// the closing journal, the Trial Balance at 31/12/2025 with and without the
// closing entries, and the FY 2026 close preview's blockers. Re-open is
// narrated, not performed. Writes CIL journals and the lock: the proof and the
// capture restore pre46. Weights are the seconds of narration each scene covers
// (tutorials/work/acct/tts.sh 46); `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const row = (page, text) => page.locator('main tr').filter({ hasText: text }).first();
const centre = (locator) => locator.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
const lockFooter = (page, date) => page.locator('div').filter({ hasText: new RegExp(`^Books locked through\\s*${date.replace(/\//g, '\\/')}$`) }).last();

/**
 * Blur bank account numbers (Bank accounts reconciled through card) now and on
 * every later render: a MutationObserver survives client-side navigation, so
 * the card is blurred the moment it mounts.
 */
async function blurAccountNumbers(page) {
  await page.evaluate(() => {
    const blur = () => {
      for (const el of document.querySelectorAll('main *')) {
        if (el.children.length === 0 && /\b\d{10,}\b/.test(el.textContent || '') && el.style.filter !== 'blur(6px)') el.style.filter = 'blur(6px)';
      }
    };
    blur();
    if (!window.__acctBlur) {
      window.__acctBlur = new MutationObserver(blur);
      window.__acctBlur.observe(document.body, { childList: true, subtree: true, characterData: true });
    }
  });
}

async function openLockDialog(page) {
  await page.getByTestId('fiscal-lock-through').fill('2026-09-30');
  await page.getByTestId('fiscal-lock-open').click();
  await page.getByTestId('fiscal-lock-confirm').waitFor({ state: 'visible', timeout: navTimeoutMs });
}

const scenes = [
  roleRouteScene('accountant', '/en/dashboard/finance/fiscal', 'Year End Closing',
    'The period lock reads 30/06/2026: nothing can be dated on or before it.', {
    weight: 17.36,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await blurAccountNumbers(page);
      await page.getByTestId('fiscal-years-table').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await blurAccountNumbers(page);
      const current = page.locator('main').getByText('30/06/2026', { exact: true });
      await expectText(page.locator('main'), '30/06/2026', 'Current lock');
      await restPointer(page, 1300, 640);
      await at(3.6);
      await pointAt(page.getByTestId('sidebar-fiscal'));
      await at(6.2);
      await pointAt(current);
      await at(9.0);
      await pointAt(lockFooter(page, '30/06/2026'));
      await at(13.0);
      await pointAt(current);
    },
  }),
  stepScene('The lock refuses',
    'Lock through 30/09/2026 is refused: 9 September recognition periods are not posted yet.',
    async (page) => {
      const at = sceneClock(page);
      await at(0.3);
      await pointAt(page.getByTestId('fiscal-lock-through'));
      await openLockDialog(page);
      await at(4.3);
      await page.getByTestId('fiscal-lock-confirm').click();
      const err = page.getByTestId('fiscal-lock-dialog-error');
      await err.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(err, '9 recognition period(s)', 'Refusal names planned recognition');
      await at(5.1);
      await pointAt(err);
      await at(12.0);
      await pointAt(err.getByText(/09\/2026/).first());
    }, { weight: 16.62 }),
  stepScene('Run month-end recognition',
    'Preview to 30/09/2026: 9 planned periods, 52,359.10. Run posts one CIL each: Dr Advance Rent, Cr Rental Income.',
    async (page) => {
      const at = sceneClock(page);
      const link = page.getByTestId('fiscal-lock-dialog-run-recognition');
      await at(0.2);
      await pointAt(link);
      await at(1.4);
      await link.click();
      const to = page.getByTestId('recognition-to-date');
      await to.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(2.6);
      await pointAt(to);
      await to.fill('2026-09-30');
      await at(4.4);
      await page.getByTestId('recognition-preview').click();
      const would = page.getByTestId('recognition-would-post');
      await would.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(would, '9', 'Would post');
      await expectText(page.getByTestId('recognition-pending-total'), '52,359.10', 'Pending total');
      await at(5.6);
      await pointAt(page.getByTestId('recognition-pending-total'));
      await at(8.1);
      await page.getByTestId('recognition-run').click();
      const dlg = page.getByRole('dialog');
      await dlg.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(8.8);
      await dlg.getByRole('button', { name: /run recognition/i }).click();
      const posted = page.getByTestId('recognition-posted');
      await posted.waitFor({ state: 'visible', timeout: 60000 });
      await expectText(posted, '9', 'Posted');
      await page.getByTestId('recognition-behind-warning').waitFor({ state: 'detached', timeout: navTimeoutMs });
      await at(24.4);
      await pointAt(posted);
      await at(27.3);
      await pointAt(page.getByRole('heading', { name: 'Month-end recognition' }));
    }, { weight: 31.02 }),
  stepScene('The lock moves',
    'Lock through 30/09/2026 again: it moves, and the sidebar shows the new date.',
    async (page) => {
      const at = sceneClock(page);
      await blurAccountNumbers(page);
      await page.getByTestId('sidebar-fiscal').click();
      await page.getByTestId('fiscal-years-table').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await blurAccountNumbers(page);
      await at(1.0);
      await openLockDialog(page);
      await at(2.6);
      await page.getByTestId('fiscal-lock-confirm').click();
      await page.getByTestId('fiscal-lock-confirm').waitFor({ state: 'detached', timeout: navTimeoutMs });
      const current = page.locator('main').getByText('30/09/2026', { exact: true });
      await current.waitFor({ state: 'visible', timeout: navTimeoutMs });
      const footer = lockFooter(page, '30/09/2026');
      await footer.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(3.8);
      await pointAt(current);
      await at(5.4);
      await pointAt(footer);
    }, { weight: 10.71 }),
  stepScene('Fiscal years',
    'FY 2025 is Closed with YEC-25/1 (net result 102,087.68); the books started on 01/09/2025.',
    async (page) => {
      const at = sceneClock(page);
      const fy = page.getByTestId('fiscal-year-2025');
      await expectText(fy, 'YEC-25/1', 'Closing journal');
      await expectText(fy, '102,087.68', 'Net result');
      await at(0.4);
      await pointAt(page.getByTestId('fiscal-years-table'));
      await at(2.2);
      await pointAt(fy.getByText('Closed'));
      await at(4.2);
      await pointAt(page.getByTestId('fiscal-books-start'));
    }, { weight: 9.23 }),
  stepScene('The year-end closing journal',
    'YEC-25/1: income debited, expenses credited, the net to Retained Earnings per building.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-journals').click();
      await page.locator('main select').first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pointAt(page.locator('main select').first());
      await page.locator('main select').first().selectOption('YEC');
      await page.getByRole('button', { name: 'Apply', exact: true }).click();
      const listed = row(page, 'YEC-25/1');
      await listed.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await listed.getByRole('link').first().click();
      const lines = page.locator('main table').first();
      await lines.getByText('Profit for the year').first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(lines, '129,687.68', 'Journal total');
      await at(7.0);
      await pointAt(row(page, 'Rental Income Palm Ridge Residences'));
      await at(9.0);
      await pointAt(row(page, 'Cleaning Services'));
      await at(11.4);
      await pointAt(row(page, '80,841.11'));
      await at(13.4);
      await pointAt(row(page, '21,246.57'));
    }, { weight: 19.57 }),
  stepScene('Trial Balance at 31/12/2025',
    'After the close income and expense net to zero and Retained Earnings holds 102,087.68; Before closing entries shows the year as reviewed: income 129,687.68 credit, expenses 27,600.00 debit.',
    async (page) => {
      const at = sceneClock(page);
      await at(0.2);
      await page.getByTestId('sidebar-trial-balance').click();
      await page.locator('#tb-as-of').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.locator('#tb-as-of').fill('2025-12-31');
      await page.getByRole('button', { name: 'Apply', exact: true }).click();
      const income = row(page, 'Sub Total — Income');
      const re = row(page, 'Retained Earnings');
      const jump = (locator) => locator.evaluate((el) => el.scrollIntoView({ block: 'center' }));
      // Wait for the 31/12/2025 figures, not the default date's first render.
      await page.locator('main tr').filter({ hasText: /Sub Total — Income\s*129,687\.68\s*129,687\.68/ })
        .waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(re, '102,087.68', 'Retained earnings');
      await page.waitForTimeout(600);
      await at(5.5);
      await jump(income);
      await pointAt(income);
      await at(9.6);
      await jump(re);
      await pointAt(re);
      await at(13.7);
      await page.getByTestId('tb-exclude-closing').evaluate((el) => el.scrollIntoView({ block: 'center' }));
      await page.getByTestId('tb-exclude-closing').check();
      await page.getByRole('button', { name: 'Apply', exact: true }).click();
      await re.waitFor({ state: 'detached', timeout: navTimeoutMs });
      await page.locator('main tr').filter({ hasText: /Sub Total — Income\s*0\.00\s*129,687\.68/ })
        .waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.waitForTimeout(400);
      await at(16.4);
      await jump(income);
      await pointAt(income);
      await at(19.9);
      await jump(income);
      await pointAt(income);
      await at(22.9);
      await pointAt(row(page, 'Sub Total — Expenses'));
    }, { weight: 25.48 }),
  stepScene('Close preview for 2026',
    'The year has not ended; recognition and VAT must be posted to 31/12/2026. Net result per building to Retained Earnings.',
    async (page) => {
      const at = sceneClock(page);
      await at(0.2);
      await blurAccountNumbers(page);
      await page.getByTestId('sidebar-fiscal').click();
      const close = page.getByTestId('fiscal-year-close-2026');
      await close.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await blurAccountNumbers(page);
      await at(2.4);
      await pointAt(close);
      await close.click();
      const blockers = page.getByTestId('fiscal-close-blockers');
      await blockers.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(blockers, 'recognition period(s)', 'Recognition blocker');
      await expectText(blockers, 'VAT tax point(s)', 'VAT blocker');
      await at(4.4);
      await pointAt(blockers);
      await at(20.2);
      await pointAt(page.getByTestId('fiscal-close-retained'));
    }, { weight: 23.27 }),
  stepScene('Re-open a closed year',
    'The Company Admin re-opens a closed year with a reason: the YEC is reversed and the lock moves back.',
    async (page) => {
      const at = sceneClock(page);
      await at(0.4);
      await page.getByRole('dialog').getByRole('button', { name: /cancel/i }).click();
      await page.getByRole('dialog').waitFor({ state: 'detached', timeout: navTimeoutMs });
      await at(1.2);
      await pointAt(page.getByTestId('fiscal-year-2025'));
      await at(6.0);
      await pointAt(page.getByTestId('fiscal-year-2025').getByText('YEC-25/1'));
    }, { weight: 19.2 }),
];

export default {
  role: 'accountant',
  anchored: true,
  warmup: [{ role: 'accountant', path: '/en/dashboard/finance/recognition' }],
  scenes,
};

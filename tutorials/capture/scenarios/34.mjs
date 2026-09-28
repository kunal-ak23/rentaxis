// Tutorial 34 — Post a tenancy contract. The Company Admin opens the pinned
// view Contracts to post, opens Sara Mansour's draft for M-1501 (the one
// contract the seed leaves unposted), reads its two charge lines and its five
// post-dated cheques, runs Review before posting and posts it. The contract
// turns Active; its six journal vouchers (one TCO, five PDR) and its twelve
// planned recognition rows are read back. The whole flow runs in one page.
//
// Posting cannot be undone in the app, so the proof and the capture each start
// from the database snapshot `pre34` (tutorials/recording-stack.sh snapshot
// pre34, restored by record-local.sh). Tenant email and phone on the overview
// are hidden (lib/page.mjs).
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { saraLeaseId } from '../lib/fixtures.mjs';
import { expectCount, expectText, pace } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const section = (page, id) => page.getByTestId(`lease-section-toggle-${id}`);

const scenes = [
  // Weights are the seconds of narration each scene covers (measured from the
  // synthesized cues); paces put each action on its cue.
  roleRouteScene('tenantAdmin', '/en/dashboard/leases', 'Contracts to post',
    'The pinned view lists every draft waiting to be posted.', {
    weight: 12.9,
    afterNavigation: async (page) => {
      await page.getByRole('heading', { name: 'Tenancy Contracts', exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 4500);
      await page.getByRole('link', { name: 'Contracts to post', exact: true }).click();
      await page.waitForURL(/view=draft/, { timeout: navTimeoutMs });
      const row = page.getByRole('row').filter({ hasText: 'M-1501' });
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(row, 'Draft', 'Draft row status');
      await expectCount(page.getByRole('row').filter({ hasText: 'Sara Mansour' }), 1, 'Sara Mansour rows');
      await pointAt(row.getByText('M-1501', { exact: true }));
    },
  }),
  stepScene('A draft with two charge lines',
    'Nothing is in the ledger yet. The deposit is held for the tenant; the rent is earned over the term.',
    async (page) => {
      await pace(page, 700);
      await page.getByRole('row').filter({ hasText: 'M-1501' }).getByText('M-1501', { exact: true }).click();
      await page.waitForURL(new RegExp(`/dashboard/leases/${saraLeaseId}`), { timeout: navTimeoutMs });
      await expectText(page.getByTestId('lease-status'), 'Draft', 'Contract status');
      await page.getByTestId('lease-lines-grid').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1100, 600);
      await pointAt(page.getByTestId('lease-status'));
      await pace(page, 6500);
      const lines = page.getByTestId('lease-lines-grid');
      await expectText(lines, 'Security Deposit', 'Deposit line');
      await expectText(lines, 'Advance Rent', 'Rent credit account');
      await expectText(page.getByTestId('lease-lines-contract-value'), '140,000.00', 'Contract value');
      await pointAt(lines.locator('thead').getByText('Credit A/C', { exact: false }).first());
      await pace(page, 3500);
      await pointAt(page.getByTestId('lease-line-row-0'));
      await pace(page, 6500);
      await pointAt(page.getByTestId('lease-line-row-1'));
    }, { weight: 23.9 }),
  stepScene('The cheque grid',
    'Five post-dated cheques. Their total has to equal the contract value, or the contract will not post.',
    async (page) => {
      await pace(page, 600);
      await page.getByTestId('lease-tab-payments').click();
      await page.getByTestId('cheque-grid').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(page.locator('[data-testid^="cheque-row-"]'), 5, 'cheque rows');
      await restPointer(page, 1100, 640);
      await pace(page, 1500);
      await pointAt(page.getByTestId('cheque-row-0'));
      await pace(page, 3500);
      await pointAt(page.getByTestId('cheque-row-4'));
      await pace(page, 2500);
      await expectText(page.getByTestId('cheque-grid-match'), 'Cheques match the contract value of 140,000.00', 'Cheque total check');
      await pointAt(page.getByTestId('cheque-grid-match'));
    }, { weight: 16.9 }),
  stepScene('Review before posting',
    'Every check a post would run, and nothing written: one TCO journal and five PDR journals, Ready to post.',
    async (page) => {
      await page.getByTestId('lease-post').click();
      await page.getByTestId('post-dry-run-ok').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('post-dry-run-ok'), 'Ready to post', 'Dry run');
      await restPointer(page, 1500, 560);
      await pace(page, 4500);
      await pointAt(page.getByText('Cheques Total', { exact: true }).last());
      await pace(page, 4000);
      await pointAt(page.getByText(/1 TCO with/).first());
      await pace(page, 6500);
      await pointAt(page.getByTestId('post-dry-run-ok'));
      await pace(page, 1000);
      await page.getByTestId('post-lease-confirm').click();
    }, { weight: 21.3 }),
  stepScene('Active',
    'Posting wrote the journals and made the contract Active. Journal Vouchers lists all six.',
    async (page) => {
      await page.getByTestId('lease-posting-journal').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('lease-status'), 'Active', 'Contract status');
      await expectText(page.getByTestId('lease-banner'), 'Posted as TCO-', 'Posted banner');
      await restPointer(page, 1100, 560);
      await pointAt(page.getByTestId('lease-banner'));
      await pace(page, 3000);
      await section(page, 'journals').click();
      const journals = page.getByTestId('lease-journals-tab').locator('table').first();
      await journals.locator('tbody tr').filter({ hasText: 'TCO' }).first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(journals.locator('tbody tr').filter({ hasText: 'PDR' }), 5, 'PDR journals');
      await expectCount(journals.locator('tbody tr').filter({ hasText: 'TCO' }), 1, 'TCO journals');
      await journals.scrollIntoViewIfNeeded();
      await pointAt(journals.locator('tbody tr').filter({ hasText: 'TCO' }).first());
    }, { weight: 8.8 }),
  stepScene('Revenue recognition',
    'The rent is planned month by month by real day count. Nothing is recognised yet.',
    async (page) => {
      await section(page, 'recognition').click();
      const schedule = page.getByTestId('recognition-schedule');
      await schedule.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectCount(schedule.locator('tbody tr').filter({ hasText: 'Planned' }), 12, 'planned recognition rows');
      await expectText(schedule, 'Matches the contract rent.', 'Schedule total');
      await schedule.scrollIntoViewIfNeeded();
      await restPointer(page, 1500, 620);
      await pace(page, 2500);
      await pointAt(schedule.locator('tbody tr').nth(1));
      await pace(page, 6000);
      await pointAt(schedule.getByText('Matches the contract rent.'));
      await pace(page, 5500);
      await page.getByTestId('lease-more-actions').click();
      const menu = page.getByRole('menu');
      await menu.waitFor({ state: 'visible' });
      for (const item of ['Amend Lines', 'Extend Contract', 'Add charge']) {
        await menu.getByRole('menuitem', { name: item, exact: true }).waitFor({ state: 'visible' });
      }
      await pace(page, 1200);
      await pointAt(menu.getByRole('menuitem', { name: 'Amend Lines', exact: true }));
    }, { weight: 24.4 }),
];

export default { role: 'tenantAdmin', scenes };

// Tutorial 34. Default role for scenes that do not name one: tenantAdmin.
import { navTimeoutMs } from '../lib/context.mjs';
import { saraLeaseId } from '../lib/fixtures.mjs';
import { roleRouteScene } from '../lib/scenes.mjs';

// ── Accounting v2 ──────────────────────────────────────────────────────────
// 34 posts the draft the seed deliberately leaves unposted, so it records
// first; 36 closes the periods that post opens up, and 37 reads the ledger
// all three of them wrote. Selectors are the ones
// `web/e2e/finance/accounting-v2.spec.ts` already proves against this build.

const scenes = [
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${saraLeaseId}`, 'Open the draft contract',
    'A tenancy contract starts as a draft. Nothing it holds has reached the ledger yet, so it can still be edited freely.', {
    weight: 70,
    afterNavigation: async (page) => {
      await page.getByTestId('lease-status').waitFor({ state: 'visible', timeout: 20_000 });
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${saraLeaseId}`, 'Read the charge lines',
    'The security deposit and the rent are separate lines because each is credited to its own account and each behaves differently.', {
    weight: 70,
    afterNavigation: async (page) => {
      await page.getByTestId('lease-lines-grid').waitFor({ state: 'visible', timeout: 20_000 });
      await page.getByTestId('lease-lines-totals').waitFor({ state: 'visible' });
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${saraLeaseId}`, 'Read the cheque grid',
    'The grid records every cheque the renter handed over. It has to add up to the contract value or the contract will not post.', {
    weight: 60,
    afterNavigation: async (page) => {
      await page.getByTestId('lease-tab-payments').click();
      await page.getByTestId('cheque-grid').waitFor({ state: 'visible', timeout: 20_000 });
      await page.getByTestId('cheque-grid-match').waitFor({ state: 'visible' });
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${saraLeaseId}`, 'Post the contract',
    'Posting writes the accounting entries and makes the contract active.', {
    weight: 60,
    afterNavigation: async (page) => {
      await page.getByTestId('lease-post').click();
      // The dry run is the dialog's own answer to "would this post?" — the
      // confirm button is only meaningful once it is on screen.
      await page.getByTestId('post-dry-run-ok').waitFor({ state: 'visible', timeout: 20_000 });
      await page.getByTestId('post-lease-confirm').click();
      await page.getByTestId('lease-posting-journal').waitFor({ state: 'visible', timeout: navTimeoutMs });
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${saraLeaseId}`, 'Read the journals it wrote',
    'One tenancy contract journal, and one post-dated cheque journal for every row of the grid.', {
    weight: 40,
    afterNavigation: async (page) => {
      await page.getByTestId('lease-tab-payments').click();
      await page.getByTestId('lease-section-toggle-journals').click();
      await page.getByTestId('lease-journals-tab').locator('table').first()
        .locator('tbody tr').filter({ hasText: 'TCO' }).first()
        .waitFor({ state: 'visible', timeout: 20_000 });
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${saraLeaseId}`, 'Read the planned recognition',
    'The rent is already divided across the term by its real day count. None of it is posted yet.', {
    weight: 30,
    afterNavigation: async (page) => {
      await page.getByTestId('lease-tab-payments').click();
      await page.getByTestId('lease-section-toggle-recognition').click();
      await page.getByTestId('recognition-schedule').waitFor({ state: 'visible', timeout: 20_000 });
    },
  }),
];

export default { role: 'tenantAdmin', scenes };

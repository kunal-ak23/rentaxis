// Tutorial 37. Default role for scenes that do not name one: tenantAdmin.
import { contractYearStart, fatimaRenterId, todayIso } from '../lib/fixtures.mjs';
import { roleRouteScene } from '../lib/scenes.mjs';

const scenes = [
  roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${fatimaRenterId}`,
    'Open a renter ledger',
    'One block per account the renter has touched, and inside each one row per entry with its document, its counter account and a running balance.', {
    weight: 75,
    afterNavigation: async (page) => {
      await page.locator('#ledger-from').fill(contractYearStart());
      await page.getByRole('button', { name: 'Apply' }).click();
      await page.getByRole('link', { name: /^TCO-/ }).first().waitFor({ state: 'visible', timeout: 20_000 });
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${fatimaRenterId}`,
    'Rent receivable and what is left on it',
    'The contract debited the whole value and the cheque journals credited it back, so what the renter owes lives in the register, not here.', {
    weight: 60,
    afterNavigation: async (page) => {
      await page.locator('#ledger-from').fill(contractYearStart());
      await page.getByRole('button', { name: 'Apply' }).click();
      await page.getByText(/rent receivable/i).first().waitFor({ state: 'visible', timeout: 20_000 });
      await page.getByText(/rent receivable/i).first().evaluate((el) => el.scrollIntoView({ block: 'start' }));
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${fatimaRenterId}`,
    'Until a cheque comes back',
    'A returned cheque debits rent receivable again. That balance is the only thing standing between this renter and a clean account.', {
    weight: 50,
    afterNavigation: async (page) => {
      await page.locator('#ledger-from').fill(contractYearStart());
      await page.getByRole('button', { name: 'Apply' }).click();
      const returned = page.getByRole('row').filter({ has: page.getByRole('link', { name: /^CBR-/ }) }).first();
      await returned.waitFor({ state: 'visible', timeout: 20_000 });
      await returned.evaluate((el) => el.scrollIntoView({ block: 'center' }));
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${fatimaRenterId}`,
    'Post-dated cheques and advance rent',
    'One block holds the paper you still have; the other winds down to zero as the rent is earned month by month.', {
    weight: 50,
    afterNavigation: async (page) => {
      await page.locator('#ledger-from').fill(contractYearStart());
      await page.getByRole('button', { name: 'Apply' }).click();
      await page.getByText(/advance rent/i).first().waitFor({ state: 'visible', timeout: 20_000 });
      await page.getByText(/advance rent/i).first().evaluate((el) => el.scrollIntoView({ block: 'start' }));
    },
  }),
  roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${fatimaRenterId}`,
    'Open the journal behind a row',
    'A journal always balances, and it is never edited or deleted, only reversed, which writes a mirror entry and links the two.', {
    weight: 45,
    afterNavigation: async (page) => {
      await page.locator('#ledger-from').fill(contractYearStart());
      await page.getByRole('button', { name: 'Apply' }).click();
      await page.getByRole('link', { name: /^TCO-/ }).first().click();
      await page.waitForURL(/\/dashboard\/finance\/journals\/[0-9a-f-]{36}/, { timeout: 20_000 });
      // Not `reverse-journal`: that button is MANUAL-only by design
      // (`journals/__tests__/reverse-manual-only.test.tsx`), and a tenancy
      // contract journal is LEASE-sourced. The lines themselves are the point.
      await page.locator('table tbody tr').first().waitFor({ state: 'visible', timeout: 20_000 });
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/finance/trial-balance', 'Check the whole ledger',
    'Total debits equal total credits. That single check is what tells you the ledger behind every screen is sound.', {
    weight: 30,
    afterNavigation: async (page) => {
      await page.locator('#tb-as-of').fill(todayIso());
      await page.getByRole('button', { name: 'Apply' }).click();
      const grand = page.locator('tr').filter({ hasText: 'Grand Total' }).first();
      await grand.waitFor({ state: 'visible', timeout: 20_000 });
      await grand.evaluate((el) => el.scrollIntoView({ block: 'center' }));
    },
  }),
];

export default { role: 'tenantAdmin', scenes };

import { test, expect } from '../fixtures/auth.fixture';

/**
 * Sidebar visibility tests per role.
 * Each test runs under multiple role projects via playwright.config.ts.
 * We detect the current role from testInfo.project.name and adjust
 * expectations when the "super-admin" project is actually a TENANT_ADMIN.
 */

type SidebarExpectation = {
  visible: string[];
  hidden: string[];
};

// accounting-v2 plan 1 removed the v1 finance menu: Transactions, Reports and
// Payments are gone, replaced by the ledger group (chart of accounts, journal
// vouchers, general ledger, tenant ledger, trial balance) plus the cheque
// register. The removed labels stay in `hidden` so a v1 page coming back is a
// failure rather than a silent restoration. See MvpSidebar.tsx `financeItems`.
const LEDGER_LINKS = ['Chart of Accounts', 'Journal Vouchers', 'General Ledger', 'Tenant Ledger', 'Trial Balance', 'Cheque Register'];
const V1_FINANCE_LINKS = ['Transactions', 'Reports'];

const SUPER_ADMIN_EXPECTATIONS: SidebarExpectation = {
  visible: ['Dashboard', 'Properties', 'Renters', 'Leases', 'Tenants', 'Users', ...LEDGER_LINKS],
  hidden: ['My Leases', 'My Unit', ...V1_FINANCE_LINKS],
};

const TENANT_ADMIN_EXPECTATIONS: SidebarExpectation = {
  visible: ['Dashboard', 'Properties', 'Renters', 'Leases', 'Users', ...LEDGER_LINKS],
  hidden: ['Tenants', 'My Leases', 'My Unit', ...V1_FINANCE_LINKS],
};

const ROLE_EXPECTATIONS: Record<string, SidebarExpectation> = {
  'tenant-admin': TENANT_ADMIN_EXPECTATIONS,
  'property-manager': {
    visible: ['Dashboard', 'Properties', 'Renters', 'Leases'],
    hidden: ['Tenants', 'Users', 'Chart of Accounts', 'Transactions', 'Reports', 'Payments', 'My Leases', 'My Unit'],
  },
  'tenant-user': {
    visible: ['Dashboard', 'My Unit'],
    hidden: ['Properties', 'Renters', 'Leases', 'Tenants', 'Users', 'Chart of Accounts', 'Transactions'],
  },
  'renter': {
    visible: ['Dashboard', 'My Leases'],
    hidden: ['Properties', 'Renters', 'Leases', 'Tenants', 'Users', 'Chart of Accounts', 'Transactions'],
  },
};

test.describe('Sidebar visibility', () => {
  test('shows correct menu items for current role', async ({ page, testContext }, testInfo) => {
    const project = testInfo.project.name;

    let expectations: SidebarExpectation | undefined;
    if (project === 'super-admin') {
      // When default admin is unavailable, super-admin project uses TENANT_ADMIN
      expectations = testContext.adminRole === 'SUPER_ADMIN'
        ? SUPER_ADMIN_EXPECTATIONS
        : TENANT_ADMIN_EXPECTATIONS;
    } else {
      expectations = ROLE_EXPECTATIONS[project];
    }

    if (!expectations) {
      test.skip();
      return;
    }

    // Navigate to dashboard.
    //
    // Not `networkidle`: a fetch whose non-2xx body is never read leaves the
    // request in flight for ever, so the idle state never arrives. A RENTER or
    // TENANT_USER on /dashboard gets two of those (403 on
    // /v1/dashboard/summary and /monthly-collections), which is what used to
    // hang this test for those two projects. The sidebar itself is what the
    // assertions need, so wait for that.
    await page.goto('/en/dashboard');

    const sidebar = page.locator('aside');
    await expect(sidebar).toBeVisible({ timeout: 15_000 });

    // Check visible items
    for (const item of expectations.visible) {
      await expect(
        sidebar.getByText(item, { exact: false }),
        `Expected "${item}" to be visible for ${project}`
      ).toBeVisible({ timeout: 5_000 });
    }

    // Check hidden items
    for (const item of expectations.hidden) {
      await expect(
        sidebar.getByText(item, { exact: true }),
        `Expected "${item}" to be hidden for ${project}`
      ).toBeHidden();
    }
  });
});

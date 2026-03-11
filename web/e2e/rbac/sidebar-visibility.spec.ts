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

const SUPER_ADMIN_EXPECTATIONS: SidebarExpectation = {
  visible: ['Dashboard', 'Properties', 'Renters', 'Leases', 'Tenants', 'Users', 'Chart of Accounts', 'Transactions', 'Reports', 'Payments'],
  hidden: ['My Leases', 'My Unit'],
};

const TENANT_ADMIN_EXPECTATIONS: SidebarExpectation = {
  visible: ['Dashboard', 'Properties', 'Renters', 'Leases', 'Users', 'Chart of Accounts', 'Transactions', 'Reports', 'Payments'],
  hidden: ['Tenants', 'My Leases', 'My Unit'],
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

    // Navigate to dashboard
    await page.goto('/en/dashboard');
    await page.waitForLoadState('networkidle');

    const sidebar = page.locator('aside');

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

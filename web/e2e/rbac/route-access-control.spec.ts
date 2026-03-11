import { test, expect } from '../fixtures/auth.fixture';

/**
 * Route access control tests.
 * Verifies that authorized roles can access their routes and see content.
 * Note: The app does not implement client-side route guards, so unauthorized
 * users can navigate to any URL but will see empty/restricted data.
 */

type RouteCheck = {
  path: string;
  allowedProjects: string[];
  /** Text that proves content loaded for authorized users */
  contentIndicator: string | RegExp;
};

const ROUTES: RouteCheck[] = [
  {
    path: '/en/dashboard',
    allowedProjects: ['super-admin', 'tenant-admin', 'property-manager', 'tenant-user', 'renter'],
    contentIndicator: /dashboard/i,
  },
  {
    path: '/en/dashboard/properties',
    allowedProjects: ['super-admin', 'tenant-admin', 'property-manager'],
    contentIndicator: /propert/i,
  },
  {
    path: '/en/dashboard/leases',
    allowedProjects: ['super-admin', 'tenant-admin'],
    contentIndicator: /lease/i,
  },
  {
    path: '/en/dashboard/renters',
    allowedProjects: ['super-admin', 'tenant-admin'],
    contentIndicator: /renter/i,
  },
  {
    path: '/en/dashboard/finance/accounts',
    allowedProjects: ['super-admin', 'tenant-admin'],
    contentIndicator: /account/i,
  },
  {
    path: '/en/dashboard/finance/payments',
    allowedProjects: ['super-admin', 'tenant-admin'],
    contentIndicator: /payment/i,
  },
  {
    path: '/en/dashboard/settings/gateway',
    allowedProjects: ['super-admin', 'tenant-admin'],
    contentIndicator: /gateway|payment/i,
  },
  {
    path: '/en/dashboard/renter-portal',
    allowedProjects: ['renter'],
    contentIndicator: /welcome|my leases|no leases|lease/i,
  },
  {
    path: '/en/superadmin/tenants',
    allowedProjects: ['super-admin'],
    contentIndicator: /tenant|organization/i,
  },
  {
    path: '/en/superadmin/users',
    allowedProjects: ['super-admin', 'tenant-admin'],
    contentIndicator: /user/i,
  },
];

test.describe('Route access control', () => {
  for (const route of ROUTES) {
    test(`${route.path} - authorized roles see content`, async ({ page, testContext }, testInfo) => {
      const project = testInfo.project.name;

      // Skip non-role projects
      if (['anonymous', 'setup', 'auth-setup'].includes(project)) {
        test.skip();
        return;
      }

      // Determine if this project should be allowed
      let isAllowed = route.allowedProjects.includes(project);

      // When super-admin is actually TENANT_ADMIN, adjust access
      if (project === 'super-admin' && testContext.adminRole !== 'SUPER_ADMIN') {
        // Super-admin project acts as tenant-admin
        isAllowed = route.allowedProjects.includes('tenant-admin');
      }

      // Only test authorized access - skip unauthorized roles
      if (!isAllowed) {
        test.skip();
        return;
      }

      await page.goto(route.path);
      await page.waitForLoadState('networkidle');

      // Authorized role should see actual content
      const body = page.locator('body');
      await expect(body).toContainText(route.contentIndicator, { timeout: 10_000 });
    });
  }
});

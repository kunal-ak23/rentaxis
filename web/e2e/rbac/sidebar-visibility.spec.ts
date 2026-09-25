import { test, expect } from '../fixtures/auth.fixture';

/**
 * Navigation visibility per role — the icon rail + section panel shell
 * (admin UI simplification, spec 2026-09-25 §1a).
 * Each test runs under multiple role projects via playwright.config.ts.
 * We detect the current role from testInfo.project.name and adjust
 * expectations when the "super-admin" project is actually a TENANT_ADMIN.
 *
 * The rail shows sections, not pages; which pages each section's panel lists
 * per role is pinned by src/lib/nav/__tests__/navModel.test.ts, and "no role
 * gained or lost a destination" by rbacParity.test.ts.
 */

type RailExpectation = { visible: string[]; hidden: string[] };

const SUPER_ADMIN_RAIL: RailExpectation = {
  visible: ['home', 'leasing', 'collection', 'accounting', 'operations', 'settings'], hidden: [],
};
const TENANT_ADMIN_RAIL: RailExpectation = SUPER_ADMIN_RAIL;

const RAIL: Record<string, RailExpectation> = {
  'tenant-admin': TENANT_ADMIN_RAIL,
  'property-manager': { visible: ['home', 'leasing', 'collection', 'accounting', 'operations'], hidden: ['settings'] },
  'tenant-user': { visible: ['home'], hidden: ['leasing', 'collection', 'accounting', 'operations', 'settings'] },
  'renter': { visible: ['home'], hidden: ['leasing', 'collection', 'accounting', 'settings'] },
};

test.describe('Sidebar visibility', () => {
  test('shows correct rail sections for current role', async ({ page, testContext }, testInfo) => {
    const project = testInfo.project.name;

    let expectations: RailExpectation | undefined;
    if (project === 'super-admin') {
      expectations = testContext.adminRole === 'SUPER_ADMIN' ? SUPER_ADMIN_RAIL : TENANT_ADMIN_RAIL;
    } else {
      expectations = RAIL[project];
    }

    if (!expectations) {
      test.skip();
      return;
    }

    // Not `networkidle`: a RENTER or TENANT_USER on /dashboard gets 403s whose
    // bodies are never read, so the idle state never arrives. Wait for the rail.
    await page.goto('/en/dashboard');

    const rail = page.getByTestId('nav-rail');
    await expect(rail).toBeVisible({ timeout: 15_000 });

    for (const id of expectations.visible) {
      await expect(rail.locator(`[data-rail="${id}"]`), `Expected rail "${id}" for ${project}`).toBeVisible({ timeout: 5_000 });
    }
    for (const id of expectations.hidden) {
      await expect(rail.locator(`[data-rail="${id}"]`), `Expected no rail "${id}" for ${project}`).toHaveCount(0);
    }
    // TENANT_USER's "My Unit" used to 404; the link is gone for everyone.
    await expect(page.locator('a[href$="/dashboard/my-unit"]')).toHaveCount(0);
  });
});

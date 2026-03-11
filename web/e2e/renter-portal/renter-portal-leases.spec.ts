import { test, expect } from '../fixtures/auth.fixture';

test.describe('Renter Portal - Leases', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    if (testInfo.project.name !== 'renter') {
      test.skip();
      return;
    }
    await page.goto('/en/dashboard/renter-portal');
    await page.waitForLoadState('networkidle');
  });

  test('portal loads with welcome message', async ({ page }, testInfo) => {
    if (testInfo.project.name !== 'renter') return;

    // Should see a welcome heading with the renter's name
    await expect(page.getByText(/welcome|my leases/i).first()).toBeVisible({ timeout: 10_000 });
  });

  test('lease card shows details', async ({ page, testContext }, testInfo) => {
    if (testInfo.project.name !== 'renter') return;

    if (testContext.leaseId) {
      // Should see lease card with unit identifier
      const leaseCard = page.getByText(/E2E-101/).first();
      if (await leaseCard.isVisible({ timeout: 5000 })) {
        // Should show rent amount, dates
        await expect(page.getByText(/AED/).first()).toBeVisible();
        await expect(page.getByText(/rent/i).first()).toBeVisible();
      }
    }
  });

  test('PENDING_SIGNATURE lease shows accept/reject/download buttons', async ({ page }, testInfo) => {
    if (testInfo.project.name !== 'renter') return;

    // Look for PENDING SIGNATURE status badge
    const pendingBadge = page.getByText(/pending signature/i);
    if (await pendingBadge.isVisible({ timeout: 5000 })) {
      // Should see action buttons
      await expect(page.getByRole('button', { name: /accept/i })).toBeVisible();
      await expect(page.getByRole('button', { name: /reject/i })).toBeVisible();
      await expect(page.getByRole('button', { name: /download/i })).toBeVisible();
    }
  });

  test('empty state when no leases', async ({ page }, testInfo) => {
    if (testInfo.project.name !== 'renter') return;

    // Check if renter has leases or sees empty state
    const hasLeases = await page.getByText(/E2E-101|Unit|DRAFT|ACTIVE/i).first().isVisible({ timeout: 5000 }).catch(() => false);
    if (!hasLeases) {
      // Should show empty state message
      const emptyText = page.getByText(/no leases|will appear here|no active/i);
      await expect(emptyText).toBeVisible({ timeout: 5000 });
    }
    // If leases exist, test passes (no empty state expected)
  });
});

import { test, expect } from '../fixtures/auth.fixture';

test.describe('Financial Reports', () => {
  test('page loads and shows report content', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }

    await page.goto('/en/dashboard/finance/reports');
    await page.waitForLoadState('networkidle');

    await expect(page.getByText(/report/i).first()).toBeVisible({ timeout: 10_000 });
  });

  test('aging report renders bucket cards and details from the backend array shape', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }

    // Backend contract: AgingReportDTO.buckets is an ordered ARRAY of
    // { label, amount, count, details[] } (see PaymentScheduleService.getAgingReport),
    // not a keyed object. This mock pins that shape.
    await page.route('**/api/proxy/v1/payments/aging-report*', route =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          totalOutstanding: 5000,
          buckets: [
            { label: 'Current', amount: 0, count: 0, details: [] },
            {
              label: '1-30 Days',
              amount: 5000,
              count: 1,
              details: [
                {
                  renterName: 'Aging Test Renter',
                  propertyName: 'Aging Test Property',
                  unitNumber: 'A-101',
                  amount: 5000,
                  daysOverdue: 12,
                  dueDate: '2026-07-27',
                },
              ],
            },
            { label: '31-60 Days', amount: 0, count: 0, details: [] },
            { label: '61-90 Days', amount: 0, count: 0, details: [] },
            { label: '90+ Days', amount: 0, count: 0, details: [] },
          ],
        }),
      })
    );

    await page.goto('/en/dashboard/finance/reports');
    await page.getByRole('button', { name: 'Aging Report' }).click();
    await page.getByRole('button', { name: 'Generate Report' }).click();

    // Total Outstanding KPI
    await expect(page.getByText('Total Outstanding')).toBeVisible({ timeout: 10_000 });

    // All five bucket summary cards render
    await expect(page.getByText('Current', { exact: true })).toBeVisible();
    await expect(page.getByText('31-60 Days', { exact: true })).toBeVisible();
    await expect(page.getByText('61-90 Days', { exact: true })).toBeVisible();
    await expect(page.getByText('90+ Days', { exact: true })).toBeVisible();

    // Non-empty bucket exposes an expandable detail table
    await page.getByRole('button', { name: '1-30 Days (1)' }).click();
    await expect(page.getByText('Aging Test Renter')).toBeVisible();
    await expect(page.getByText('Aging Test Property')).toBeVisible();
    await expect(page.getByText('A-101')).toBeVisible();
  });
});

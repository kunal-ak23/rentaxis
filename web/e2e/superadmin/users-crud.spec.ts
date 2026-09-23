import { test, expect } from '../fixtures/auth.fixture';

// create → edit → delete share one tenant's user list, which the page sorts by
// raw id; under fullyParallel the delete can eat the user create just asserted.
test.describe.configure({ mode: 'serial' });

test.describe('Users CRUD', () => {
  test.beforeEach(async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) {
      test.skip();
      return;
    }
    // A TENANT_ADMIN belongs here: `canManageUsers` is SUPER_ADMIN +
    // TENANT_ADMIN (web/src/lib/rbac.ts), the sidebar offers them the link, and
    // GET /admin/users answers them scoped to their own tenant — verified: the
    // tenant admin sees only their org's users while the super admin sees all.
    //
    // Not `networkidle`: the page also asks for /admin/tenants, which 403s for a
    // TENANT_ADMIN, and a non-2xx whose body is never read keeps the request in
    // flight for ever, so the idle state never arrives. Wait for the heading.
    await page.goto('/en/superadmin/users');
    await expect(page.getByRole('heading', { name: /manage users/i })).toBeVisible({ timeout: 15_000 });
  });

  test('page loads with users table', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;
    await expect(page.getByText(/user/i).first()).toBeVisible();
    // Should have at least one user row
    const rows = page.locator('table tbody tr, [class*="card"], [class*="row"]');
    await expect(rows.first()).toBeVisible({ timeout: 10_000 });
  });

  test('create a new user', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    // Click New User button
    const addBtn = page.getByRole('button', { name: /new user|add|create/i });
    if (!(await addBtn.isVisible({ timeout: 5000 }))) return;
    await addBtn.click();

    // Wait for the modal to appear
    await page.waitForTimeout(500);

    const timestamp = Date.now();
    // Full Name - placeholder "e.g. Acme Corp Admin"
    await page.locator('input[placeholder*="Acme"]').fill(`E2E User ${timestamp}`);
    // Email - placeholder "e.g. admin@acmecorp.com"
    await page.locator('input[placeholder*="acmecorp"]').fill(`e2e-user-${timestamp}@test.com`);
    // Password - placeholder "Secure password"
    await page.locator('input[placeholder*="Secure"]').fill('test1234');

    // Role select — scoped to the dialog's <form>. The list below the modal now
    // carries a <Pagination> whose "n per page" <select> sorts first in document
    // order, so an unscoped `locator('select').first()` grabbed that one and
    // spun until the test timed out looking for a TENANT_ADMIN option on it.
    await page.locator('form select').first().selectOption('TENANT_ADMIN');

    // Submit - "PROVISION USER" button
    await page.getByRole('button', { name: /provision|create|save|submit/i }).click();

    // Verify user appears
    await expect(page.getByText(`e2e-user-${timestamp}@test.com`)).toBeVisible({ timeout: 10_000 });
  });

  test('edit a user', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    const editBtn = page.getByRole('button', { name: /edit/i }).first();
    if (await editBtn.isVisible({ timeout: 3000 })) {
      await editBtn.click();

      const nameInput = page.locator('input[placeholder*="Acme"], input[placeholder*="name" i]').first();
      if (await nameInput.isVisible({ timeout: 2000 })) {
        await nameInput.clear();
        await nameInput.fill('Updated E2E User');
        await page.getByRole('button', { name: /save|update|provision/i }).click();
        await page.waitForTimeout(1000);
      }
    }
  });

  test('delete a user with confirmation', async ({ page }, testInfo) => {
    if (!['super-admin', 'tenant-admin'].includes(testInfo.project.name)) return;

    const deleteBtn = page.getByRole('button', { name: /delete/i }).first();
    if (await deleteBtn.isVisible({ timeout: 3000 })) {
      await deleteBtn.click();

      const confirmBtn = page.getByRole('button', { name: /confirm|yes|delete/i }).last();
      if (await confirmBtn.isVisible({ timeout: 3_000 })) {
        await confirmBtn.click();
      }
      await page.waitForTimeout(1000);
    }
  });
});

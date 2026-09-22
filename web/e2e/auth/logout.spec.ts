import { test, expect } from '../fixtures/auth.fixture';

test.describe('Logout', () => {
  // Runs under every role project (each has a storageState).
  test('clicking Logout redirects to login page', async ({ page }) => {
    await page.goto('/en/dashboard/properties');

    // `networkidle` is not usable on this app: any fetch that comes back non-2xx
    // has its body left unread by the caller, so Chromium keeps the request
    // in flight for ever and the idle state never arrives. A RENTER on
    // /dashboard/properties gets exactly that (403 on /v1/properties), which is
    // why this test used to hang for the renter project. Wait for the chrome
    // the test actually needs instead.
    const profileMenu = page.getByTestId('profile-menu');
    await expect(profileMenu).toBeVisible({ timeout: 15_000 });

    // Logout no longer sits in the sidebar — it lives in the profile popover
    // in TopHeader, behind the avatar button.
    await profileMenu.click();
    await page.getByTestId('logout').click();

    // Should redirect to login page. `waitUntil: 'commit'` for the same reason
    // the wait above is not `networkidle`: signOut() bounces back through
    // /dashboard/properties before the proxy sends us to the login page, and
    // that intermediate page never fires `load` for a role whose 403 body is
    // left unread. The URL landing on the login page is the assertion.
    await page.waitForURL(/\/auth\/login/, { timeout: 15_000, waitUntil: 'commit' });
    expect(page.url()).toContain('/auth/login');
  });
});

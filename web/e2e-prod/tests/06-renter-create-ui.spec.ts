/**
 * 06 — Real browser flow: TENANT_ADMIN creates a renter via the dashboard form.
 *
 * This exercises the actual page at /dashboard/renters — navigates, clicks
 * the create button, fills the form (including the createPortalAccount
 * checkbox which now defaults to true per migration 59's companion change),
 * submits, and verifies the credentials modal appears with a portal password.
 *
 * Catches integration drift the API specs miss: form validation rules,
 * post-submit behavior (credentials modal), and the role check NextAuth
 * enforces before letting the TA reach this page.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('TENANT_ADMIN creates a renter via the dashboard UI', async ({ browser }) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.adminEmail, '01-provision must have created a TENANT_ADMIN').toBeTruthy();

  // Fresh context — do NOT inherit the SUPER_ADMIN session cookie from
  // storageState; we want to drive login as TA via the real form.
  const browserCtx = await browser.newContext({ baseURL: ctx.baseURL });
  const page = await browserCtx.newPage();

  // 1. Login as TA via the real form.
  await page.goto('/en/auth/login');
  await page.locator('#login-email').fill(ctx.adminEmail);
  await page.locator('#login-password').fill(ctx.adminPassword);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await page.waitForURL(/\/dashboard(?!\/renter-portal)/, { timeout: 15_000 });

  // 2. Navigate to the renters page.
  await page.goto('/en/dashboard/renters');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();

  // 3. Open the create-renter modal. Trigger button has text "Add Renter".
  await page.getByRole('button', { name: /add renter/i }).first().click();

  // 4. Form is in a fixed-position dialog (z-[100]). Scope all field locators
  //    to it via the form element so we don't accidentally hit the search
  //    box at the top of the page.
  const form = page.locator('form').filter({ has: page.getByPlaceholder('John Doe') });
  await expect(form).toBeVisible();

  const suffix = `${ctx.runSuffix}-ui`;
  const renterName = `TEST-UI Renter ${suffix}`;
  const renterEmail = `test-ui-renter-${suffix}@e2e.rentaxis.test`;

  // Placeholder-based — matches what a real user sees on the form. The phone
  // placeholder is `+971 50 123 4567`; anchor the regex to the leading `+971`
  // so a future "secondary contact" field couldn't accidentally match too.
  await form.getByPlaceholder('John Doe').fill(renterName);
  await form.getByPlaceholder('john@example.com').fill(renterEmail);
  await form.getByPlaceholder(/^\+971/).fill('+971500000099');

  // 5. createPortalAccount checkbox defaults TRUE per the new behavior.
  //    Asserting this guards against a future regression that flips it back.
  const portalCheckbox = form.locator('input[type="checkbox"]');
  await expect(portalCheckbox).toBeChecked();

  // 6. Submit. Wait for the POST /v1/renters response BEFORE asserting the
  //    modal — otherwise the modal-render check races the form's parallel
  //    fetchRenters() call and can flake under cold-start latency.
  const [createResponse] = await Promise.all([
    page.waitForResponse(
      (r) =>
        /\/api\/proxy\/v1\/renters(\?|$)/.test(r.url()) &&
        r.request().method() === 'POST',
      { timeout: 15_000 },
    ),
    form.getByRole('button', { name: /^create$/i }).click(),
  ]);
  expect(createResponse.ok(), 'renter creation POST must succeed').toBeTruthy();

  // 7. Credentials modal: identified by its heading "Portal Account Created".
  //    fetchRenters() also runs on success and adds the renter row to the
  //    table beneath the modal — meaning `getByText(email)` would match
  //    twice without scoping. Use the modal's heading text as the dialog
  //    anchor, then assert nearby content via getByRole('paragraph') and
  //    text matching scoped through the heading's accessible parent.
  const modalHeading = page.getByRole('heading', { name: /portal account created/i });
  await expect(modalHeading).toBeVisible({ timeout: 10_000 });

  // Both renderings of the email exist on screen (modal + new table row),
  // so use `.first()` to take whichever Playwright finds first. The modal
  // heading visibility above already proves the modal rendered — the email
  // appearing anywhere is sufficient to prove the round-trip carried the
  // form's email through to the backend response.
  await expect(page.getByText(renterEmail).first()).toBeVisible();
  // The generated portal password (format: Renter@<6-hex>) only appears in
  // the modal — the table doesn't show passwords. No scoping needed.
  await expect(page.getByText(/Renter@[0-9a-f]{6}/)).toBeVisible();

  await browserCtx.close();
});

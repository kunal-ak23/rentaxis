// Multi-step UI flows reused by more than one scenario.
import { navTimeoutMs } from './context.mjs';
import { bookingPreferredDate, parkingSpotNumber } from './fixtures.mjs';
import { waitForApp } from './page.mjs';

export async function signInWithCredentials(page, credentials) {
  await page.locator('#login-email').fill(credentials.email);
  await page.locator('#login-password').fill(credentials.password);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await page.waitForURL(/\/(en|ar)\/dashboard/, { timeout: navTimeoutMs });
  await waitForApp(page);
}

export async function openGlobalSearch(page, query) {
  await page.keyboard.press(process.platform === 'darwin' ? 'Meta+K' : 'Control+K');
  const dialog = page.getByRole('dialog', { name: /search/i });
  await dialog.waitFor({ state: 'visible' });
  await dialog.getByRole('textbox').fill(query);
  await dialog.getByRole('button').filter({ hasText: query }).first().waitFor({ state: 'visible', timeout: navTimeoutMs });
}

export async function openPreparedParkingRequest(page) {
  const card = page.getByText(parkingSpotNumber, { exact: true }).locator('../..');
  await card.getByRole('button', { name: 'Request', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: `Request ${parkingSpotNumber}` });
  await dialog.waitFor({ state: 'visible' });
  await dialog.locator('input[type="date"]').fill(bookingPreferredDate);
  await dialog.locator('textarea').fill('Tutorial parking request for a second family vehicle.');
  return dialog;
}

export async function openPreparedBookingApproval(page) {
  const row = page.getByRole('row').filter({ hasText: parkingSpotNumber }).filter({ hasText: 'Ahmed Hassan' });
  await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
  await row.click();
  const drawer = page.getByRole('dialog', { name: 'Booking Request' });
  await drawer.waitFor({ state: 'visible' });
  await drawer.getByRole('textbox').fill('Approved for the tutorial resident after checking unit and availability.');
  return drawer;
}

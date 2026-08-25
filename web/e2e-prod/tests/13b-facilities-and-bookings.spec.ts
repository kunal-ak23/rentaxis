/**
 * 13b — Renter-visible facilities and manager booking decisions.
 * Runs before settlement while the renter still has an ACTIVE lease.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('renter requests amenity and parking access and manager decides them', async ({ browser }) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.lease?.status).toMatch(/ACTIVE/i);

  const pmCtx = await loginAsNextAuth(ctx.baseURL, ctx.pmEmail, ctx.pmPassword);
  await setActiveTenant(pmCtx, ctx.tenant.id);
  const renterCtx = await loginAsNextAuth(
    ctx.baseURL,
    ctx.renter.email,
    ctx.renter.portalPassword,
  );

  const amenity = await api.createAmenity(pmCtx, {
    propertyId: ctx.property.id,
    nameEn: `TEST-Residents Lounge ${ctx.runSuffix}`,
    bookable: true,
  });
  const parking = await api.createParkingSpot(pmCtx, {
    propertyId: ctx.property.id,
    spotNumber: `TEST-BOOK-${ctx.runSuffix}`,
    level: 'B3',
  });

  const facilities = await api.getMyFacilities(renterCtx);
  expect(facilities.amenities.some((item) => item.id === amenity.id)).toBeTruthy();
  expect(facilities.parkingSpots.some((item) => item.id === parking.id)).toBeTruthy();

  const preferredDate = new Date();
  preferredDate.setUTCDate(preferredDate.getUTCDate() + 7);
  const date = preferredDate.toISOString().slice(0, 10);

  const amenityBooking = await api.createBooking(renterCtx, {
    resourceType: 'AMENITY',
    resourceId: amenity.id,
    unitId: ctx.unit.id,
    preferredDate: date,
    note: 'TEST-Family gathering',
  });
  expect(amenityBooking.status).toBe('PENDING');
  const approvedAmenity = await api.approveBooking(
    pmCtx,
    amenityBooking.id,
    'Approved for production E2E',
  );
  expect(approvedAmenity.status).toBe('APPROVED');

  const renterBrowser = await browser.newContext({ baseURL: ctx.baseURL });
  const renterPage = await renterBrowser.newPage();
  await renterPage.goto('/en/auth/login');
  await renterPage.locator('#login-email').fill(ctx.renter.email);
  await renterPage.locator('#login-password').fill(ctx.renter.portalPassword);
  await renterPage.getByRole('button', { name: /sign in|log in/i }).click();
  await renterPage.waitForURL(/\/dashboard\/renter-portal/, { timeout: 15_000 });
  await renterPage.goto('/en/dashboard/renter-portal/facilities');
  await expect(renterPage.getByRole('heading', { level: 1, name: 'Facilities & Parking' })).toBeVisible();
  await expect(renterPage.getByText(amenity.nameEn, { exact: true }).first()).toBeVisible();
  await expect(renterPage.getByText(parking.spotNumber, { exact: true })).toBeVisible();

  const parkingCard = renterPage.getByText(parking.spotNumber, { exact: true }).locator('..').locator('..');
  await parkingCard.getByRole('button', { name: 'Request' }).click();
  const requestDialog = renterPage.getByRole('dialog', { name: `Request ${parking.spotNumber}` });
  await requestDialog.locator('input[type="date"]').fill(date);
  await requestDialog.getByPlaceholder('Anything the manager should know').fill('TEST-Second vehicle');
  const [createResponse] = await Promise.all([
    renterPage.waitForResponse((response) =>
      response.url().endsWith('/api/proxy/v1/bookings') && response.request().method() === 'POST',
    ),
    requestDialog.getByRole('button', { name: 'Submit Request' }).click(),
  ]);
  expect(createResponse.ok()).toBeTruthy();
  const parkingBooking: { id: string; status: string } = await createResponse.json();
  expect(parkingBooking.status).toBe('PENDING');
  // The renter table shows the resource/status while the submitted note is
  // intentionally exposed in the manager detail drawer below.
  await expect(
    renterPage.getByRole('row').filter({ hasText: parking.spotNumber }),
  ).toBeVisible();
  expect((await api.getMyBookings(renterCtx)).some((item) => item.id === parkingBooking.id)).toBeTruthy();

  const detail = await api.getBooking(pmCtx, parkingBooking.id);
  expect(detail.request.id).toBe(parkingBooking.id);
  expect((await api.getBookings(pmCtx, ctx.property.id)).content.some((item) => item.id === parkingBooking.id)).toBeTruthy();

  const managerBrowser = await browser.newContext({ baseURL: ctx.baseURL });
  const managerPage = await managerBrowser.newPage();
  await managerPage.goto('/en/auth/login');
  await managerPage.locator('#login-email').fill(ctx.pmEmail);
  await managerPage.locator('#login-password').fill(ctx.pmPassword);
  await managerPage.getByRole('button', { name: /sign in|log in/i }).click();
  await managerPage.waitForURL(/\/dashboard(?!\/renter-portal)/, { timeout: 15_000 });
  await managerPage.goto('/en/dashboard/bookings');
  await expect(managerPage.getByRole('heading', { level: 1, name: 'Booking Requests' })).toBeVisible();
  await managerPage.locator('select').first().selectOption(ctx.property.id);
  await managerPage.getByText(parking.spotNumber, { exact: true }).click();
  const bookingDrawer = managerPage.getByRole('dialog', { name: 'Booking Request' });
  await expect(bookingDrawer.getByText('TEST-Second vehicle', { exact: false })).toBeVisible();
  await bookingDrawer.locator('textarea').fill('Approved parking');
  const [approveResponse] = await Promise.all([
    managerPage.waitForResponse((response) => response.url().endsWith(`/bookings/${parkingBooking.id}/approve`)),
    bookingDrawer.getByRole('button', { name: 'Approve' }).click(),
  ]);
  expect(approveResponse.ok()).toBeTruthy();
  await expect(bookingDrawer.getByText('Approved', { exact: true })).toBeVisible();
  expect((await api.getBooking(pmCtx, parkingBooking.id)).request.status).toBe('APPROVED');

  await renterPage.reload();
  const parkingRequestRow = renterPage.getByRole('row').filter({ hasText: parking.spotNumber });
  await parkingRequestRow.getByRole('button', { name: 'Release Spot' }).click();
  const releaseDialog = renterPage.getByRole('heading', { name: 'Release Spot' }).locator('..').locator('..');
  await expect(releaseDialog.getByText('Give up this parking spot?', { exact: false })).toBeVisible();
  const [releaseResponse] = await Promise.all([
    renterPage.waitForResponse((response) => response.url().endsWith(`/bookings/${parkingBooking.id}/release`)),
    releaseDialog.getByRole('button', { name: 'Release Spot' }).click(),
  ]);
  expect(releaseResponse.ok()).toBeTruthy();
  expect((await api.getBooking(pmCtx, parkingBooking.id)).request.status).toBe('RELEASED');

  // Separate pending request exercises renter cancellation.
  const cancelled = await api.createBooking(renterCtx, {
    resourceType: 'AMENITY',
    resourceId: amenity.id,
    unitId: ctx.unit.id,
    preferredDate: date,
    note: 'TEST-Cancel this request',
  });
  expect((await api.cancelBooking(renterCtx, cancelled.id)).status).toBe('CANCELLED');

  await renterBrowser.close();
  await managerBrowser.close();
  await api.deactivateAmenity(pmCtx, amenity.id);
  await api.deactivateParkingSpot(pmCtx, parking.id);
  await renterCtx.request.dispose();
  await pmCtx.request.dispose();
});

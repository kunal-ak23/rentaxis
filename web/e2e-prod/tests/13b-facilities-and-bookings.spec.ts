/**
 * 13b — Renter-visible facilities and manager booking decisions.
 * Runs before settlement while the renter still has an ACTIVE lease.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('renter requests amenity and parking access and manager decides them', async () => {
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

  const parkingBooking = await api.createBooking(renterCtx, {
    resourceType: 'PARKING_SPOT',
    resourceId: parking.id,
    unitId: ctx.unit.id,
    preferredDate: date,
    note: 'TEST-Second vehicle',
  });
  expect(parkingBooking.status).toBe('PENDING');
  expect((await api.getMyBookings(renterCtx)).some((item) => item.id === parkingBooking.id)).toBeTruthy();

  const detail = await api.getBooking(pmCtx, parkingBooking.id);
  expect(detail.booking.id).toBe(parkingBooking.id);
  expect((await api.getBookings(pmCtx, ctx.property.id)).content.some((item) => item.id === parkingBooking.id)).toBeTruthy();

  expect((await api.approveBooking(pmCtx, parkingBooking.id, 'Approved parking')).status).toBe('APPROVED');
  expect((await api.releaseBooking(renterCtx, parkingBooking.id)).status).toBe('RELEASED');

  // Separate pending request exercises renter cancellation.
  const cancelled = await api.createBooking(renterCtx, {
    resourceType: 'AMENITY',
    resourceId: amenity.id,
    unitId: ctx.unit.id,
    preferredDate: date,
    note: 'TEST-Cancel this request',
  });
  expect((await api.cancelBooking(renterCtx, cancelled.id)).status).toBe('CANCELLED');

  await api.deactivateAmenity(pmCtx, amenity.id);
  await api.deactivateParkingSpot(pmCtx, parking.id);
  await renterCtx.request.dispose();
  await pmCtx.request.dispose();
});

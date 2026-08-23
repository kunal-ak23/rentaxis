/**
 * 15 — Listing publication and renter marketplace journey.
 * Runs after settlement so the seeded unit is vacant and can be marketed.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('manager publishes a listing and renter manages marketplace interest', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.unit?.id, '01-provision must run first').toBeTruthy();

  const pmCtx = await loginAsNextAuth(ctx.baseURL, ctx.pmEmail, ctx.pmPassword);
  await setActiveTenant(pmCtx, ctx.tenant.id);
  const renterCtx = await loginAsNextAuth(
    ctx.baseURL,
    ctx.renter.email,
    ctx.renter.portalPassword,
  );

  const availableFrom = new Date().toISOString().slice(0, 10);
  const listing = await api.createListing(pmCtx, {
    unitId: ctx.unit.id,
    titleEn: `TEST-Marina Home ${ctx.runSuffix}`,
    annualRent: 600000,
    availableFrom,
  });
  expect(listing.status).toBe('DRAFT');
  expect(listing.tenantSlug).toBeTruthy();
  expect(listing.slug).toBeTruthy();

  const updated = await api.updateListing(pmCtx, listing.id, {
    titleEn: `TEST-Marina Home Updated ${ctx.runSuffix}`,
    annualRent: 610000,
    availableFrom,
  });
  expect(updated.annualRent).toBe(610000);

  await api.publishListing(pmCtx, listing.id);
  const marketplace = await api.getMarketplaceListings(renterCtx, listing.tenantSlug);
  expect(marketplace.content.some((item) => item.id === listing.id)).toBeTruthy();

  const detail = await api.getMarketplaceListing(renterCtx, listing.tenantSlug, listing.slug);
  expect(detail.id).toBe(listing.id);

  await api.addListingInterest(renterCtx, listing.id, 'Please arrange a viewing.');
  expect((await api.getWishlist(renterCtx)).some((item) => item.id === listing.id)).toBeTruthy();
  expect((await api.getListingInterests(pmCtx, listing.id)).content).toHaveLength(1);

  await api.withdrawListingInterest(renterCtx, listing.id);
  expect((await api.getWishlist(renterCtx)).some((item) => item.id === listing.id)).toBeFalsy();

  await api.unlistListing(pmCtx, listing.id);
  await api.deleteListing(pmCtx, listing.id);

  await pmCtx.request.dispose();
  await renterCtx.request.dispose();
});

/**
 * 15 — Listing publication and renter marketplace journey.
 * Runs after settlement so the seeded unit is vacant and can be marketed.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('manager publishes a listing that public and renter journeys can use', async ({ browser }) => {
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

  const managerContext = await browser.newContext({ baseURL: ctx.baseURL });
  const managerPage = await managerContext.newPage();
  await managerPage.goto('/en/auth/login');
  await managerPage.locator('#login-email').fill(ctx.pmEmail);
  await managerPage.locator('#login-password').fill(ctx.pmPassword);
  await managerPage.getByRole('button', { name: /sign in|log in/i }).click();
  await managerPage.waitForURL(/\/dashboard(?!\/renter-portal)/, { timeout: 15_000 });
  await managerPage.goto('/en/dashboard/listings');
  await expect(managerPage.getByRole('heading', { level: 1, name: 'Listings' })).toBeVisible();
  const listingTitle = `TEST-Marina Home Updated ${ctx.runSuffix}`;
  const managerRow = managerPage.getByRole('row').filter({ hasText: listingTitle });
  await expect(managerRow.getByText('Published', { exact: true })).toBeVisible();
  await managerRow.getByRole('link', { name: listingTitle }).click();
  await expect(managerPage.getByRole('heading', { level: 1, name: listingTitle })).toBeVisible();
  for (const tab of ['Details', 'Media', 'Amenities', 'SEO', 'Pricing', 'Location']) {
    await expect(managerPage.getByRole('button', { name: tab, exact: true })).toBeVisible();
  }

  const publicContext = await browser.newContext({ baseURL: ctx.baseURL });
  const publicPage = await publicContext.newPage();
  await publicPage.goto(`/l/${listing.tenantSlug}`);
  const publicListingLink = publicPage.locator(`a[href="/l/${listing.tenantSlug}/${listing.slug}"]`);
  await expect(publicListingLink).toContainText(`TEST-Marina Home Updated ${ctx.runSuffix}`);
  await publicListingLink.click();
  await expect(publicPage).toHaveURL(new RegExp(`/l/${listing.tenantSlug}/${listing.slug}$`));
  await expect(
    publicPage.getByRole('heading', {
      level: 1,
      name: `TEST-Marina Home Updated ${ctx.runSuffix}`,
    }),
  ).toBeVisible();
  await expect(publicPage.getByRole('link', { name: /login to continue/i })).toBeVisible();

  const marketplace = await api.getMarketplaceListings(renterCtx, listing.tenantSlug);
  expect(marketplace.content.some((item) => item.id === listing.id)).toBeTruthy();

  const detail = await api.getMarketplaceListing(renterCtx, listing.tenantSlug, listing.slug);
  expect(detail.id).toBe(listing.id);

  await api.addListingInterest(renterCtx, listing.id, 'Please arrange a viewing.');
  expect((await api.getWishlist(renterCtx)).some((item) => item.id === listing.id)).toBeTruthy();
  expect((await api.getListingInterests(pmCtx, listing.id)).content).toHaveLength(1);

  await managerPage.goto('/en/dashboard/listings');
  const interestedRow = managerPage.getByRole('row').filter({ hasText: listingTitle });
  await interestedRow.getByRole('button', { name: '1' }).click();
  const interestsDrawer = managerPage.getByRole('dialog', { name: 'Interested Renters' });
  await expect(interestsDrawer.getByText(/Please arrange a viewing\./)).toBeVisible();
  await expect(interestsDrawer.getByText(ctx.renter.email, { exact: true })).toBeVisible();

  await api.withdrawListingInterest(renterCtx, listing.id);
  expect((await api.getWishlist(renterCtx)).some((item) => item.id === listing.id)).toBeFalsy();

  await api.unlistListing(pmCtx, listing.id);
  await api.deleteListing(pmCtx, listing.id);

  await managerContext.close();
  await publicContext.close();
  await pmCtx.request.dispose();
  await renterCtx.request.dispose();
});

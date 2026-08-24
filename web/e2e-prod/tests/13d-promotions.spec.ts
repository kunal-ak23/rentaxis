/**
 * 13d — Tenant-admin promotion setup and renter engagement.
 * Runs before settlement because property-targeted offers require an ACTIVE lease.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('admin publishes a targeted coupon and renter engagement reaches analytics', async ({ browser }) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  const taCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  const renterCtx = await loginAsNextAuth(
    ctx.baseURL,
    ctx.renter.email,
    ctx.renter.portalPassword,
  );

  const businessBody = {
    nameEn: `TEST-Cafe ${ctx.runSuffix}`,
    nameAr: `مقهى تجريبي ${ctx.runSuffix}`,
    category: 'DINING',
    phoneE164: '+971500000006',
    whatsappE164: '+971500000006',
    allowedDomains: ['example.com'],
    active: true,
  };
  const business = await api.createPromoBusiness(taCtx, businessBody);
  expect(business.category).toBe('DINING');

  const renamed = await api.updatePromoBusiness(taCtx, business.id, {
    ...businessBody,
    nameEn: `${businessBody.nameEn} Updated`,
  });
  expect(renamed.nameEn).toContain('Updated');
  expect(
    (await api.listPromoBusinesses(taCtx)).content.some((item) => item.id === business.id),
  ).toBeTruthy();

  const adBody = {
    businessId: business.id,
    titleEn: `TEST-20% off ${ctx.runSuffix}`,
    titleAr: `خصم تجريبي 20٪ ${ctx.runSuffix}`,
    subtitleEn: 'TEST-E2E targeted resident offer',
    accentColor: '#2563EB',
    ctaType: 'COUPON',
    ctaLabelEn: 'Copy code',
    couponCode: `TEST${ctx.runSuffix}`.replace(/[^A-Za-z0-9]/g, '').slice(0, 32),
    couponTermsEn: 'TEST-E2E only',
    startsAt: new Date(Date.now() - 60 * 60 * 1000).toISOString(),
    endsAt: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(),
    priority: 7,
    placement: 'HOME_AND_OFFERS',
    propertyIds: [ctx.property.id],
    active: true,
  };
  const ad = await api.createPromoAd(taCtx, adBody);
  expect(ad.propertyIds).toEqual([ctx.property.id]);
  expect(ad.couponCode).toBe(adBody.couponCode);

  const updatedAd = await api.updatePromoAd(taCtx, ad.id, {
    ...adBody,
    subtitleEn: 'TEST-E2E targeted resident offer updated',
    priority: 8,
  });
  expect(updatedAd.priority).toBe(8);

  const feedCard = (await api.getPromotionFeed(renterCtx)).find((item) => item.id === ad.id);
  expect(feedCard).toBeTruthy();
  expect(feedCard!.ctaType).toBe('COUPON');
  expect(feedCard!.couponCode).toBe(adBody.couponCode);

  const offerCard = (await api.getPromotionOffers(renterCtx, 'DINING')).find(
    (item) => item.id === ad.id,
  );
  expect(offerCard?.business.category).toBe('DINING');

  await api.recordPromotionEvents(renterCtx, [
    { adId: ad.id, type: 'IMPRESSION' },
    { adId: ad.id, type: 'CLICK' },
  ]);
  const stats = await api.getPromoAdStats(taCtx, ad.id);
  expect(stats.impressions).toBeGreaterThanOrEqual(1);
  expect(stats.clicks).toBeGreaterThanOrEqual(1);
  expect(stats.tapThroughRate).toBeGreaterThan(0);

  const listedAd = (await api.listPromoAds(taCtx, business.id)).content.find(
    (item) => item.id === ad.id,
  );
  expect(listedAd?.impressions).toBeGreaterThanOrEqual(1);
  expect(listedAd?.clicks).toBeGreaterThanOrEqual(1);

  const adminBrowser = await browser.newContext({ baseURL: ctx.baseURL });
  const adminPage = await adminBrowser.newPage();
  await adminPage.goto('/en/auth/login');
  await adminPage.locator('#login-email').fill(ctx.adminEmail);
  await adminPage.locator('#login-password').fill(ctx.adminPassword);
  await adminPage.getByRole('button', { name: /sign in|log in/i }).click();
  await adminPage.waitForURL(/\/dashboard(?!\/renter-portal)/, { timeout: 15_000 });
  await adminPage.goto('/en/dashboard/promotions');
  await expect(adminPage.getByRole('heading', { level: 1, name: 'Promotions' })).toBeVisible();

  const adRow = adminPage.getByRole('row').filter({ hasText: adBody.titleEn });
  await expect(adRow.getByText(`${businessBody.nameEn} Updated`, { exact: true })).toBeVisible();
  await expect(adRow.getByText('Show coupon code', { exact: true })).toBeVisible();
  await expect(adRow.getByText('Live', { exact: true })).toBeVisible();
  await expect(adRow.getByText(String(listedAd!.impressions), { exact: true })).toBeVisible();
  await expect(adRow.getByText(String(listedAd!.clicks), { exact: true })).toBeVisible();

  await adminPage.getByRole('tab', { name: 'Businesses' }).click();
  const businessRow = adminPage.getByRole('row').filter({ hasText: `${businessBody.nameEn} Updated` });
  await expect(businessRow.getByText('Dining', { exact: true })).toBeVisible();
  await expect(businessRow.getByText('+971500000006', { exact: true })).toBeVisible();
  await adminBrowser.close();

  // Engagement history is deliberately retained, so used ads cannot be hard-deleted.
  // Deactivate this test content; 99-cleanup removes the disposable tenant and its history.
  expect((await api.updatePromoAd(taCtx, ad.id, { ...adBody, active: false })).active).toBe(false);

  const unusedBusiness = await api.createPromoBusiness(taCtx, {
    ...businessBody,
    nameEn: `TEST-Unused Business ${ctx.runSuffix}`,
  });
  await api.deletePromoBusiness(taCtx, unusedBusiness.id);
  expect(
    (await api.listPromoBusinesses(taCtx)).content.some((item) => item.id === unusedBusiness.id),
  ).toBeFalsy();

  await taCtx.request.dispose();
  await renterCtx.request.dispose();
});

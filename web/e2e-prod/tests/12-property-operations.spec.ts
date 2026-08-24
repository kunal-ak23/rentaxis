/**
 * 12 — Property setup beyond the basic property/unit fixture.
 * Covers building inventory plus the PROPERTY_MANAGER-operated contacts,
 * amenity, and parking lifecycle used by the property detail tabs.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('admin and assigned manager maintain and browse the complete property detail', async ({ browser }) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.property?.id, '01-provision must run first').toBeTruthy();

  const taCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  const pmCtx = await loginAsNextAuth(ctx.baseURL, ctx.pmEmail, ctx.pmPassword);
  await setActiveTenant(taCtx, ctx.tenant.id);
  await setActiveTenant(pmCtx, ctx.tenant.id);

  const building = await api.createBuilding(taCtx, {
    propertyId: ctx.property.id,
    nameEn: `TEST-Tower Operations ${ctx.runSuffix}`,
    floors: 12,
  });
  expect(building.floors).toBe(12);
  const buildings = await api.getBuildingsForProperty(pmCtx, ctx.property.id);
  expect(buildings.some((item) => item.id === building.id)).toBeTruthy();

  const contact = await api.createPropertyContact(pmCtx, ctx.property.id, {
    category: 'PLUMBER',
    name: `TEST-Plumber ${ctx.runSuffix}`,
    phone: '+971500000002',
    email: 'plumber-e2e@test.example',
  });
  const updatedContact = await api.updatePropertyContact(pmCtx, ctx.property.id, contact.id, {
    category: 'BUILDING_MAINTENANCE',
    name: `TEST-Maintenance Desk ${ctx.runSuffix}`,
    phone: '+971500000003',
    notes: 'Available around the clock',
  });
  expect(updatedContact.category).toBe('BUILDING_MAINTENANCE');
  const contacts = await api.getPropertyContacts(pmCtx, ctx.property.id);
  expect(contacts.some((item) => item.id === contact.id)).toBeTruthy();

  const amenity = await api.createAmenity(pmCtx, {
    propertyId: ctx.property.id,
    nameEn: `TEST-Gym ${ctx.runSuffix}`,
    buildingIds: [building.id],
  });
  expect(amenity.bookable).toBeTruthy();
  expect(amenity.buildingIds).toContain(building.id);
  const renamedAmenity = await api.updateAmenity(pmCtx, amenity.id, {
    nameEn: `TEST-Fitness Centre ${ctx.runSuffix}`,
  });
  expect(renamedAmenity.nameEn).toContain('Fitness Centre');
  const amenities = await api.getAmenities(pmCtx, ctx.property.id);
  expect(amenities.content.some((item) => item.id === amenity.id)).toBeTruthy();

  const spot = await api.createParkingSpot(pmCtx, {
    propertyId: ctx.property.id,
    spotNumber: `TEST-P-${ctx.runSuffix}`,
    level: 'B1',
    buildingIds: [building.id],
  });
  expect(spot.buildingIds).toContain(building.id);
  const movedSpot = await api.updateParkingSpot(pmCtx, spot.id, { level: 'B2' });
  expect(movedSpot.level).toBe('B2');
  const spots = await api.getParkingSpots(pmCtx, ctx.property.id);
  expect(spots.content.some((item) => item.id === spot.id)).toBeTruthy();

  // Exercise the real property detail UI while every fixture is still active.
  // The API assertions above establish the data contract; these checks prove
  // that the portfolio tabs actually render the same records for an assigned
  // PROPERTY_MANAGER.
  const browserCtx = await browser.newContext({ baseURL: ctx.baseURL });
  const page = await browserCtx.newPage();
  await page.goto('/en/auth/login');
  await page.locator('#login-email').fill(ctx.pmEmail);
  await page.locator('#login-password').fill(ctx.pmPassword);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await page.waitForURL(/\/dashboard(?!\/renter-portal)/, { timeout: 15_000 });

  await page.goto(`/en/dashboard/properties/${ctx.property.id}`);
  await expect(
    page.getByRole('heading', { level: 1, name: `TEST-Tower ${ctx.runSuffix}` }),
  ).toBeVisible();
  await expect(page.getByText(`TEST-Maintenance Desk ${ctx.runSuffix}`)).toBeVisible();

  await page.getByRole('button', { name: /^buildings$/i }).click();
  await expect(page.getByText(`TEST-Tower Operations ${ctx.runSuffix}`)).toBeVisible();

  await page.getByRole('button', { name: /^units$/i }).click();
  await expect(page.getByText(`TEST-${ctx.runSuffix}`, { exact: true })).toBeVisible();

  await page.getByRole('button', { name: /^amenities$/i }).click();
  await expect(page.getByText(`TEST-Fitness Centre ${ctx.runSuffix}`)).toBeVisible();

  await page.getByRole('button', { name: /^parking$/i }).click();
  await expect(page.getByText(`TEST-P-${ctx.runSuffix}`, { exact: true })).toBeVisible();

  await browserCtx.close();

  // Deactivation is intentionally soft for bookable inventory. Contact and
  // building deletion are hard and safe here because no unit references this
  // extra building and the scoped amenity/spot are already deactivated.
  await api.updateAmenity(pmCtx, amenity.id, { buildingIds: [] });
  await api.updateParkingSpot(pmCtx, spot.id, { buildingIds: [] });
  await api.deactivateAmenity(pmCtx, amenity.id);
  await api.deactivateParkingSpot(pmCtx, spot.id);
  await api.deletePropertyContact(pmCtx, ctx.property.id, contact.id);
  await api.deleteBuilding(taCtx, building.id);

  const contactsAfterDelete = await api.getPropertyContacts(pmCtx, ctx.property.id);
  expect(contactsAfterDelete.some((item) => item.id === contact.id)).toBeFalsy();

  await taCtx.request.dispose();
  await pmCtx.request.dispose();
});

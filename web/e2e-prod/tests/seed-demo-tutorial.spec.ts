/**
 * Creates one isolated, synthetic tenant for the Miftah product tutorial.
 *
 * This is intentionally a Playwright API test so it reuses the production
 * NextAuth client and proxy path used by the existing E2E suite. It writes a
 * redacted manifest to tutorials/state and a chmod-600 local credentials file
 * ignored by git.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { api, loginAsNextAuth, setActiveTenant, ProdContext } from '../helpers/prod-client';

const BASE_URL = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';
const STATE_DIR = path.resolve(__dirname, '../../../tutorials/state');
const ASSET_DIR = path.resolve(__dirname, '../../../tutorials/rentaxis-demo/assets/pexels');

async function json<T>(ctx: ProdContext, method: 'get' | 'post' | 'put', endpoint: string, body?: unknown): Promise<T> {
  const response = await ctx.request[method](`/api/proxy${endpoint}`, body === undefined ? { failOnStatusCode: false } : {
    data: body,
    headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
  });
  if (!response.ok()) {
    throw new Error(`${method.toUpperCase()} ${endpoint} → ${response.status()}: ${(await response.text()).slice(0, 500)}`);
  }
  return response.status() === 204 ? (undefined as T) : response.json() as Promise<T>;
}

async function uploadListingMedia(ctx: ProdContext, listingId: string, filename: string, caption: string, isCover: boolean) {
  const filePath = path.join(ASSET_DIR, filename);
  const response = await ctx.request.post(`/api/proxy/listings/${listingId}/media`, {
    multipart: {
      file: { name: filename, mimeType: 'image/jpeg', buffer: fs.readFileSync(filePath) },
      caption,
      isCover: String(isCover),
    },
    failOnStatusCode: false,
  });
  if (!response.ok()) {
    throw new Error(`POST /listings/${listingId}/media → ${response.status()}: ${(await response.text()).slice(0, 500)}`);
  }
  return response.json() as Promise<{ id: string; url: string }>;
}

test('seed isolated bilingual Miftah Demo Tutorial tenant', async () => {
  const suffix = Date.now().toString(36);
  const label = `Miftah Demo Tutorial ${new Date().toISOString().slice(0, 10)}-${suffix.slice(-5)}`;
  const password = `MiftahDemo!${suffix.slice(-8)}A`;
  const base = `demo-${suffix}`;
  const adminEmail = `${base}-admin@demo.miftah.invalid`;
  const managerEmail = `${base}-manager@demo.miftah.invalid`;
  const guardEmail = `${base}-guard@demo.miftah.invalid`;
  const renterEmail = `${base}-resident@demo.miftah.invalid`;

  const superEmail = process.env.PROD_SUPERADMIN_EMAIL;
  const superPassword = process.env.PROD_SUPERADMIN_PASSWORD;
  expect(superEmail && superPassword, 'production super-admin credentials must be configured in .env.local').toBeTruthy();

  const superCtx = await loginAsNextAuth(BASE_URL, superEmail!, superPassword!);
  const tenant = await api.createTenant(superCtx, label);
  await api.setTenantFeature(superCtx, tenant.id, 'EMAIL_NOTIFICATIONS', true);
  await api.setTenantFeature(superCtx, tenant.id, 'LISTINGS', true);
  await api.setTenantFeature(superCtx, tenant.id, 'MEETINGS', true);
  await setActiveTenant(superCtx, tenant.id);

  const admin = await api.createUser(superCtx, tenant.id, {
    name: 'Demo Admin / مدير العرض', email: adminEmail, password, role: 'TENANT_ADMIN',
  });
  const manager = await api.createUser(superCtx, tenant.id, {
    name: 'Demo Property Manager / مدير العقار', email: managerEmail, password, role: 'PROPERTY_MANAGER',
  });
  const guard = await api.createUser(superCtx, tenant.id, {
    name: 'Demo Gate Guard / حارس البوابة', email: guardEmail, password, role: 'SECURITY_GUARD',
  });

  const adminCtx = await loginAsNextAuth(BASE_URL, adminEmail, password);
  await setActiveTenant(adminCtx, tenant.id);

  const property = await json<{ id: string; nameEn: string; nameAr: string }>(adminCtx, 'post', '/v1/properties', {
    nameEn: 'Marina Oasis Residences',
    nameAr: 'مساكن واحة المارينا',
    emirate: 'DUBAI',
    address: 'Marina Promenade, Dubai, UAE',
    makaniNumber: '12345 67890',
    type: 'RESIDENTIAL',
    fixedExpenses: 125000,
  });
  const building = await json<{ id: string; nameEn: string; nameAr: string }>(adminCtx, 'post', '/v1/buildings', {
    property: { id: property.id }, nameEn: 'Oasis Tower', nameAr: 'برج الواحة', floors: 18,
  });
  const leasedUnit = await json<{ id: string; unitNumber: string }>(adminCtx, 'post', '/v1/units', {
    property: { id: property.id }, building: { id: building.id }, unitNumber: '1201', type: 'BHK2',
    sizeSqft: 1240, expectedRent: 98000, actualRent: 98000, status: 'VACANT', currentTenantName: '',
  });
  const listingUnit = await json<{ id: string; unitNumber: string }>(adminCtx, 'post', '/v1/units', {
    property: { id: property.id }, building: { id: building.id }, unitNumber: '1202', type: 'BHK1',
    sizeSqft: 780, expectedRent: 72000, actualRent: 0, status: 'VACANT', currentTenantName: '',
  });

  await api.assignUserToProperty(adminCtx, manager.id, property.id);
  await json(adminCtx, 'put', `/v1/gatepass/guards/${guard.id}/properties`, [property.id]);
  const amenity = await json<{ id: string }>(adminCtx, 'post', '/v1/amenities', {
    propertyId: property.id, nameEn: 'Infinity Pool', nameAr: 'المسبح اللامتناهي',
    description: 'Rooftop pool with marina views / مسبح على السطح بإطلالة على المارينا', bookable: true, buildingIds: [building.id],
  });
  const parking = await api.createParkingSpot(adminCtx, { propertyId: property.id, spotNumber: 'B1-1201', level: 'B1', buildingIds: [building.id] });
  const contact = await api.createPropertyContact(adminCtx, property.id, {
    category: 'BUILDING_MAINTENANCE', name: 'Oasis Maintenance Desk', phone: '+97145550120',
    email: 'maintenance@demo.miftah.invalid', notes: 'English / العربية support',
  });
  const vendor = await api.createVendor(adminCtx, { name: 'Gulf Home Services / خدمات الخليج المنزلية' });

  const renter = await json<{ id: string; userId: string; portalPassword: string | null }>(adminCtx, 'post', '/v1/renters', {
    nameEn: 'Lina Haddad', nameAr: 'لينا حداد', email: renterEmail, phone: '+971501234567', primaryLanguage: 'EN', createPortalAccount: true,
  });
  expect(renter.userId).toBeTruthy();
  // accounting-v2 plan 2: a lease becomes ACTIVE via draft (lines) -> generate
  // cheques -> post; `PUT /leases/{id}/activate` is gone.
  const activeLease = await api.postLeaseFlow(adminCtx, {
    unitId: leasedUnit.id, renterId: renter.id, startDate: new Date().toISOString().slice(0, 10),
    endDate: new Date(Date.now() + 365 * 24 * 3600 * 1000).toISOString().slice(0, 10), rentAmount: 98000, paymentTerms: 4,
  });
  const lease = activeLease;
  await api.logInteraction(adminCtx, lease.id, { type: 'CALL', direction: 'OUTBOUND', summary: 'Welcome call completed / تم إكمال مكالمة الترحيب' });

  const managerCtx = await loginAsNextAuth(BASE_URL, managerEmail, password);
  await setActiveTenant(managerCtx, tenant.id);
  const listing = await json<{ id: string; slug: string }>(managerCtx, 'post', '/listings', {
    unitId: listingUnit.id,
    titleEn: 'Bright Marina One-Bedroom Retreat', titleAr: 'شقة مشرقة بغرفة نوم واحدة في المارينا',
    descriptionEn: 'A calm, sunlit home with a marina-facing balcony, pool access, covered parking, and 24/7 security.',
    descriptionAr: 'منزل هادئ ومشرق مع شرفة مطلة على المارينا وإمكانية استخدام المسبح وموقف مغطى وأمن على مدار الساعة.',
    bedrooms: 1, bathrooms: 2, sizeSqft: 780, floor: 12, parkingSpaces: 1, furnishing: 'SEMI_FURNISHED', viewType: 'SEA',
    annualRent: 72000, securityDeposit: 5000, minLeaseMonths: 12, chequesAccepted: 4, dewaIncluded: false, chillerIncluded: true,
    utilitiesEstimate: 650, availableFrom: new Date().toISOString().slice(0, 10),
    seoTitle: 'Marina Oasis one-bedroom apartment', seoDescription: 'Bilingual Miftah demo listing', seoKeywords: 'marina,dubai,apartment',
    ogImageUrl: '', lat: 25.0805, lng: 55.1404,
    amenities: [{ amenity: 'POOL' }, { amenity: 'GYM' }, { amenity: 'SEA_VIEW' }, { amenity: 'COVERED_PARKING' }, { amenity: 'SECURITY_24_7' }],
  });
  await uploadListingMedia(managerCtx, listing.id, 'house-minimal-front.jpg', 'Contemporary exterior / واجهة عصرية', true);
  await uploadListingMedia(managerCtx, listing.id, 'house-modern-glass.jpg', 'Garden and pool / الحديقة والمسبح', false);
  await uploadListingMedia(managerCtx, listing.id, 'house-two-story.jpg', 'Family-ready entrance / مدخل مناسب للعائلات', false);
  await json(managerCtx, 'post', `/listings/${listing.id}/publish`);

  const renterCtx = await loginAsNextAuth(BASE_URL, renterEmail, renter.portalPassword!);
  await setActiveTenant(renterCtx, tenant.id);
  const ticket = await api.createTicket(renterCtx, {
    propertyId: property.id, unitId: leasedUnit.id, leaseId: lease.id,
    title: 'Kitchen tap needs attention / صنبور المطبخ يحتاج إلى صيانة',
    description: 'The kitchen tap is dripping. Please arrange a visit. الصنبور يقطر ونرجو ترتيب زيارة.', category: 'PLUMBING', priority: 'MEDIUM',
  });
  const now = Date.now();
  const meeting = await json<{ id: string }>(renterCtx, 'post', '/v1/meetings', {
    type: 'PROPERTY_VISIT', purpose: 'OTHER', title: 'Home orientation / جولة تعريفية بالمنزل',
    notes: 'Bilingual handover walkthrough', slotStart: new Date(now + 48 * 3600 * 1000).toISOString(),
    hostUserId: manager.id, leaseId: lease.id, propertyId: property.id, unitId: leasedUnit.id,
  });
  const pass = await json<{ id: string; numericCode: string }>(renterCtx, 'post', '/v1/gatepass', {
    unitId: leasedUnit.id, guestName: 'Omar Haddad', guestPhone: '+971509876543', purpose: 'Family visit / زيارة عائلية',
    vehicleNumber: 'DXB A 1201', passType: 'SINGLE_USE', validFrom: new Date(now + 3600 * 1000).toISOString(), validTo: new Date(now + 24 * 3600 * 1000).toISOString(),
  });

  const guardCtx = await loginAsNextAuth(BASE_URL, guardEmail, password);
  await setActiveTenant(guardCtx, tenant.id);
  const guardProperties = await json<Array<{ id: string; name: string }>>(guardCtx, 'get', '/gatepass/my-properties');
  const expected = await json<unknown[]>(guardCtx, 'get', '/gatepass/expected-today');

  const manifest = {
    schemaVersion: 1, runId: suffix, createdAt: new Date().toISOString(), environment: 'production',
    baseUrl: BASE_URL, synthetic: true, tenant: { id: tenant.id, name: tenant.name },
    users: {
      admin: { id: admin.id, email: adminEmail, role: 'TENANT_ADMIN' },
      manager: { id: manager.id, email: managerEmail, role: 'PROPERTY_MANAGER' },
      guard: { id: guard.id, email: guardEmail, role: 'SECURITY_GUARD' },
      renter: { id: renter.id, userId: renter.userId, email: renterEmail, role: 'RENTER' },
    },
    property: { id: property.id, nameEn: property.nameEn, nameAr: property.nameAr, buildingId: building.id, amenityId: amenity.id, parkingId: parking.id, contactId: contact.id },
    units: { leased: { id: leasedUnit.id, number: leasedUnit.unitNumber }, listed: { id: listingUnit.id, number: listingUnit.unitNumber } },
    lease: { id: lease.id, status: activeLease.status }, listing: { id: listing.id, slug: listing.slug, status: 'PUBLISHED' },
    workflows: { ticketId: ticket.id, meetingId: meeting.id, gatePassId: pass.id, guardPropertyCount: guardProperties.length, expectedTodayCount: expected.length },
    bilingual: { englishArabicFieldsSeeded: true, appLocaleValidation: 'pending' },
    imageSources: [
      'https://www.pexels.com/photo/modern-house-facade-10610731/',
      'https://www.pexels.com/photo/concept-of-modern-house-9976121/',
      'https://www.pexels.com/photo/modern-house-exterior-design-8134821/',
    ],
  };
  fs.mkdirSync(STATE_DIR, { recursive: true });
  const manifestPath = path.join(STATE_DIR, `miftah-demo-tutorial-${suffix}.json`);
  fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
  const secretPath = path.join(STATE_DIR, `miftah-demo-tutorial-${suffix}-secrets.local.json`);
  fs.writeFileSync(secretPath, JSON.stringify({ tenant: tenant.name, tenantId: tenant.id, password, users: { adminEmail, managerEmail, guardEmail, renterEmail, renterPortalPassword: renter.portalPassword } }, null, 2), { mode: 0o600 });
  fs.chmodSync(secretPath, 0o600);
  console.log(`Demo tenant created: ${tenant.name} (${tenant.id})`);
  console.log(`Redacted manifest: ${manifestPath}`);
  console.log(`Local credentials file (chmod 600, git-ignored): ${secretPath}`);
  await guardCtx.request.dispose();
  await renterCtx.request.dispose();
  await managerCtx.request.dispose();
  await adminCtx.request.dispose();
  await superCtx.request.dispose();
});

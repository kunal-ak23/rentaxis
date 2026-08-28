/** Resume the one synthetic seed that stopped after media upload on a 204 publish response. */
import { test, expect } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { api, loginAsNextAuth, setActiveTenant, ProdContext } from '../helpers/prod-client';

const BASE_URL = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';
const TENANT_ID = 'd49a5016-cced-4f81-a4f3-354714120c65';
const TENANT_NAME = 'Miftah Demo Tutorial 2026-08-27-zmkbe';
const SUFFIX = 'mtbzmkbe';
const STATE_DIR = path.resolve(__dirname, '../../../tutorials/state');
const SECRET_PATH = path.join(STATE_DIR, `miftah-demo-tutorial-${SUFFIX}-secrets.local.json`);
const PASSWORD = JSON.parse(fs.readFileSync(SECRET_PATH, 'utf8')).password as string;

async function readJson<T>(ctx: ProdContext, endpoint: string): Promise<T> {
  const r = await ctx.request.get(`/api/proxy${endpoint}`, { failOnStatusCode: false });
  if (!r.ok()) throw new Error(`GET ${endpoint} → ${r.status()}: ${(await r.text()).slice(0, 400)}`);
  return r.json() as Promise<T>;
}
async function writeJson<T>(ctx: ProdContext, endpoint: string, body: unknown): Promise<T | undefined> {
  const r = await ctx.request.post(`/api/proxy${endpoint}`, { data: body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false });
  if (!(r.ok() || r.status() === 204)) throw new Error(`POST ${endpoint} → ${r.status()}: ${(await r.text()).slice(0, 400)}`);
  return r.status() === 204 ? undefined : r.json() as Promise<T>;
}
async function publish(ctx: ProdContext, listingId: string) {
  const r = await ctx.request.post(`/api/proxy/listings/${listingId}/publish`, { failOnStatusCode: false });
  if (!(r.status() === 204 || r.ok())) throw new Error(`publish → ${r.status()}: ${(await r.text()).slice(0, 400)}`);
}

test('resume Miftah Demo Tutorial seed and write manifest', async () => {
  const superCtx = await loginAsNextAuth(BASE_URL, process.env.PROD_SUPERADMIN_EMAIL!, process.env.PROD_SUPERADMIN_PASSWORD!);
  const tenants = await api.listTenants(superCtx);
  expect(tenants.some((t) => t.id === TENANT_ID && t.name === TENANT_NAME)).toBeTruthy();

  const usersResponse = await superCtx.request.get('/api/proxy/admin/users');
  const users = await usersResponse.json() as Array<{ id: string; email: string; role: string; tenantId: string }>;
  const scoped = users.filter((u) => u.tenantId === TENANT_ID);
  const find = (role: string) => scoped.find((u) => u.role === role)!;
  const admin = find('TENANT_ADMIN');
  const manager = find('PROPERTY_MANAGER');
  const guard = find('SECURITY_GUARD');
  const renterUser = find('RENTER');
  const adminCtx = await loginAsNextAuth(BASE_URL, admin.email, PASSWORD);
  await setActiveTenant(adminCtx, TENANT_ID);
  const guardUpdate = await adminCtx.request.put(`/api/proxy/admin/users/${guard.id}`, {
    data: { email: guard.email, password: null, name: 'Demo Gate Guard / حارس البوابة', role: 'SECURITY_GUARD', tenantId: TENANT_ID, phoneNumber: '+971501234568' },
    headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
  });
  if (!guardUpdate.ok()) throw new Error(`set synthetic guard phone → ${guardUpdate.status()}: ${(await guardUpdate.text()).slice(0, 400)}`);
  const properties = await readJson<Array<Record<string, unknown>>>(adminCtx, '/v1/properties');
  const property = properties.map((p) => (p.property as Record<string, unknown>) ?? p)
    .find((p) => p.nameEn === 'Marina Oasis Residences' || p.name === 'Marina Oasis Residences')!;
  expect(property?.id).toBeTruthy();
  const propertyId = String(property.id);
  const buildings = await readJson<Array<{ id: string; nameEn: string; nameAr: string }>>(adminCtx, `/v1/buildings/property/${propertyId}`);
  const units = await readJson<Array<{ id: string; unitNumber: string }>>(adminCtx, `/v1/units/property/${propertyId}`);
  const renters = await readJson<Array<{ id: string; userId: string; email: string }>>(adminCtx, '/v1/renters');
  const renter = renters.find((r) => r.userId === renterUser.id)!;
  // The failed seed returned the generated portal password but did not persist
  // it. Reset only this synthetic renter to the known demo password so the
  // mobile/API flows can be exercised deterministically.
  await (async () => {
    const r = await adminCtx.request.put(`/api/proxy/admin/users/${renterUser.id}`, {
      data: { email: renterUser.email, password: PASSWORD, name: 'Lina Haddad', role: 'RENTER', tenantId: TENANT_ID },
      headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
    });
    if (!r.ok()) throw new Error(`reset synthetic renter password → ${r.status()}: ${(await r.text()).slice(0, 400)}`);
  })();
  const leases = await readJson<Array<{ id: string; unitId: string; status: string }>>(adminCtx, `/v1/leases/property/${propertyId}`);
  const lease = leases.find((l) => l.unitId === units.find((u) => u.unitNumber === '1201')?.id)!;
  const vendors = await readJson<Array<{ id: string; nameEn: string }>>(adminCtx, '/v1/vendors');
  const vendor = vendors.find((v) => v.nameEn.includes('Gulf Home Services'));
  const amenities = await readJson<{ content: Array<{ id: string; nameEn: string }> }>(adminCtx, `/v1/amenities?propertyId=${propertyId}&size=50`);
  const parking = await readJson<{ content: Array<{ id: string; spotNumber: string }> }>(adminCtx, `/v1/parking-spots?propertyId=${propertyId}&size=50`);
  const contacts = await readJson<Array<{ id: string; name: string }>>(adminCtx, `/v1/properties/${propertyId}/contacts`);

  const managerCtx = await loginAsNextAuth(BASE_URL, manager.email, PASSWORD);
  await setActiveTenant(managerCtx, TENANT_ID);
  const listingPage = await readJson<{ content: Array<{ id: string; slug: string; status: string; titleEn: string; titleAr: string }> }>(managerCtx, '/listings?size=50');
  const listing = listingPage.content.find((l) => l.titleEn === 'Bright Marina One-Bedroom Retreat' || (l as { title?: string }).title === 'Bright Marina One-Bedroom Retreat')!;
  expect(listing?.id).toBeTruthy();
  if (listing.status !== 'PUBLISHED') await publish(managerCtx, listing.id);

  const renterCtx = await loginAsNextAuth(BASE_URL, renterUser.email, PASSWORD);
  await setActiveTenant(renterCtx, TENANT_ID);
  let tickets = await readJson<Array<{ id: string; title: string }>>(renterCtx, '/v1/tickets');
  let meetings = await readJson<{ content: Array<{ id: string; title: string }> }>(renterCtx, '/v1/meetings/my?size=50');
  let passes = await readJson<Array<{ id: string; numericCode: string }>>(renterCtx, '/v1/gatepass/mine');
  if (!tickets.some((t) => t.title.includes('Kitchen tap'))) {
    await api.createTicket(renterCtx, { propertyId, unitId: lease.unitId, leaseId: lease.id, title: 'Kitchen tap needs attention / صنبور المطبخ يحتاج إلى صيانة', description: 'The kitchen tap is dripping. Please arrange a visit. الصنبور يقطر ونرجو ترتيب زيارة.', category: 'PLUMBING', priority: 'MEDIUM' });
    tickets = await readJson<Array<{ id: string; title: string }>>(renterCtx, '/v1/tickets');
  }
  if (!meetings.content.some((m) => (m.title ?? '').includes('Home orientation'))) {
    const slot = new Date(Date.now() + 48 * 3600 * 1000);
    slot.setUTCHours(8, 0, 0, 0); // 12:00 UAE, on a 30-minute boundary
    await writeJson(renterCtx, '/v1/meetings', { type: 'PROPERTY_VISIT', purpose: 'OTHER', title: 'Home orientation / جولة تعريفية بالمنزل', notes: 'Bilingual handover walkthrough', slotStart: slot.toISOString(), hostUserId: manager.id, leaseId: lease.id, propertyId, unitId: lease.unitId });
    meetings = await readJson<{ content: Array<{ id: string; title: string }> }>(renterCtx, '/v1/meetings/my?size=50');
  }
  if (passes.length === 0) {
    await writeJson(renterCtx, '/v1/gatepass', { unitId: lease.unitId, guestName: 'Omar Haddad', guestPhone: '+971509876543', purpose: 'Family visit / زيارة عائلية', vehicleNumber: 'DXB A 1201', passType: 'SINGLE_USE', validFrom: new Date(Date.now() + 3600 * 1000).toISOString(), validTo: new Date(Date.now() + 24 * 3600 * 1000).toISOString() });
    passes = await readJson<Array<{ id: string; numericCode: string }>>(renterCtx, '/v1/gatepass/mine');
  }
  // SECURITY_GUARD accounts use the app's phone/OTP flow and are deliberately
  // excluded from password login. Verify the posting from the manager scope;
  // the mobile security flow is validated separately with the existing seeded
  // session integration harness.
  const guardProperties = await readJson<unknown[]>(adminCtx, `/v1/gatepass/guards/${guard.id}/properties`);

  const manifest = {
    schemaVersion: 1, runId: SUFFIX, createdAt: new Date().toISOString(), environment: 'production', baseUrl: BASE_URL, synthetic: true,
    tenant: { id: TENANT_ID, name: TENANT_NAME },
    users: { admin: { id: admin.id, email: admin.email, role: admin.role }, manager: { id: manager.id, email: manager.email, role: manager.role }, guard: { id: guard.id, email: guard.email, role: guard.role }, renter: { id: renter.id, userId: renterUser.id, email: renterUser.email, role: renterUser.role } },
    property: { id: propertyId, nameEn: 'Marina Oasis Residences', nameAr: 'مساكن واحة المارينا', buildingId: buildings[0]?.id, amenityId: amenities.content[0]?.id, parkingId: parking.content[0]?.id, contactId: contacts[0]?.id, vendorId: vendor?.id },
    units: Object.fromEntries(units.map((u) => [u.unitNumber === '1201' ? 'leased' : 'listed', { id: u.id, number: u.unitNumber }])),
    lease: { id: lease.id, status: lease.status }, listing: { id: listing.id, slug: listing.slug, status: 'PUBLISHED' },
    workflows: { ticketId: tickets[0]?.id, meetingId: meetings.content[0]?.id, gatePassId: passes[0]?.id, guardPropertyCount: guardProperties.length },
    bilingual: { englishArabicFieldsSeeded: true, appLocaleValidation: 'pending' },
    imageSources: ['https://www.pexels.com/photo/modern-house-facade-10610731/', 'https://www.pexels.com/photo/concept-of-modern-house-9976121/', 'https://www.pexels.com/photo/modern-house-exterior-design-8134821/'],
  };
  fs.mkdirSync(STATE_DIR, { recursive: true });
  const manifestPath = path.join(STATE_DIR, `miftah-demo-tutorial-${SUFFIX}.json`);
  fs.writeFileSync(manifestPath, JSON.stringify(manifest, null, 2));
  const secretPath = path.join(STATE_DIR, `miftah-demo-tutorial-${SUFFIX}-secrets.local.json`);
  fs.writeFileSync(secretPath, JSON.stringify({ tenant: TENANT_NAME, tenantId: TENANT_ID, password: PASSWORD, users: { adminEmail: admin.email, managerEmail: manager.email, guardEmail: guard.email, guardPhone: '+971501234568', renterEmail: renterUser.email, renterPortalPassword: PASSWORD }, gatePassNumericCode: passes[0]?.numericCode }, null, 2), { mode: 0o600 });
  fs.chmodSync(secretPath, 0o600);
  console.log(`Resumed ${TENANT_NAME} (${TENANT_ID})`);
  console.log(`Redacted manifest: ${manifestPath}`);
  console.log(`Local credentials file: ${secretPath}`);
  await renterCtx.request.dispose(); await managerCtx.request.dispose(); await adminCtx.request.dispose(); await superCtx.request.dispose();
});

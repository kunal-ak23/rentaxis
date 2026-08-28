/** Seed bilingual wellness amenities and a recordable booking-request lifecycle. */
import { test, expect } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { api, loginAsNextAuth, ProdContext, setActiveTenant } from '../helpers/prod-client';

const BASE_URL = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';
const REPO_ROOT = path.resolve(__dirname, '../../..');
const STATE_PATH = path.join(REPO_ROOT, 'tutorials/state/miftah-demo-tutorial-mtbzmkbe.json');
const SECRET_PATH = path.join(REPO_ROOT, 'tutorials/state/miftah-demo-tutorial-mtbzmkbe-secrets.local.json');

type Json = Record<string, any>;

const DEFINITIONS = [
  {
    key: 'sauna',
    nameEn: 'Sky Sauna',
    nameAr: 'ساونا سكاي',
    description: 'Dry sauna with marina views / ساونا جافة بإطلالة على المارينا',
    preferredDate: '2026-09-02',
    note: 'Evening wellness session for two / جلسة عافية مسائية لشخصين',
    desiredStatus: 'PENDING',
  },
  {
    key: 'spa',
    nameEn: 'Marina Spa',
    nameAr: 'سبا المارينا',
    description: 'Resident spa and treatment suite / سبا وجناح علاجي للمقيمين',
    preferredDate: '2026-09-03',
    note: 'Please reserve the 6 PM resident session / يرجى حجز جلسة المقيمين الساعة السادسة مساءً',
    desiredStatus: 'PENDING',
  },
  {
    key: 'steam',
    nameEn: 'Steam & Hammam Suite',
    nameAr: 'جناح البخار والحمام',
    description: 'Private steam and hammam suite / جناح خاص للبخار والحمام',
    preferredDate: '2026-09-01',
    note: 'Weekend family wellness booking / حجز عائلي للعافية في عطلة نهاية الأسبوع',
    desiredStatus: 'APPROVED',
    adminNote: 'Approved — access is reserved from 5 PM / تمت الموافقة — الدخول محجوز من الساعة الخامسة',
  },
  {
    key: 'wellness',
    nameEn: 'Wellness Treatment Studio',
    nameAr: 'استوديو علاجات العافية',
    description: 'Massage and recovery treatment room / غرفة للتدليك وعلاجات الاستشفاء',
    preferredDate: '2026-09-05',
    note: 'Requesting a recovery treatment appointment / طلب موعد لجلسة استشفاء',
    desiredStatus: 'REJECTED',
    adminNote: 'Unavailable during scheduled maintenance / غير متاح أثناء الصيانة المجدولة',
  },
] as const;

function readJson(file: string): Json {
  return JSON.parse(fs.readFileSync(file, 'utf8')) as Json;
}

async function json<T>(ctx: ProdContext, method: 'get' | 'post' | 'put', endpoint: string, body?: unknown): Promise<T> {
  const response = await ctx.request[method](`/api/proxy${endpoint}`, body === undefined ? {
    failOnStatusCode: false,
  } : {
    data: body,
    headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
  });
  if (!response.ok()) {
    throw new Error(`${method.toUpperCase()} ${endpoint} -> ${response.status()}: ${(await response.text()).slice(0, 700)}`);
  }
  return response.status() === 204 ? (undefined as T) : response.json() as Promise<T>;
}

test('seed wellness amenities and booking requests for the Demo Tutorial tenant', async () => {
  test.setTimeout(4 * 60_000);
  const state = readJson(STATE_PATH);
  const secrets = readJson(SECRET_PATH);
  expect(state.synthetic).toBe(true);
  expect(state.tenant.id).toBe('d49a5016-cced-4f81-a4f3-354714120c65');

  const adminCtx = await loginAsNextAuth(BASE_URL, state.users.admin.email, secrets.password);
  const managerCtx = await loginAsNextAuth(BASE_URL, state.users.manager.email, secrets.password);
  const renterCtx = await loginAsNextAuth(BASE_URL, state.users.renter.email, secrets.users.renterPortalPassword);
  for (const ctx of [adminCtx, managerCtx, renterCtx]) await setActiveTenant(ctx, state.tenant.id);

  const superEmail = process.env.PROD_SUPERADMIN_EMAIL;
  const superPassword = process.env.PROD_SUPERADMIN_PASSWORD;
  expect(superEmail && superPassword).toBeTruthy();
  const superCtx = await loginAsNextAuth(BASE_URL, superEmail!, superPassword!);
  await api.setTenantFeature(superCtx, state.tenant.id, 'EMAIL_NOTIFICATIONS', false);

  const amenitiesByKey: Record<string, Json> = {};
  const bookingsByKey: Record<string, Json> = {};
  try {
    const amenityPage = await json<Json>(adminCtx, 'get', `/v1/amenities?propertyId=${state.property.id}&size=100`);
    const amenities: Json[] = amenityPage.content || [];
    for (const definition of DEFINITIONS) {
      let amenity = amenities.find((row) => row.nameEn === definition.nameEn);
      if (!amenity) {
        amenity = await json<Json>(adminCtx, 'post', '/v1/amenities', {
          propertyId: state.property.id,
          nameEn: definition.nameEn,
          nameAr: definition.nameAr,
          description: definition.description,
          bookable: true,
          buildingIds: [state.property.buildingId],
        });
        amenities.push(amenity);
      }
      expect(amenity.nameAr).toBe(definition.nameAr);
      expect(amenity.bookable).toBe(true);
      amenitiesByKey[definition.key] = amenity;
    }

    let renterBookings = await json<Json[]>(renterCtx, 'get', '/v1/bookings/my');
    for (const definition of DEFINITIONS) {
      const amenity = amenitiesByKey[definition.key];
      let booking = renterBookings.find((row) =>
        row.amenityId === amenity.id
          && row.preferredDate === definition.preferredDate
          && row.status === definition.desiredStatus,
      );

      if (!booking) {
        if (definition.desiredStatus === 'PENDING') {
          booking = renterBookings.find((row) => row.amenityId === amenity.id && row.status === 'PENDING');
        }
        if (!booking) {
          booking = await json<Json>(renterCtx, 'post', '/v1/bookings', {
            resourceType: 'AMENITY',
            resourceId: amenity.id,
            unitId: state.units.leased.id,
            preferredDate: definition.preferredDate,
            note: definition.note,
          });
          renterBookings.push(booking);
        }
        if (definition.desiredStatus === 'APPROVED' && booking.status === 'PENDING') {
          booking = await json<Json>(managerCtx, 'post', `/v1/bookings/${booking.id}/approve`, {
            adminNote: definition.adminNote,
          });
        } else if (definition.desiredStatus === 'REJECTED' && booking.status === 'PENDING') {
          booking = await json<Json>(adminCtx, 'post', `/v1/bookings/${booking.id}/reject`, {
            adminNote: definition.adminNote,
          });
        }
      }
      expect(booking.status).toBe(definition.desiredStatus);
      bookingsByKey[definition.key] = booking;
    }
  } finally {
    await api.setTenantFeature(superCtx, state.tenant.id, 'EMAIL_NOTIFICATIONS', true);
    await superCtx.request.dispose();
  }

  const renterFacilities = await json<Json>(renterCtx, 'get', '/v1/facilities/my');
  for (const amenity of Object.values(amenitiesByKey)) {
    expect(renterFacilities.amenities.some((row: Json) => row.id === amenity.id)).toBeTruthy();
  }

  const adminPage = await json<Json>(adminCtx, 'get', `/v1/bookings?propertyId=${state.property.id}&size=100`);
  const managerPage = await json<Json>(managerCtx, 'get', `/v1/bookings?propertyId=${state.property.id}&size=100`);
  const managerPendingAcrossPortfolio = await json<Json>(managerCtx, 'get', '/v1/bookings?status=PENDING&size=100');
  for (const booking of Object.values(bookingsByKey)) {
    expect(adminPage.content.some((row: Json) => row.id === booking.id)).toBeTruthy();
    expect(managerPage.content.some((row: Json) => row.id === booking.id)).toBeTruthy();
  }
  const pendingIds = [bookingsByKey.sauna.id, bookingsByKey.spa.id];
  expect(pendingIds.every((id) => managerPendingAcrossPortfolio.content.some((row: Json) => row.id === id))).toBeTruthy();

  state.facilityDemo = {
    seededAt: new Date().toISOString(),
    propertyId: state.property.id,
    propertyNameEn: state.property.nameEn,
    propertyNameAr: state.property.nameAr,
    amenities: Object.fromEntries(DEFINITIONS.map((definition) => [definition.key, {
      id: amenitiesByKey[definition.key].id,
      nameEn: definition.nameEn,
      nameAr: definition.nameAr,
      bookable: true,
    }])),
    bookingRequests: Object.fromEntries(DEFINITIONS.map((definition) => [definition.key, {
      id: bookingsByKey[definition.key].id,
      resourceName: bookingsByKey[definition.key].resourceName,
      preferredDate: bookingsByKey[definition.key].preferredDate,
      status: bookingsByKey[definition.key].status,
      decidedBy: definition.desiredStatus === 'APPROVED' ? 'PROPERTY_MANAGER'
        : definition.desiredStatus === 'REJECTED' ? 'TENANT_ADMIN' : null,
    }])),
    verification: {
      renterVisibleAmenityCount: Object.keys(amenitiesByKey).length,
      adminVisibleRequestCount: Object.keys(bookingsByKey).length,
      managerVisibleRequestCount: Object.keys(bookingsByKey).length,
      managerPortfolioPendingRequestCount: managerPendingAcrossPortfolio.totalElements,
      emailNotificationsRestored: true,
    },
  };
  fs.writeFileSync(STATE_PATH, `${JSON.stringify(state, null, 2)}\n`, 'utf8');

  await Promise.all([adminCtx.request.dispose(), managerCtx.request.dispose(), renterCtx.request.dispose()]);
});

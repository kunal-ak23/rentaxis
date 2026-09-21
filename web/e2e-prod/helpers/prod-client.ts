/**
 * Prod API client. Goes through the Next.js proxy at /api/proxy/* using a
 * NextAuth session cookie. The middleware in web/src/proxy.ts validates the
 * cookie and injects X-User-Id / X-User-Role / X-Tenant-Id headers when
 * forwarding to the backend.
 *
 * Cookie name: `__Secure-next-auth.session-token` on HTTPS,
 *              `next-auth.session-token` on HTTP.
 */
import { APIRequestContext, expect, request as playwrightRequest } from '@playwright/test';

export interface ProdContext {
  baseURL: string;
  request: APIRequestContext;
  // Populated after login.
  user?: { id: string; email: string; role: string; tenantId: string | null; tenantIds: string[] };
  // Populated by setActiveTenant() — sent to backend via X-Tenant-Id.
  activeTenantId?: string;
}

/**
 * Logs in via NextAuth's credentials provider and returns a request context
 * with the session cookie attached. Stable across calls — the returned
 * `request` can be passed straight into apiCall().
 */
export async function loginAsNextAuth(
  baseURL: string,
  email: string,
  password: string,
): Promise<ProdContext> {
  // Throwaway context for the CSRF + login dance.
  const ctx = await playwrightRequest.newContext({ baseURL, ignoreHTTPSErrors: false });

  // 1. CSRF token.
  const csrfRes = await ctx.get('/api/auth/csrf');
  expect(csrfRes.ok(), `CSRF request failed: ${csrfRes.status()}`).toBeTruthy();
  const { csrfToken } = await csrfRes.json();

  // 2. Credentials login. NextAuth expects form-encoded body, not JSON.
  const loginRes = await ctx.post('/api/auth/callback/credentials', {
    form: {
      csrfToken,
      email,
      password,
      callbackUrl: `${baseURL}/`,
      json: 'true',
    },
    // NextAuth responds 302 on success — don't auto-follow, we just need the cookie set.
    maxRedirects: 0,
    failOnStatusCode: false,
  });

  // 200 (json:true) or 302 (redirect) both mean success. 401/403 = bad creds.
  const status = loginRes.status();
  if (status >= 400) {
    const body = await loginRes.text().catch(() => '');
    throw new Error(`NextAuth login failed (${status}) for ${email}: ${body.slice(0, 300)}`);
  }

  // 3. Confirm we got a session cookie. Different name on http vs https.
  const cookies = (await ctx.storageState()).cookies;
  const sessionCookie = cookies.find(
    (c) => c.name === '__Secure-next-auth.session-token' || c.name === 'next-auth.session-token',
  );
  if (!sessionCookie) {
    throw new Error(
      `Login succeeded with status ${status} but no session cookie found. ` +
        `Cookies present: ${cookies.map((c) => c.name).join(', ')}`,
    );
  }

  // 4. Fetch the session to learn who we are.
  const sessionRes = await ctx.get('/api/auth/session');
  const session = await sessionRes.json();
  if (!session?.user?.id) {
    throw new Error(`Session payload missing user. Got: ${JSON.stringify(session).slice(0, 300)}`);
  }

  return {
    baseURL,
    request: ctx,
    user: {
      id: session.user.id,
      email: session.user.email,
      role: session.user.role,
      tenantId: session.user.tenantId ?? null,
      tenantIds: session.user.tenantIds ?? [],
    },
  };
}

/**
 * Pivots a SUPER_ADMIN's effective tenant by setting the `active_tenant_id`
 * cookie. The proxy reads this cookie and forwards it as X-Tenant-Id.
 */
export async function setActiveTenant(pctx: ProdContext, tenantId: string): Promise<void> {
  // Cookie has to be set on the request context. Playwright doesn't expose
  // a direct cookie-set on APIRequestContext, so we round-trip through storage.
  const state = await pctx.request.storageState();
  const host = new URL(pctx.baseURL).hostname;
  const filtered = state.cookies.filter((c) => c.name !== 'active_tenant_id');
  filtered.push({
    name: 'active_tenant_id',
    value: tenantId,
    domain: host,
    path: '/',
    expires: -1,
    httpOnly: false,
    secure: pctx.baseURL.startsWith('https'),
    sameSite: 'Lax',
  });
  // Recreate the context with the merged cookies.
  const newCtx = await playwrightRequest.newContext({
    baseURL: pctx.baseURL,
    storageState: { ...state, cookies: filtered },
  });
  await pctx.request.dispose().catch(() => {});
  pctx.request = newCtx;
  pctx.activeTenantId = tenantId;
}

/* ------------------------------------------------------------------ */
/* Thin wrappers over /api/proxy/* — one per controller surface used.  */
/* Path convention: backend `/api/<x>` → proxy `/api/proxy/<x>`.       */
/* ------------------------------------------------------------------ */

async function postJson<T>(pctx: ProdContext, path: string, body: unknown): Promise<T> {
  const res = await pctx.request.post(`/api/proxy${path}`, {
    data: body,
    headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
  });
  if (!res.ok()) {
    const txt = await res.text().catch(() => '');
    throw new Error(`POST /api/proxy${path} → ${res.status()}: ${txt.slice(0, 400)}`);
  }
  return res.json() as Promise<T>;
}

async function putJson<T>(pctx: ProdContext, path: string, body: unknown): Promise<T> {
  const res = await pctx.request.put(`/api/proxy${path}`, {
    data: body,
    headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
  });
  if (!res.ok()) {
    const txt = await res.text().catch(() => '');
    throw new Error(`PUT /api/proxy${path} → ${res.status()}: ${txt.slice(0, 400)}`);
  }
  return res.json() as Promise<T>;
}

async function getJson<T>(pctx: ProdContext, path: string): Promise<T> {
  const res = await pctx.request.get(`/api/proxy${path}`, { failOnStatusCode: false });
  if (!res.ok()) {
    const txt = await res.text().catch(() => '');
    throw new Error(`GET /api/proxy${path} → ${res.status()}: ${txt.slice(0, 400)}`);
  }
  return res.json() as Promise<T>;
}

async function deleteOk(pctx: ProdContext, path: string): Promise<void> {
  const res = await pctx.request.delete(`/api/proxy${path}`, { failOnStatusCode: false });
  if (!res.ok()) {
    const txt = await res.text().catch(() => '');
    throw new Error(`DELETE /api/proxy${path} → ${res.status()}: ${txt.slice(0, 400)}`);
  }
}

async function postOk(pctx: ProdContext, path: string, body?: unknown): Promise<void> {
  const res = await pctx.request.post(`/api/proxy${path}`, {
    ...(body === undefined ? {} : { data: body, headers: { 'Content-Type': 'application/json' } }),
    failOnStatusCode: false,
  });
  if (!res.ok()) {
    const txt = await res.text().catch(() => '');
    throw new Error(`POST /api/proxy${path} → ${res.status()}: ${txt.slice(0, 400)}`);
  }
}

function monthsInclusive(startDate: string, endDate: string): number {
  const [sy, sm, sd] = startDate.split('-').map(Number);
  const [ey, em, ed] = endDate.split('-').map(Number);
  // The end date is the inclusive last day of tenancy, so compare against the
  // day after it — matching endDate.plusDays(1) on the backend.
  const endExclusive = new Date(Date.UTC(ey, em - 1, ed + 1));
  let months =
    (endExclusive.getUTCFullYear() - sy) * 12 + (endExclusive.getUTCMonth() - (sm - 1));
  // A partial trailing month does not count, the same way
  // ChronoUnit.MONTHS.between truncates.
  if (endExclusive.getUTCDate() < sd) months -= 1;
  return Math.max(months, 1);
}

export const api = {
  // Provisioning
  createTenant: (pctx: ProdContext, name: string) =>
    postJson<{ id: string; name: string }>(pctx, '/admin/tenants', { name }),
  createUser: (
    pctx: ProdContext,
    tenantId: string,
    u: { name: string; email: string; password: string; role: string; phoneNumber?: string },
  ) => postJson<{ id: string; email: string; role: string }>(pctx, '/admin/users', { ...u, tenantId }),
  assignUserToProperty: async (
    pctx: ProdContext,
    userId: string,
    propertyId: string,
  ): Promise<void> => {
    const res = await pctx.request.post(
      `/api/proxy/admin/users/${userId}/properties/${propertyId}`,
      { failOnStatusCode: false },
    );
    if (!res.ok()) {
      throw new Error(
        `POST property assignment ${userId}/${propertyId} → ${res.status()}: ${(
          await res.text().catch(() => '')
        ).slice(0, 400)}`,
      );
    }
  },
  /**
   * Enable a per-tenant feature toggle. SUPER_ADMIN only.
   * Used by 01-provision to flip EMAIL_NOTIFICATIONS on for the test tenant —
   * otherwise the EmailDispatcher skips every event with
   * `email.dispatch.skipped reason=feature_disabled` (the toggle defaults
   * to false per TenantFeature.EMAIL_NOTIFICATIONS, phased-rollout pattern).
   */
  setTenantFeature: async (
    pctx: ProdContext,
    tenantId: string,
    feature:
      | 'EMAIL_NOTIFICATIONS'
      | 'LISTINGS'
      | 'MEETINGS'
      | 'LEASE_RENEWALS'
      | 'GATEPASS',
    enabled: boolean,
  ): Promise<void> => {
    const res = await pctx.request.put(
      `/api/proxy/admin/tenants/${tenantId}/features/${feature}`,
      {
        data: { enabled },
        headers: { 'Content-Type': 'application/json' },
        failOnStatusCode: false,
      },
    );
    if (!res.ok()) {
      throw new Error(`PUT feature ${feature}=${enabled} → ${res.status()}: ${(await res.text().catch(() => '')).slice(0, 400)}`);
    }
  },

  // Hard-deletes the tenant and every tenant-scoped row. Requires confirmName
  // to match the tenant's current name exactly — safety gate against deleting
  // the wrong UUID. Returns 204 on success.
  deleteTenant: async (pctx: ProdContext, tenantId: string, confirmName: string): Promise<void> => {
    const res = await pctx.request.delete(
      `/api/proxy/admin/tenants/${tenantId}?confirmName=${encodeURIComponent(confirmName)}`,
      { failOnStatusCode: false },
    );
    // A prior cleanup attempt or an operator may already have removed the
    // disposable fixture. The desired postcondition is still satisfied; the
    // cleanup spec verifies absence immediately after this call.
    if (res.status() !== 204 && res.status() !== 404) {
      const txt = await res.text().catch(() => '');
      throw new Error(`DELETE /admin/tenants/${tenantId} → ${res.status()}: ${txt.slice(0, 400)}`);
    }
  },

  // Property + Unit — payload shapes match web/src/app/[locale]/dashboard/properties/page.tsx
  // (handleProjectSubmit + handlePropertySubmit) exactly. Sending fewer
  // fields than the real form risks hiding drift in @NotNull defaults or
  // validators added later.
  createProperty: (pctx: ProdContext, p: { nameEn: string; emirate?: string; type?: string }) =>
    postJson<{ id: string; nameEn: string }>(pctx, '/v1/properties', {
      nameEn: p.nameEn,
      nameAr: p.nameEn,
      emirate: p.emirate ?? 'DUBAI',
      address: '123 TEST-E2E Blvd',
      makaniNumber: '',
      type: p.type ?? 'RESIDENTIAL',
      fixedExpenses: 0,
    }),
  createUnit: (
    pctx: ProdContext,
    u: { propertyId: string; unitNumber: string; type?: string; expectedRent?: number },
  ) =>
    postJson<{ id: string; unitNumber: string }>(pctx, '/v1/units', {
      property: { id: u.propertyId },
      unitNumber: u.unitNumber,
      type: u.type ?? 'BHK1',
      sizeSqft: 600,
      expectedRent: u.expectedRent ?? 5000,
      actualRent: 0,
      status: 'VACANT',
      currentTenantName: '',
    }),
  createBuilding: (
    pctx: ProdContext,
    b: { propertyId: string; nameEn: string; floors: number },
  ) =>
    postJson<{ id: string; nameEn: string; floors: number }>(pctx, '/v1/buildings', {
      property: { id: b.propertyId },
      nameEn: b.nameEn,
      nameAr: b.nameEn,
      floors: b.floors,
    }),
  getBuildingsForProperty: (pctx: ProdContext, propertyId: string) =>
    getJson<Array<{ id: string; nameEn: string; floors: number }>>(
      pctx,
      `/v1/buildings/property/${propertyId}`,
    ),
  deleteBuilding: (pctx: ProdContext, buildingId: string) =>
    deleteOk(pctx, `/v1/buildings/${buildingId}`),

  createPropertyContact: (
    pctx: ProdContext,
    propertyId: string,
    c: { category: string; name: string; phone: string; email?: string; notes?: string },
  ) =>
    postJson<{ id: string; category: string; name: string; phone: string }>(
      pctx,
      `/v1/properties/${propertyId}/contacts`,
      { ...c, sortOrder: 0 },
    ),
  updatePropertyContact: (
    pctx: ProdContext,
    propertyId: string,
    contactId: string,
    c: { category: string; name: string; phone: string; email?: string; notes?: string },
  ) =>
    putJson<{ id: string; category: string; name: string; phone: string }>(
      pctx,
      `/v1/properties/${propertyId}/contacts/${contactId}`,
      { ...c, sortOrder: 0 },
    ),
  getPropertyContacts: (pctx: ProdContext, propertyId: string) =>
    getJson<Array<{ id: string; category: string; name: string; phone: string }>>(
      pctx,
      `/v1/properties/${propertyId}/contacts`,
    ),
  deletePropertyContact: (pctx: ProdContext, propertyId: string, contactId: string) =>
    deleteOk(pctx, `/v1/properties/${propertyId}/contacts/${contactId}`),

  createAmenity: (
    pctx: ProdContext,
    a: { propertyId: string; nameEn: string; buildingIds?: string[]; bookable?: boolean },
  ) =>
    postJson<{
      id: string;
      nameEn: string;
      active: boolean;
      bookable: boolean;
      buildingIds: string[];
    }>(pctx, '/v1/amenities', {
      propertyId: a.propertyId,
      nameEn: a.nameEn,
      nameAr: a.nameEn,
      description: 'Production E2E fixture',
      bookable: a.bookable ?? true,
      buildingIds: a.buildingIds ?? [],
    }),
  updateAmenity: (
    pctx: ProdContext,
    amenityId: string,
    a: { nameEn?: string; active?: boolean; buildingIds?: string[] },
  ) =>
    putJson<{ id: string; nameEn: string; active: boolean; buildingIds: string[] }>(
      pctx,
      `/v1/amenities/${amenityId}`,
      a,
    ),
  getAmenities: (pctx: ProdContext, propertyId: string) =>
    getJson<{ content: Array<{ id: string; nameEn: string; active: boolean }> }>(
      pctx,
      `/v1/amenities?propertyId=${propertyId}`,
    ),
  deactivateAmenity: (pctx: ProdContext, amenityId: string) =>
    deleteOk(pctx, `/v1/amenities/${amenityId}`),

  createParkingSpot: (
    pctx: ProdContext,
    s: { propertyId: string; spotNumber: string; level?: string; buildingIds?: string[] },
  ) =>
    postJson<{
      id: string;
      spotNumber: string;
      level: string;
      active: boolean;
      buildingIds: string[];
    }>(pctx, '/v1/parking-spots', {
      propertyId: s.propertyId,
      spotNumber: s.spotNumber,
      level: s.level ?? 'B1',
      covered: true,
      buildingIds: s.buildingIds ?? [],
    }),
  updateParkingSpot: (
    pctx: ProdContext,
    spotId: string,
    s: { spotNumber?: string; level?: string; active?: boolean; buildingIds?: string[] },
  ) =>
    putJson<{ id: string; spotNumber: string; level: string; active: boolean }>(
      pctx,
      `/v1/parking-spots/${spotId}`,
      s,
    ),
  getParkingSpots: (pctx: ProdContext, propertyId: string) =>
    getJson<{ content: Array<{ id: string; spotNumber: string; active: boolean }> }>(
      pctx,
      `/v1/parking-spots?propertyId=${propertyId}`,
    ),
  deactivateParkingSpot: (pctx: ProdContext, spotId: string) =>
    deleteOk(pctx, `/v1/parking-spots/${spotId}`),

  createStaff: (
    pctx: ProdContext,
    s: { propertyId: string; nameEn: string; employeeId: string },
  ) =>
    postJson<{ id: string; nameEn: string; designation: string; active: boolean }>(
      pctx,
      '/v1/staff',
      {
        nameEn: s.nameEn,
        nameAr: s.nameEn,
        employeeId: s.employeeId,
        designation: 'Facilities Coordinator',
        department: 'Operations',
        monthlySalary: 7500,
        joinDate: new Date().toISOString().slice(0, 10),
        phone: '+971500000004',
        emiratesId: '',
        passportNumber: '',
        active: true,
        property: { id: s.propertyId },
      },
    ),
  updateStaff: (
    pctx: ProdContext,
    staffId: string,
    s: { nameEn: string; propertyId: string; active: boolean },
  ) =>
    putJson<{ id: string; nameEn: string; active: boolean }>(pctx, `/v1/staff/${staffId}`, {
      nameEn: s.nameEn,
      nameAr: s.nameEn,
      employeeId: `UPDATED-${staffId.slice(0, 6)}`,
      designation: 'Senior Facilities Coordinator',
      department: 'Operations',
      monthlySalary: 8000,
      joinDate: new Date().toISOString().slice(0, 10),
      phone: '+971500000004',
      active: s.active,
      property: { id: s.propertyId },
    }),
  getStaffByProperty: (pctx: ProdContext, propertyId: string) =>
    getJson<Array<{ id: string; nameEn: string; active: boolean }>>(
      pctx,
      `/v1/staff/by-property/${propertyId}`,
    ),
  deleteStaff: (pctx: ProdContext, staffId: string) => deleteOk(pctx, `/v1/staff/${staffId}`),

  createBankAccount: (
    pctx: ProdContext,
    b: { propertyId: string; bankName: string; accountNumber: string },
  ) =>
    postJson<{
      id: string;
      bankName: string;
      branchName: string;
      isDefault: boolean;
      active: boolean;
    }>(pctx, '/v1/bank-accounts', {
      bankName: b.bankName,
      accountNumber: b.accountNumber,
      iban: 'AE070331234567890123456',
      branchName: 'Dubai Main',
      currency: 'AED',
      isDefault: true,
      active: true,
      property: { id: b.propertyId },
    }),
  updateBankAccount: (
    pctx: ProdContext,
    bankAccountId: string,
    b: { propertyId: string; bankName: string; accountNumber: string },
  ) =>
    putJson<{ id: string; bankName: string; branchName: string; isDefault: boolean }>(
      pctx,
      `/v1/bank-accounts/${bankAccountId}`,
      {
        bankName: b.bankName,
        accountNumber: b.accountNumber,
        iban: 'AE070331234567890123456',
        branchName: 'Marina Branch',
        currency: 'AED',
        isDefault: true,
        active: true,
        property: { id: b.propertyId },
      },
    ),
  getBankAccountsByProperty: (pctx: ProdContext, propertyId: string) =>
    getJson<Array<{ id: string; bankName: string; isDefault: boolean }>>(
      pctx,
      `/v1/bank-accounts/by-property/${propertyId}`,
    ),
  deleteBankAccount: (pctx: ProdContext, bankAccountId: string) =>
    deleteOk(pctx, `/v1/bank-accounts/${bankAccountId}`),

  getFineSettings: (pctx: ProdContext) =>
    getJson<{
      bounceAmount: number;
      signatureMismatchAmount: number;
      accountClosedAmount: number;
      graceDays: number;
      perDayRate: number;
    }>(pctx, '/v1/settings/fines'),
  updateFineSettings: (
    pctx: ProdContext,
    f: {
      bounceAmount: number;
      signatureMismatchAmount: number;
      accountClosedAmount: number;
      graceDays: number;
      perDayRate: number;
    },
  ) =>
    putJson<{
      bounceAmount: number;
      signatureMismatchAmount: number;
      accountClosedAmount: number;
      graceDays: number;
      perDayRate: number;
    }>(pctx, '/v1/settings/fines', f),
  saveRentSettings: (
    pctx: ProdContext,
    propertyId: string,
    s: {
      dueDayOfMonth: number;
      gracePeriodDays: number;
      penaltyType: string;
      penaltyAmount: number;
      onlinePaymentEnabled: boolean;
    },
  ) =>
    postJson<{
      propertyId: string;
      dueDayOfMonth: number;
      gracePeriodDays: number;
      penaltyType: string;
      penaltyAmount: number;
      onlinePaymentEnabled: boolean;
    }>(pctx, `/v1/rent-settings/${propertyId}`, s),
  getRentSettings: (pctx: ProdContext, propertyId: string) =>
    getJson<{
      propertyId: string;
      dueDayOfMonth: number;
      gracePeriodDays: number;
      penaltyType: string;
      penaltyAmount: number;
      onlinePaymentEnabled: boolean;
    }>(pctx, `/v1/rent-settings/${propertyId}`),

  seedAccounts: (pctx: ProdContext) =>
    postJson<Array<{ id: string; code: string; nameEn: string; accountType: string }>>(
      pctx,
      '/v1/finance/accounts/seed',
      {},
    ),
  getAccounts: (pctx: ProdContext) =>
    getJson<
      Array<{
        id: string;
        code: string;
        nameEn: string;
        /** `AccountDTO.name` — `nameEn` above is the older alias some callers read. */
        name?: string;
        accountType: string;
        /** BANK / CASH / … — a refund may only be paid from an active asset leaf. */
        accountSubType?: string | null;
        group?: boolean;
        active?: boolean;
      }>
    >(pctx, '/v1/finance/accounts'),

  createListing: (
    pctx: ProdContext,
    l: { unitId: string; titleEn: string; annualRent: number; availableFrom: string },
  ) =>
    postJson<{
      id: string;
      unitId: string;
      status: string;
      titleEn: string;
      annualRent: number;
      tenantSlug: string;
      slug: string;
    }>(pctx, '/listings', {
      unitId: l.unitId,
      titleEn: l.titleEn,
      titleAr: l.titleEn,
      descriptionEn: 'Production E2E marketplace fixture',
      descriptionAr: 'Production E2E marketplace fixture',
      bedrooms: 1,
      bathrooms: 1,
      sizeSqft: 600,
      floor: 12,
      parkingSpaces: 1,
      furnishing: 'UNFURNISHED',
      viewType: 'CITY',
      annualRent: l.annualRent,
      securityDeposit: 5000,
      minLeaseMonths: 12,
      chequesAccepted: 4,
      dewaIncluded: false,
      chillerIncluded: false,
      utilitiesEstimate: 500,
      availableFrom: l.availableFrom,
      seoTitle: l.titleEn,
      seoDescription: 'TEST-E2E listing',
      seoKeywords: 'test,e2e,rental',
      lat: 25.2048,
      lng: 55.2708,
      amenities: [
        { amenity: 'GYM', customLabel: null },
        { amenity: 'COVERED_PARKING', customLabel: null },
      ],
    }),
  updateListing: (
    pctx: ProdContext,
    listingId: string,
    l: { titleEn: string; annualRent: number; availableFrom: string },
  ) =>
    putJson<{ id: string; titleEn: string; annualRent: number; status: string }>(
      pctx,
      `/listings/${listingId}`,
      {
        titleEn: l.titleEn,
        titleAr: l.titleEn,
        descriptionEn: 'Updated production E2E marketplace fixture',
        descriptionAr: 'Updated production E2E marketplace fixture',
        bedrooms: 1,
        bathrooms: 1,
        sizeSqft: 600,
        floor: 12,
        parkingSpaces: 1,
        furnishing: 'SEMI_FURNISHED',
        viewType: 'CITY',
        annualRent: l.annualRent,
        securityDeposit: 5000,
        minLeaseMonths: 12,
        chequesAccepted: 4,
        dewaIncluded: false,
        chillerIncluded: false,
        utilitiesEstimate: 500,
        availableFrom: l.availableFrom,
        amenities: [{ amenity: 'GYM', customLabel: null }],
      },
    ),
  publishListing: (pctx: ProdContext, listingId: string) =>
    postOk(pctx, `/listings/${listingId}/publish`),
  unlistListing: (pctx: ProdContext, listingId: string) =>
    postOk(pctx, `/listings/${listingId}/unlist`),
  deleteListing: (pctx: ProdContext, listingId: string) =>
    deleteOk(pctx, `/listings/${listingId}`),
  getMarketplaceListings: (pctx: ProdContext, tenantSlug: string) =>
    getJson<{ content: Array<{ id: string; titleEn: string; status: string; slug: string }> }>(
      pctx,
      `/marketplace/${tenantSlug}/listings`,
    ),
  getMarketplaceListing: (pctx: ProdContext, tenantSlug: string, listingSlug: string) =>
    getJson<{ id: string; titleEn: string; tenantSlug: string; slug: string }>(
      pctx,
      `/marketplace/${tenantSlug}/listings/${listingSlug}`,
    ),
  addListingInterest: (pctx: ProdContext, listingId: string, note: string) =>
    postOk(pctx, `/marketplace/listings/${listingId}/interest`, { note }),
  withdrawListingInterest: (pctx: ProdContext, listingId: string) =>
    deleteOk(pctx, `/marketplace/listings/${listingId}/interest`),
  getWishlist: (pctx: ProdContext) =>
    getJson<Array<{ id: string; titleEn: string }>>(pctx, '/marketplace/me/wishlist'),
  getListingInterests: (pctx: ProdContext, listingId: string) =>
    getJson<{ content: Array<{ id: string; renterUserId: string; note: string }> }>(
      pctx,
      `/listings/${listingId}/interests`,
    ),

  getMeetingSlots: (pctx: ProdContext, hostUserId: string, date: string) =>
    getJson<Array<{ start: string; end: string; available: boolean }>>(
      pctx,
      `/v1/meetings/slots?hostUserId=${hostUserId}&date=${date}`,
    ),
  createMeeting: (
    pctx: ProdContext,
    m: {
      hostUserId: string;
      slotStart: string;
      propertyId: string;
      unitId: string;
      title: string;
    },
  ) =>
    postJson<{
      id: string;
      status: string;
      requesterUserId: string;
      hostUserId: string;
      slotStart: string;
    }>(pctx, '/v1/meetings', {
      type: 'PROPERTY_VISIT',
      purpose: 'PROPERTY_VIEWING',
      title: m.title,
      notes: 'Production E2E fixture',
      slotStart: m.slotStart,
      hostUserId: m.hostUserId,
      propertyId: m.propertyId,
      unitId: m.unitId,
    }),
  approveMeeting: (pctx: ProdContext, meetingId: string) =>
    putJson<{ id: string; status: string }>(pctx, `/v1/meetings/${meetingId}/approve`, {}),
  completeMeeting: (pctx: ProdContext, meetingId: string) =>
    putJson<{ id: string; status: string }>(pctx, `/v1/meetings/${meetingId}/complete`, {}),
  getMeeting: (pctx: ProdContext, meetingId: string) =>
    getJson<{ id: string; status: string; title: string }>(pctx, `/v1/meetings/${meetingId}`),
  getMyMeetings: (pctx: ProdContext, perspective = 'requester') =>
    getJson<{ content: Array<{ id: string; status: string }> }>(
      pctx,
      `/v1/meetings/my?perspective=${perspective}`,
    ),
  getMeetings: (pctx: ProdContext) =>
    getJson<{ content: Array<{ id: string; status: string }> }>(pctx, '/v1/meetings'),
  getMeetingCalendar: (pctx: ProdContext, start: string, end: string) =>
    getJson<{ content: Array<{ id: string; status: string }> }>(
      pctx,
      `/v1/meetings/calendar?start=${encodeURIComponent(start)}&end=${encodeURIComponent(end)}`,
    ),

  getMyFacilities: (pctx: ProdContext) =>
    getJson<{
      amenities: Array<{ id: string; nameEn: string; bookable: boolean; pendingCount: number }>;
      parkingSpots: Array<{ id: string; spotNumber: string; held: boolean; pendingCount: number }>;
    }>(pctx, '/v1/facilities/my'),
  createBooking: (
    pctx: ProdContext,
    b: {
      resourceType: 'AMENITY' | 'PARKING_SPOT';
      resourceId: string;
      unitId: string;
      preferredDate: string;
      note: string;
    },
  ) =>
    postJson<{
      id: string;
      resourceType: string;
      amenityId: string | null;
      parkingSpotId: string | null;
      unitId: string;
      status: string;
    }>(pctx, '/v1/bookings', b),
  getMyBookings: (pctx: ProdContext) =>
    getJson<Array<{ id: string; resourceType: string; status: string }>>(pctx, '/v1/bookings/my'),
  getBookings: (pctx: ProdContext, propertyId: string) =>
    getJson<{ content: Array<{ id: string; resourceType: string; status: string }> }>(
      pctx,
      `/v1/bookings?propertyId=${propertyId}`,
    ),
  getBooking: (pctx: ProdContext, bookingId: string) =>
    getJson<{
      request: { id: string; resourceType: string; status: string };
      otherRequests: Array<{ id: string; status: string }>;
    }>(pctx, `/v1/bookings/${bookingId}`),
  approveBooking: (pctx: ProdContext, bookingId: string, adminNote: string) =>
    postJson<{ id: string; status: string; adminNote: string }>(
      pctx,
      `/v1/bookings/${bookingId}/approve`,
      { adminNote },
    ),
  cancelBooking: (pctx: ProdContext, bookingId: string) =>
    postJson<{ id: string; status: string }>(pctx, `/v1/bookings/${bookingId}/cancel`, {}),
  releaseBooking: (pctx: ProdContext, bookingId: string) =>
    postJson<{ id: string; status: string }>(pctx, `/v1/bookings/${bookingId}/release`, {}),

  setGuardProperties: (pctx: ProdContext, guardUserId: string, propertyIds: string[]) =>
    putJson<string[]>(pctx, `/v1/gatepass/guards/${guardUserId}/properties`, propertyIds),
  getAssignedGuardPropertyIds: (pctx: ProdContext, guardUserId: string) =>
    getJson<string[]>(pctx, `/v1/gatepass/guards/${guardUserId}/properties`),
  getGuardProperties: (pctx: ProdContext) =>
    getJson<Array<{ id: string; name: string }>>(pctx, '/v1/gatepass/my-properties'),
  createGatePass: (
    pctx: ProdContext,
    p: { unitId: string; validFrom: string; validTo: string; guestName: string },
  ) =>
    postJson<{
      id: string;
      propertyId: string;
      unitId: string;
      status: string;
      qrToken: string;
      numericCode: string;
    }>(pctx, '/v1/gatepass', {
      unitId: p.unitId,
      guestName: p.guestName,
      guestPhone: '+971500000005',
      purpose: 'TEST-Visitor access',
      vehicleNumber: 'TEST-E2E',
      passType: 'RECURRING',
      validFrom: p.validFrom,
      validTo: p.validTo,
    }),
  getMyGatePasses: (pctx: ProdContext) =>
    getJson<Array<{ id: string; status: string; qrToken: string; numericCode: string }>>(
      pctx,
      '/v1/gatepass/mine',
    ),
  getGatePassApprovals: (pctx: ProdContext) =>
    getJson<Array<{ id: string; propertyId: string; status: string }>>(pctx, '/v1/gatepass/approvals'),
  approveGatePass: (pctx: ProdContext, gatePassId: string, approved: boolean) =>
    postJson<{ id: string; propertyId: string; status: string }>(
      pctx,
      `/v1/gatepass/${gatePassId}/approval`,
      { approved },
    ),
  getExpectedGatePasses: (pctx: ProdContext) =>
    getJson<Array<{ id: string; propertyId: string; status: string }>>(
      pctx,
      '/v1/gatepass/expected-today',
    ),
  scanGatePass: (
    pctx: ProdContext,
    body: { qrToken?: string; numericCode?: string; direction: 'ENTRY' | 'EXIT' },
  ) =>
    postJson<{ result: string; reason: string | null; guestName: string; unitNumber: string }>(
      pctx,
      '/v1/gatepass/scan',
      body,
    ),
  getGatePassReport: (pctx: ProdContext, from: string, to: string, propertyId: string) =>
    getJson<Array<{ scanId: string; gatePassId: string; result: string; propertyId: string }>>(
      pctx,
      `/v1/gatepass/report?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}&propertyId=${propertyId}`,
    ),
  cancelGatePass: (pctx: ProdContext, gatePassId: string) =>
    postJson<{ id: string; status: string }>(pctx, `/v1/gatepass/${gatePassId}/cancel`, {}),

  getWalkInDestinations: (pctx: ProdContext, propertyId: string) =>
    getJson<Array<{
      unitId: string;
      unitNumber: string;
      propertyId: string;
      buildingId: string | null;
      buildingName: string | null;
    }>>(pctx, `/v1/gatepass/walk-in/destinations?propertyId=${propertyId}`),
  getEffectiveGatePolicy: (pctx: ProdContext, propertyId: string) =>
    getJson<{
      id: string | null;
      propertyId: string;
      inherited: boolean;
      requireUnregisteredApproval: boolean;
      requireRegisteredApproval: boolean;
      notifyRegisteredEntry: boolean;
      requireFreshPhoto: boolean;
      approvalTimeoutMinutes: number;
    }>(pctx, `/v1/gatepass/policies/effective?propertyId=${propertyId}`),
  setGatePolicy: (
    pctx: ProdContext,
    propertyId: string,
    body: {
      requireUnregisteredApproval: boolean;
      requireRegisteredApproval: boolean;
      notifyRegisteredEntry: boolean;
      requireFreshPhoto: boolean;
      approvalTimeoutMinutes: number;
    },
  ) => putJson<{
    id: string;
    propertyId: string;
    inherited: boolean;
    requireUnregisteredApproval: boolean;
    requireRegisteredApproval: boolean;
    notifyRegisteredEntry: boolean;
    requireFreshPhoto: boolean;
    approvalTimeoutMinutes: number;
  }>(pctx, `/v1/gatepass/policies?propertyId=${propertyId}`, body),
  createManagedVisitorRegistration: (
    pctx: ProdContext,
    body: {
      propertyId: string;
      unitId: string;
      name: string;
      phone: string;
      visitorType:
        | 'GUEST'
        | 'DELIVERY'
        | 'MAID'
        | 'MILK_VENDOR'
        | 'LAUNDRY_VENDOR'
        | 'SERVICE_VENDOR'
        | 'OTHER';
      validFrom: string;
      validTo: string;
      active: boolean;
    },
  ) => postJson<{
    id: string;
    name: string;
    phone: string;
    visitorType: string;
    lastUnitId: string;
    registeredForSelectedUnit: boolean;
  }>(pctx, '/v1/gatepass/visitors/registration', body),
  lookupWalkInVisitor: (
    pctx: ProdContext,
    propertyId: string,
    unitId: string,
    phone: string,
  ) => getJson<{
    id: string;
    name: string;
    phone: string;
    visitorType: string;
    lastUnitId: string;
    registeredForSelectedUnit: boolean;
  }>(
    pctx,
    `/v1/gatepass/walk-in/visitor?propertyId=${propertyId}&unitId=${unitId}&phone=${encodeURIComponent(phone)}`,
  ),

  createPromoBusiness: (
    pctx: ProdContext,
    body: {
      nameEn: string;
      nameAr?: string;
      category: string;
      phoneE164?: string;
      whatsappE164?: string;
      allowedDomains?: string[];
      active: boolean;
    },
  ) =>
    postJson<{ id: string; nameEn: string; category: string; active: boolean }>(
      pctx,
      '/v1/promotions/businesses',
      body,
    ),
  updatePromoBusiness: (
    pctx: ProdContext,
    businessId: string,
    body: {
      nameEn: string;
      nameAr?: string;
      category: string;
      phoneE164?: string;
      whatsappE164?: string;
      allowedDomains?: string[];
      active: boolean;
    },
  ) =>
    putJson<{ id: string; nameEn: string; category: string; active: boolean }>(
      pctx,
      `/v1/promotions/businesses/${businessId}`,
      body,
    ),
  listPromoBusinesses: (pctx: ProdContext) =>
    getJson<{ content: Array<{ id: string; nameEn: string; active: boolean; adCount: number }> }>(
      pctx,
      '/v1/promotions/businesses?size=100',
    ),
  deletePromoBusiness: (pctx: ProdContext, businessId: string) =>
    deleteOk(pctx, `/v1/promotions/businesses/${businessId}`),
  createPromoAd: (
    pctx: ProdContext,
    body: {
      businessId: string;
      titleEn: string;
      titleAr?: string;
      subtitleEn?: string;
      accentColor?: string;
      ctaType: string;
      ctaLabelEn?: string;
      couponCode?: string;
      couponTermsEn?: string;
      startsAt: string;
      endsAt: string;
      priority: number;
      placement: string;
      propertyIds: string[];
      active: boolean;
    },
  ) =>
    postJson<{
      id: string;
      businessId: string;
      titleEn: string;
      couponCode: string | null;
      priority: number;
      placement: string;
      active: boolean;
      propertyIds: string[];
    }>(pctx, '/v1/promotions/ads', body),
  updatePromoAd: (
    pctx: ProdContext,
    adId: string,
    body: {
      businessId: string;
      titleEn: string;
      titleAr?: string;
      subtitleEn?: string;
      accentColor?: string;
      ctaType: string;
      ctaLabelEn?: string;
      couponCode?: string;
      couponTermsEn?: string;
      startsAt: string;
      endsAt: string;
      priority: number;
      placement: string;
      propertyIds: string[];
      active: boolean;
    },
  ) =>
    putJson<{
      id: string;
      titleEn: string;
      priority: number;
      placement: string;
      active: boolean;
      propertyIds: string[];
    }>(pctx, `/v1/promotions/ads/${adId}`, body),
  listPromoAds: (pctx: ProdContext, businessId: string) =>
    getJson<{
      content: Array<{
        id: string;
        titleEn: string;
        impressions: number;
        clicks: number;
        active: boolean;
      }>;
    }>(pctx, `/v1/promotions/ads?businessId=${businessId}&size=100`),
  getPromotionFeed: (pctx: ProdContext) =>
    getJson<
      Array<{
        id: string;
        titleEn: string;
        ctaType: string;
        couponCode: string | null;
        business: { id: string; nameEn: string; category: string };
      }>
    >(pctx, '/v1/promotions/feed'),
  getPromotionOffers: (pctx: ProdContext, category?: string) =>
    getJson<
      Array<{
        id: string;
        titleEn: string;
        ctaType: string;
        couponCode: string | null;
        business: { id: string; nameEn: string; category: string };
      }>
    >(pctx, `/v1/promotions/offers${category ? `?category=${category}` : ''}`),
  recordPromotionEvents: (
    pctx: ProdContext,
    events: Array<{ adId: string; type: 'IMPRESSION' | 'CLICK' }>,
  ) => postOk(pctx, '/v1/promotions/events', { events }),
  getPromoAdStats: (pctx: ProdContext, adId: string) =>
    getJson<{
      adId: string;
      impressions: number;
      clicks: number;
      tapThroughRate: number;
    }>(pctx, `/v1/promotions/ads/${adId}/stats`),

  getAvailablePaymentGateways: (pctx: ProdContext) =>
    getJson<
      Array<{
        id: string;
        code: string;
        name: string;
        isActive: boolean;
        supportedCurrencies: string;
      }>
    >(pctx, '/v1/gateway-config/gateways'),
  savePaymentGatewayConfig: (
    pctx: ProdContext,
    body: {
      gatewayId: string;
      apiKey?: string | null;
      apiSecret?: string | null;
      webhookSecret?: string | null;
      isActive: boolean;
      isTestMode: boolean;
    },
  ) =>
    postJson<{
      id: string;
      gatewayId: string;
      gatewayCode: string;
      gatewayName: string;
      apiKey: null;
      apiSecret: null;
      webhookSecret: null;
      apiKeyMasked: string;
      hasWebhookSecret: boolean;
      isActive: boolean;
      isTestMode: boolean;
    }>(pctx, '/v1/gateway-config', body),
  getActivePaymentGatewayConfig: async (pctx: ProdContext) => {
    const res = await pctx.request.get('/api/proxy/v1/gateway-config', {
      failOnStatusCode: false,
    });
    if (res.status() === 204) {
      return null;
    }
    if (!res.ok()) {
      const txt = await res.text().catch(() => '');
      throw new Error(`GET /api/proxy/v1/gateway-config → ${res.status()}: ${txt.slice(0, 400)}`);
    }
    return res.json() as Promise<{
      id: string;
      gatewayId: string;
      gatewayCode: string;
      gatewayName: string;
      apiKey: null;
      apiSecret: null;
      webhookSecret: null;
      apiKeyMasked: string;
      hasWebhookSecret: boolean;
      isActive: boolean;
      isTestMode: boolean;
    }>;
  },

  // Renter + Lease
  // Backend defaults createPortalAccount=true and returns portalPassword on
  // the response. We don't need to pass the flag explicitly anymore.
  createRenter: (pctx: ProdContext, r: { nameEn: string; email: string; createPortalAccount?: boolean }) =>
    postJson<{ id: string; nameEn: string; email: string; userId: string; portalPassword: string | null }>(
      pctx,
      '/v1/renters',
      {
        nameEn: r.nameEn,
        nameAr: r.nameEn,
        email: r.email,
        phone: '+971500000000',
        primaryLanguage: 'EN',
        ...(r.createPortalAccount === false ? { createPortalAccount: false } : {}),
      },
    ),
  // Lease — payload mirrors LeaseWizard.tsx exactly. The frontend wizard
  // computes `rentAmount = monthlyRent * monthsBetween` so the lease total
  // is the lifetime rent, not the monthly rent. We mimic that, otherwise the
  // resulting lease has a tiny `rentAmount` and downstream tests behave
  // differently than a real lease.
  //
  // Note: the same monthsBetween calculation lives in LeaseMetadataEditor.tsx
  // (line ~201). If those two diverge from each other in the future, this
  // helper will silently desync from one of them.
  // Mirrors backend DateMath.monthsInclusive:
  //   max(ChronoUnit.MONTHS.between(start, end.plusDays(1)), 1)
  // i.e. whole months from start to the day after the inclusive last day of
  // tenancy. Parsed as UTC parts so the result does not shift with the runner's
  // timezone. The previous calendar-month formula
  // ((endY-startY)*12 + (endM-startM) + 1) disagreed with the backend on any
  // lease that is not a whole number of months: a today -> today+60d lease is
  // 3 by that formula and 2 here, which is what the backend actually charges.
  monthsInclusive,

  /**
   * v2's draft-lease body: `lines` cut from the tenant's charge-type
   * catalogue (`RENT` + `SECURITY_DEPOSIT`, seeded by `seedDefaultAccounts`
   * below), not the v1 flat `rentAmount`/`monthlyRent`/`depositAmount`.
   * `rentAmount` here is the full contract value for the term — the same
   * convention the old helper used for its own `rentAmount`.
   *
   * The draft is NOT posted — callers that need an ACTIVE lease (the old
   * `activateLease` contract) call `postLeaseFlow` below, which chains
   * generate-cheques + post the way the wizard does.
   */
  createLease: (
    pctx: ProdContext,
    l: {
      unitId: string;
      renterId: string;
      startDate: string;
      endDate: string;
      rentAmount: number;
      paymentTerms?: number;
      depositAmount?: number;
    },
  ) =>
    postJson<{ id: string; status: string }>(pctx, '/v1/leases', {
      unitId: l.unitId,
      renterId: l.renterId,
      startDate: l.startDate,
      endDate: l.endDate,
      paymentTerms: l.paymentTerms ?? 4,
      paymentMethod: 'CHEQUE',
      depositPaymentMethod: 'CHEQUE',
      lines: [
        { chargeTypeCode: 'RENT', grossAmount: l.rentAmount },
        { chargeTypeCode: 'SECURITY_DEPOSIT', grossAmount: l.depositAmount ?? 5000 },
      ],
    }),
  /** `POST /leases/{id}/cheques/generate` — the service fills in every omitted field from the lease's own defaults. */
  generateCheques: (
    pctx: ProdContext,
    leaseId: string,
    req: { installments?: number; firstDueDate?: string; distribution?: string; foldDepositsAndFeesIntoFirst?: boolean } = {},
  ) => postJson<Array<{ id: string; seqNo: number; amount: number; status: string; mode: string }>>(
    pctx,
    `/v1/leases/${leaseId}/cheques/generate`,
    req,
  ),
  /** `POST /leases/{id}/post` — writes the TCO (+ PDR per cheque) and moves the lease to ACTIVE. */
  postDraftLease: (pctx: ProdContext, leaseId: string) =>
    postJson<{
      lease: { id: string; status: string };
      tcoJournalId: string;
      tcoEntryNumber: string;
      cheques: Array<{ id: string; seqNo: number; amount: number; status: string }>;
    }>(pctx, `/v1/leases/${leaseId}/post`, undefined),
  /**
   * v2's replacement for the old `activateLease`: create the draft, cut the
   * cheque grid, post. `PUT /leases/{id}/activate` (no body, no Content-Type)
   * and the payment-schedule it used to spin up are both gone.
   */
  postLeaseFlow: async (
    pctx: ProdContext,
    l: {
      unitId: string;
      renterId: string;
      startDate: string;
      endDate: string;
      rentAmount: number;
      paymentTerms?: number;
      depositAmount?: number;
    },
  ) => {
    const draft = await api.createLease(pctx, l);
    await api.generateCheques(pctx, draft.id, { installments: l.paymentTerms ?? 4 });
    const posted = await api.postDraftLease(pctx, draft.id);
    return posted.lease;
  },
  getLease: (pctx: ProdContext, leaseId: string) =>
    getJson<{
      id: string;
      unitId: string;
      renterId: string;
      startDate: string;
      endDate: string;
      status: string;
      rentAmount: number | null;
      depositAmount: number | null;
      ejariNumber: string | null;
      paymentTerms: number | null;
      installmentDistribution: string | null;
      paymentMethod: string | null;
      depositPaymentMethod: string | null;
      paymentReferenceNumber: string | null;
      agreementDate: string | null;
      rentVatApplicable: boolean | null;
      contractValue: number | null;
      lines: Array<{ id: string; seqNo: number; chargeTypeCode: string; grossAmount: number; netAmount: number }>;
    }>(pctx, `/v1/leases/${leaseId}`),
  getLeaseCheques: (pctx: ProdContext, leaseId: string) =>
    getJson<Array<{
      id: string;
      seqNo: number;
      amount: number;
      status: string;
      mode: string;
      chequeNumber: string | null;
      due: boolean;
      overdue: boolean;
    }>>(pctx, `/v1/leases/${leaseId}/cheques`),
  updateDraftLease: (
    pctx: ProdContext,
    leaseId: string,
    body: {
      unitId: string;
      renterId: string;
      startDate: string;
      endDate: string;
      ejariNumber?: string | null;
      paymentTerms?: number | null;
      installmentDistribution?: string | null;
      paymentMethod?: string | null;
      depositPaymentMethod?: string | null;
      paymentReferenceNumber?: string | null;
      agreementDate?: string | null;
      rentVatApplicable?: boolean | null;
      lines: Array<{ chargeTypeCode?: string | null; chargeTypeId?: string | null; grossAmount: number; discountAmount?: number | null }>;
    },
  ) => putJson<{ id: string; status: string; ejariNumber: string; paymentReferenceNumber: string }>(
    pctx,
    `/v1/leases/${leaseId}`,
    body,
  ),
  deleteDraftLease: (pctx: ProdContext, leaseId: string) =>
    deleteOk(pctx, `/v1/leases/${leaseId}`),
  /**
   * v2's replacement for the old per-row `updateLeasePaymentSchedule`:
   * `PUT /leases/{id}/cheques` re-saves the whole DRAFT grid in one call
   * (`ChequeGrid`'s own `toChequeRows`/`leaseApi.saveCheques`).
   */
  saveLeaseCheques: (
    pctx: ProdContext,
    leaseId: string,
    rows: Array<{
      id?: string | null;
      seqNo?: number | null;
      postingDate?: string | null;
      chequeNumber?: string | null;
      chequeDate?: string | null;
      payeeBank?: string | null;
      debitAccountId?: string | null;
      amount: number;
      narration?: string | null;
      mode?: 'PDC' | 'CASH' | 'TRANSFER' | 'ONLINE';
    }>,
  ) => putJson<Array<{ id: string; status: string; mode: string; chequeNumber: string | null }>>(
    pctx,
    `/v1/leases/${leaseId}/cheques`,
    rows,
  ),
  /**
   * `scheduleId` is kept as the field name Task 15 aliased for the upload
   * screen (`BulkAttachChequeItem#chequeId`, `@JsonAlias("scheduleId")`), but
   * it now names a cheque-register row, not a payment-schedule row, and the
   * response is `{ cheques: ChequeDTO[] }`, not `{ schedules: [...] }` — a
   * bulk attach never posts, so status stays whatever it was (DRAFT or
   * REGISTERED), never "COLLECTED".
   */
  bulkAttachCheques: (
    pctx: ProdContext,
    leaseId: string,
    items: Array<{
      scheduleId: string;
      chequeNumber: string;
      chequeDate: string;
      bankName: string;
      payerName: string;
      imageUrl: string;
      imageBlobPath: string;
      imageUploadedAt: string;
    }>,
  ) => postJson<{
    cheques: Array<{ id: string; status: string; chequeNumber: string; imageUrl: string | null }>;
  }>(pctx, `/v1/leases/${leaseId}/cheques/bulk-attach`, { items }),
  /**
   * v2's `extend` posts a fresh TCO for the extension's own `lines` and
   * registers `cheques` whose total must equal those lines' VAT-inclusive
   * total (`ExtendLeaseDialog`'s own `matches` gate) — unlike the v1
   * single-field `{ newEndDate }` body. `lineGrossAmount` is one new RENT
   * line for the extension period; `chequeAmount` (defaults to the same
   * figure — VAT is off by default on a RENT line) is what the one cheque
   * this helper registers for it must carry.
   */
  extendLease: (
    pctx: ProdContext,
    leaseId: string,
    newEndDate: string,
    lineGrossAmount: number,
    chequeAmount: number = lineGrossAmount,
  ) =>
    postJson<{
      lease: { id: string; status: string; endDate: string };
      tcoJournalId: string;
      cheques: Array<{ id: string; amount: number; status: string }>;
    }>(pctx, `/v1/leases/${leaseId}/extend`, {
      newEndDate,
      lines: [{ chargeTypeCode: 'RENT', grossAmount: lineGrossAmount }],
      cheques: [{ amount: chequeAmount, mode: 'PDC', postingDate: newEndDate }],
    }),
  /**
   * accounting-v2 plan 3 — ending a contract on a date. The preview writes
   * nothing; `terminateLease` hands back the cheques the preview listed,
   * truncates the recognition schedule at `terminationDate` and reverses the
   * unearned rent. Both lists must account for EVERY uncleared row, which is
   * why the caller passes the preview's own ids rather than a filter.
   */
  previewTermination: (pctx: ProdContext, leaseId: string, date: string) =>
    getJson<{
      terminationDate: string;
      earnedRentThroughDate: number;
      recognisedSoFar: number;
      unearnedRent: number;
      chequesToReturn: Array<{ id: string; amount: number }>;
      chequesToKeep: Array<{ id: string; amount: number }>;
      bouncedOutstanding: Array<{ id: string; amount: number }>;
      receivableAfter: number;
    }>(pctx, `/v1/leases/${leaseId}/terminate/preview?date=${date}`),
  terminateLease: (
    pctx: ProdContext,
    leaseId: string,
    body: { terminationDate: string; returnChequeIds?: string[]; keepChequeIds?: string[]; notes?: string },
  ) =>
    postJson<{ id: string; status: string; terminatedOn: string; terminationJournalId: string | null }>(
      pctx,
      `/v1/leases/${leaseId}/terminate`,
      body,
    ),
  /** The month-end close. `to` later than today is a 400; `preview` writes nothing. */
  runRecognition: (pctx: ProdContext, to: string, preview = false) =>
    postJson<{
      preview: boolean;
      posted: number;
      wouldPost: number;
      amount: number;
      skippedLocked: number;
      booksLockedThrough: string | null;
      failed: number;
      errors: string[];
    }>(pctx, `/v1/finance/recognition/run?to=${to}&preview=${preview}`, {}),
  /**
   * The move-out statement, recomputed from the ledger on every read. The old
   * shape (`depositAmount` / `unpaidRentTotal` / `penaltyTotal` /
   * `suggestedRefund`) was deleted with `SettlementPreviewDTO` in plan 3;
   * unpaid rent and penalties now live INSIDE `receivableBalance`, which is why
   * they are no longer separate figures and cannot be settlement lines either.
   */
  getSettlementPreview: (pctx: ProdContext, leaseId: string) =>
    getJson<{
      asOf: string;
      earnedRent: number;
      receivedTotal: number;
      receivableBalance: number;
      depositsHeld: number;
      penaltiesOutstanding: number;
      instrumentsOutstanding: number;
      outstandingInstruments: Array<{ id: string; amount: number; status: string }>;
      totalDeductions: number;
      totalAdditions: number;
      netRefund: number;
      unrecognisedEntries: number;
    }>(pctx, `/v1/leases/${leaseId}/settlement/preview`),
  saveSettlementDraft: (
    pctx: ProdContext,
    leaseId: string,
    body: {
      notes: string;
      deductions: Array<{
        category?: string;
        description: string;
        amount: number;
        autoCalculated: boolean;
        type: 'DEDUCTION' | 'ADDITION';
        additionCategory?: string;
      }>;
    },
  ) =>
    postJson<{
      id: string;
      leaseId: string;
      status: string;
      totalDeductions: number;
      totalAdditions: number;
      refundAmount: number;
      deductions: Array<{ id: string; type: string; amount: number }>;
    }>(pctx, `/v1/leases/${leaseId}/settlement/draft`, body),
  getSettlement: (pctx: ProdContext, leaseId: string) =>
    getJson<{
      id: string;
      leaseId: string;
      status: string;
      totalDeductions: number;
      totalAdditions: number;
      refundAmount: number;
      balanceDue: number;
      journalNumber: string | null;
      collectionChequeId: string | null;
    }>(pctx, `/v1/leases/${leaseId}/settlement`),
  /**
   * Finalise now takes a body and answers with the SETTLEMENT, not the lease —
   * and it no longer terminates anything: `LeaseClosureService` closes the
   * contract only once the register holds nothing. `refundBankAccountId` is
   * required exactly when `netRefund > 0`, and `acknowledgeOutstanding` exactly
   * when it refunds over instruments that are still out.
   */
  finalizeSettlement: (
    pctx: ProdContext,
    leaseId: string,
    body: { settlementDate: string; refundBankAccountId?: string | null; acknowledgeOutstanding?: boolean },
  ) =>
    postJson<{ id: string; status: string; refundAmount: number; balanceDue: number; journalNumber: string | null }>(
      pctx,
      `/v1/leases/${leaseId}/settlement/finalize`,
      body,
    ),
  getLeaseEvents: (pctx: ProdContext, leaseId: string) =>
    getJson<
      Array<{
        id: string;
        previousState: string;
        newState: string;
        notes: string;
      }>
    >(pctx, `/v1/leases/${leaseId}/events`),

  // Maintenance tickets. This models the cross-role journey used by the UI:
  // renter reports/replies/shares OTP/rates; manager progresses and closes.
  createTicket: (
    pctx: ProdContext,
    t: {
      propertyId: string;
      unitId?: string;
      leaseId?: string;
      title: string;
      description: string;
      category: string;
      priority: string;
    },
  ) =>
    postJson<{
      id: string;
      status: string;
      reportedBy: string;
      closureOtp: string | null;
    }>(pctx, '/v1/tickets', t),
  assignTicket: (pctx: ProdContext, ticketId: string, assignTo: string) =>
    putJson<{ id: string; status: string; assignedTo: string }>(
      pctx,
      `/v1/tickets/${ticketId}/assign`,
      { assignTo },
    ),
  estimateTicket: (pctx: ProdContext, ticketId: string, hours: number) =>
    putJson<{ id: string; estimatedResolutionHours: number }>(
      pctx,
      `/v1/tickets/${ticketId}/estimate`,
      { hours },
    ),
  updateTicketStatus: (pctx: ProdContext, ticketId: string, status: string) =>
    putJson<{ id: string; status: string; closureOtp: string | null }>(
      pctx,
      `/v1/tickets/${ticketId}/status`,
      { status },
    ),
  getTicket: (pctx: ProdContext, ticketId: string) =>
    getJson<{
      id: string;
      status: string;
      assignedTo: string | null;
      closureOtp: string | null;
      satisfactionRating: number | null;
    }>(pctx, `/v1/tickets/${ticketId}`),
  addTicketReply: (pctx: ProdContext, ticketId: string, message: string) =>
    postJson<{ id: string; userId: string; message: string }>(
      pctx,
      `/v1/tickets/${ticketId}/replies`,
      { message },
    ),
  getTicketReplies: (pctx: ProdContext, ticketId: string) =>
    getJson<Array<{ id: string; userId: string; message: string }>>(
      pctx,
      `/v1/tickets/${ticketId}/replies`,
    ),
  getTicketHistory: (pctx: ProdContext, ticketId: string) =>
    getJson<Array<{ id: string; action: string; fromStatus: string; toStatus: string }>>(
      pctx,
      `/v1/tickets/${ticketId}/history`,
    ),
  closeTicket: (pctx: ProdContext, ticketId: string, otp: string) =>
    putJson<{ id: string; status: string }>(pctx, `/v1/tickets/${ticketId}/close`, { otp }),
  rateTicket: (pctx: ProdContext, ticketId: string, rating: number, comment: string) =>
    putJson<{ id: string; satisfactionRating: number; satisfactionComment: string }>(
      pctx,
      `/v1/tickets/${ticketId}/rate`,
      { rating, comment },
    ),
  getTicketReport: (pctx: ProdContext, propertyId: string) =>
    getJson<{
      totalTickets: number;
      openCount: number;
      resolvedCount: number;
      closedCount: number;
      avgSatisfaction: number;
    }>(pctx, `/v1/tickets/reports?propertyId=${propertyId}`),

  // Cheque register lifecycle (accounting v2 plan 2). There is no
  // payment-schedule resource and no PENDING→COLLECTED step any more: a
  // cheque exists as REGISTERED the moment it is generated/posted, and moves
  // REGISTERED → DEPOSITED → CLEARED/BOUNCED → REPLACED via
  // `ChequeController` (`web/src/lib/api/leasing.ts` `chequeApi`).
  depositCheque: (pctx: ProdContext, chequeId: string, dto: { date?: string; notes?: string } = {}) =>
    putJson<{ id: string; status: string }>(pctx, `/v1/cheques/${chequeId}/deposit`, dto),
  /** REGISTERED CASH/TRANSFER only — confirms a row straight to CLEARED, no deposit step. */
  receiveCheque: (pctx: ProdContext, chequeId: string, dto: { date?: string; notes?: string } = {}) =>
    putJson<{ id: string; status: string }>(pctx, `/v1/cheques/${chequeId}/receive`, dto),
  clearCheque: (pctx: ProdContext, chequeId: string, dto: { date?: string; notes?: string } = {}) =>
    putJson<{ id: string; status: string }>(pctx, `/v1/cheques/${chequeId}/clear`, dto),
  /** `body.failureReason` is required — the backend 400s a bounce without one. */
  bounceCheque: (
    pctx: ProdContext,
    chequeId: string,
    failureReason: 'BOUNCE' | 'SIGNATURE_MISMATCH' | 'ACCOUNT_CLOSED',
    dto: { date?: string; notes?: string } = {},
  ) =>
    putJson<{ id: string; status: string; failureReason: string }>(pctx, `/v1/cheques/${chequeId}/bounce`, {
      ...dto,
      failureReason,
    }),
  replaceCheque: (
    pctx: ProdContext,
    chequeId: string,
    replacements: Array<{ amount: number; mode?: 'PDC' | 'CASH' | 'TRANSFER'; chequeNumber?: string | null; chequeDate?: string | null; payeeBank?: string | null }>,
    dto: { date?: string; notes?: string } = {},
  ) =>
    postJson<Array<{ id: string; status: string; amount: number }>>(pctx, `/v1/cheques/${chequeId}/replace`, {
      ...dto,
      replacements,
    }),
  chequeSummary: (pctx: ProdContext, propertyId?: string) =>
    getJson<{
      registeredCount: number; registeredAmount: number;
      depositedCount: number; depositedAmount: number;
      clearedThisMonthAmount: number;
      bouncedCount: number; bouncedAmount: number;
      dueCount: number; dueAmount: number;
      overdueCount: number; overdueAmount: number;
    }>(pctx, `/v1/cheques/summary${propertyId ? `?propertyId=${propertyId}` : ''}`),
  chequeAging: (pctx: ProdContext, propertyId?: string) =>
    getJson<{ buckets: Array<{ label: string; count: number; amount: number }>; totalOutstanding: number; totalCount: number }>(
      pctx,
      `/v1/cheques/aging${propertyId ? `?propertyId=${propertyId}` : ''}`,
    ),

  // Penalty worklist (`PenaltyAssessmentController`, `/api/v1/penalties`) —
  // replaces v1's payment-schedule `mark-failed` (auto-charged) and the old
  // `/v1/penalties/{id}/payments`/`/waive` shapes. Proposing raises no
  // journal; only `approve` does, and it also opens a collection row on the
  // register that is paid down like any other cheque (deposit/clear it, or
  // receive it as cash via `cashReceipt` below) rather than through a
  // separate "penalty payment" endpoint.
  proposePenalty: (
    pctx: ProdContext,
    body: { leaseId: string; chequeId?: string | null; reason: 'CHEQUE_RETURN' | 'LATE_PAYMENT' | 'OTHER'; amount: number; description?: string | null },
  ) =>
    postJson<{ id: string; status: string; reason: string; amount: number }>(pctx, '/v1/penalties', body),
  approvePenalty: (pctx: ProdContext, penaltyId: string, date?: string) =>
    postJson<{ id: string; status: string; journalId: string; collectionChequeId: string; collectionStatus: string }>(
      pctx,
      `/v1/penalties/${penaltyId}/approve`,
      { date },
    ),
  waivePenalty: (pctx: ProdContext, penaltyId: string, note: string) =>
    postJson<{ id: string; status: string; resolutionNote: string }>(pctx, `/v1/penalties/${penaltyId}/waive`, { note }),
  reversePenalty: (pctx: ProdContext, penaltyId: string, note: string, date?: string) =>
    postJson<{ id: string; status: string; resolutionNote: string }>(pctx, `/v1/penalties/${penaltyId}/reverse`, { date, note }),
  listPenalties: (pctx: ProdContext, leaseId: string, status?: 'PROPOSED' | 'APPROVED' | 'WAIVED' | 'REVERSED') =>
    getJson<{
      content: Array<{
        id: string;
        leaseId: string;
        chequeId: string | null;
        chequeNumber: string | null;
        reason: string;
        amount: number;
        status: string;
        collectionChequeId: string | null;
        collectionStatus: string | null;
        resolutionNote: string | null;
      }>;
    }>(pctx, `/v1/penalties?leaseId=${leaseId}${status ? `&status=${status}` : ''}&size=200`),
  myPenalties: (pctx: ProdContext) =>
    getJson<Array<{ id: string; leaseId: string; reason: string; amount: number; status: string }>>(pctx, '/v1/penalties/mine'),
  /** `POST /cheques/lease/{leaseId}/cash-receipt` — a new CASH/TRANSFER row, created and received in one call. */
  cashReceipt: (
    pctx: ProdContext,
    leaseId: string,
    body: { postingDate?: string; amount: number; narration?: string | null; debitAccountId?: string | null; mode?: 'CASH' | 'TRANSFER' },
  ) => postJson<{ id: string; status: string; amount: number; mode: string }>(pctx, `/v1/cheques/lease/${leaseId}/cash-receipt`, body),

  // Vendor — payload matches the finance/vendors page (handleSubmit). The
  // entity does NOT have `category`, `contactEmail`, or `contactPhone`
  // fields — those would be silently ignored by the backend. Real fields
  // are nameEn/nameAr, tradeLicenseNumber, trn, email, phone, contactPerson,
  // address, bank details, notes, active.
  createVendor: (pctx: ProdContext, v: { name: string }) =>
    postJson<{ id: string; nameEn: string }>(pctx, '/v1/vendors', {
      nameEn: v.name,
      nameAr: v.name,
      tradeLicenseNumber: '',
      trn: '',
      email: 'vendor-e2e@test.example',
      phone: '+971500000001',
      contactPerson: 'E2E Test Contact',
      address: '',
      bankName: '',
      bankAccountNumber: '',
      iban: '',
      notes: '',
      active: true,
    }),

  // Interactions — payload matches LogInteractionDialog.tsx exactly:
  // {type, direction, occurredAt, summary, outcome (null), followUpDate (null)}.
  logInteraction: (
    pctx: ProdContext,
    leaseId: string,
    i: { type: string; direction: string; summary: string; occurredAt?: string },
  ) =>
    postJson<{ id: string; type: string; summary: string }>(
      pctx,
      `/v1/leases/${leaseId}/interactions`,
      {
        type: i.type,
        direction: i.direction,
        occurredAt: i.occurredAt ?? new Date().toISOString(),
        summary: i.summary,
        outcome: null,
        followUpDate: null,
      },
    ),
  listInteractions: (pctx: ProdContext, leaseId: string) =>
    getJson<{ content: Array<{ id: string; type: string; summary: string }> }>(
      pctx,
      `/v1/leases/${leaseId}/interactions`,
    ),

  // Ops renewal — PR #99's tenant-scoped operation avoids processing unrelated
  // opted-in organizations while the disposable production fixture is tested.
  triggerRenewalScan: (pctx: ProdContext, tenantId: string) =>
    postOk(pctx, `/v1/admin/renewals/run-now/${tenantId}`),
  getMyRenewals: (pctx: ProdContext) =>
    getJson<{
      leases: Array<{
        leaseId: string;
        endDate: string;
        daysRemaining: number;
        opportunityId: string | null;
        stage: string | null;
        intent: string | null;
        reminders: Array<{ slot: number; status: string; sentAt: string | null }>;
      }>;
    }>(pctx, '/v1/me/renewals'),
  setRenewalIntent: (
    pctx: ProdContext,
    opportunityId: string,
    intent: 'RENEW' | 'MOVE_OUT' | 'DISCUSS',
  ) =>
    postJson<{ intent: string; stage: string }>(
      pctx,
      `/v1/me/renewals/${opportunityId}/intent`,
      { intent },
    ),
  markLeaseRenewed: (pctx: ProdContext, leaseId: string, note: string) =>
    postJson<{ id: string; stage: string; outcome: string; closedAt: string }>(
      pctx,
      `/v1/leases/${leaseId}/renewal/mark-renewed`,
      { note },
    ),

  // Sanity / identity (used by smoke).
  listTenants: (pctx: ProdContext) =>
    getJson<Array<{ id: string; name: string }>>(pctx, '/admin/tenants'),
};

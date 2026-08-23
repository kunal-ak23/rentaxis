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

export const api = {
  // Provisioning
  createTenant: (pctx: ProdContext, name: string) =>
    postJson<{ id: string; name: string }>(pctx, '/admin/tenants', { name }),
  createUser: (
    pctx: ProdContext,
    tenantId: string,
    u: { name: string; email: string; password: string; role: string },
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
    if (res.status() !== 204) {
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
    getJson<Array<{ id: string; code: string; nameEn: string; accountType: string }>>(
      pctx,
      '/v1/finance/accounts',
    ),
  saveAccountMapping: (
    pctx: ProdContext,
    m: { transactionNature: string; debitAccountId: string; creditAccountId: string },
  ) =>
    postJson<{
      id: string;
      transactionNature: string;
      debitAccountId: string;
      creditAccountId: string;
    }>(pctx, '/v1/finance/account-mappings', m),
  getAccountMappings: (pctx: ProdContext) =>
    getJson<Array<{ id: string; transactionNature: string }>>(
      pctx,
      '/v1/finance/account-mappings',
    ),
  createFinancialTransaction: (
    pctx: ProdContext,
    t: { accountId: string; propertyId: string; description: string; debit: number; credit: number },
  ) =>
    postJson<{ id: string; description: string; accountCode: string; debit: number; credit: number }>(
      pctx,
      '/v1/finance/transactions',
      {
        date: new Date().toISOString().slice(0, 10),
        description: t.description,
        account: { id: t.accountId },
        property: { id: t.propertyId },
        debit: t.debit,
        credit: t.credit,
        vatApplicable: false,
        vatAmount: 0,
        vatRate: 0,
        grossAmount: Math.max(t.debit, t.credit),
        netAmount: Math.max(t.debit, t.credit),
        notes: 'Production E2E fixture',
      },
    ),

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
  ) => {
    const start = new Date(l.startDate);
    const end = new Date(l.endDate);
    const months = Math.max(
      1,
      (end.getFullYear() - start.getFullYear()) * 12 + (end.getMonth() - start.getMonth()) + 1,
    );
    const paymentTerms = l.paymentTerms ?? 4;
    const totalRent = l.rentAmount * months;
    // PaymentScheduleService caps the largest cheque at the deposit amount.
    // Default the fixture deposit to the average installment so the helper
    // remains valid as dates, monthly rent, or cheque count change.
    const depositAmount = l.depositAmount ?? Math.ceil(totalRent / paymentTerms);
    return postJson<{ id: string; status: string }>(pctx, '/v1/leases', {
      unitId: l.unitId,
      renterId: l.renterId,
      startDate: l.startDate,
      endDate: l.endDate,
      rentAmount: totalRent, // lifetime — matches wizard
      monthlyRent: l.rentAmount,
      depositAmount,
      ejariNumber: null,
      paymentTerms,
      paymentMethod: 'CHEQUE',
      depositPaymentMethod: 'CHEQUE',
      paymentReferenceNumber: null,
      agreementDate: null,
      adminFee: 0,
      parkingRemoteFee: 0,
      rentVatApplicable: false,
      adminFeeVatApplicable: false,
      securityDepositVatApplicable: false,
      parkingRemoteVatApplicable: false,
    });
  },
  // The leases page calls PUT /activate with no body at all (no Content-Type).
  // Our helper sends `{}` which is functionally equivalent.
  activateLease: (pctx: ProdContext, leaseId: string) =>
    putJson<{ id: string; status: string }>(pctx, `/v1/leases/${leaseId}/activate`, {}),
  extendLease: (pctx: ProdContext, leaseId: string, newEndDate: string) =>
    postJson<{ id: string; status: string; endDate: string }>(pctx, `/v1/leases/${leaseId}/extend`, {
      newEndDate,
    }),
  getSettlementPreview: (pctx: ProdContext, leaseId: string) =>
    getJson<{
      depositAmount: number;
      unpaidRentTotal: number;
      penaltyTotal: number;
      suggestedRefund: number;
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
    }>(pctx, `/v1/leases/${leaseId}/settlement`),
  finalizeSettlement: (pctx: ProdContext, leaseId: string) =>
    postJson<{ id: string; status: string }>(pctx, `/v1/leases/${leaseId}/settlement/finalize`, {}),
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

  // Payment schedule + cheque lifecycle. Important: each payment-schedule row
  // IS the cheque (no separate /cheques resource). Lifecycle endpoints are
  // PUT on /v1/payments/{id}/{action} and all take UpdatePaymentStatusDTO.
  getPaymentScheduleForLease: (pctx: ProdContext, leaseId: string) =>
    getJson<Array<{ id: string; dueDate: string; amount: number; status: string }>>(
      pctx,
      `/v1/payments/lease/${leaseId}`,
    ),
  collectPayment: (
    pctx: ProdContext,
    paymentScheduleId: string,
    dto: { chequeNumber?: string; bankName?: string; chequeDate?: string; payerName?: string; notes?: string },
  ) =>
    putJson<{ id: string; status: string }>(pctx, `/v1/payments/${paymentScheduleId}/collect`, dto),
  depositPayment: (pctx: ProdContext, paymentScheduleId: string, dto: { notes?: string } = {}) =>
    putJson<{ id: string; status: string }>(pctx, `/v1/payments/${paymentScheduleId}/deposit`, dto),
  clearPayment: (pctx: ProdContext, paymentScheduleId: string, dto: { notes?: string } = {}) =>
    putJson<{ id: string; status: string }>(pctx, `/v1/payments/${paymentScheduleId}/clear`, dto),
  bouncePayment: (pctx: ProdContext, paymentScheduleId: string, dto: { notes?: string } = {}) =>
    putJson<{ id: string; status: string }>(pctx, `/v1/payments/${paymentScheduleId}/bounce`, dto),

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
    getJson<Array<{ id: string; type: string; summary: string }>>(
      pctx,
      `/v1/leases/${leaseId}/interactions`,
    ),

  // Reports / financial — backend mounts these at /api/v1/finance/*.
  getFinancialTransactions: (pctx: ProdContext, leaseId: string) =>
    getJson<Array<{ id: string; type: string; amount: number; description: string }>>(
      pctx,
      `/v1/finance/transactions?leaseId=${leaseId}`,
    ),
  getTrialBalance: (pctx: ProdContext) =>
    getJson<unknown>(pctx, '/v1/finance/reports/trial-balance'),

  // Ops renewal — triggers the renewal scanner manually for the test tenant.
  triggerRenewalScan: (pctx: ProdContext) =>
    postJson<unknown>(pctx, '/v1/admin/renewals/run-now', {}),

  // Sanity / identity (used by smoke).
  listTenants: (pctx: ProdContext) =>
    getJson<Array<{ id: string; name: string }>>(pctx, '/admin/tenants'),
};

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

export const api = {
  // Provisioning
  createTenant: (pctx: ProdContext, name: string) =>
    postJson<{ id: string; name: string }>(pctx, '/admin/tenants', { name }),
  createUser: (
    pctx: ProdContext,
    tenantId: string,
    u: { name: string; email: string; password: string; role: string },
  ) => postJson<{ id: string; email: string; role: string }>(pctx, '/admin/users', { ...u, tenantId }),
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
    feature: 'EMAIL_NOTIFICATIONS' | 'LISTINGS' | 'MEETINGS',
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
    l: { unitId: string; renterId: string; startDate: string; endDate: string; rentAmount: number },
  ) => {
    const start = new Date(l.startDate);
    const end = new Date(l.endDate);
    const months = Math.max(
      1,
      (end.getFullYear() - start.getFullYear()) * 12 + (end.getMonth() - start.getMonth()),
    );
    return postJson<{ id: string; status: string }>(pctx, '/v1/leases', {
      unitId: l.unitId,
      renterId: l.renterId,
      startDate: l.startDate,
      endDate: l.endDate,
      rentAmount: l.rentAmount * months, // lifetime — matches wizard
      monthlyRent: l.rentAmount,
      depositAmount: 5000,
      ejariNumber: null,
      paymentTerms: 4,
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

/**
 * Direct HTTP client for backend API (bypasses Next.js proxy).
 * Used in global-setup to seed test data.
 */

const BACKEND_URL = process.env.BACKEND_URL || 'http://localhost:8080';

function authHeaders(userId: string, role: string, tenantId: string | null): Record<string, string> {
  const h: Record<string, string> = {
    'X-User-Id': userId,
    'X-User-Role': role,
    'Content-Type': 'application/json',
  };
  if (tenantId) {
    h['X-Tenant-Id'] = tenantId;
    h['X-User-Tenant-Id'] = tenantId;
  }
  return h;
}

async function apiCall<T>(path: string, options: RequestInit = {}): Promise<T> {
  const res = await fetch(`${BACKEND_URL}${path}`, options);
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`API ${options.method || 'GET'} ${path} failed (${res.status}): ${text}`);
  }
  const contentType = res.headers.get('content-type');
  if (contentType?.includes('application/json')) {
    return res.json() as Promise<T>;
  }
  return {} as T;
}

export async function login(email: string, password: string) {
  return apiCall<{
    id: string;
    email: string;
    name: string;
    role: string;
    tenantId: string | null;
    tenantIds: string[];
  }>('/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email, password }),
  });
}

export async function register(fullName: string, companyName: string, email: string, password: string) {
  return apiCall<{
    id: string;
    email: string;
    name: string;
    role: string;
    tenantId: string;
    tenantIds: string[];
  }>('/api/auth/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ fullName, companyName, email, password }),
  });
}

export async function createTenant(
  userId: string,
  role: string,
  tenantId: string | null,
  name: string,
) {
  return apiCall<{ id: string; name: string }>('/api/admin/tenants', {
    method: 'POST',
    headers: authHeaders(userId, role, tenantId),
    body: JSON.stringify({ name }),
  });
}

export async function listTenants(userId: string, role: string) {
  return apiCall<{ id: string; name: string }[]>('/api/admin/tenants', {
    headers: authHeaders(userId, role, null),
  });
}

export async function createUser(
  userId: string,
  role: string,
  tenantId: string,
  user: { name: string; email: string; password: string; role: string },
) {
  return apiCall<{ id: string; email: string; role: string }>('/api/admin/users', {
    method: 'POST',
    headers: authHeaders(userId, role, tenantId),
    body: JSON.stringify({ ...user, tenantId }),
  });
}

export async function createProperty(
  userId: string,
  role: string,
  tenantId: string,
  property: { nameEn: string; nameAr?: string; address?: string; emirate?: string; type?: string },
) {
  return apiCall<{ id: string; nameEn: string }>('/api/v1/properties', {
    method: 'POST',
    headers: authHeaders(userId, role, tenantId),
    body: JSON.stringify({
      nameEn: property.nameEn,
      nameAr: property.nameAr || property.nameEn,
      address: property.address || '123 Test Street',
      emirate: property.emirate || 'DUBAI',
      type: property.type || 'RESIDENTIAL',
    }),
  });
}

export async function createUnit(
  userId: string,
  role: string,
  tenantId: string,
  unit: { propertyId: string; unitNumber: string; type?: string; sizeSqft?: number; expectedRent?: number },
) {
  return apiCall<{ id: string; unitNumber: string }>('/api/v1/units', {
    method: 'POST',
    headers: authHeaders(userId, role, tenantId),
    body: JSON.stringify({
      property: { id: unit.propertyId },
      unitNumber: unit.unitNumber,
      type: unit.type || 'BHK1',
      sizeSqft: unit.sizeSqft || 100,
      expectedRent: unit.expectedRent || 5000,
    }),
  });
}

export async function createRenter(
  userId: string,
  role: string,
  tenantId: string,
  renter: {
    nameEn: string;
    nameAr?: string;
    email: string;
    phone?: string;
    createPortalAccount?: boolean;
  },
) {
  return apiCall<{ id: string; nameEn: string; email: string }>('/api/v1/renters', {
    method: 'POST',
    headers: authHeaders(userId, role, tenantId),
    body: JSON.stringify({
      nameEn: renter.nameEn,
      nameAr: renter.nameAr || renter.nameEn,
      email: renter.email,
      phone: renter.phone || '+971501234567',
      primaryLanguage: 'EN',
      createPortalAccount: renter.createPortalAccount ?? false,
    }),
  });
}

// v1's `createLease` (flat rentAmount/depositAmount body, no `lines`) lived
// here. Its replacement — `lines`-based, matching accounting-v2 plan 2's
// `DraftLeaseInput` — is defined below, next to `generateCheques`/
// `postLease`/`createAndPostLease`, the v2 flow that replaces the deleted
// `PUT /leases/{id}/activate`.

export async function generateContract(
  userId: string,
  role: string,
  tenantId: string,
  leaseId: string,
) {
  return apiCall<any>(`/api/v1/leases/${leaseId}/generate-contract`, {
    method: 'POST',
    headers: authHeaders(userId, role, tenantId),
  });
}

/**
 * Seeds the tenant's chart of accounts (+ the property account template +
 * the charge-type catalogue, chained server-side — see
 * `AccountController#seedDefaultAccounts`). Idempotent; safe to call once per
 * tenant right after it is created and before any property, so every
 * property created afterwards gets its own generated account set and every
 * lease line has a RENT / SECURITY_DEPOSIT charge type to point at.
 *
 * accounting-v2 plan 2 replaced the flat `rentAmount`/`depositAmount`/
 * `paymentTerms` lease body with `lines` cut from this catalogue, and
 * `PUT /leases/{id}/activate` is gone — a lease now becomes ACTIVE only by
 * `POST /leases/{id}/post`, which needs a real chart behind it.
 */
export async function seedChartOfAccounts(
  userId: string,
  role: string,
  tenantId: string,
) {
  return apiCall<any[]>('/api/v1/finance/accounts/seed', {
    method: 'POST',
    headers: authHeaders(userId, role, tenantId),
  });
}

/**
 * v2's replacement for `activateLease`: draft (with `lines`) → generate the
 * cheque grid → post. `PUT /leases/{id}/activate` and the flat
 * rentAmount/depositAmount/paymentTerms body it took no longer exist
 * (LeaseController, `web/src/lib/api/leasing.ts`).
 *
 * `rentAmount` here is the FULL contract value for the term (matches the
 * wizard's own RENT line), not a monthly figure — same convention the old
 * v1 `createLease` used for its `rentAmount` field.
 */
export async function createLease(
  userId: string,
  role: string,
  tenantId: string,
  lease: {
    unitId: string;
    renterId: string;
    startDate: string;
    endDate: string;
    rentAmount: number;
    depositAmount?: number;
    paymentTerms?: number;
    /** Overrides the default RENT + SECURITY_DEPOSIT pair entirely — e.g. a rent-only cheque grid. */
    lines?: Array<{ chargeTypeCode: string; grossAmount: number }>;
  },
) {
  const lines = lease.lines ?? [
    { chargeTypeCode: 'RENT', grossAmount: lease.rentAmount },
    { chargeTypeCode: 'SECURITY_DEPOSIT', grossAmount: lease.depositAmount ?? 5000 },
  ];
  return apiCall<{ id: string; status: string }>('/api/v1/leases', {
    method: 'POST',
    headers: authHeaders(userId, role, tenantId),
    body: JSON.stringify({
      unitId: lease.unitId,
      renterId: lease.renterId,
      startDate: lease.startDate,
      endDate: lease.endDate,
      paymentTerms: lease.paymentTerms || 4,
      paymentMethod: 'CHEQUE',
      depositPaymentMethod: 'CHEQUE',
      lines,
    }),
  });
}

/** `GET /leases/{id}/cheques` — the register rows cut for this lease. */
export async function getLeaseCheques(
  userId: string,
  role: string,
  tenantId: string,
  leaseId: string,
) {
  return apiCall<Array<{ id: string; seqNo: number; amount: number; status: string; mode: string }>>(
    `/api/v1/leases/${leaseId}/cheques`,
    { headers: authHeaders(userId, role, tenantId) },
  );
}

/** `POST /leases/{id}/cheques/generate` — every field optional, the service fills in the lease's own defaults. */
export async function generateCheques(
  userId: string,
  role: string,
  tenantId: string,
  leaseId: string,
  /**
   * `foldDepositsAndFeesIntoFirst` defaults to TRUE server-side
   * (GenerateChequesRequest's own doc), so a caller that wants the deposit on a
   * row of its own — which is what a termination hands back — has to say so.
   */
  req: {
    installments?: number;
    firstDueDate?: string;
    distribution?: string;
    foldDepositsAndFeesIntoFirst?: boolean;
  } = {},
) {
  return apiCall<any[]>(`/api/v1/leases/${leaseId}/cheques/generate`, {
    method: 'POST',
    headers: authHeaders(userId, role, tenantId),
    body: JSON.stringify(req),
  });
}

/** `POST /leases/{id}/post` — writes the TCO (+ PDR per cheque) and moves the lease to ACTIVE. */
export async function postLease(
  userId: string,
  role: string,
  tenantId: string,
  leaseId: string,
) {
  return apiCall<{ lease: { id: string; status: string }; tcoJournalId: string; tcoEntryNumber: string; cheques: any[] }>(
    `/api/v1/leases/${leaseId}/post`,
    {
      method: 'POST',
      headers: authHeaders(userId, role, tenantId),
    },
  );
}

/**
 * The full v2 replacement for the old draft-then-`PUT .../activate` pair:
 * create the draft with `lines`, cut the cheque grid, post. Returns the
 * posted lease (ACTIVE) the way `activateLease` used to.
 */
export async function createAndPostLease(
  userId: string,
  role: string,
  tenantId: string,
  lease: {
    unitId: string;
    renterId: string;
    startDate: string;
    endDate: string;
    rentAmount: number;
    depositAmount?: number;
    paymentTerms?: number;
  },
) {
  const draft = await createLease(userId, role, tenantId, lease);
  await generateCheques(userId, role, tenantId, draft.id, { installments: lease.paymentTerms || 4 });
  const posted = await postLease(userId, role, tenantId, draft.id);
  return posted.lease;
}

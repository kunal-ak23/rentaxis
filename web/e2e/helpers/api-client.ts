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
  },
) {
  return apiCall<{ id: string; status: string }>('/api/v1/leases', {
    method: 'POST',
    headers: authHeaders(userId, role, tenantId),
    body: JSON.stringify({
      unitId: lease.unitId,
      renterId: lease.renterId,
      startDate: lease.startDate,
      endDate: lease.endDate,
      rentAmount: lease.rentAmount,
      depositAmount: lease.depositAmount || 5000,
      paymentTerms: lease.paymentTerms || 4,
    }),
  });
}

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

export async function activateLease(
  userId: string,
  role: string,
  tenantId: string,
  leaseId: string,
) {
  return apiCall<{ id: string; status: string }>(`/api/v1/leases/${leaseId}/activate`, {
    method: 'PUT',
    headers: authHeaders(userId, role, tenantId),
  });
}

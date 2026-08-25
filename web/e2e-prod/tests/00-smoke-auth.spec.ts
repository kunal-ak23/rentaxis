/**
 * Smoke 00 — proves the auth + proxy chain works end-to-end before any
 * downstream test wastes time. If this fails, everything else will too.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('session cookie carries through to backend via /api/proxy', async ({ request }) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));

  // /api/auth/session should reflect the SUPER_ADMIN we logged in as.
  const sessionRes = await request.get('/api/auth/session');
  expect(sessionRes.ok()).toBeTruthy();
  const session = await sessionRes.json();
  expect(session.user.email).toBe(ctx.user.email);
  expect(session.user.role).toBe('SUPER_ADMIN');

  // Reach through the proxy to a backend endpoint. /api/proxy/admin/tenants
  // must round-trip through middleware → header injection → ApiSecurityFilter
  // → controller. Only SUPER_ADMIN can list tenants, so a 200 here proves both
  // the proxy header injection AND the role check on the backend.
  const tenantsRes = await request.get('/api/proxy/admin/tenants');
  expect(
    tenantsRes.ok(),
    `Backend /admin/tenants through proxy returned ${tenantsRes.status()} — ` +
      `expected 200 (proxy headers + SUPER_ADMIN role check both working)`,
  ).toBeTruthy();
  const tenants = await tenantsRes.json();
  expect(Array.isArray(tenants)).toBeTruthy();
});

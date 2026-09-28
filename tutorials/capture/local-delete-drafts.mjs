#!/usr/bin/env node
// Delete DRAFT tenancy contracts a local take created, so repeated takes do not
// pile drafts into the contract list the next recording shows.
//
//   node tutorials/capture/local-delete-drafts.mjs <seed-manifest.json> <backend-url> <lease-id>...
//
// Local only (localhost backend), tenant admin from the seed manifest, and only
// contracts the backend still reports as DRAFT — the endpoint refuses others.

import fs from 'node:fs';

const [, , manifestPath, backendArg, ...ids] = process.argv;
if (!manifestPath || !backendArg) {
  console.error('Usage: node tutorials/capture/local-delete-drafts.mjs <seed-manifest.json> <backend-url> <lease-id>...');
  process.exit(2);
}
const backend = new URL(backendArg);
if (!['localhost', '127.0.0.1'].includes(backend.hostname)) throw new Error(`Refusing ${backend.origin}: local stacks only.`);
if (ids.length === 0) process.exit(0);

const seed = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const login = await fetch(`${backend.origin}/api/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: seed.adminLogin.email, password: seed.adminLogin.password }),
});
if (!login.ok) throw new Error(`Tenant admin login failed: HTTP ${login.status}`);
const user = await login.json();
const headers = { 'X-User-Id': String(user.id), 'X-User-Role': user.role };
if (user.tenantId) Object.assign(headers, { 'X-Tenant-Id': String(user.tenantId), 'X-User-Tenant-Id': String(user.tenantId) });
if (user.token) headers.Authorization = `Bearer ${user.token}`;

let failed = 0;
for (const id of ids) {
  if (!/^[0-9a-f-]{36}$/.test(id)) { console.error(`skip ${id}: not a UUID`); failed++; continue; }
  const lease = await fetch(`${backend.origin}/api/v1/leases/${id}`, { headers });
  if (lease.status === 404) { console.log(`draft ${id}: already gone`); continue; }
  const status = lease.ok ? (await lease.json()).status : `HTTP ${lease.status}`;
  if (status !== 'DRAFT') { console.error(`draft ${id}: status ${status}, not deleting`); failed++; continue; }
  const res = await fetch(`${backend.origin}/api/v1/leases/${id}`, { method: 'DELETE', headers });
  if (res.ok) console.log(`draft ${id}: deleted`);
  else { console.error(`draft ${id}: delete failed HTTP ${res.status}`); failed++; }
}
process.exit(failed ? 1 : 0);

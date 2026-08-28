/** Demonstrates guard walk-in → resident approval → guard notification. */
import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { loginAsNextAuth, setActiveTenant, ProdContext } from '../helpers/prod-client';

const BASE_URL = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';
const STATE_PATH = path.resolve(__dirname, '../../../tutorials/state/miftah-demo-tutorial-mtbzmkbe.json');
const SECRET_PATH = path.resolve(__dirname, '../../../tutorials/state/miftah-demo-tutorial-mtbzmkbe-secrets.local.json');
const state = JSON.parse(fs.readFileSync(STATE_PATH, 'utf8'));
const secrets = JSON.parse(fs.readFileSync(SECRET_PATH, 'utf8'));

async function proxyJson<T>(ctx: ProdContext, endpoint: string, body?: unknown): Promise<T> {
  const response = body === undefined
    ? await ctx.request.get(`/api/proxy${endpoint}`)
    : await ctx.request.post(`/api/proxy${endpoint}`, { data: body, headers: { 'Content-Type': 'application/json' } });
  expect(response.ok(), `${endpoint} should succeed`).toBeTruthy();
  return response.json() as Promise<T>;
}

async function directJson<T>(ctx: ReturnType<typeof playwrightRequest.newContext> extends Promise<infer R> ? R : never, method: 'get' | 'post', endpoint: string, body?: unknown): Promise<T> {
  const response = method === 'get'
    ? await ctx.get(endpoint, { failOnStatusCode: false })
    : await ctx.post(endpoint, { data: body, headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false });
  if (!response.ok()) throw new Error(`${method.toUpperCase()} ${endpoint} → ${response.status()}: ${(await response.text()).slice(0, 400)}`);
  return response.json() as Promise<T>;
}

test('guard walk-in is approved by resident and guard is notified', async () => {
  const manager = await loginAsNextAuth(BASE_URL, secrets.users.managerEmail, secrets.password);
  await setActiveTenant(manager, state.tenant.id);
  const policyResponse = await manager.request.put(`/api/proxy/v1/gatepass/policies?propertyId=${state.property.id}`, {
    data: { requireUnregisteredApproval: true, requireRegisteredApproval: true, notifyRegisteredEntry: true, requireFreshPhoto: false, approvalTimeoutMinutes: 30 },
    headers: { 'Content-Type': 'application/json' }, failOnStatusCode: false,
  });
  expect(policyResponse.ok(), `gate policy update failed: ${await policyResponse.text()}`).toBeTruthy();

  const guard = await playwrightRequest.newContext({
    baseURL: BASE_URL,
    extraHTTPHeaders: {
      'X-User-Id': state.users.guard.id,
      'X-User-Role': 'SECURITY_GUARD',
      'X-Tenant-Id': state.tenant.id,
      'X-User-Tenant-Id': state.tenant.id,
    },
  });
  const walkInResponse = await guard.post('/api/v1/gatepass/walk-in', {
    multipart: {
      propertyId: state.property.id,
      unitId: state.units.leased.id,
      name: 'Sara Khan / سارة خان',
      phone: '+971509112233',
      visitorType: 'GUEST',
      purpose: 'Family visit / زيارة عائلية',
      vehicleNumber: 'DXB B 7788',
    },
    failOnStatusCode: false,
  });
  if (!walkInResponse.ok()) throw new Error(`walk-in create → ${walkInResponse.status()}: ${(await walkInResponse.text()).slice(0, 500)}`);
  const walkIn = await walkInResponse.json() as { id: string; status: string; guestName: string };
  expect(walkIn.status).toBe('PENDING_APPROVAL');

  const resident = await loginAsNextAuth(BASE_URL, secrets.users.renterEmail, secrets.password);
  await setActiveTenant(resident, state.tenant.id);
  const residentNotifications = await proxyJson<Array<{ type: string; referenceId: string }>>(resident, '/v1/notifications?unreadOnly=true&size=100');
  expect(residentNotifications.some((n) => n.type === 'GATE_VISITOR_APPROVAL_REQUIRED' && n.referenceId === walkIn.id)).toBeTruthy();
  const approvals = await proxyJson<Array<{ id: string; guestName: string }>>(resident, '/v1/gatepass/resident-approvals');
  expect(approvals.some((p) => p.id === walkIn.id && p.guestName.includes('Sara'))).toBeTruthy();

  const approved = await proxyJson<{ id: string; status: string }>(resident, `/v1/gatepass/resident-approvals/${walkIn.id}`, { approved: true });
  expect(approved.status).toBe('ACTIVE');

  const guardStatus = await directJson<{ id: string; status: string; guestName: string }>(guard, 'get', `/api/v1/gatepass/walk-in/${walkIn.id}/status`);
  expect(guardStatus.status).toBe('ACTIVE');
  const guardNotifications = await directJson<Array<{ type: string; referenceId: string }>>(guard, 'get', '/api/v1/notifications?unreadOnly=true&size=100');
  expect(guardNotifications.some((n) => n.type === 'GATE_PASS_APPROVED' && n.referenceId === walkIn.id)).toBeTruthy();

  state.guestVisit = {
    passId: walkIn.id,
    guestName: walkIn.guestName,
    initialStatus: 'PENDING_APPROVAL',
    residentNotification: 'GATE_VISITOR_APPROVAL_REQUIRED',
    residentDecision: 'APPROVED',
    finalStatus: 'ACTIVE',
    guardNotification: 'GATE_PASS_APPROVED',
  };
  fs.writeFileSync(STATE_PATH, JSON.stringify(state, null, 2));
  console.log(`Guest visit approval flow passed: ${walkIn.id}`);
  await guard.dispose(); await resident.request.dispose(); await manager.request.dispose();
});

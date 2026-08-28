import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { loginAsNextAuth, setActiveTenant, ProdContext } from '../helpers/prod-client';

const BASE_URL = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';
const STATE_PATH = path.resolve(__dirname, '../../../tutorials/state/miftah-demo-tutorial-mtbzmkbe.json');
const SECRET_PATH = path.resolve(__dirname, '../../../tutorials/state/miftah-demo-tutorial-mtbzmkbe-secrets.local.json');
const state = JSON.parse(fs.readFileSync(STATE_PATH, 'utf8'));
const secrets = JSON.parse(fs.readFileSync(SECRET_PATH, 'utf8'));

test('leave a pending visitor request ready for the tutorial showcase', async () => {
  const resident = await loginAsNextAuth(BASE_URL, secrets.users.renterEmail, secrets.password);
  await setActiveTenant(resident, state.tenant.id);
  const guard = await playwrightRequest.newContext({
    baseURL: BASE_URL,
    extraHTTPHeaders: {
      'X-User-Id': state.users.guard.id, 'X-User-Role': 'SECURITY_GUARD',
      'X-Tenant-Id': state.tenant.id, 'X-User-Tenant-Id': state.tenant.id,
    },
  });
  const response = await guard.post('/api/v1/gatepass/walk-in', {
    multipart: {
      propertyId: state.property.id, unitId: state.units.leased.id,
      name: 'Mariam Ali / مريم علي', phone: '+971509112244', visitorType: 'GUEST',
      purpose: 'Family visit / زيارة عائلية', vehicleNumber: 'DXB C 2299',
    }, failOnStatusCode: false,
  });
  expect(response.ok(), `walk-in create failed: ${await response.text()}`).toBeTruthy();
  const pass = await response.json() as { id: string; status: string; guestName: string };
  expect(pass.status).toBe('PENDING_APPROVAL');
  const approvalsResponse = await resident.request.get('/api/proxy/v1/gatepass/resident-approvals');
  expect(approvalsResponse.ok()).toBeTruthy();
  const approvals = await approvalsResponse.json() as Array<{ id: string; guestName: string }>;
  expect(approvals.some((p) => p.id === pass.id)).toBeTruthy();
  state.pendingGuestVisit = { passId: pass.id, guestName: pass.guestName, status: pass.status, nextAction: 'Resident approves from the Resident app; guard then receives GATE_PASS_APPROVED.' };
  fs.writeFileSync(STATE_PATH, JSON.stringify(state, null, 2));
  console.log(`Pending tutorial visitor ready: ${pass.id}`);
  await guard.dispose(); await resident.request.dispose();
});

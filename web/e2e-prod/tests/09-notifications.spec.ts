/**
 * 09 — User notifications.
 *
 * Two channels exist in the product:
 *   - In-app notifications (notifications table, GET /v1/notifications).
 *     Created by NotificationService.notify() — e.g., TENANT_PROVISIONED
 *     is fired to all SUPER_ADMINs when a new tenant is created
 *     (LandlordOrgService.provisionTenant). This spec asserts the
 *     SUPER_ADMIN's feed contains our test-tenant provisioning.
 *
 *   - Email events (EmailEventType: USER_INVITED, USER_WELCOMED,
 *     TENANT_ADMIN_ADDED, etc.). Dispatched via Spring events and routed
 *     through email_outbox to SMTP. We can't directly assert delivery
 *     from prod without inbox access — BUT the renter we provisioned in
 *     01 has email `kunalsharma.ks13+e2e-renter-<suffix>@gmail.com`,
 *     which gmail's +alias routes to kunalsharma.ks13@gmail.com. The
 *     operator can manually verify the USER_INVITED email lands there
 *     after this run.
 *
 * Spec scope: API-level assertion on the in-app feed (deterministic).
 * Email delivery is documented but not auto-asserted.
 */
import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('SUPER_ADMIN receives TENANT_PROVISIONED in-app notification for the test tenant', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.tenant?.id).toBeTruthy();

  // Reuse the SUPER_ADMIN session saved by global-setup — the
  // TENANT_PROVISIONED event fires to the SA who created the tenant in 01.
  const request = await playwrightRequest.newContext({
    baseURL: ctx.baseURL,
    storageState: path.join(__dirname, '..', '.auth', 'superadmin.json'),
  });

  const res = await request.get('/api/proxy/v1/notifications?page=0&size=50');
  expect(res.ok(), `notifications GET returned ${res.status()}`).toBeTruthy();
  const notifications: Array<{
    id: string;
    type: string;
    title: string;
    message: string;
    referenceType?: string;
    referenceId?: string;
  }> = await res.json();

  // Find the TENANT_PROVISIONED notification for our specific test tenant.
  // The service writes referenceType=TENANT, referenceId=tenant.id.
  const provisioned = notifications.find(
    (n) =>
      n.type === 'TENANT_PROVISIONED' &&
      n.referenceId === ctx.tenant.id,
  );
  expect(
    provisioned,
    `expected TENANT_PROVISIONED notification for tenant ${ctx.tenant.id}; ` +
      `found ${notifications.length} notifications, types: ${[...new Set(notifications.map((n) => n.type))].join(', ')}`,
  ).toBeTruthy();

  // Message should mention the tenant name (humans read these).
  expect(provisioned!.message).toContain(ctx.tenant.name);

  // Unread-count endpoint should also reflect at least one unread.
  const unreadRes = await request.get('/api/proxy/v1/notifications/unread-count');
  expect(unreadRes.ok()).toBeTruthy();
  const { count } = await unreadRes.json();
  expect(typeof count).toBe('number');
  expect(count).toBeGreaterThanOrEqual(1);

  // Document the email-side expectation for the operator running this suite.
  test.info().annotations.push({
    type: 'manual-verification',
    description:
      `Email events fired during this run that should arrive at ` +
      `kunalsharma.ks13@gmail.com (via +alias):\n` +
      `  - USER_INVITED (renter): test renter created in 01-provision\n` +
      `  - TENANT_ADMIN_ADDED: TA created in 01-provision (no +alias — internal email)\n` +
      `Verify manually in the inbox; subjects include "RentAxis" and the test tenant suffix ` +
      ctx.runSuffix,
  });

  await request.dispose();
});

/**
 * 13e — Notification ownership boundary introduced by PR #98.
 * Uses a renter notification generated inside the disposable test tenant.
 */
import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { loginAsNextAuth } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('one authenticated user cannot mark another user notification as read', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  const renterCtx = await loginAsNextAuth(
    ctx.baseURL,
    ctx.renter.email,
    ctx.renter.portalPassword,
  );
  const renterNotificationsRes = await renterCtx.request.get(
    '/api/proxy/v1/notifications?page=0&size=100',
  );
  expect(renterNotificationsRes.ok()).toBeTruthy();
  const renterNotifications: Array<{
    id: string;
    referenceId?: string;
    type: string;
    isRead: boolean;
  }> = await renterNotificationsRes.json();

  // Ticket, meeting, booking, gate-pass, and listing specs all create renter
  // notifications. Select one tied to this disposable tenant's own entities.
  // Compare only against ids that actually exist: a notification with no
  // referenceId would otherwise match an absent ctx value via undefined ===
  // undefined and silently pick a row from another tenant.
  const ownIds = [ctx.ticketId, ctx.lease.id, ctx.property.id].filter(Boolean);
  const target = renterNotifications.find(
    (item) => item.referenceId != null && ownIds.includes(item.referenceId),
  ) ?? renterNotifications.find((item) => item.type.startsWith('GATE_PASS_'));
  expect(target, 'earlier E2E workflows must create a renter notification').toBeTruthy();

  const superAdmin = await playwrightRequest.newContext({
    baseURL: ctx.baseURL,
    storageState: path.join(__dirname, '..', '.auth', 'superadmin.json'),
  });
  const foreignRead = await superAdmin.put(`/api/proxy/v1/notifications/${target!.id}/read`, {
    failOnStatusCode: false,
  });
  expect(foreignRead.status()).toBe(404);

  const afterRes = await renterCtx.request.get('/api/proxy/v1/notifications?page=0&size=100');
  expect(afterRes.ok()).toBeTruthy();
  const after: Array<{ id: string; isRead: boolean }> = await afterRes.json();
  expect(after.find((item) => item.id === target!.id)?.isRead).toBe(target!.isRead);

  await renterCtx.request.dispose();
  await superAdmin.dispose();
});

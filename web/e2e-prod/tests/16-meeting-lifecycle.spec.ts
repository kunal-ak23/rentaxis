/** 16 — Renter request and property-manager meeting lifecycle. */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('renter and manager complete the meeting lifecycle through the portal', async ({ browser }) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.pmUserId, '01-provision must persist the property manager').toBeTruthy();

  const renterCtx = await loginAsNextAuth(
    ctx.baseURL,
    ctx.renter.email,
    ctx.renter.portalPassword,
  );
  const pmCtx = await loginAsNextAuth(ctx.baseURL, ctx.pmEmail, ctx.pmPassword);
  await setActiveTenant(pmCtx, ctx.tenant.id);

  const tomorrow = new Date();
  tomorrow.setUTCDate(tomorrow.getUTCDate() + 1);
  const date = tomorrow.toISOString().slice(0, 10);
  const slots = await api.getMeetingSlots(renterCtx, ctx.pmUserId, date);
  const slot = slots.find((candidate) => candidate.available && new Date(candidate.start) > new Date());
  expect(slot, 'tomorrow should have at least one available meeting slot').toBeTruthy();

  const meetingTitle = `TEST-Property handover ${ctx.runSuffix}`;
  const meeting = await api.createMeeting(renterCtx, {
    hostUserId: ctx.pmUserId,
    slotStart: slot!.start,
    propertyId: ctx.property.id,
    unitId: ctx.unit.id,
    title: meetingTitle,
  });
  expect(meeting.status).toBe('REQUESTED');
  expect(meeting.requesterUserId).toBe(ctx.renter.userId);
  expect(meeting.hostUserId).toBe(ctx.pmUserId);

  expect((await api.getMyMeetings(renterCtx)).content.some((item) => item.id === meeting.id)).toBeTruthy();
  expect((await api.getMeetings(pmCtx)).content.some((item) => item.id === meeting.id)).toBeTruthy();

  const renterBrowser = await browser.newContext({ baseURL: ctx.baseURL });
  const renterPage = await renterBrowser.newPage();
  await renterPage.goto('/en/auth/login');
  await renterPage.locator('#login-email').fill(ctx.renter.email);
  await renterPage.locator('#login-password').fill(ctx.renter.portalPassword);
  await renterPage.getByRole('button', { name: /sign in|log in/i }).click();
  await renterPage.waitForURL(/\/dashboard\/renter-portal/, { timeout: 15_000 });
  await renterPage.goto('/en/dashboard/meetings');
  await expect(renterPage.getByRole('heading', { level: 1, name: 'Meetings' })).toBeVisible();
  await renterPage.getByRole('button', { name: 'List' }).click();
  await expect(renterPage.getByText(meetingTitle, { exact: true })).toBeVisible();
  await expect(renterPage.getByText('REQUESTED', { exact: true })).toBeVisible();
  await renterPage.goto(`/en/dashboard/meetings/${meeting.id}`);
  await expect(renterPage.getByRole('heading', { level: 1, name: meetingTitle })).toBeVisible();
  await expect(renterPage.getByText('REQUESTED', { exact: true })).toBeVisible();

  const managerBrowser = await browser.newContext({ baseURL: ctx.baseURL });
  const managerPage = await managerBrowser.newPage();
  await managerPage.goto('/en/auth/login');
  await managerPage.locator('#login-email').fill(ctx.pmEmail);
  await managerPage.locator('#login-password').fill(ctx.pmPassword);
  await managerPage.getByRole('button', { name: /sign in|log in/i }).click();
  await managerPage.waitForURL(/\/dashboard(?!\/renter-portal)/, { timeout: 15_000 });
  await managerPage.goto(`/en/dashboard/meetings/${meeting.id}`);
  await expect(managerPage.getByRole('heading', { level: 1, name: meetingTitle })).toBeVisible();

  const [approveResponse] = await Promise.all([
    managerPage.waitForResponse((response) => response.url().endsWith(`/meetings/${meeting.id}/approve`)),
    managerPage.getByRole('button', { name: /^approve$/i }).click(),
  ]);
  expect(approveResponse.ok()).toBeTruthy();
  await expect(managerPage.getByText('APPROVED', { exact: true })).toBeVisible();
  expect((await api.getMeeting(renterCtx, meeting.id)).status).toBe('APPROVED');

  const rangeStart = new Date(slot!.start);
  rangeStart.setUTCHours(0, 0, 0, 0);
  const rangeEnd = new Date(rangeStart);
  rangeEnd.setUTCDate(rangeEnd.getUTCDate() + 2);
  const calendar = await api.getMeetingCalendar(
    pmCtx,
    rangeStart.toISOString(),
    rangeEnd.toISOString(),
  );
  expect(calendar.content.some((item) => item.id === meeting.id)).toBeTruthy();

  const [completeResponse] = await Promise.all([
    managerPage.waitForResponse((response) => response.url().endsWith(`/meetings/${meeting.id}/complete`)),
    managerPage.getByRole('button', { name: /^complete$/i }).click(),
  ]);
  expect(completeResponse.ok()).toBeTruthy();
  await expect(managerPage.getByText('COMPLETED', { exact: true })).toBeVisible();
  expect((await api.getMeeting(pmCtx, meeting.id)).status).toBe('COMPLETED');

  await renterBrowser.close();
  await managerBrowser.close();
  await renterCtx.request.dispose();
  await pmCtx.request.dispose();
});

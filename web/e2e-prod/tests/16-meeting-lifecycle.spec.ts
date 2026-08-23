/** 16 — Renter request and property-manager meeting lifecycle. */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('renter requests an available slot and manager approves and completes it', async () => {
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

  const meeting = await api.createMeeting(renterCtx, {
    hostUserId: ctx.pmUserId,
    slotStart: slot!.start,
    propertyId: ctx.property.id,
    unitId: ctx.unit.id,
    title: `TEST-Property handover ${ctx.runSuffix}`,
  });
  expect(meeting.status).toBe('REQUESTED');
  expect(meeting.requesterUserId).toBe(ctx.renter.userId);
  expect(meeting.hostUserId).toBe(ctx.pmUserId);

  expect((await api.getMyMeetings(renterCtx)).content.some((item) => item.id === meeting.id)).toBeTruthy();
  expect((await api.getMeetings(pmCtx)).content.some((item) => item.id === meeting.id)).toBeTruthy();

  const approved = await api.approveMeeting(pmCtx, meeting.id);
  expect(approved.status).toBe('APPROVED');
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

  const completed = await api.completeMeeting(pmCtx, meeting.id);
  expect(completed.status).toBe('COMPLETED');

  await renterCtx.request.dispose();
  await pmCtx.request.dispose();
});

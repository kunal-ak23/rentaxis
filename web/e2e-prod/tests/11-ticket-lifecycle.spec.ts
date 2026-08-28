/**
 * 11 — Complete maintenance ticket lifecycle across the real product roles.
 *
 * The renter reports and replies, the assigned property manager estimates and
 * progresses the work. The renter reads and shares the closure OTP, the
 * manager enters it to close the ticket, and the renter rates the result.
 * All records live inside the disposable TEST-E2E tenant removed by 99-cleanup.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('renter and property manager complete a ticket end to end', async () => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.renter?.userId, '01-provision must persist the renter portal user').toBeTruthy();
  expect(ctx.pmUserId, '01-provision must persist the property manager').toBeTruthy();

  const renterCtx = await loginAsNextAuth(
    ctx.baseURL,
    ctx.renter.email,
    ctx.renter.portalPassword,
  );
  const pmCtx = await loginAsNextAuth(ctx.baseURL, ctx.pmEmail, ctx.pmPassword);
  await setActiveTenant(pmCtx, ctx.tenant.id);

  const ticket = await api.createTicket(renterCtx, {
    propertyId: ctx.property.id,
    unitId: ctx.unit.id,
    leaseId: ctx.lease.id,
    title: `TEST-Leaking tap ${ctx.runSuffix}`,
    description: 'Kitchen tap is leaking continuously; production E2E fixture.',
    category: 'PLUMBING',
    priority: 'HIGH',
  });
  expect(ticket.status).toBe('OPEN');
  expect(ticket.reportedBy).toBe(ctx.renter.userId);

  const assigned = await api.assignTicket(pmCtx, ticket.id, ctx.pmUserId);
  expect(assigned.status).toBe('ASSIGNED');
  expect(assigned.assignedTo).toBe(ctx.pmUserId);

  const estimated = await api.estimateTicket(pmCtx, ticket.id, 4);
  expect(estimated.estimatedResolutionHours).toBe(4);

  const renterReply = await api.addTicketReply(
    renterCtx,
    ticket.id,
    'Access is available after 10:00 AM.',
  );
  expect(renterReply.userId).toBe(ctx.renter.userId);

  await expect(api.updateTicketStatus(pmCtx, ticket.id, 'IN_PROGRESS')).resolves.toMatchObject({
    status: 'IN_PROGRESS',
  });
  await expect(api.updateTicketStatus(pmCtx, ticket.id, 'RESOLVED')).resolves.toMatchObject({
    status: 'RESOLVED',
    closureOtp: null,
  });

  // The OTP is intentionally redacted from manager responses and exposed only
  // when the reporter fetches the ticket.
  const renterView = await api.getTicket(renterCtx, ticket.id);
  expect(renterView.closureOtp).toMatch(/^\d{6}$/);

  const closed = await api.closeTicket(pmCtx, ticket.id, renterView.closureOtp!);
  expect(closed.status).toBe('CLOSED');

  const rated = await api.rateTicket(renterCtx, ticket.id, 5, 'Resolved quickly');
  expect(rated.satisfactionRating).toBe(5);

  const replies = await api.getTicketReplies(renterCtx, ticket.id);
  expect(replies.some((reply) => reply.id === renterReply.id)).toBeTruthy();

  const history = await api.getTicketHistory(pmCtx, ticket.id);
  expect(history.some((event) => event.action === 'ASSIGNED')).toBeTruthy();
  expect(history.some((event) => event.toStatus === 'CLOSED')).toBeTruthy();

  const report = await api.getTicketReport(pmCtx, ctx.property.id);
  expect(report.totalTickets).toBeGreaterThanOrEqual(1);
  expect(report.closedCount).toBeGreaterThanOrEqual(1);
  expect(report.avgSatisfaction).toBeGreaterThanOrEqual(5);

  await renterCtx.request.dispose();
  await pmCtx.request.dispose();
});

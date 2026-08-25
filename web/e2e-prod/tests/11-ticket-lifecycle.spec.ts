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

test('renter and property manager complete a ticket end to end', async ({ browser }) => {
  test.setTimeout(120_000);
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

  const ticketTitle = `TEST-Leaking tap ${ctx.runSuffix}`;
  const ticket = await api.createTicket(renterCtx, {
    propertyId: ctx.property.id,
    unitId: ctx.unit.id,
    leaseId: ctx.lease.id,
    title: ticketTitle,
    description: 'Kitchen tap is leaking continuously; production E2E fixture.',
    category: 'PLUMBING',
    priority: 'HIGH',
  });
  expect(ticket.status).toBe('OPEN');
  expect(ticket.reportedBy).toBe(ctx.renter.userId);

  // 13e-notification-ownership selects a notification belonging to this
  // disposable tenant by referenceId, and the ticket is the notification it
  // most reliably gets. Without this write-back that selector compared against
  // undefined.
  fs.writeFileSync(
    CONTEXT_FILE,
    JSON.stringify({ ...ctx, ticketId: ticket.id }, null, 2),
  );

  const renterBrowser = await browser.newContext({ baseURL: ctx.baseURL });
  const renterPage = await renterBrowser.newPage();
  await renterPage.goto('/en/auth/login');
  await renterPage.locator('#login-email').fill(ctx.renter.email);
  await renterPage.locator('#login-password').fill(ctx.renter.portalPassword);
  await renterPage.getByRole('button', { name: /sign in|log in/i }).click();
  await renterPage.waitForURL(/\/dashboard\/renter-portal/, { timeout: 15_000 });
  await renterPage.goto('/en/dashboard/tickets');
  await expect(renterPage.getByRole('heading', { level: 1, name: 'Maintenance Tickets' })).toBeVisible();
  await expect(renterPage.getByText(ticketTitle, { exact: true })).toBeVisible();
  await renterPage.goto(`/en/dashboard/tickets/${ticket.id}`);
  await expect(renterPage.getByRole('heading', { level: 1, name: ticketTitle })).toBeVisible();
  await renterPage.getByPlaceholder('Type your reply...').fill('Access is available after 10:00 AM.');
  const [replyResponse] = await Promise.all([
    renterPage.waitForResponse((response) =>
      response.url().endsWith(`/tickets/${ticket.id}/replies`) && response.request().method() === 'POST',
    ),
    renterPage.getByRole('button', { name: 'Send' }).click(),
  ]);
  expect(replyResponse.ok()).toBeTruthy();
  await expect(renterPage.getByText('Access is available after 10:00 AM.', { exact: true })).toBeVisible();
  const renterReply = (await api.getTicketReplies(renterCtx, ticket.id)).find(
    (reply) => reply.message === 'Access is available after 10:00 AM.',
  );
  expect(renterReply, 'the UI reply must be persisted and readable through the API').toBeTruthy();
  expect(renterReply!.userId).toBe(ctx.renter.userId);

  const managerBrowser = await browser.newContext({ baseURL: ctx.baseURL });
  // This spec validates tickets, not first-login onboarding. Prevent the
  // delayed admin tour from appearing over the OTP close button mid-flow.
  await managerBrowser.addInitScript(() => {
    localStorage.setItem('rentaxis_tours_completed', JSON.stringify(['admin-onboarding']));
  });
  const managerPage = await managerBrowser.newPage();
  await managerPage.goto('/en/auth/login');
  await managerPage.locator('#login-email').fill(ctx.pmEmail);
  await managerPage.locator('#login-password').fill(ctx.pmPassword);
  await managerPage.getByRole('button', { name: /sign in|log in/i }).click();
  await managerPage.waitForURL(/\/dashboard(?!\/renter-portal)/, { timeout: 15_000 });
  await managerPage.goto(`/en/dashboard/tickets/${ticket.id}`);
  await expect(managerPage.getByRole('heading', { level: 1, name: ticketTitle })).toBeVisible();

  const [assignResponse] = await Promise.all([
    managerPage.waitForResponse((response) => response.url().endsWith(`/tickets/${ticket.id}/assign`)),
    managerPage.getByRole('button', { name: 'Assign to Me' }).click(),
  ]);
  expect(assignResponse.ok()).toBeTruthy();
  await expect(managerPage.getByText('ASSIGNED', { exact: true }).first()).toBeVisible();
  expect((await api.getTicket(pmCtx, ticket.id)).assignedTo).toBe(ctx.pmUserId);

  await managerPage.getByPlaceholder('Hours').fill('4');
  const [estimateResponse] = await Promise.all([
    managerPage.waitForResponse((response) => response.url().endsWith(`/tickets/${ticket.id}/estimate`)),
    managerPage.getByRole('button', { name: 'Set ETA' }).click(),
  ]);
  expect(estimateResponse.ok()).toBeTruthy();
  await expect(managerPage.getByText('4 hours', { exact: true })).toBeVisible();

  const [startResponse] = await Promise.all([
    managerPage.waitForResponse((response) => response.url().endsWith(`/tickets/${ticket.id}/status`)),
    managerPage.getByRole('button', { name: 'Start Work' }).click(),
  ]);
  expect(startResponse.ok()).toBeTruthy();
  await expect(managerPage.getByText('IN PROGRESS', { exact: true }).first()).toBeVisible();

  const [resolveResponse] = await Promise.all([
    managerPage.waitForResponse((response) => response.url().endsWith(`/tickets/${ticket.id}/status`)),
    managerPage.getByRole('button', { name: 'Mark Resolved' }).click(),
  ]);
  expect(resolveResponse.ok()).toBeTruthy();
  await expect(managerPage.getByText('RESOLVED', { exact: true }).first()).toBeVisible();
  expect((await api.getTicket(pmCtx, ticket.id)).closureOtp).toBeNull();

  // The OTP is intentionally redacted from manager responses and exposed only
  // when the reporter fetches the ticket.
  const renterView = await api.getTicket(renterCtx, ticket.id);
  expect(renterView.closureOtp).toMatch(/^\d{6}$/);

  await renterPage.reload();
  await expect(renterPage.getByText(renterView.closureOtp!, { exact: true })).toBeVisible();
  await managerPage.getByPlaceholder('6-digit OTP').fill(renterView.closureOtp!);
  const [closeResponse] = await Promise.all([
    managerPage.waitForResponse((response) => response.url().endsWith(`/tickets/${ticket.id}/close`)),
    managerPage.getByRole('button', { name: 'Close', exact: true }).click(),
  ]);
  expect(closeResponse.ok()).toBeTruthy();
  await expect(managerPage.getByText('CLOSED', { exact: true }).first()).toBeVisible();

  await renterPage.reload();
  const ratingCard = renterPage.getByRole('heading', { name: 'Rate this service' }).locator('..');
  await ratingCard.locator('button').nth(4).click();
  await ratingCard.getByPlaceholder('Optional comment...').fill('Resolved quickly');
  const [ratingResponse] = await Promise.all([
    renterPage.waitForResponse((response) => response.url().endsWith(`/tickets/${ticket.id}/rate`)),
    ratingCard.getByRole('button', { name: 'Submit Rating' }).click(),
  ]);
  expect(ratingResponse.ok()).toBeTruthy();
  await expect(renterPage.getByText('Your Rating', { exact: true })).toBeVisible();
  expect((await api.getTicket(renterCtx, ticket.id)).satisfactionRating).toBe(5);

  const replies = await api.getTicketReplies(renterCtx, ticket.id);
  expect(replies.some((reply) => reply.id === renterReply!.id)).toBeTruthy();

  const history = await api.getTicketHistory(pmCtx, ticket.id);
  expect(history.some((event) => event.action === 'ASSIGNED')).toBeTruthy();
  expect(history.some((event) => event.toStatus === 'CLOSED')).toBeTruthy();

  const report = await api.getTicketReport(pmCtx, ctx.property.id);
  expect(report.totalTickets).toBeGreaterThanOrEqual(1);
  expect(report.closedCount).toBeGreaterThanOrEqual(1);
  expect(report.avgSatisfaction).toBeGreaterThanOrEqual(5);

  await managerPage.goto('/en/dashboard/tickets/reports');
  await expect(managerPage.getByRole('heading', { level: 1, name: 'Ticket Reports' })).toBeVisible();
  await expect(managerPage.getByText('Total Tickets', { exact: true })).toBeVisible();
  await expect(managerPage.getByText('Avg Satisfaction', { exact: true })).toBeVisible();

  await renterBrowser.close();
  await managerBrowser.close();
  await renterCtx.request.dispose();
  await pmCtx.request.dispose();
});

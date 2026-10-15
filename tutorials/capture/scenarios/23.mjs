// Tutorial 23 — Meetings (Oasis Crest Properties). The Tenant Ahmed Hassan
// requests an office visit to renew his contract on A-101 (twelve months at
// AED 88,000) → the Company Admin (the default host) opens it from the
// calendar and approves it → closes off the past: completes Fatima Al Zaabi's
// cheque replacement visit (29 Sep, Approved) and cancels the penthouse
// viewing (30 Sep, never confirmed) → filters the list to Approved.
//
// Snapshot pre23 = pre22 (the seeded meetings untouched). The new meeting is
// created on camera; the proof and capture restore pre23.
// Weights are the seconds of narration each scene covers (tutorials/work/acct/tts.sh).
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { isoDate } from '../lib/fixtures.mjs';
import { goto } from '../lib/page.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const PURPOSE = 'Tenancy Contract Renewal';
const next = (page) => page.getByRole('button', { name: 'Next', exact: true });
const row = (page, text) => page.locator('tr').filter({ hasText: text });
const statusPill = (page, label) => page.locator('main').getByText(label, { exact: true }).first();

/** A weekday (Mon–Fri) at least three days out, so the host has free slots. */
function meetingDate() {
  const d = new Date();
  d.setDate(d.getDate() + 3);
  while (d.getDay() === 0 || d.getDay() === 6) d.setDate(d.getDate() + 1);
  return isoDate(d);
}

async function openList(page) {
  await goto(page, '/en/dashboard/meetings');
  await page.getByRole('button', { name: 'List' }).click();
  await row(page, 'Penthouse viewing').waitFor({ state: 'visible', timeout: navTimeoutMs });
}

const scenes = [
  roleRouteScene('renter', '/en/dashboard/meetings', 'Request a meeting',
    'The Tenant picks the type, purpose, contract and a free slot, and can propose renewal terms.', {
    weight: 42.08,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await page.getByRole('button', { name: 'New Meeting' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(11.8);
      await page.getByRole('button', { name: 'New Meeting' }).click();
      await at(13.9);
      await page.getByRole('button', { name: /Office Visit/ }).click();
      await at(14.9);
      await next(page).click();
      const purpose = page.locator('select').filter({ hasText: 'Select purpose' });
      await purpose.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(18.7);
      await purpose.selectOption('LEASE_RENEWAL');
      await at(20.2);
      await page.locator('select').filter({ hasText: 'Select contract' }).selectOption({ label: 'Oasis Crest Residence Tower — Unit A-101' });
      await at(21.6);
      await next(page).click();
      const date = page.locator('input[type=date]').last();
      await date.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(22.8);
      await date.fill(meetingDate());
      const slot = page.locator('button').filter({ hasText: /^\d{1,2}:\d{2}/ }).first();
      await slot.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(26.2);
      await slot.click();
      await at(27.3);
      await next(page).click();
      const months = page.locator('input[type=number]').first();
      await months.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByText('Current contract ends', { exact: false }), '2026-12-31', 'Contract end on the renewal step');
      await at(28.6);
      await months.fill('12');
      await page.locator('input[type=number]').nth(1).fill('88000');
      await page.locator('textarea').last().fill('Happy to renew for another year.');
      await at(33.3);
      await next(page).click();
      const submit = page.getByRole('button', { name: 'Request Meeting' });
      await submit.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByText('Review your meeting request').locator('xpath=..'), 'AED 88000', 'Review shows the proposed rent');
      await at(34.2);
      await pointAt(page.getByText('PROPOSED RENT', { exact: false }).first());
      await at(36.2);
      await submit.click();
      await submit.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      await at(37.9);
      await page.getByRole('button', { name: 'List' }).click();
      const mine = row(page, PURPOSE);
      await expectText(mine, 'Requested', 'New meeting status');
      await expectText(mine, 'Noura Al Suwaidi', 'New meeting host');
      await at(38.6);
      await pointAt(mine.getByText('Requested'));
      await at(40.3);
      await pointAt(mine.getByText('Noura Al Suwaidi'));
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/meetings', 'Approve it',
    'The host opens the request from the calendar, reads the proposal and approves.', {
    weight: 17.93,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const event = page.locator('.fc-event').filter({ hasText: PURPOSE }).first();
      await event.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(2.6);
      await pointAt(page.locator('.fc-event').filter({ hasText: 'Penthouse' }).first());
      await at(4.8);
      await pointAt(event);
      await at(6.9);
      await event.click();
      const approve = page.getByRole('button', { name: /^Approve/ });
      await approve.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.locator('main'), 'AED 88,000.00', 'Renewal proposal on the meeting');
      await expectText(page.locator('main'), 'Ahmed Hassan', 'Requested by');
      await at(8.6);
      await pointAt(page.getByText('Requested By', { exact: false }).first());
      await at(10.4);
      await pointAt(page.getByText('AED 88,000.00'));
      await at(12.8);
      await approve.click();
      await page.getByRole('button', { name: /^Complete/ }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(13.6);
      await pointAt(statusPill(page, 'Approved'));
      await at(15.4);
      await pointAt(page.getByRole('button', { name: /Mark No Show/ }));
    },
  }),
  stepScene('Close off the past',
    'Complete the meetings that happened; cancel the ones that did not, so the record stays.',
    async (page) => {
      const at = sceneClock(page);
      await openList(page);
      await at(2.4);
      await row(page, 'Replacement cheque').getByRole('link', { name: 'View' }).click();
      const complete = page.getByRole('button', { name: /^Complete/ });
      await complete.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(4.4);
      await pointAt(page.getByText('29 September 2026', { exact: false }).first());
      await at(7.6);
      await complete.click();
      await statusPill(page, 'Completed').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(9.0);
      await openList(page);
      await expectText(row(page, 'Replacement cheque'), 'Completed', 'Fatima\'s visit completed');
      await at(10.4);
      await row(page, 'Penthouse viewing').getByRole('link', { name: 'View' }).click();
      const cancel = page.getByRole('button', { name: /^Cancel/ }).first();
      await cancel.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(11.6);
      await pointAt(page.getByText('30 September 2026', { exact: false }).first());
      await at(13.9);
      await cancel.click();
      await statusPill(page, 'Cancelled').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(14.8);
      await pointAt(statusPill(page, 'Cancelled'));
      await at(17.0);
      await openList(page);
      await at(18.4);
      await page.locator('select').filter({ hasText: 'All Statuses' }).selectOption('APPROVED');
      const approved = row(page, PURPOSE);
      await expectText(approved, 'Approved', 'Renewal meeting listed as Approved');
      await row(page, 'Penthouse viewing').waitFor({ state: 'hidden', timeout: navTimeoutMs });
      await at(20.6);
      await pointAt(approved.getByText('Approved'));
    }, { weight: 22.77 }),
];

export default { role: 'tenantAdmin', anchored: true, scenes };

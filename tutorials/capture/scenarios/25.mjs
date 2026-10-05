// Tutorial 25 — Gate passes (Oasis Crest Properties), re-scoped to the web
// screens a recording can reach: the Tenant's passes, the manager's approvals
// and the gate policy. The web gate desk is SECURITY_GUARD-only and guards
// cannot sign in on the web (phone sign-in only), so check-in and walk-ins are
// not shown (UX gap logged).
//
// Ahmed Hassan creates a single-visit pass (Omar Khalil, today 18:00–23:00,
// active at once, gate code) and requests a recurring one (Maria Santos,
// housekeeping, today + 30 days, awaiting approval) → the Company Admin
// approves it and walks through Policy & visitors (Residence Tower) → Ahmed
// sees it Active with its code.
//
// Snapshot pre25 = pre24 with the seeded Layla Mansour pass cancelled off
// camera (its window ended 29 Sep but it still read Active — gap logged).
// Record with TZ=Asia/Dubai: the form's times are Gulf time and the browser
// shows them in its own zone.
// Weights are the seconds of narration each scene covers (tutorials/work/acct/tts.sh).
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { isoDate } from '../lib/fixtures.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene } from '../lib/scenes.mjs';

const GUEST = 'Omar Khalil';
const MAID = 'Maria Santos';
const passRow = (page, name) => page.locator('tr').filter({ hasText: name });
const inDays = (n) => { const d = new Date(); d.setDate(d.getDate() + n); return isoDate(d); };

const scenes = [
  roleRouteScene('renter', '/en/dashboard/renter-portal/gate-passes', 'Invite a visitor',
    'A single visit is active at once; a recurring pass waits for a manager.', {
    weight: 45.16,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const request = page.getByRole('button', { name: /Request pass/ }).first();
      await request.waitFor({ state: 'visible', timeout: navTimeoutMs });
      if (validTZ() !== 'Asia/Dubai') throw new Error(`Record tutorial 25 with TZ=Asia/Dubai (browser zone is ${validTZ()}).`);
      await restPointer(page, 1300, 640);
      await at(12.3);
      await request.click();
      let form = page.getByTestId('gatepass-form-drawer');
      await form.locator('#gp-name').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(14.7);
      await form.locator('#gp-name').fill(GUEST);
      await form.locator('#gp-phone').fill('+971500000021');
      await at(17.4);
      await form.locator('#gp-purpose').fill('Dinner guest');
      await form.locator('#gp-vehicle').fill('D 48213');
      await at(20.2);
      await pointAt(form.getByText('Single visit', { exact: true }));
      await at(21.8);
      await form.locator('#gp-date').fill(inDays(0));
      await form.locator('#gp-from').fill('18:00');
      await form.locator('#gp-until').fill('23:00');
      await at(25.4);
      await form.getByTestId('gatepass-submit').click();
      const detail = page.getByTestId('gatepass-detail-drawer');
      await detail.getByText('Gate code', { exact: false }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(passRow(page, GUEST), 'Active', 'Single visit active at once');
      await expectText(passRow(page, GUEST), '18:00', 'Visit window from 18:00');
      await at(27.4);
      await pointAt(detail.getByText('Active', { exact: true }).first());
      await at(29.2);
      await pointAt(detail.getByText('Gate code', { exact: false }));
      await at(33.4);
      await page.getByTestId('gatepass-detail-drawer-close').click().catch(() => page.keyboard.press('Escape'));
      await detail.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      await at(34.6);
      await request.click();
      form = page.getByTestId('gatepass-form-drawer');
      await form.locator('#gp-name').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(35.8);
      await form.getByText('Recurring', { exact: true }).click();
      await at(38.0);
      await form.locator('#gp-name').fill(MAID);
      await form.locator('#gp-phone').fill('+971500000022');
      await form.locator('#gp-purpose').fill('Housekeeping, weekday mornings');
      await at(40.8);
      await form.locator('#gp-first').fill(inDays(0));
      await form.locator('#gp-last').fill(inDays(30));
      await at(42.4);
      await form.getByTestId('gatepass-submit').click();
      await expectText(passRow(page, MAID), 'Awaiting approval', 'Recurring pass waits for approval');
      await page.keyboard.press('Escape');
      await at(43.6);
      await pointAt(passRow(page, MAID).getByText('Awaiting approval'));
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/gatepass/approvals', 'Approve and set the policy',
    'Approve the regular visitor; set walk-in rules, regular visitors and guard postings per property.', {
    weight: 28.17,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const row = passRow(page, MAID);
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(4.2);
      await pointAt(row.getByText('Housekeeping, weekday mornings'));
      await at(6.9);
      await row.getByRole('button', { name: /^Approve/ }).click();
      await at(7.6);
      await page.getByTestId('approval-confirm').click();
      const notice = page.getByText(`${MAID}'s pass is now active`, { exact: false });
      await notice.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(8.4);
      await pointAt(notice);
      await at(10.2);
      await page.locator('main').getByText('Policy & visitors', { exact: true }).first().click();
      const property = page.locator('#gs-property');
      await property.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await property.selectOption({ label: 'Oasis Crest Residence Tower' });
      const approves = page.getByText('Tenant approves new visitors', { exact: true });
      await approves.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(13.9);
      await pointAt(approves);
      await at(16.4);
      await pointAt(page.getByText('Photo required at the gate', { exact: true }));
      await at(18.8);
      await pointAt(page.locator('#gs-timeout'));
      await at(21.0);
      const regular = page.getByText('Regular visitors', { exact: true }).first();
      await regular.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'start' }));
      await at(22.0);
      await pointAt(regular);
      await at(24.8);
      const guard = page.getByText('Samir Nasser').first();
      await guard.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
      await pointAt(guard);
    },
  }),
  roleRouteScene('renter', '/en/dashboard/renter-portal/gate-passes', 'Ready for the gate',
    'The approved pass carries the code the guard enters at the gate.', {
    weight: 12.57,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const row = passRow(page, MAID);
      await expectText(row, 'Active', 'Recurring pass active after approval');
      await restPointer(page, 1300, 640);
      await at(1.0);
      await pointAt(row.getByText('Active', { exact: true }));
      await at(3.4);
      await row.getByRole('button', { name: 'View' }).click();
      const detail = page.getByTestId('gatepass-detail-drawer');
      await detail.getByText('Gate code', { exact: false }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(4.6);
      await pointAt(detail.getByText('Gate code', { exact: false }));
      await at(7.2);
      await pointAt(detail.getByText('Share the code only with the visitor', { exact: false }));
    },
  }),
];

function validTZ() { return process.env.TZ || Intl.DateTimeFormat().resolvedOptions().timeZone; }

export default { role: 'tenantAdmin', anchored: true, scenes };

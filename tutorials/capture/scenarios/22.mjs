// Tutorial 22 — Maintenance tickets (Oasis Crest Properties). The Tenant Ahmed
// Hassan reports a bedroom AC fault (HVAC, High) → the Company Admin gives
// Khalid Rahman (on the staff list as Maintenance Supervisor) a Company User
// login — the maintenance-team role tickets are assigned to — and assigns the
// ticket to him, replies, sets an ETA, starts and resolves it → the Tenant sees
// the six-digit closure code → the admin closes with it, history → the Tenant
// rates the service → Ticket Reports.
//
// Snapshot pre22 = pre21 plus TKT-26/1 (leaking tap) handled by Maya Khoury and
// closed with its code and rated 4 stars off camera, so the reports' averages have a
// ticket older than an hour (the report counts whole hours). Khalid's login and
// TKT-26/2 are created on camera; the proof and capture restore pre22.
// Weights are the seconds of narration each scene covers (tutorials/work/acct/tts.sh).
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { goto } from '../lib/page.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const TITLE = 'Bedroom air conditioning not cooling';
const state = { ticketPath: null, otp: null };

const type = (locator, text, delay = 30) => locator.click().then(() => locator.pressSequentially(text, { delay }));
const statusBadge = (page, label) => page.locator('h1').locator('xpath=following-sibling::span[1]').filter({ hasText: label });

/** A scene that opens the new ticket in a fresh context of `role`. */
function ticketScene(role, title, body, weight, after) {
  return {
    title, body, role, weight, verifyTenantContext: true,
    run: async (page) => {
      if (!state.ticketPath) throw new Error('The new ticket was not opened earlier in this run.');
      await goto(page, state.ticketPath);
      await page.getByRole('heading', { name: TITLE }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await after(page);
    },
  };
}

const scenes = [
  roleRouteScene('renter', '/en/dashboard/tickets', 'Report a problem',
    'The Tenant raises a ticket for their own unit, with a category and a priority.', {
    weight: 26.2,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await page.getByText('TKT-26/1').first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(11.4);
      await page.getByRole('button', { name: 'Create Ticket' }).first().click();
      const titleInput = page.getByPlaceholder('Brief summary of the issue');
      const modal = page.locator('div.fixed').filter({ has: titleInput }).last();
      await at(13.8);
      await type(titleInput, TITLE);
      await modal.locator('textarea').fill('The bedroom AC runs but blows warm air since yesterday evening.');
      await at(17.0);
      await pointAt(modal.getByText('Oasis Crest Residence Tower — Unit A-101'));
      await at(19.9);
      await modal.locator('select').nth(0).selectOption('HVAC');
      await at(20.9);
      await modal.locator('select').nth(1).selectOption('HIGH');
      await at(21.8);
      await modal.getByRole('button', { name: 'Create Ticket' }).click();
      const row = page.locator('tr').filter({ hasText: TITLE });
      await expectText(row, 'TKT-26/2', 'New ticket reference');
      await expectText(row, 'Open', 'New ticket status');
      await expectText(row, 'High', 'New ticket priority');
      await at(24.6);
      await pointAt(row.getByText('Open'));
    },
  }),
  roleRouteScene('tenantAdmin', '/en/dashboard/settings?section=users', 'Give the supervisor a login',
    'Tickets are assigned to users; the maintenance team signs in as Company Users.', {
    weight: 31.1,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const supervisor = page.getByText('Maintenance Supervisor').first();
      await supervisor.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(2.4);
      await supervisor.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
      await at(3.6);
      await pointAt(page.locator('tr').filter({ hasText: 'Khalid Rahman' }).filter({ hasText: 'Maintenance Supervisor' }).first());
      await at(7.0);
      await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'smooth' }));
      await at(9.0);
      await page.getByRole('button', { name: 'New User' }).click();
      await at(12.4);
      await page.getByPlaceholder('e.g. Acme Corp Admin').fill('Khalid Rahman');
      await page.getByPlaceholder('e.g. admin@acmecorp.com').fill('khalid@oasiscrest.example');
      await at(14.4);
      await page.locator('select').filter({ hasText: 'Company User' }).last().selectOption('TENANT_USER');
      await at(16.6);
      await page.getByRole('button', { name: 'Provision User' }).last().click();
      await page.getByPlaceholder('e.g. Acme Corp Admin').waitFor({ state: 'hidden', timeout: navTimeoutMs });
      const newRow = page.locator('tr').filter({ hasText: 'khalid@oasiscrest.example' });
      await expectText(newRow, 'Company User', 'Khalid\'s new login');
      await at(23.8);
      await pointAt(newRow.getByText('Khalid Rahman'));
      await at(27.8);
      await pointAt(newRow.getByText('Resend invite'));
    },
  }),
  stepScene('Assign the work',
    'Assign the ticket, keep the Tenant posted, then move it through In Progress to Resolved.',
    async (page) => {
      const at = sceneClock(page);
      await goto(page, '/en/dashboard/tickets');
      const row = page.locator('tr').filter({ hasText: TITLE });
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(1.2);
      await row.getByText('View').click();
      await page.getByRole('heading', { name: TITLE }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      state.ticketPath = new URL(page.url()).pathname;
      await at(3.0);
      await page.getByRole('button', { name: /^Assign To/ }).click();
      await at(4.6);
      await page.getByRole('button', { name: /Khalid Rahman/ }).click();
      await statusBadge(page, 'Assigned').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByText('Ticket assigned to', { exact: false }).first(), 'Khalid Rahman', 'Assignment history');
      await at(5.6);
      await pointAt(statusBadge(page, 'Assigned'));
      await at(8.0);
      await pointAt(page.getByText('Assigned To', { exact: true }).locator('xpath=../..'));
      await at(10.5);
      await page.getByPlaceholder('Type your reply...').fill('Khalid will inspect the unit today between 2 and 4 pm.');
      await page.getByRole('button', { name: 'Send' }).click();
      await page.locator('main').getByText('Khalid will inspect the unit today').first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(14.2);
      await type(page.locator('input[type="number"]'), '4', 60);
      await page.getByRole('button', { name: 'Set ETA' }).click();
      await page.getByText('4 hours').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(17.5);
      await page.getByRole('button', { name: 'Start Work' }).click();
      await statusBadge(page, 'In Progress').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(19.9);
      await page.getByRole('button', { name: /Mark Resolved/ }).click();
      await statusBadge(page, 'Resolved').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(20.8);
      await pointAt(statusBadge(page, 'Resolved'));
    }, { weight: 22.4 }),
  ticketScene('renter', 'The closure code',
    'Only the Tenant sees the code, and shares it once the work is done.', 7.7, async (page) => {
      const at = sceneClock(page);
      const box = page.getByText('Share this code', { exact: false }).locator('xpath=..');
      const code = box.locator('p').nth(1);
      await code.waitFor({ state: 'visible', timeout: navTimeoutMs });
      const otp = (await code.innerText()).trim();
      if (!/^\d{6}$/.test(otp)) throw new Error(`Closure code: expected six digits, found "${otp}".`);
      state.otp = otp;
      await restPointer(page, 1300, 640);
      await at(2.4);
      await pointAt(code);
    }),
  ticketScene('tenantAdmin', 'Close with the code',
    'The code closes the ticket; the history shows who did what, and when.', 13.6, async (page) => {
      const at = sceneClock(page);
      if (!state.otp) throw new Error('No closure code was read from the Tenant\'s view.');
      const input = page.locator('input[maxlength="6"]');
      await input.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(1.2);
      await type(input, state.otp, 90);
      await at(2.8);
      await page.getByRole('button', { name: /^Close$/ }).click();
      await statusBadge(page, 'Closed').waitFor({ state: 'visible', timeout: navTimeoutMs });
      const closed = page.getByText('Ticket closed with OTP verification');
      await closed.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(4.6);
      await pointAt(statusBadge(page, 'Closed'));
      await at(6.6);
      await pointAt(page.getByText('Ticket assigned to', { exact: false }).first());
      await at(8.4);
      await pointAt(page.getByText('In Progress → Resolved', { exact: false }).first());
      await at(10.6);
      await pointAt(closed);
    }),
  stepScene('Ticket reports',
    'Counts by status, category and priority, with resolution time and satisfaction.',
    async (page) => {
      const at = sceneClock(page);
      await goto(page, '/en/dashboard/tickets/reports');
      const main = page.locator('main');
      await main.getByText('By Status').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(main.locator('tr').filter({ hasText: 'Closed' }).first(), '2', 'Closed tickets');
      await restPointer(page, 1300, 640);
      await at(0.8);
      await pointAt(main.getByText('By Status'));
      await at(1.8);
      await pointAt(main.getByText('By Category'));
      await at(2.8);
      await pointAt(main.getByText('By Priority'));
      await at(3.6);
      await pointAt(main.getByText('Avg Resolution', { exact: false }));
      await at(5.2);
      await pointAt(main.getByText('Avg Satisfaction', { exact: false }));
    }, { weight: 6.3 }),
  ticketScene('renter', 'Rate the service',
    'After closure the Tenant rates the work.', 10.5, async (page) => {
      const at = sceneClock(page);
      const comment = page.getByPlaceholder('Optional comment...');
      await comment.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(2.9);
      await comment.locator('xpath=preceding-sibling::div[1]//button').nth(4).click();
      await type(comment, 'Fixed the same day, thank you.', 20);
      await at(5.6);
      await page.getByRole('button', { name: 'Submit Rating' }).click();
      await comment.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      await expectText(page.locator('main'), 'Fixed the same day, thank you.', 'Saved rating comment');
    }),
];

export default { role: 'tenantAdmin', anchored: true, scenes };

// Tutorial 05 — Create users and staff. The System Admin, inside Oasis Crest
// Properties, provisions a Property Manager with a property assignment, checks
// it on the property, adds a staff record, and offboards both: the staff record
// is marked Inactive (kept for history) and the user is deleted. Leftovers from
// an earlier take are removed off camera before the first frame.
import { navTimeoutMs, seed } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { applyCaptureStyles, goto, waitForApp } from '../lib/page.mjs';
import { expectCount, expectText, pace } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const usersPage = '/en/dashboard/settings?section=users';
const marinaId = seed.properties?.marina;
const marinaName = 'Oasis Crest Marina Heights';
const towerName = 'Oasis Crest Residence Tower';
const manager = { name: 'Layla Haddad', email: 'layla.haddad@oasiscrest.example', phone: '+971 50 000 0417' };
const staff = { name: 'Khalid Rahman', employeeId: 'OC-FAC-014', designation: 'Maintenance Supervisor', department: 'Facilities', salary: '6500' };

/** Delete a user or staff record an earlier take left behind (API, off camera). */
async function removeLeftovers(page) {
  const users = await page.request.get('/api/proxy/admin/users');
  if (!users.ok()) throw new Error(`Listing users failed: ${users.status()}`);
  for (const user of (await users.json()).filter((u) => u.email === manager.email)) {
    const del = await page.request.delete(`/api/proxy/admin/users/${user.id}`);
    if (!del.ok()) throw new Error(`Removing the earlier ${manager.name} failed: ${del.status()}`);
  }
  const members = await page.request.get('/api/proxy/v1/staff');
  if (!members.ok()) throw new Error(`Listing staff failed: ${members.status()}`);
  for (const member of (await members.json()).filter((m) => m.employeeId === staff.employeeId)) {
    const del = await page.request.delete(`/api/proxy/v1/staff/${member.id}`);
    if (!del.ok()) throw new Error(`Removing the earlier ${staff.name} failed: ${del.status()}`);
  }
}

const userRow = (page) => page.getByRole('row').filter({ hasText: manager.email });
const staffRow = (page) => page.getByRole('row').filter({ hasText: staff.employeeId });
const userForm = (page) => page.locator('#user-form');
/** An assigned-property chip in the user form (assignments load after the form opens). */
const assignmentChip = (page, name) => userForm(page).locator('div.flex-wrap > div', { hasText: name });
const staffForm = (page) => page.locator('form').filter({ has: page.getByText('Employee ID', { exact: true }) });
/** A staff-form input or select by its label (the label is the control's previous sibling). */
const staffField = (page, label) => staffForm(page).locator(`xpath=.//label[normalize-space()="${label}"]/following-sibling::*[1]`);

const scenes = [
  // Weights follow the narration: roughly the seconds each part takes to speak.
  {
    ...roleRouteScene('superadmin', usersPage, 'Users & staff',
      'Users can sign in; staff records hold employment details. Search before you create, to avoid duplicates.', { weight: 30 }),
    run: async (page) => {
      await goto(page, usersPage);
      await removeLeftovers(page);
      await goto(page, usersPage);
      await page.getByText('Staff Management', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByRole('button', { name: 'New User', exact: true }).waitFor({ state: 'visible' });
      await pace(page, 9000);
      await page.getByPlaceholder('Search users...').fill('layla');
      await expectCount(userRow(page), 0, `${manager.name} rows`);
      await restPointer(page, 1300, 520);
    },
  },
  stepScene('Provision New User',
    'Property Manager: the lowest role that covers the work. Assign the properties this person runs.',
    async (page) => {
      await page.getByRole('button', { name: 'New User', exact: true }).click();
      await page.getByText('Provision New User', { exact: true }).waitFor({ state: 'visible' });
      await pace(page, 2000);
      await page.getByPlaceholder('e.g. Acme Corp Admin').fill(manager.name);
      await page.getByPlaceholder('e.g. admin@acmecorp.com').fill(manager.email);
      await page.getByPlaceholder('e.g. +971 50 123 4567').fill(manager.phone);
      const role = userForm(page).locator('select').filter({ has: page.locator('option', { hasText: 'Property Manager' }) });
      await role.selectOption({ label: 'Property Manager' });
      // The property picker lists the chosen organisation's properties, so the
      // organisation comes first.
      await userForm(page).locator('select').filter({ has: page.locator('option', { hasText: 'None (System Admin context)' }) })
        .selectOption({ label: 'Oasis Crest Properties' });
      await pace(page, 4000);
      await userForm(page).locator('select').filter({ has: page.locator('option', { hasText: 'Add property...' }) })
        .selectOption({ label: marinaName });
      await assignmentChip(page, marinaName).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 2000);
    }, { weight: 36 }),
  stepScene('Provision User',
    'The new user appears as a Property Manager. They receive an email invite to set their own password.',
    async (page) => {
      await page.getByRole('button', { name: 'Provision User', exact: true }).click();
      await userRow(page).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(userRow(page), 'Property Manager', 'New user role');
      await restPointer(page, 1300, 620);
    }, { weight: 13 }),
  stepScene('Edit the assignment',
    'Edit reopens the form. Add a second property, then select Update User.',
    async (page) => {
      await userRow(page).getByRole('button', { name: 'Edit', exact: true }).click();
      await page.getByText('Edit User', { exact: true }).waitFor({ state: 'visible' });
      await assignmentChip(page, marinaName).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 3000);
      await userForm(page).locator('select').filter({ has: page.locator('option', { hasText: 'Add property...' }) })
        .selectOption({ label: towerName });
      await assignmentChip(page, towerName).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByRole('button', { name: 'Update User', exact: true }).click();
      await page.getByText('Edit User', { exact: true }).waitFor({ state: 'hidden', timeout: navTimeoutMs });
      await restPointer(page, 1300, 620);
    }, { weight: 14 }),
  roleRouteScene('superadmin', `/en/dashboard/properties/${marinaId}`, 'The property shows its manager',
    'The property overview now lists the new Property Manager.', {
    weight: 13,
    afterNavigation: async (page) => {
      if (!marinaId) throw new Error('The seed manifest has no marina property id.');
      await expectText(page.locator('main'), marinaName, 'Property');
      await page.getByText(manager.name, { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pointAt(page.getByText(manager.name, { exact: true }));
    },
  }),
  roleRouteScene('superadmin', usersPage, 'Add Staff',
    'A staff record: name, employee ID, designation, department, salary and property. It gives no sign-in.', {
    weight: 30,
    afterNavigation: async (page) => {
      await page.getByText('Staff Management', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByRole('button', { name: 'Add Staff', exact: true }).click();
      await staffField(page, 'Name (English)').waitFor({ state: 'visible' });
      await staffField(page, 'Name (English)').fill(staff.name);
      await staffField(page, 'Employee ID').fill(staff.employeeId);
      await staffField(page, 'Designation').fill(staff.designation);
      await staffField(page, 'Department').fill(staff.department);
      await staffField(page, 'Monthly Salary (AED)').fill(staff.salary);
      await staffField(page, 'Assigned Property').selectOption({ label: marinaName });
      if (!(await staffForm(page).getByRole('checkbox').isChecked())) throw new Error('A new staff record should start Active.');
      await pace(page, 3000);
      await staffForm(page).getByRole('button', { name: 'Add Staff', exact: true }).click();
      await staffRow(page).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(staffRow(page), 'Active', 'New staff status');
      await expectText(staffRow(page), staff.designation, 'New staff designation');
      await restPointer(page, 1300, 560);
    },
  }),
  stepScene('When someone leaves',
    'Mark the staff record Inactive to keep its history, and delete the user so the account can no longer sign in.',
    async (page) => {
      await staffRow(page).getByRole('button', { name: 'Edit Staff' }).click();
      await staffField(page, 'Name (English)').waitFor({ state: 'visible' });
      await staffForm(page).getByRole('checkbox').uncheck();
      await pace(page, 2000);
      await staffForm(page).getByRole('button', { name: 'Edit Staff', exact: true }).click();
      await staffForm(page).waitFor({ state: 'detached', timeout: navTimeoutMs });
      await expectText(staffRow(page), 'Inactive', 'Staff status');
      await pace(page, 3000);
      await page.getByPlaceholder('Search users...').fill('layla');
      await userRow(page).getByRole('button', { name: 'Delete', exact: true }).click();
      await page.getByText('Delete User', { exact: true }).first().waitFor({ state: 'visible' });
      await pace(page, 3000);
      await page.getByRole('button', { name: 'Delete User', exact: true }).click();
      await page.getByRole('button', { name: 'Delete User', exact: true }).waitFor({ state: 'detached', timeout: navTimeoutMs });
      await userRow(page).waitFor({ state: 'detached', timeout: navTimeoutMs });
      await page.getByPlaceholder('Search users...').fill('');
      await restPointer(page, 1300, 560);
    }, { weight: 30 }),
];

export default { role: 'superadmin', scenes };

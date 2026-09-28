// Tutorial 05. Default role for scenes that do not name one: superadmin.
import { towerId } from '../lib/fixtures.mjs';
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/superadmin/users', 'User administration', 'Create and edit organisation users with the minimum role needed for their work.', async (page) => {
    await page.getByRole('button', { name: 'New User', exact: true }).click();
    await page.getByText('Provision New User', { exact: true }).last().waitFor({ state: 'visible' });
  }),
  routeScene('/en/dashboard/staff', 'Staff directory', 'Staff records hold employment context separately from application login access.', async (page) => {
    await page.getByRole('button', { name: 'Add Staff', exact: true }).click();
    await page.getByText('Add Staff', { exact: true }).last().waitFor({ state: 'visible' });
  }),
  routeScene(`/en/dashboard/properties/${towerId}`, 'Property assignment', 'Property managers should be assigned only to the properties they are responsible for.'),
  routeScene('/en/superadmin/users', 'Access review', 'Review role and assignment changes after saving, and remove obsolete access promptly.', async (page) => {
    await page.getByPlaceholder('Search users...').fill('manager@tutorial-studio.example.com');
  }),
];

export default { role: 'superadmin', scenes };

// Tutorial 06. Default role for scenes that do not name one: tenantAdmin.
import { towerId } from '../lib/fixtures.mjs';
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/properties', 'Property portfolio', 'Projects and properties provide the foundation for units, leases, operations, and reporting.', async (page) => {
    await page.getByTestId('properties-more').click();
    await page.getByTestId('properties-add-project').click();
    await page.getByText('Create a new Project (Portfolio Group).', { exact: true }).waitFor({ state: 'visible' });
  }),
  routeScene(`/en/dashboard/properties/${towerId}`, 'Prepared residential tower', 'Review bilingual identity, address, emirate, portfolio type, and operational summary.'),
  routeScene('/en/dashboard/properties', 'Card view', 'Use cards for visual scanning of property names, occupancy, and summary information.', async (page) => {
    await page.getByRole('button', { name: 'Cards', exact: true }).click();
  }),
  routeScene('/en/dashboard/properties', 'Table view', 'Use the table for compact comparison across a larger portfolio.', async (page) => {
    await page.getByRole('button', { name: 'Table', exact: true }).click();
  }),
];

export default { role: 'tenantAdmin', scenes };

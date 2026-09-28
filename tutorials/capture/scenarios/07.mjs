// Tutorial 07. Default role for scenes that do not name one: tenantAdmin.
import { towerId } from '../lib/fixtures.mjs';
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene(`/en/dashboard/properties/${towerId}`, 'Property operations', 'A property can contain buildings, units, contacts, amenities, and parking inventory.'),
  routeScene(`/en/dashboard/properties/${towerId}`, 'Buildings', 'Buildings organise floors and provide an optional scope for resident facilities.', async (page) => {
    await page.getByRole('button', { name: /^buildings$/i }).click();
    await page.getByRole('button', { name: 'Add Building', exact: true }).click();
    await page.getByText('Add Building', { exact: true }).last().waitFor({ state: 'visible' });
  }),
  routeScene(`/en/dashboard/properties/${towerId}`, 'Units', 'Unit status and rent expectations connect portfolio data to leasing.', async (page) => {
    await page.getByRole('button', { name: /^units$/i }).click();
    await page.getByRole('button', { name: 'Add Unit', exact: true }).click();
    await page.getByText('Add Unit', { exact: true }).last().waitFor({ state: 'visible' });
  }),
  routeScene(`/en/dashboard/properties/${towerId}`, 'Contacts', 'Operations contacts keep emergency and maintenance details discoverable without mixing them with login accounts.', async (page) => {
    await page.getByRole('button', { name: 'Add Contact', exact: true }).click();
    await page.getByText('Add Contact', { exact: true }).last().waitFor({ state: 'visible' });
  }),
  routeScene(`/en/dashboard/properties/${towerId}`, 'Amenities', 'Bookable amenities can be property-wide or restricted to selected buildings.', async (page) => {
    await page.getByRole('button', { name: /^amenities$/i }).click();
    await page.getByRole('button', { name: 'Add Amenity', exact: true }).click();
    await page.getByText('Add Amenity', { exact: true }).last().waitFor({ state: 'visible' });
  }),
  routeScene(`/en/dashboard/properties/${towerId}`, 'Parking', 'Parking spots retain level, coverage, availability, and booking state.', async (page) => {
    await page.getByRole('button', { name: /^parking$/i }).click();
    await page.getByRole('button', { name: 'Add Spot', exact: true }).click();
    await page.getByText('Add Spot', { exact: true }).last().waitFor({ state: 'visible' });
  }),
];

export default { role: 'tenantAdmin', scenes };

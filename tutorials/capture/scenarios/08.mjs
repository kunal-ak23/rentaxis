// Tutorial 08. Default role for scenes that do not name one: tenantAdmin.
import { routeScene } from '../lib/scenes.mjs';

const scenes = [
  routeScene('/en/dashboard/properties', 'Portfolio import', 'Use the current workbook template for controlled onboarding and migration.', async (page) => {
    await page.getByTestId('properties-more').click();
    await page.getByTestId('properties-import-portfolio').click();
    await page.getByText('Import Portfolio', { exact: true }).last().waitFor({ state: 'visible' });
    await page.getByText('Download Template', { exact: true }).click();
  }),
  routeScene('/en/dashboard/properties', 'Validate before import', 'Review required sheets, reference values, and row-level validation errors before creating records.', async (page) => {
    await page.getByTestId('properties-more').click();
    await page.getByTestId('properties-import-portfolio').click();
    await page.getByText('Import Portfolio', { exact: true }).last().waitFor({ state: 'visible' });
  }),
  routeScene('/en/dashboard/properties', 'Verify imported records', 'After a successful job, reconcile counts and inspect representative properties, units, renters, leases, and installments.'),
];

export default { role: 'tenantAdmin', scenes };

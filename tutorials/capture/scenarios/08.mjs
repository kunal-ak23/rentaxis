// Tutorial 08 — Import a property portfolio in bulk. The Company Admin opens
// Import Portfolio, downloads the template, uploads a workbook with two
// deliberate mistakes (validation fails, nothing is created), then the
// corrected workbook (1 project, 2 buildings, 4 units, 2 tenants), and checks
// the project and the tenants it created. The workbooks are fictional
// fixtures built from the app's own template (fixtures/make-portfolio-
// workbooks.py). The app cannot delete projects, units or tenants, so what an
// earlier take imported is purged from the local tutorial database before the
// first frame (lib/local-db.mjs refuses anything with history).
import path from 'node:path';
import { navTimeoutMs, tenantId } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { purgeProject, purgeTenantProfile } from '../lib/local-db.mjs';
import { goto } from '../lib/page.mjs';
import { expectCount, expectText, pace } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const fixtures = path.join(import.meta.dirname, '..', 'fixtures');
const invalidWorkbook = path.join(fixtures, 'oasis-crest-garden-villas-invalid.xlsx');
const validWorkbook = path.join(fixtures, 'oasis-crest-garden-villas.xlsx');
const projectName = 'Oasis Crest Garden Villas';
const tenantEmails = ['omar.almansoori@oasiscrest.example', 'leila.farouk@oasiscrest.example'];
const listPage = '/en/dashboard/properties';

const importDialog = (page) => page.locator('div')
  .filter({ has: page.getByRole('heading', { name: 'Import Portfolio', exact: true }) })
  .filter({ has: page.getByRole('button', { name: 'Upload & Import', exact: true }) }).last();
const resultPanel = (page) => page.locator('div')
  .filter({ has: page.getByRole('button', { name: 'Close', exact: true }) })
  .filter({ has: page.getByRole('heading', { level: 2 }) }).last();
/** The count tile for a result label ("Units" → its number). */
const resultCount = async (page, label) => {
  const tile = resultPanel(page).locator('div.text-center').filter({ has: page.getByText(label, { exact: true }) }).first();
  // The tile is the count, then the label (rendered uppercase).
  return (await tile.innerText()).trim().split(/\s+/)[0];
};

async function chooseAndUpload(page, file) {
  const dialog = importDialog(page);
  await pointAt(dialog.getByText('Drop .xlsx file here or click to browse', { exact: true }));
  await dialog.locator('input[type="file"]').setInputFiles(file);
  await dialog.getByText(path.basename(file), { exact: true }).waitFor({ state: 'visible' });
  await pace(page, 2500);
  await dialog.getByRole('button', { name: 'Upload & Import', exact: true }).click();
}

const scenes = [
  // Weights are the seconds of narration each scene covers, and paces put
  // each action on its cue (measured from the synthesized subtitles).
  {
    ...roleRouteScene('tenantAdmin', listPage, 'Import Portfolio',
      'One workbook, built from the current template: Properties, Units, Tenants and Tenancy Contracts sheets.', { weight: 31.9 }),
    run: async (page) => {
      purgeProject(tenantId, projectName);
      for (const email of tenantEmails) purgeTenantProfile(tenantId, email);
      await goto(page, listPage);
      await page.getByRole('heading', { name: 'Projects', exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 7000);
      await page.getByTestId('properties-more').click();
      await page.getByTestId('properties-import-portfolio').click();
      await importDialog(page).waitFor({ state: 'visible' });
      await pace(page, 3000);
      const download = page.waitForEvent('download', { timeout: navTimeoutMs });
      await importDialog(page).getByRole('button', { name: 'Download Template', exact: true }).click();
      const file = await download;
      if (file.suggestedFilename() !== 'portfolio-import-template.xlsx') throw new Error(`Unexpected template: ${file.suggestedFilename()}`);
      await file.cancel();
      await expectText(importDialog(page), 'Sheets: Properties, Units, Tenants, Tenancy Contracts', 'Sheet hint');
      await pace(page, 3500);
      await pointAt(importDialog(page).getByText('Sheets: Properties, Units, Tenants, Tenancy Contracts', { exact: true }));
    },
  },
  stepScene('Validation comes first',
    'A workbook with mistakes is refused as a whole: each error names its sheet, row, column and cause.',
    async (page) => {
      await pace(page, 3000);
      await chooseAndUpload(page, invalidWorkbook);
      await page.getByRole('heading', { name: 'Validation Failed', exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(resultPanel(page), 'Invalid unit type: 3 BED', 'Unit type error');
      await expectText(resultPanel(page), "Property 'Oasis Crest Garden Vilas' not found in Properties sheet", 'Project name error');
      const projects = await (await page.request.get('/api/proxy/v1/properties')).json();
      if (JSON.stringify(projects).includes(projectName)) throw new Error('A failed validation created the project.');
      await restPointer(page, 1700, 620);
      await pointAt(resultPanel(page).getByText('Invalid unit type: 3 BED', { exact: false }));
    }, { weight: 24.7 }),
  stepScene('Upload the corrected workbook',
    'The corrected workbook imports: the counts show what was created.',
    async (page) => {
      await pace(page, 3000);
      await resultPanel(page).getByRole('button', { name: 'Try Again', exact: true }).click();
      await importDialog(page).waitFor({ state: 'visible' });
      await chooseAndUpload(page, validWorkbook);
      await page.getByRole('heading', { name: 'Import Successful', exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      const expected = { Properties: '1', Buildings: '2', Units: '4', Tenants: '2', 'Tenancy Contracts': '0' };
      for (const [label, count] of Object.entries(expected)) {
        const actual = await resultCount(page, label);
        if (actual !== count) throw new Error(`Imported ${label}: expected ${count}, found ${actual}.`);
      }
      await restPointer(page, 1700, 620);
    }, { weight: 17.9 }),
  stepScene('Check what was created',
    'Open the new project: its buildings and units match the workbook.',
    async (page) => {
      await pace(page, 1500);
      await resultPanel(page).getByRole('button', { name: 'Close', exact: true }).click();
      const row = page.getByRole('row').filter({ hasText: projectName });
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pointAt(row.getByRole('cell').first());
      await pace(page, 3000);
      await row.getByText('Manage', { exact: true }).click();
      await page.getByRole('heading', { name: projectName }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByRole('button', { name: 'Units', exact: true }).click();
      for (const number of ['GV-101', 'GV-102', 'GV-201', 'GV-202']) {
        await page.getByRole('row').filter({ hasText: number }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      }
      await expectText(page.getByRole('row').filter({ hasText: 'GV-102' }), 'Villa Court 1', 'GV-102 building');
      await expectText(page.getByRole('row').filter({ hasText: 'GV-201' }), 'Villa Court 2', 'GV-201 building');
      await restPointer(page, 1700, 760);
    }, { weight: 12.5 }),
  roleRouteScene('tenantAdmin', '/en/dashboard/renters', 'The imported tenants',
    'Imported tenants join the Tenants list, ready for tenancy contracts.', {
    weight: 17.0,
    afterNavigation: async (page) => {
      for (const name of ['Omar Al Mansoori', 'Leila Farouk']) {
        await page.getByRole('row').filter({ hasText: name }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      }
      await expectCount(page.getByRole('row').filter({ hasText: 'Omar Al Mansoori' }), 1, 'Omar Al Mansoori rows');
      // No portal invitation for imported tenants: no Resend invite on their rows.
      for (const name of ['Omar Al Mansoori', 'Leila Farouk']) {
        await expectCount(page.getByRole('row').filter({ hasText: name }).getByText('Resend invite'), 0, `${name} invite actions`);
      }
      await pointAt(page.getByRole('row').filter({ hasText: 'Leila Farouk' }).getByRole('link').first());
    },
  }),
];

export default { role: 'tenantAdmin', scenes };

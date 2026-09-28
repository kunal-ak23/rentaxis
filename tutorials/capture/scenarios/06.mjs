// Tutorial 06 — Create a project and property portfolio. The Company Admin
// searches for the planned name, creates the project "Oasis Crest Creek
// Gardens" (bilingual name, emirate, type, address, Makani number, fixed
// expenses), adds its first property (unit CG-101), opens the project and its
// Units tab, and compares the Cards and Table views. The app cannot delete a
// project or a unit, so a project an earlier take created is purged from the
// local tutorial database before the first frame (lib/local-db.mjs refuses
// anything with history).
import { navTimeoutMs, tenantId } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { purgeProject } from '../lib/local-db.mjs';
import { fieldByLabel as field, modalForm as dialog } from '../lib/forms.mjs';
import { creekGardens } from '../lib/portfolio.mjs';
import { goto } from '../lib/page.mjs';
import { expectCount, expectText, pace } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const listPage = '/en/dashboard/properties';
const project = { ...creekGardens, makani: creekGardens.makaniNumber, fixedExpenses: String(creekGardens.fixedExpenses) };
const unit = { number: 'CG-101', type: '2 BHK', size: '1240', rent: '98000' };

const search = (page) => page.getByPlaceholder('Search...');
const projectRow = (page) => page.getByRole('row').filter({ hasText: project.nameEn });

const scenes = [
  // Weights are the seconds of narration each scene covers, and paces put
  // each action on its cue (measured from the rendered subtitles).
  {
    ...roleRouteScene('tenantAdmin', listPage, 'Properties & Units',
      'Each project is a site: its units, vacancies, occupancy and revenue. Search before you create, to avoid duplicates.', { weight: 22.2 }),
    run: async (page) => {
      purgeProject(tenantId, project.nameEn);
      await goto(page, listPage);
      await page.getByRole('heading', { name: 'Projects', exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 13500);
      await search(page).fill('Creek Gardens');
      await page.waitForTimeout(1200);
      await expectCount(projectRow(page), 0, `${project.nameEn} rows`);
      await restPointer(page, 1700, 620);
    },
  },
  stepScene('Add Project',
    'A project groups one site: bilingual name, emirate, type, address, Makani number and yearly fixed expenses.',
    async (page) => {
      await page.getByTestId('properties-more').click();
      await page.getByTestId('properties-add-project').click();
      const form = dialog(page, 'Add Project');
      await form.waitFor({ state: 'visible' });
      await pace(page, 3500);
      await field(form, 'Name (English)').fill(project.nameEn);
      await field(form, 'Name (Arabic)').fill(project.nameAr);
      await pace(page, 3000);
      await field(form, 'Emirate').selectOption({ label: 'Dubai' });
      await field(form, 'Type').selectOption({ label: 'Residential' });
      await pace(page, 2000);
      await field(form, 'Address').fill(project.address);
      await field(form, 'Makani Number').fill(project.makani);
      await pace(page, 4500);
      await field(form, 'Fixed Expenses').fill(project.fixedExpenses);
      await pace(page, 2000);
    }, { weight: 24.6 }),
  stepScene('Create',
    'The project joins the list, with no units yet.',
    async (page) => {
      await dialog(page, 'Add Project').getByRole('button', { name: 'Create', exact: true }).click();
      await projectRow(page).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(projectRow(page), 'RESIDENTIAL', 'Project type');
      await expectText(projectRow(page), 'Dubai', 'Project location');
      await restPointer(page, 1700, 620);
      await pointAt(projectRow(page).getByRole('cell').first());
    }, { weight: 4.4 }),
  stepScene('Add Property',
    'A property is one rentable unit in a project: unit number, type, size, status and expected yearly rent.',
    async (page) => {
      await pace(page, 1500);
      await page.getByRole('button', { name: 'Add Property', exact: true }).click();
      const form = dialog(page, 'Add Property');
      await form.waitFor({ state: 'visible' });
      await pace(page, 4500);
      await field(form, 'Select Project').selectOption({ label: project.nameEn });
      await field(form, 'Unit Number').fill(unit.number);
      await field(form, 'Type').selectOption({ label: unit.type });
      await pace(page, 1500);
      await field(form, 'Size (SqFt)').fill(unit.size);
      if ((await field(form, 'Status').inputValue()) !== 'VACANT') throw new Error('A new property should start Vacant.');
      await pace(page, 2500);
      await field(form, 'Expected Rent (AED/year)').fill(unit.rent);
      await pace(page, 2500);
      await form.getByRole('button', { name: 'Create', exact: true }).click();
      await form.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      // The row's UNITS and VACANT columns both count the new unit.
      await page.waitForFunction((name) => {
        const row = [...document.querySelectorAll('tr')].find((r) => r.textContent.includes(name));
        return row && [...row.querySelectorAll('td')].map((td) => td.textContent.trim()).filter((t) => t === '1').length >= 2;
      }, project.nameEn, { timeout: navTimeoutMs });
      await restPointer(page, 1700, 620);
    }, { weight: 24.6 }),
  stepScene('Open the project',
    'Manage opens the project: its header, and tabs for buildings, units, contracts, amenities, parking and ledger accounts.',
    async (page) => {
      await pointAt(projectRow(page).getByText('Manage', { exact: true }));
      await pace(page, 1200);
      await projectRow(page).getByText('Manage', { exact: true }).click();
      await page.waitForURL(/\/dashboard\/properties\/[0-9a-f-]{36}/, { timeout: navTimeoutMs });
      await page.getByRole('heading', { name: project.nameEn }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.locator('main'), 'Creek Promenade', 'Project address');
      await pace(page, 8500);
      await page.getByRole('button', { name: 'Units', exact: true }).click();
      const unitRow = page.getByRole('row').filter({ hasText: unit.number });
      await unitRow.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(unitRow, 'VACANT', 'Unit status');
      await expectText(unitRow, '98,000', 'Unit rent');
      await restPointer(page, 1700, 700);
    }, { weight: 17.0 }),
  stepScene('Cards and Table',
    'Cards for scanning occupancy at a glance; Table for comparing a larger portfolio.',
    async (page) => {
      await page.getByRole('button', { name: 'Back to Projects', exact: true }).click();
      await page.getByRole('heading', { name: 'Projects', exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await search(page).fill('');
      await page.getByRole('button', { name: 'Cards', exact: true }).click();
      const card = page.locator('div').filter({ has: page.getByText(project.nameEn, { exact: true }) })
        .filter({ has: page.getByText('Manage Property', { exact: true }) }).last();
      await card.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(card, '0%', 'Card occupancy');
      await pointAt(card.getByText(project.nameEn, { exact: true }));
      await pace(page, 5500);
      await page.getByRole('button', { name: 'Table', exact: true }).click();
      await projectRow(page).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1700, 620);
    }, { weight: 21.8 }),
];

export default { role: 'tenantAdmin', scenes };

// Tutorial 07 — Buildings, units, contacts, amenities, and parking. Inside the
// project tutorial 06 creates (Oasis Crest Creek Gardens, rebuilt off camera
// to 06's end state so this can be retaken alone), the Company Admin adds a
// building, a unit in it, a security contact, a bookable pool scoped to the
// building and a covered parking spot, then deactivates the pool to show that
// retiring keeps history, where a contact is simply deleted.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { fieldByLabel as field, modalForm } from '../lib/forms.mjs';
import { goto } from '../lib/page.mjs';
import { creekGardens, resetCreekGardens } from '../lib/portfolio.mjs';
import { expectText, pace } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const building = { nameEn: 'Creek Gardens — Block A', nameAr: 'حدائق الخور — المبنى أ', floors: '14' };
const unit = { number: 'A-1402', type: '1 BHK', size: '820', rent: '74000' };
const contact = { category: 'Security', name: 'Creek Gardens Security Desk', phone: '+971 4 000 0112', notes: 'Gate 1, staffed around the clock' };
const amenity = { nameEn: "Residents' Pool", nameAr: 'مسبح السكان', description: 'Rooftop pool on Block A, open 7 am to 10 pm.' };
const spot = { number: 'P1-014', level: 'P1' };

let projectPath = '/en/dashboard/properties';
const tab = (page, name) => page.getByRole('button', { name, exact: true });
const rowWith = (page, text) => page.getByRole('row').filter({ hasText: text });

const scenes = [
  // Weights are the seconds of narration each scene covers, and paces put
  // each action on its cue (measured from the synthesized subtitles).
  {
    ...roleRouteScene('tenantAdmin', projectPath, 'Inside a project',
      'Buildings, units, key contacts, amenities and parking all belong to one project.', { weight: 18.8 }),
    run: async (page) => {
      // Off camera, before the first page load (the API needs no page).
      projectPath = `/en/dashboard/properties/${await resetCreekGardens(page)}`;
      await goto(page, '/en/dashboard/properties');
      const row = page.getByRole('row').filter({ hasText: creekGardens.nameEn });
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 6000);
      await pointAt(row.getByText('Manage', { exact: true }));
      await pace(page, 2500);
      await row.getByText('Manage', { exact: true }).click();
      await page.waitForURL(`**${projectPath}`, { timeout: navTimeoutMs });
      await page.getByRole('heading', { name: creekGardens.nameEn }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByText('Key Contacts', { exact: false }).first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1780, 700);
      await pace(page, 4500);
      await pointAt(page.getByText('No key contacts added yet', { exact: false }));
      await pace(page, 2500);
      await pointAt(tab(page, 'Buildings'));
    },
  },
  stepScene('Add Building',
    'A building has an English and an Arabic name and a floor count.',
    async (page) => {
      await tab(page, 'Buildings').click();
      await page.getByRole('button', { name: 'Add Building', exact: true }).click();
      const form = page.locator('main form').first(); // an inline form, not a modal
      await form.waitFor({ state: 'visible' });
      await field(form, 'Name (EN)').fill(building.nameEn);
      await field(form, 'Name (AR)').fill(building.nameAr);
      await field(form, 'Floors').fill(building.floors);
      await pace(page, 3500);
      await form.getByRole('button', { name: 'Save', exact: true }).click();
      await form.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      const card = page.locator('main').getByText(building.nameEn, { exact: true });
      await card.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.locator('main'), `${building.floors} Floors`, 'Building floors');
      await restPointer(page, 1780, 700);
    }, { weight: 13.2 }),
  stepScene('Add Unit',
    'A unit in the building: number, type, size and expected yearly rent. It starts Vacant.',
    async (page) => {
      await tab(page, 'Units').click();
      await page.getByRole('button', { name: 'Add Unit', exact: true }).click();
      const form = page.locator('main form').first(); // an inline form, not a modal
      await form.waitFor({ state: 'visible' });
      await field(form, 'Unit Number').fill(unit.number);
      await field(form, 'Type').selectOption({ label: unit.type });
      await pace(page, 1000);
      await field(form, 'Size (SqFt)').fill(unit.size);
      await field(form, 'Expected Rent (AED/year)').fill(unit.rent);
      await pace(page, 1500);
      await field(form, 'Building').selectOption({ label: building.nameEn });
      await pace(page, 1500);
      await form.getByRole('button', { name: 'Save Unit', exact: true }).click();
      await form.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      await rowWith(page, unit.number).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(rowWith(page, unit.number), building.nameEn, 'Unit building');
      await expectText(rowWith(page, unit.number), 'VACANT', 'Unit status');
      await restPointer(page, 1780, 760);
    }, { weight: 17.2 }),
  stepScene('Add Contact',
    'Key contacts are the numbers staff need on site: security, maintenance, clinics. They are not sign-ins.',
    async (page) => {
      await pace(page, 5500);
      await tab(page, 'Overview').click();
      await page.getByRole('button', { name: 'Add Contact', exact: true }).click();
      const form = modalForm(page, 'Add Contact');
      await form.waitFor({ state: 'visible' });
      await field(form, 'Category').selectOption({ label: contact.category });
      await pace(page, 1500);
      await field(form, 'Name').fill(contact.name);
      await field(form, 'Phone').fill(contact.phone);
      await field(form, 'Notes').fill(contact.notes);
      await pace(page, 1500);
      await form.getByRole('button', { name: 'Save Contact', exact: true }).click();
      await form.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      await page.locator('main').getByText(contact.name, { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.locator('main'), contact.notes, 'Contact notes');
      await restPointer(page, 1780, 900);
    }, { weight: 19.2 }),
  stepScene('Add Amenity',
    'A bookable amenity, free of charge, limited to Block A. Tenants can request it from the portal.',
    async (page) => {
      await tab(page, 'Amenities').click();
      await page.getByRole('button', { name: 'Add Amenity', exact: true }).click();
      const form = modalForm(page, 'Add Amenity');
      await form.waitFor({ state: 'visible' });
      await field(form, 'Name (EN)').fill(amenity.nameEn);
      await field(form, 'Name (AR)').fill(amenity.nameAr);
      await field(form, 'Description').fill(amenity.description);
      if (!(await form.getByRole('checkbox').first().isChecked())) throw new Error('A new amenity should start Bookable.');
      await pointAt(form.getByRole('checkbox').first());
      await pace(page, 3000);
      await form.getByRole('button', { name: building.nameEn, exact: true }).click();
      await pace(page, 2500);
      await form.getByRole('button', { name: 'Save', exact: true }).click();
      await form.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      const row = rowWith(page, amenity.nameEn);
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(row, 'BOOKABLE', 'Amenity bookable');
      await expectText(row, 'ACTIVE', 'Amenity status');
      await expectText(row, building.nameEn, 'Amenity towers');
      await restPointer(page, 1780, 760);
    }, { weight: 18.8 }),
  stepScene('Add Spot',
    'A parking spot: number, level, covered or not, and the buildings it serves.',
    async (page) => {
      await tab(page, 'Parking').click();
      await page.getByRole('button', { name: 'Add Spot', exact: true }).click();
      const form = modalForm(page, 'Add Spot');
      await form.waitFor({ state: 'visible' });
      await field(form, 'Spot Number').fill(spot.number);
      await field(form, 'Level').fill(spot.level);
      await pace(page, 1500);
      await form.getByRole('checkbox').first().check();
      await form.getByRole('button', { name: building.nameEn, exact: true }).click();
      await pace(page, 1500);
      await form.getByRole('button', { name: 'Save', exact: true }).click();
      await form.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      const row = rowWith(page, spot.number);
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(row, 'Yes', 'Spot covered');
      await expectText(row, 'ACTIVE', 'Spot status');
      await restPointer(page, 1780, 760);
    }, { weight: 12.4 }),
  stepScene('Retire, don’t delete',
    'Deactivate an amenity or spot that is no longer offered: it stays on record with its bookings.',
    async (page) => {
      await tab(page, 'Amenities').click();
      const row = rowWith(page, amenity.nameEn);
      await row.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await pace(page, 6500);
      await row.getByRole('button', { name: 'Deactivate' }).click();
      const confirm = page.getByRole('dialog');
      await confirm.waitFor({ state: 'visible' });
      await pace(page, 1500);
      await confirm.getByRole('button', { name: 'Deactivate' }).click();
      await confirm.waitFor({ state: 'hidden', timeout: navTimeoutMs });
      await row.filter({ hasText: 'INACTIVE' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1780, 760);
      await pointAt(row.getByText('INACTIVE', { exact: true }));
    }, { weight: 28.3 }),
];

export default { role: 'tenantAdmin', scenes };

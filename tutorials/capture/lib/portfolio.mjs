// The project tutorials 06 and 07 work in. 06 creates it on camera; 07 starts
// from 06's end state, rebuilt off camera so either can be retaken alone.
import { tenantId } from './context.mjs';
import { purgeProject } from './local-db.mjs';

export const creekGardens = {
  nameEn: 'Oasis Crest Creek Gardens',
  nameAr: 'حدائق القمة على الخور',
  emirate: 'DUBAI',
  type: 'RESIDENTIAL',
  address: 'Creek Promenade, Dubai Creek Harbour, Dubai',
  makaniNumber: '31245-78906',
  fixedExpenses: 48000,
};
export const creekGardensFirstUnit = { unitNumber: 'CG-101', type: 'BHK2', sizeSqft: 1240, expectedRent: 98000 };

/** Purge the project, then create it and its first unit through the app's API (tutorial 06's end state). */
export async function resetCreekGardens(page) {
  purgeProject(tenantId, creekGardens.nameEn);
  const created = await page.request.post('/api/proxy/v1/properties', { data: creekGardens });
  if (!created.ok()) throw new Error(`Creating ${creekGardens.nameEn} failed: ${created.status()}`);
  const { id } = await created.json();
  const unit = await page.request.post('/api/proxy/v1/units', {
    data: { property: { id }, ...creekGardensFirstUnit, actualRent: 0, status: 'VACANT' },
  });
  if (!unit.ok()) throw new Error(`Creating ${creekGardensFirstUnit.unitNumber} failed: ${unit.status()}`);
  return id;
}

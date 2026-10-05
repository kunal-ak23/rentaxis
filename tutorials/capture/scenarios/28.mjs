// Tutorial 28 — Promotions (Oasis Crest Properties), the Company Admin Noura
// Al Suwaidi. Adds a fictional partner business (Al Waha Fitness Studio,
// Fitness) → a coupon ad for it (OASISFIT, today + 30 days, priority 6,
// Residence Tower only) → the ad is Live with 0 views / 0 taps → filter the
// Ads list by the business. Editing is left out: the editor reopens the start
// date a day early (tutorials/bugs/28-ad-start-date-shifts-on-edit.md).
//
// Artwork and the business logo are URL fields (no upload), so the ad runs on
// its card colour. Views/Taps come from the mobile app and start at zero.
// Snapshot pre28 = pre25 (nothing else needed).
// Weights are the seconds of narration each scene covers (tutorials/work/acct/tts.sh).
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { isoDate } from '../lib/fixtures.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene } from '../lib/scenes.mjs';

const BIZ = 'Al Waha Fitness Studio';
const AD = 'First class free for residents';
const inDays = (n) => { const d = new Date(); d.setDate(d.getDate() + n); return isoDate(d); };
const centre = (locator) => locator.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
const main = (page) => page.locator('main');

const scenes = [
  roleRouteScene('tenantAdmin', '/en/dashboard/promotions', 'Partner business and coupon ad',
    'Add the partner, then a coupon ad shown only to the Residence Tower.', {
    weight: 93.58,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const m = main(page);
      const addAd = m.getByRole('button', { name: 'Add ad', exact: true });
      await addAd.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 640);
      await at(6.4);
      await pointAt(m.getByRole('heading', { name: 'Promotions' }).first());
      await at(8.6);
      await m.getByRole('tab', { name: 'Businesses', exact: true }).click();
      const addBiz = m.getByRole('button', { name: 'Add business', exact: true });
      await addBiz.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(9.9);
      await addBiz.click();
      await m.getByLabel('Name (English)').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(10.8);
      await m.getByLabel('Name (English)').fill(BIZ);
      await m.getByLabel('Name (Arabic)').fill('استوديو الواحة للياقة');
      await at(13.6);
      await m.getByLabel('Category').selectOption({ label: 'Fitness' });
      await at(14.6);
      await m.getByLabel('Phone').fill('+971500000031');
      await m.getByLabel('WhatsApp').fill('+971500000031');
      await at(16.8);
      await pointAt(m.getByText('Ads for this business can only link to these domains', { exact: false }));
      await at(20.6);
      await pointAt(m.getByLabel('Active'));
      await at(21.5);
      await m.getByRole('button', { name: 'Save', exact: true }).click();
      await expectText(m.locator('tr').filter({ hasText: BIZ }), 'Fitness', 'Business saved');
      await at(22.6);
      await m.getByRole('tab', { name: 'Ads', exact: true }).click();
      await addAd.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(23.8);
      await addAd.click();
      const title = m.getByLabel('Title (English)');
      await title.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(25.5);
      await m.getByLabel('Business', { exact: true }).selectOption({ label: BIZ });
      await at(26.9);
      await title.fill(AD);
      await m.getByLabel('Title (Arabic)').fill('الحصة الأولى مجانية للسكان');
      await at(29.2);
      await m.getByLabel('Eyebrow (English)').fill('New in Residence Tower');
      await m.getByLabel('Eyebrow (Arabic)').fill('جديد في برج السكن');
      await at(31.2);
      await m.getByLabel('Card colour').fill('#DCEFE4');
      await at(32.8);
      const preview = m.getByText('Preview', { exact: true }).first();
      await pointAt(m.getByText(AD, { exact: true }).last());
      await at(35.8);
      const tap = m.getByLabel('What happens on tap');
      await pointAt(tap);
      await at(42.6);
      await tap.selectOption({ label: 'Show coupon code' });
      const code = m.getByLabel('Coupon code');
      await code.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(43.6);
      await code.fill('OASISFIT');
      await at(45.0);
      await m.getByLabel('Terms (English)').fill('One free class per resident. Show the code at reception.');
      await m.getByLabel('Terms (Arabic)').fill('حصة مجانية واحدة لكل ساكن. اعرض الرمز في الاستقبال.');
      await at(47.3);
      const ends = m.getByLabel('Ends');
      await centre(ends);
      await page.waitForTimeout(700);
      await pointAt(ends);
      await at(51.6);
      await m.getByLabel('Starts').fill(inDays(0));
      await ends.fill(inDays(30));
      await at(54.8);
      const priority = m.locator('input[type=range]');
      await priority.fill('6');
      await pointAt(m.getByText('Higher priority means more airtime', { exact: false }));
      await at(57.8);
      const where = m.getByLabel('Where it shows');
      await centre(where);
      await page.waitForTimeout(700);
      await pointAt(where);
      await at(62.8);
      const tower = m.getByLabel('Oasis Crest Residence Tower');
      await centre(tower);
      await at(65.6);
      await tower.check();
      await expectText(m.getByText('Show to', { exact: true }).locator('xpath=..'), 'Oasis Crest Residence Tower', 'Targeted to the Tower');
      await at(68.6);
      await centre(preview);
      await m.getByRole('button', { name: 'Arabic', exact: true }).click();
      await pointAt(m.getByText('الحصة الأولى مجانية للسكان', { exact: true }).last());
      await at(70.4);
      const save = m.getByRole('button', { name: 'Save', exact: true });
      await centre(save);
      await save.click();
      const row = m.locator('tr').filter({ hasText: AD });
      await expectText(row, 'Live', 'Ad is Live');
      await expectText(row, BIZ, 'Ad belongs to the business');
      await at(71.4);
      await pointAt(row.getByText('Live', { exact: true }));
      await at(73.0);
      await pointAt(m.getByText('Views', { exact: true }));
      await at(76.6);
      await pointAt(m.getByText('Taps', { exact: true }));
      await at(80.2);
      await m.getByLabel('Business', { exact: true }).selectOption({ label: BIZ });
      await expectText(m.locator('tbody'), AD, 'Filtered to the business');
      await at(83.2);
      await pointAt(row.getByText(BIZ, { exact: true }));
      await at(86.4);
      await pointAt(row.locator('td').nth(5));
      await at(89.4);
      await pointAt(row.getByText(AD, { exact: true }));
    },
  }),
];

export default { role: 'tenantAdmin', anchored: true, scenes };

// Tutorial 17 — Chart of accounts, property account sets and charge types
// (Palm Ridge Properties, the Accountant Rania Khoury). The chart's tree, the
// property filter (Palm Ridge Residences' own leaves), the property's Ledger
// accounts tab (role -> account), the Property account template and the
// company-wide Default accounts, then Charge types and how each fee is earned.
// Read-only.
//
// Weights are seconds of narration per scene (tutorials/work/acct/tts.sh);
// `at(s)` puts an action on its cue.
import { navTimeoutMs } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { palmProperty } from '../lib/fixtures.mjs';
import { expectText, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const residences = palmProperty('residences');
const coaRow = (page, code) => page.getByTestId('coa-row').filter({ hasText: code }).first();
const defaultRow = (page, role) => page.locator('main tr').filter({ has: page.getByText(role, { exact: true }) }).last();

const scenes = [
  roleRouteScene('accountant', '/en/dashboard/finance/accounts', 'Chart of Accounts',
    'Five types, groups that hold accounts, and leaves where the journals post.', {
    weight: 32.1,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await coaRow(page, '100001').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(coaRow(page, 'A-02-01'), 'GROUP', 'Receivable group');
      await restPointer(page, 1300, 600);
      await at(4.0);
      await pointAt(page.getByText(/^tree$/i).first());
      // Collapse Assets so the five type groups (A, B, C, D, F) sit on one screen, then open it again.
      const assets = page.getByTestId('coa-row').first();
      await at(13.6);
      await assets.locator('button').first().click();
      await coaRow(page, 'Equity').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(15.4);
      await pointAt(coaRow(page, 'Liabilities'));
      await at(16.6);
      await pointAt(coaRow(page, 'Income'));
      await at(17.5);
      await pointAt(coaRow(page, 'Expenses'));
      await at(18.3);
      await pointAt(coaRow(page, 'Equity'));
      await at(19.1);
      await assets.locator('button').first().click();
      await coaRow(page, '100001').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await at(19.9);
      await pointAt(coaRow(page, 'A-02-01').getByText('Rental Receivable A/c', { exact: true }));
      await at(21.6);
      await pointAt(coaRow(page, 'A-02-02').getByText('Bank', { exact: true }));
      await at(23.5);
      await pointAt(coaRow(page, '100001'));
      await at(26.5);
      await pointAt(coaRow(page, '100005'));
    },
  }),
  stepScene('One set of accounts per building',
    'Filtered to Palm Ridge Residences: its own receivable, PDCs, bank, advance rent, deposits and income.',
    async (page) => {
      const at = sceneClock(page);
      await at(0.6);
      await page.locator('main select').nth(1).selectOption(residences);
      await coaRow(page, '100003').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 600);
      await at(5.0);
      await pointAt(coaRow(page, '100001'));
      await at(6.5);
      await pointAt(coaRow(page, '100004'));
      await at(7.8);
      await pointAt(coaRow(page, '100005'));
      await at(9.0);
      await pointAt(coaRow(page, '100002'));
      await at(10.0);
      await pointAt(coaRow(page, '100006'));
      await at(11.0);
      await pointAt(coaRow(page, '100003'));
    }, { weight: 14.4 }),
  roleRouteScene('accountant', `/en/dashboard/properties/${residences}`, 'Ledger accounts',
    'Each role points to one account for this property. Posting rules name roles; the property decides the account.', {
    weight: 17.0,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      await at(1.2);
      await page.getByText('Ledger accounts', { exact: true }).first().click();
      const rows = page.getByTestId('property-account-row');
      await rows.first().waitFor({ state: 'visible', timeout: navTimeoutMs });
      const rr = rows.filter({ hasText: 'RENT RECEIVABLE' }).first();
      await expectText(rr, 'Rent Receivable - Palm Ridge Residences', 'Rent Receivable mapping');
      await restPointer(page, 1300, 600);
      await at(4.8);
      await pointAt(rr);
      await at(6.5);
      await pointAt(rows.filter({ hasText: 'ADVANCE RENT' }).first());
      await at(10.8);
      await pointAt(rr.getByText('Change', { exact: true }));
      await at(13.6);
      await pointAt(rows.filter({ hasText: 'BANK' }).first());
    },
  }),
  roleRouteScene('accountant', '/en/dashboard/finance/account-template', 'Property account template',
    'A name pattern and a parent group per role; a new building gets its accounts from it.', {
    weight: 29.6,
    afterNavigation: async (page) => {
      const at = sceneClock(page);
      const pattern = page.locator('main input[value="Rent Receivable - {property}"]');
      await pattern.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1300, 600);
      await at(3.5);
      await pointAt(page.locator('main input[value="Advance Rent - {property}"]'));
      await at(6.0);
      await pointAt(page.getByText('Use {property} for the property name', { exact: true }));
      await at(9.6);
      await pointAt(page.locator('main tr').filter({ has: page.getByText('ADVANCE RENT', { exact: true }) }).first().locator('td').nth(2));
      await at(12.5);
      await pointAt(pattern);
      await at(17.2);
      await pointAt(page.getByText('Default accounts', { exact: true }));
      await at(21.8);
      await pointAt(defaultRow(page, 'CASH'));
      await at(23.3);
      await pointAt(defaultRow(page, 'OUTPUT VAT'));
      await at(24.7);
      await pointAt(defaultRow(page, 'INPUT VAT'));
      await at(26.2);
      await pointAt(defaultRow(page, 'PDC PAYABLE'));
      await at(27.7);
      await pointAt(defaultRow(page, 'BANK CHARGES'));
    },
  }),
  stepScene('Charge types',
    'Each charge a contract can carry, and how each fee reaches the books.',
    async (page) => {
      const at = sceneClock(page);
      await page.getByTestId('sidebar-charge-types').click();
      await page.getByTestId('charge-types-table').waitFor({ state: 'visible', timeout: navTimeoutMs });
      const rule = async (code, text) => {
        const got = await page.getByTestId(`charge-type-recognition-${code}`).evaluate((s) => s.selectedOptions[0].text);
        if (got !== text) throw new Error(`${code} is earned "${got}", not "${text}"`);
      };
      await rule('PARKING_FEE', 'Over the term, like rent');
      await rule('ADMIN_FEE', 'One-off, when charged');
      await rule('RENEWAL_FEE', 'One-off, when charged');
      await rule('UTILITIES', 'Recovered at cost (not income)');
      await restPointer(page, 1300, 600);
      await at(4.3);
      await pointAt(page.getByTestId('charge-type-RENT'));
      await at(8.0);
      await pointAt(page.getByTestId('charge-type-SECURITY_DEPOSIT'));
      await at(12.0);
      await pointAt(page.getByTestId('charge-type-recognition-PARKING_FEE'));
      await at(16.2);
      await pointAt(page.getByTestId('charge-type-recognition-ADMIN_FEE'));
      await at(18.4);
      await pointAt(page.getByTestId('charge-type-recognition-RENEWAL_FEE'));
      await at(20.6);
      await pointAt(page.getByTestId('charge-type-recognition-UTILITIES'));
      await at(24.4);
      await pointAt(page.getByTestId('charge-types-table'));
    }, { weight: 29.5 }),
];

export default { role: 'accountant', scenes };

import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as path from 'node:path';
import { setActiveTenant, type ProdContext } from '../e2e-prod/helpers/prod-client';

const AUTH = path.join(__dirname, '..', 'e2e-prod', '.auth', 'superadmin.json');
const BASE = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';

/**
 * The below-asking-rent warning, against the deployed bundle. It shipped
 * comparing a monthly figure to an annual one and warned on every lease, so
 * the case that matters is the one that must stay SILENT: a lease at exactly
 * the unit's asking rate.
 *
 * Read-only — fills the form, never saves.
 */
test('the rent warning reads the asking rent as annual', async ({ page }) => {
    const ctx: ProdContext = {
        baseURL: BASE,
        request: await playwrightRequest.newContext({ baseURL: BASE, storageState: AUTH }),
    };
    const tenants = await (await ctx.request.get('/api/proxy/admin/tenants')).json();
    await setActiveTenant(ctx, tenants[0].id);

    const units = await (await ctx.request.get('/api/proxy/v1/units')).json();
    const list = Array.isArray(units) ? units : units.content ?? [];
    const unit = list.find((u: any) => u.status === 'VACANT' && Number(u.expectedRent) > 0);
    test.skip(!unit, 'no vacant unit with an asking rent on production');
    const asking = Number(unit.expectedRent);
    const atRate = Math.round(asking / 12);
    console.log(`  unit ${unit.unitNumber}: asking ${asking}/year → ${atRate}/month at rate`);

    await page.context().addCookies([
        { name: 'active_tenant_id', value: tenants[0].id, domain: new URL(BASE).hostname, path: '/' },
    ]);
    await page.goto('/en/dashboard/leases');
    await page.getByRole('button', { name: /draft lease/i }).first().click();

    // The wizard is not a role="dialog" (issue #176); anchor on its close button.
    const wizard = page
        .locator('div.fixed.inset-0')
        .filter({ has: page.getByRole('button', { name: 'Close wizard' }) })
        .first();
    await expect(wizard).toBeVisible();

    await wizard.getByRole('combobox').nth(0).click();
    await wizard.getByRole('option').filter({ hasText: unit.unitNumber }).first().click();
    await wizard.getByRole('combobox').nth(1).click();
    await wizard.getByRole('option').nth(1).click();
    await wizard.getByRole('button', { name: /^next$/i }).click();

    const rent = wizard.locator('input[type="number"]').nth(0);
    const warning = wizard.getByTestId('below-expected-rent');

    // While we are here: the field must open empty, not on a 0 (#255).
    await expect(rent).toHaveValue('');

    // At the asking rate: silent. This is what was broken.
    await rent.fill(String(atRate));
    await expect(warning).toBeHidden();
    console.log(`  ${atRate}/month → silent, as it should be`);

    // Clearly under: warns, and names the annual figures.
    const under = Math.round(atRate * 0.8);
    await rent.fill(String(under));
    await expect(warning).toBeVisible();
    const text = (await warning.textContent()) ?? '';
    console.log(`  ${under}/month → "${text.trim()}"`);
    expect(text).toContain('a year');
    expect(text).not.toContain('belowExpectedRent');   // catalog miss would render the key
});

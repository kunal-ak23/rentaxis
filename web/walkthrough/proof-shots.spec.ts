import { test, expect, request as playwrightRequest, type Locator, type Page } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { setActiveTenant, type ProdContext } from '../e2e-prod/helpers/prod-client';

/**
 * Screenshot proof, taken against production, for the two client reports:
 * the "0" that had to be typed around, and the below-asking-rent warning.
 *
 * Read-only. Fills the wizard and never saves, so no lease, tenant or unit is
 * created and there is nothing to clean up afterwards.
 */

const AUTH = path.join(__dirname, '..', 'e2e-prod', '.auth', 'superadmin.json');
const BASE = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';
const OUT = path.join(__dirname, 'proof');

fs.mkdirSync(OUT, { recursive: true });

async function shot(target: Locator | Page, name: string) {
    const file = path.join(OUT, `${name}.png`);
    await target.screenshot({ path: file });
    console.log(`  saved ${name}.png`);
}

test('proof: empty number fields and the asking-rent warning', async ({ page }) => {
    const ctx: ProdContext = {
        baseURL: BASE,
        request: await playwrightRequest.newContext({ baseURL: BASE, storageState: AUTH }),
    };
    const tenants = await (await ctx.request.get('/api/proxy/admin/tenants')).json();
    await setActiveTenant(ctx, tenants[0].id);

    const units = await (await ctx.request.get('/api/proxy/v1/units')).json();
    const list = Array.isArray(units) ? units : units.content ?? [];
    const unit = list.find((u: any) => u.status === 'VACANT' && Number(u.expectedRent) > 0);
    expect(unit, 'need a vacant unit with an asking rent').toBeTruthy();
    const asking = Number(unit.expectedRent);
    const atRate = Math.round(asking / 12);
    console.log(`  unit ${unit.unitNumber}: asks ${asking}/year, i.e. ${atRate}/month`);

    await page.context().addCookies([
        { name: 'active_tenant_id', value: tenants[0].id, domain: new URL(BASE).hostname, path: '/' },
    ]);
    // Suppress the first-run tour, which otherwise covers the form.
    await page.addInitScript(() => {
        try { window.localStorage.setItem('rentaxis_tours_completed', JSON.stringify(['admin-onboarding'])); } catch {}
    });

    await page.goto('/en/dashboard/leases');
    await page.getByRole('button', { name: /draft (lease|tenancy contract)/i }).first().click();

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
    const deposit = wizard.locator('input[type="number"]').nth(1);
    const warning = wizard.getByTestId('below-expected-rent');

    // ---- 1. the fields open empty, not on a 0 -------------------------------
    await expect(rent).toHaveValue('');
    await expect(deposit).toHaveValue('');
    await shot(wizard, '1-fields-open-empty');

    // ---- 2. typing gives the number typed, not 0 + the number ---------------
    await rent.click();
    await rent.type('5000', { delay: 120 });
    await expect(rent).toHaveValue('5000');
    await deposit.click();
    await deposit.type('5000', { delay: 120 });
    await expect(deposit).toHaveValue('5000');
    await shot(wizard, '2-typed-cleanly-no-leading-zero');
    console.log(`  typed 5000 → field reads "${await rent.inputValue()}"`);

    // ---- 3. at the unit's asking rate: no warning ---------------------------
    await rent.fill(String(atRate));
    await expect(warning).toBeHidden();
    await shot(wizard, '3-at-asking-rent-no-warning');

    // ---- 4. below it: warning, naming both periods --------------------------
    const under = Math.round(atRate * 0.8);
    await rent.fill(String(under));
    await expect(warning).toBeVisible();
    console.log(`  ${under}/month → "${(await warning.textContent())?.trim()}"`);
    await shot(wizard, '4-below-asking-rent-warns');

    // ---- 5. the lease that used to be refused outright ----------------------
    // 5,000/month with a 5,000 deposit over 6 cheques — the client's report.
    await rent.fill('5000');
    const start = new Date();
    const end = new Date(start);
    end.setFullYear(end.getFullYear() + 1);
    end.setDate(end.getDate() - 1);
    const iso = (d: Date) => d.toISOString().slice(0, 10);
    await wizard.locator('input[type="date"]').nth(0).fill(iso(start));
    await wizard.locator('input[type="date"]').nth(1).fill(iso(end));
    await wizard.getByRole('button', { name: /^next$/i }).click();   // charges
    await wizard.getByRole('button', { name: /^next$/i }).click();   // payment plan

    const installments = wizard.locator('input[type="number"]').nth(0);
    await installments.fill('6');
    await expect(wizard.getByText(/exceeding the deposit/i)).toBeHidden();
    await expect(wizard.getByText(/cannot distribute rent/i)).toBeHidden();
    // The preview is debounced; wait for the rows rather than a fixed sleep.
    await expect(wizard.getByText(/installment preview/i)).toBeVisible();
    await expect(wizard.locator('table tbody tr').first()).toBeVisible({ timeout: 30_000 });
    await shot(wizard, '5-six-cheques-with-deposit-accepted');

    const rows = await wizard.locator('table tbody tr').count();
    console.log(`  6 cheques with a 5,000 deposit → preview rendered ${rows} rows, no error`);
});

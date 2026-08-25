/**
 * 13 — Tenant administration and finance configuration.
 * Covers the settings and finance surfaces that feed rent collection,
 * bookkeeping, staffing, and property banking.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'fs';
import * as path from 'path';
import { api, loginAsNextAuth, setActiveTenant } from '../helpers/prod-client';

const CONTEXT_FILE = path.join(__dirname, '..', '.test-context.json');

test('tenant admin configures and browses finance, staffing, banking, and vendors', async ({ browser }) => {
  const ctx = JSON.parse(fs.readFileSync(CONTEXT_FILE, 'utf8'));
  expect(ctx.property?.id, '01-provision must run first').toBeTruthy();

  const taCtx = await loginAsNextAuth(ctx.baseURL, ctx.adminEmail, ctx.adminPassword);
  await setActiveTenant(taCtx, ctx.tenant.id);

  const fines = await api.updateFineSettings(taCtx, {
    bounceAmount: 500,
    signatureMismatchAmount: 350,
    accountClosedAmount: 750,
    graceDays: 2,
    perDayRate: 25,
  });
  expect(fines.bounceAmount).toBe(500);
  expect((await api.getFineSettings(taCtx)).perDayRate).toBe(25);

  const rentSettings = await api.saveRentSettings(taCtx, ctx.property.id, {
    dueDayOfMonth: 5,
    gracePeriodDays: 3,
    penaltyType: 'FIXED_PER_DAY',
    penaltyAmount: 200,
    onlinePaymentEnabled: false,
  });
  expect(rentSettings.propertyId).toBe(ctx.property.id);
  expect((await api.getRentSettings(taCtx, ctx.property.id)).dueDayOfMonth).toBe(5);

  const seededAccounts = await api.seedAccounts(taCtx);
  const accounts = seededAccounts.length >= 2 ? seededAccounts : await api.getAccounts(taCtx);
  expect(accounts.length).toBeGreaterThanOrEqual(2);

  const mapping = await api.saveAccountMapping(taCtx, {
    transactionNature: 'SALARY_PAYMENT',
    debitAccountId: accounts[0].id,
    creditAccountId: accounts[1].id,
  });
  expect(mapping.transactionNature).toBe('SALARY_PAYMENT');
  expect((await api.getAccountMappings(taCtx)).some((item) => item.id === mapping.id)).toBeTruthy();

  const staff = await api.createStaff(taCtx, {
    propertyId: ctx.property.id,
    nameEn: `TEST-Facilities Coordinator ${ctx.runSuffix}`,
    employeeId: `TEST-${ctx.runSuffix}`,
  });
  expect(staff.active).toBeTruthy();
  const updatedStaff = await api.updateStaff(taCtx, staff.id, {
    nameEn: `TEST-Senior Coordinator ${ctx.runSuffix}`,
    propertyId: ctx.property.id,
    active: false,
  });
  expect(updatedStaff.active).toBeFalsy();
  expect((await api.getStaffByProperty(taCtx, ctx.property.id)).some((item) => item.id === staff.id)).toBeTruthy();

  const bank = await api.createBankAccount(taCtx, {
    propertyId: ctx.property.id,
    bankName: 'TEST-E2E Bank',
    accountNumber: `TEST${ctx.runSuffix}`,
  });
  expect(bank.isDefault).toBeTruthy();
  const updatedBank = await api.updateBankAccount(taCtx, bank.id, {
    propertyId: ctx.property.id,
    bankName: 'TEST-E2E Bank Updated',
    accountNumber: `TEST${ctx.runSuffix}`,
  });
  expect(updatedBank.branchName).toBe('Marina Branch');
  expect(updatedBank.isDefault).toBeTruthy();
  expect((await api.getBankAccountsByProperty(taCtx, ctx.property.id)).some((item) => item.id === bank.id)).toBeTruthy();

  const transaction = await api.createFinancialTransaction(taCtx, {
    accountId: accounts[0].id,
    propertyId: ctx.property.id,
    description: `TEST-Manual expense ${ctx.runSuffix}`,
    debit: 125,
    credit: 0,
  });
  expect(transaction.id).toBeTruthy();
  expect(transaction.accountCode).toBeTruthy();

  const transactionList = await taCtx.request.get(
    `/api/proxy/v1/finance/transactions?propertyId=${ctx.property.id}`,
  );
  expect(transactionList.ok()).toBeTruthy();
  const transactions: Array<{ id: string }> = await transactionList.json();
  expect(transactions.some((item) => item.id === transaction.id)).toBeTruthy();

  const browserCtx = await browser.newContext({ baseURL: ctx.baseURL });
  const page = await browserCtx.newPage();
  await page.goto('/en/auth/login');
  await page.locator('#login-email').fill(ctx.adminEmail);
  await page.locator('#login-password').fill(ctx.adminPassword);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await page.waitForURL(/\/dashboard(?!\/renter-portal)/, { timeout: 15_000 });

  await page.goto('/en/dashboard/finance/accounts');
  await expect(page.getByText(accounts[0].code, { exact: true }).first()).toBeVisible();

  await page.goto('/en/dashboard/settings/account-mappings');
  await expect(page.getByRole('heading', { level: 3, name: 'SALARY_PAYMENT' })).toBeVisible();

  await page.goto('/en/dashboard/staff');
  await expect(page.getByText(`TEST-Senior Coordinator ${ctx.runSuffix}`, { exact: true })).toBeVisible();

  await page.goto('/en/dashboard/finance/bank-accounts');
  await expect(page.getByText('TEST-E2E Bank Updated', { exact: true })).toBeVisible();

  await page.goto('/en/dashboard/finance/vendors');
  await expect(page.getByText(`TEST-Vendor ${ctx.runSuffix}`, { exact: true })).toBeVisible();

  await page.goto('/en/dashboard/finance/transactions');
  await expect(page.getByText(`TEST-Manual expense ${ctx.runSuffix}`, { exact: true })).toBeVisible();

  await browserCtx.close();

  await api.deleteStaff(taCtx, staff.id);
  await api.deleteBankAccount(taCtx, bank.id);
  expect((await api.getStaffByProperty(taCtx, ctx.property.id)).some((item) => item.id === staff.id)).toBeFalsy();
  expect((await api.getBankAccountsByProperty(taCtx, ctx.property.id)).some((item) => item.id === bank.id)).toBeFalsy();

  await taCtx.request.dispose();
});

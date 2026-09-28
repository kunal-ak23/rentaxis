// Tutorial 20. Default role for scenes that do not name one: superadmin.
import { goto } from '../lib/page.mjs';

const scenes = [
  {
    title: 'Start with the operational dashboard',
    body: 'Use tenant-scoped KPIs as signals, then open the underlying report for detail.',
    run: (page) => goto(page, '/en/dashboard'),
  },
  {
    title: 'Open Financial Reports',
    body: 'Reports can be generated at organisation, property, or unit level.',
    run: (page) => goto(page, '/en/dashboard/finance/reports'),
  },
  {
    title: 'Profit & Loss',
    body: 'Review income and expenses for the selected period and reporting scope.',
    run: async (page) => {
      await goto(page, '/en/dashboard/finance/reports');
      await page.getByRole('button', { name: 'Profit & Loss', exact: true }).click();
      await page.waitForTimeout(250);
    },
  },
  {
    title: 'Balance Sheet',
    body: 'Compare assets, liabilities, and equity at the chosen reporting date.',
    run: async (page) => {
      await goto(page, '/en/dashboard/finance/reports');
      await page.getByRole('button', { name: 'Balance Sheet', exact: true }).click();
      await page.waitForTimeout(250);
    },
  },
  {
    title: 'Trial Balance',
    body: 'Use the trial balance to review debit and credit totals before deeper analysis.',
    run: async (page) => {
      await goto(page, '/en/dashboard/finance/reports');
      await page.getByRole('button', { name: 'Trial Balance', exact: true }).click();
      await page.waitForTimeout(250);
    },
  },
  {
    title: 'Aging Report',
    body: 'Group overdue receivables into aging buckets so collection priorities are visible.',
    run: async (page) => {
      await goto(page, '/en/dashboard/finance/reports');
      await page.getByRole('button', { name: 'Aging Report', exact: true }).click();
      await page.waitForTimeout(250);
    },
  },
  {
    title: 'VAT Return',
    body: 'Review VAT output and input figures for the configured period.',
    run: async (page) => {
      await goto(page, '/en/dashboard/finance/reports');
      await page.getByRole('button', { name: 'VAT Return', exact: true }).click();
      await page.waitForTimeout(250);
    },
  },
  {
    title: 'Ticket Reporting',
    body: 'Use ticket reporting to understand workload, status, and operational trends.',
    run: async (page) => {
      await goto(page, '/en/dashboard/finance/reports');
      await page.getByRole('button', { name: 'Tickets', exact: true }).click();
      await page.waitForTimeout(250);
    },
  },
  {
    title: 'Choose the correct scope',
    body: 'Confirm organisation or property scope and the date range before generating a report.',
    run: async (page) => {
      await goto(page, '/en/dashboard/finance/reports');
      await page.getByRole('button', { name: 'Profit & Loss', exact: true }).click();
      await page.getByRole('button', { name: 'Org', exact: true }).click();
      await page.getByRole('button', { name: 'Generate Report', exact: true }).click();
      await page.waitForTimeout(1000);
    },
  },
];

export default { role: 'superadmin', scenes };

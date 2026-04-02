import type { TourDef } from './types';

export const financeOverviewTour: TourDef = {
  id: 'finance-overview',
  name: 'Finance Module Tour',
  description: 'Understanding accounts, transactions, and reports',
  roles: ['TENANT_ADMIN'],
  steps: [
    {
      id: 'accounts-page',
      target: '[data-tour="accounts-header"]',
      title: 'Chart of Accounts',
      text: 'This is your chart of accounts — the foundation of financial tracking. Accounts are grouped by type: Asset, Liability, Equity, Income, and Expense.',
      position: 'bottom',
      nextRoute: '/dashboard/finance/accounts',
    },
    {
      id: 'transactions-link',
      target: '[data-tour="sidebar-transactions"]',
      title: 'Transactions',
      text: 'View and record financial transactions. Some are created automatically when payment statuses change.',
      position: 'right',
    },
    {
      id: 'reports-link',
      target: '[data-tour="sidebar-reports"]',
      title: 'Financial Reports',
      text: 'Generate reports at the organization, property, or unit level to track your financial performance.',
      position: 'right',
    },
    {
      id: 'payments-link',
      target: '[data-tour="sidebar-payments"]',
      title: 'Payments',
      text: 'Track rent collection across all leases. Update payment statuses as you collect cheques or receive transfers.',
      position: 'right',
    },
  ],
};

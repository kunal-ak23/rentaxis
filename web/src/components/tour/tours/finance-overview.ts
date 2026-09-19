import type { TourDef } from './types';

export const financeOverviewTour: TourDef = {
  id: 'finance-overview',
  name: 'Finance Module Tour',
  description: 'Understanding accounts, journals, and the ledger',
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
      id: 'journals-link',
      target: '[data-tour="sidebar-journals"]',
      title: 'Journal Vouchers',
      text: 'Every entry in the books lives here as a balanced voucher. Most are posted automatically as payments move; you can also post a manual one, and reverse anything that was wrong.',
      position: 'right',
    },
    {
      id: 'general-ledger-link',
      target: '[data-tour="sidebar-general-ledger"]',
      title: 'General Ledger',
      text: 'Read any account back as a running ledger — opening balance, every line that hit it, and the closing balance — filtered by property, unit, lease or renter.',
      position: 'right',
    },
    {
      id: 'cheques-link',
      target: '[data-tour="sidebar-cheques-register"]',
      title: 'Cheque Register',
      text: 'Track rent collection across all leases. Deposit, clear, bounce and replace cheques as they move through the register.',
      position: 'right',
    },
  ],
};

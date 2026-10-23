import type { TourDef } from './types';

export const financeOverviewTour: TourDef = {
  id: 'finance-overview',
  roles: ['TENANT_ADMIN', 'ACCOUNTANT'],
  steps: [
    {
      id: 'accounts-page',
      target: '[data-tour="accounts-header"]',
      position: 'bottom',
      nextRoute: '/dashboard/finance/accounts',
    },
    {
      id: 'journals-link',
      target: '[data-tour="sidebar-journals"]',
      position: 'right',
      // The next targets are Accounting-panel items; keep that panel showing.
      nextRoute: '/dashboard/finance/journals',
    },
    {
      id: 'general-ledger-link',
      target: '[data-tour="sidebar-general-ledger"]',
      position: 'right',
    },
    {
      id: 'cheques-link',
      target: '[data-tour="sidebar-cheques-register"]',
      position: 'right',
    },
  ],
};

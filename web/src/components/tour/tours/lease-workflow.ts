import type { TourDef } from './types';

/**
 * The contract list (/dashboard/leases). Targets are the list's own test ids, so the
 * tour follows the page without extra markup; a step whose target a role does not
 * see (Draft Tenancy Contract is Company Admin only) shows centred instead.
 */
export const leaseWorkflowTour: TourDef = {
  id: 'lease-workflow',
  roles: ['TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER'],
  steps: [
    {
      id: 'contract-pills',
      target: '[data-testid="contract-pills"]',
      position: 'bottom',
      nextRoute: '/dashboard/leases',
    },
    {
      id: 'contract-search',
      target: '[data-testid="lease-search"]',
      position: 'bottom',
    },
    {
      id: 'renewal-season',
      target: '[data-testid="lease-renewal-season"]',
      position: 'bottom',
    },
    {
      id: 'draft-contract',
      target: '[data-testid="lease-new"]',
      position: 'bottom',
    },
  ],
};

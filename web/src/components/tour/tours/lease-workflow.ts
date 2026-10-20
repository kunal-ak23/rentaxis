import type { TourDef } from './types';

/**
 * The contract list (/dashboard/leases). Targets are the list's own test ids, so the
 * tour follows the page without extra markup; a step whose target a role does not
 * see (Draft Tenancy Contract is Company Admin only) shows centred instead.
 */
export const leaseWorkflowTour: TourDef = {
  id: 'lease-workflow',
  name: 'Tenancy Contracts Tour',
  description: 'Find, draft and post tenancy contracts, and work the renewal season',
  roles: ['TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER'],
  steps: [
    {
      id: 'contract-pills',
      target: '[data-testid="contract-pills"]',
      title: 'Contracts by stage',
      text: 'Each pill is a stage, with its count: Draft & to sign, Active, Expiring, Notice and Ended.',
      position: 'bottom',
      nextRoute: '/dashboard/leases',
    },
    {
      id: 'contract-search',
      target: '[data-testid="lease-search"]',
      title: 'Find a contract',
      text: 'Narrow the list to one property or tower, or search it.',
      position: 'bottom',
    },
    {
      id: 'draft-contract',
      target: '[data-testid="lease-new"]',
      title: 'Draft Tenancy Contract',
      text: 'Company Admins draft a contract here: tenant, unit, dates, rent and the number of cheques. Open a draft and press Post Contract to put it on the books.',
      position: 'bottom',
    },
    {
      id: 'renewal-season',
      target: '[data-testid="lease-renewal-season"]',
      title: 'Renewal season',
      text: "The month's expiring contracts in one queue: renew, extend, mark not renewing or bulk terminate.",
      position: 'bottom',
    },
  ],
};

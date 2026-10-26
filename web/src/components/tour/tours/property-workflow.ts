import type { TourDef } from './types';

export const propertyWorkflowTour: TourDef = {
  id: 'property-workflow',
  roles: ['TENANT_ADMIN', 'PROPERTY_MANAGER'],
  steps: [
    {
      id: 'properties-page',
      target: '[data-tour="properties-header"]',
      position: 'bottom',
      nextRoute: '/dashboard/properties',
    },
    {
      id: 'add-property-btn',
      target: '[data-tour="add-property-btn"]',
      position: 'bottom',
    },
    {
      id: 'property-table',
      target: '[data-tour="properties-table"]',
      position: 'top',
    },
  ],
};

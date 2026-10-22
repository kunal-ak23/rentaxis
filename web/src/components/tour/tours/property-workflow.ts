import type { TourDef } from './types';

export const propertyWorkflowTour: TourDef = {
  id: 'property-workflow',
  name: 'Property Setup Tour',
  description: 'Learn how to add properties, units, and buildings',
  roles: ['TENANT_ADMIN', 'PROPERTY_MANAGER'],
  steps: [
    {
      id: 'properties-page',
      target: '[data-tour="properties-header"]',
      title: 'Properties List',
      text: 'This page shows your projects (each building or development) with their units, occupancy and rent.',
      position: 'bottom',
      nextRoute: '/dashboard/properties',
    },
    {
      id: 'add-property-btn',
      target: '[data-tour="add-property-btn"]',
      title: 'Add a Property',
      text: 'Company Admins add here: Add Property adds a unit to a project, and More › Add Project creates the project itself (name, emirate, type, address and Makani number).',
      position: 'bottom',
    },
    {
      id: 'property-table',
      target: '[data-tour="properties-table"]',
      title: 'Property Table',
      text: 'Open a project for its Buildings, Units, Contracts, Amenities, Parking and Accounts tabs.',
      position: 'top',
    },
  ],
};

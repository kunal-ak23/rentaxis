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
      text: 'This page shows all your properties. You can see the name, type, location, and occupancy for each one.',
      position: 'bottom',
      nextRoute: '/dashboard/properties',
    },
    {
      id: 'add-property-btn',
      target: '[data-tour="add-property-btn"]',
      title: 'Add a Property',
      text: "Click here to add a new property. You'll enter the name, type, address, and unit count.",
      position: 'bottom',
    },
    {
      id: 'property-table',
      target: '[data-tour="properties-table"]',
      title: 'Property Table',
      text: 'Your properties are listed here. Click on any property to view its details, units, and buildings.',
      position: 'top',
    },
  ],
};

import type { TourDef } from './types';

export const renterPortalTour: TourDef = {
  id: 'renter-portal',
  roles: ['RENTER'],
  steps: [
    {
      id: 'renter-home',
      target: '[data-tour="dashboard-header"]',
      position: 'bottom',
    },
    {
      id: 'my-leases',
      target: '[data-tour="sidebar-my-leases"]',
      position: 'right',
    },
    {
      id: 'my-payments',
      target: '[data-tour="sidebar-my-payments"]',
      position: 'right',
    },
    {
      id: 'my-tickets',
      target: '[data-tour="sidebar-my-tickets"]',
      position: 'right',
    },
  ],
};

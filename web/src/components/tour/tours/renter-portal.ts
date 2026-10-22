import type { TourDef } from './types';

export const renterPortalTour: TourDef = {
  id: 'renter-portal',
  name: 'Tenant Portal Tour',
  description: 'Your self-service portal walkthrough',
  roles: ['RENTER'],
  steps: [
    {
      id: 'renter-home',
      target: '[data-tour="dashboard-header"]',
      title: 'Your Tenant Portal',
      text: 'Welcome! This is your self-service portal where you can view your contracts, follow your cheques, and submit maintenance requests.',
      position: 'bottom',
    },
    {
      id: 'my-leases',
      target: '[data-tour="sidebar-my-leases"]',
      title: 'My Contracts',
      text: 'View your active contract details including rent amount, dates, and contract documents.',
      position: 'right',
    },
    {
      id: 'my-payments',
      target: '[data-tour="sidebar-my-payments"]',
      title: 'My Payments',
      text: 'See which cheques are due, which are at the bank, and your receipts. Where your landlord offers online payment, you can pay here too.',
      position: 'right',
    },
    {
      id: 'my-tickets',
      target: '[data-tour="sidebar-my-tickets"]',
      title: 'My Tickets',
      text: 'Report maintenance issues or make requests. Track the status of your tickets here.',
      position: 'right',
    },
  ],
};

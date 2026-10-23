import type { TourDef } from './types';

export const superAdminTour: TourDef = {
  id: 'super-admin',
  roles: ['SUPER_ADMIN'],
  steps: [
    {
      id: 'admin-dashboard',
      target: '[data-tour="dashboard-header"]',
      position: 'bottom',
      // Organisations and Users are in the Settings panel's Administration group.
      nextRoute: '/dashboard/settings',
    },
    {
      id: 'tenants-link',
      target: '[data-tour="sidebar-tenants"]',
      position: 'right',
    },
    {
      id: 'users-link',
      target: '[data-tour="sidebar-users"]',
      position: 'right',
    },
    {
      id: 'tenant-switcher',
      target: '[data-tour="tenant-switcher"]',
      position: 'bottom',
    },
  ],
};

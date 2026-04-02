import type { TourDef } from './types';

export const superAdminTour: TourDef = {
  id: 'super-admin',
  name: 'Super Admin Tour',
  description: 'System administration overview',
  roles: ['SUPER_ADMIN'],
  steps: [
    {
      id: 'admin-dashboard',
      target: '[data-tour="dashboard-header"]',
      title: 'System Dashboard',
      text: 'As a System Admin, you have full access across all tenants. This dashboard shows system-wide metrics.',
      position: 'bottom',
    },
    {
      id: 'tenants-link',
      target: '[data-tour="sidebar-tenants"]',
      title: 'Tenants',
      text: 'Manage all registered organizations. Create new tenants, view their details, and monitor usage.',
      position: 'right',
    },
    {
      id: 'users-link',
      target: '[data-tour="sidebar-users"]',
      title: 'Users',
      text: 'Manage user accounts across all tenants. Create users, assign roles, and control access.',
      position: 'right',
    },
    {
      id: 'tenant-switcher',
      target: '[data-tour="tenant-switcher"]',
      title: 'Tenant Switcher',
      text: 'Switch between tenants to view their data and manage their settings.',
      position: 'bottom',
    },
  ],
};

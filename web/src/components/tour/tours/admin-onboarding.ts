import type { TourDef } from './types';

export const adminOnboardingTour: TourDef = {
  id: 'admin-onboarding',
  name: 'Welcome Tour',
  description: 'Quick overview of your RentAxis dashboard',
  roles: ['TENANT_ADMIN', 'PROPERTY_MANAGER', 'TENANT_USER'],
  steps: [
    {
      id: 'welcome',
      target: '[data-tour="dashboard-header"]',
      title: 'Welcome to RentAxis!',
      text: 'This is your dashboard — it shows key metrics about your properties, leases, and finances at a glance.',
      position: 'bottom',
    },
    {
      id: 'sidebar-nav',
      target: '[data-tour="sidebar-nav"]',
      title: 'Sidebar Navigation',
      text: 'Use the sidebar to navigate between modules. You can collapse it for more screen space.',
      position: 'right',
    },
    {
      id: 'sidebar-properties',
      target: '[data-tour="sidebar-properties"]',
      title: 'Properties',
      text: 'Manage your property portfolio here — add properties, buildings, and units.',
      position: 'right',
    },
    {
      id: 'sidebar-leases',
      target: '[data-tour="sidebar-leases"]',
      title: 'Leases',
      text: 'Create and manage leases, link renters to units, and track payment schedules.',
      position: 'right',
    },
    {
      id: 'sidebar-finance',
      target: '[data-tour="sidebar-finance"]',
      title: 'Finance',
      text: 'Track income, expenses, and generate financial reports for your properties.',
      position: 'right',
    },
    {
      id: 'help-fab',
      target: '[data-tour="help-fab"]',
      title: 'Need Help?',
      text: 'Click this button anytime for contextual help, guided tours, or to browse the Help Center.',
      position: 'top',
    },
  ],
};

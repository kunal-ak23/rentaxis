import type { TourDef } from './types';

export const adminOnboardingTour: TourDef = {
  id: 'admin-onboarding',
  // Company Users get their own tour (company-user-onboarding): their rail has no Leasing or Accounting.
  roles: ['TENANT_ADMIN', 'PROPERTY_MANAGER'],
  onboarding: true,
  steps: [
    {
      id: 'welcome',
      target: '[data-tour="dashboard-header"]',
      position: 'bottom',
    },
    {
      id: 'sidebar-nav',
      target: '[data-tour="sidebar-nav"]',
      // Below 768 px the rail sits in the drawer the header's menu button opens.
      mobileTarget: '[data-testid="header-menu"]',
      position: 'right',
    },
    {
      // The Leasing rail icon (its section's tourId): contracts, tenants, properties & units.
      id: 'sidebar-properties',
      target: '[data-tour="sidebar-leases"]',
      position: 'right',
    },
    {
      id: 'sidebar-finance',
      target: '[data-tour="sidebar-finance"]',
      position: 'right',
    },
    {
      id: 'help-fab',
      target: '[data-tour="help-fab"]',
      position: 'top',
    },
    {
      id: 'header-help',
      target: '[data-tour="header-help"]',
      position: 'bottom',
    },
  ],
};

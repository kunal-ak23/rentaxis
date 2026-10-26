import type { TourDef } from './types';

/**
 * A Company User's rail is Home only (navModel), so their welcome tour covers
 * Home, the rail and the ways to Help, and nothing they cannot open.
 */
export const companyUserOnboardingTour: TourDef = {
  id: 'company-user-onboarding',
  roles: ['TENANT_USER'],
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

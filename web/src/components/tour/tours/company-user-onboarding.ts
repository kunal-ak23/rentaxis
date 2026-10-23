import type { TourDef } from './types';

/**
 * A Company User's rail is Home only (navModel), so their welcome tour covers
 * Home, the rail and the ways to Help, and nothing they cannot open.
 */
export const companyUserOnboardingTour: TourDef = {
  id: 'company-user-onboarding',
  name: 'Welcome Tour',
  description: 'Quick overview of your Miftah home',
  roles: ['TENANT_USER'],
  onboarding: true,
  steps: [
    {
      id: 'welcome',
      target: '[data-tour="dashboard-header"]',
      title: 'Welcome to Miftah!',
      text: 'This is your home page. Ask your Company Admin if you need access to more.',
      position: 'bottom',
    },
    {
      id: 'sidebar-nav',
      target: '[data-tour="sidebar-nav"]',
      title: 'Navigation',
      text: 'The rail on the left holds your sections. Yours has Home.',
      position: 'right',
    },
    {
      id: 'help-fab',
      target: '[data-tour="help-fab"]',
      title: 'Need Help?',
      text: 'Click this button anytime for contextual help, guided tours, or to browse the Help Center.',
      position: 'top',
    },
    {
      id: 'header-help',
      target: '[data-tour="header-help"]',
      title: 'Help & Guides',
      text: 'Articles and video tutorials for your account.',
      position: 'bottom',
    },
  ],
};

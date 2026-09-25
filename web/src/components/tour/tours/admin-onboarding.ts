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
      text: 'This is your home page — the numbers that matter and today\'s work.',
      position: 'bottom',
    },
    {
      id: 'sidebar-nav',
      target: '[data-tour="sidebar-nav"]',
      title: 'Navigation',
      text: 'The rail on the left holds your sections; the panel beside it lists the pages of the section you are in.',
      position: 'right',
    },
    {
      // The Leasing rail icon (its section's tourId): contracts, tenants, properties & units.
      id: 'sidebar-properties',
      target: '[data-tour="sidebar-leases"]',
      title: 'Leasing',
      text: 'Tenancy contracts, tenants, properties & units.',
      position: 'right',
    },
    {
      id: 'sidebar-finance',
      target: '[data-tour="sidebar-finance"]',
      title: 'Accounting',
      text: 'Journal vouchers, registers, receipts & payments and final reports live behind this one door.',
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
      text: 'Articles and tours for every page.',
      position: 'bottom',
    },
  ],
};

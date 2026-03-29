import { adminOnboardingTour } from './admin-onboarding';
import { propertyWorkflowTour } from './property-workflow';
import { financeOverviewTour } from './finance-overview';
import { renterPortalTour } from './renter-portal';
import { superAdminTour } from './super-admin';
import type { TourDef } from './types';
import type { UserRole } from '@/lib/rbac';

export const ALL_TOURS: TourDef[] = [
  adminOnboardingTour,
  propertyWorkflowTour,
  financeOverviewTour,
  renterPortalTour,
  superAdminTour,
];

export function getToursForRole(role?: UserRole): TourDef[] {
  if (!role) return [];
  return ALL_TOURS.filter(t => t.roles.includes(role));
}

export function getTourById(id: string): TourDef | undefined {
  return ALL_TOURS.find(t => t.id === id);
}

export type { TourDef, TourStepDef } from './types';

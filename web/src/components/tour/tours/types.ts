import type { UserRole } from '@/lib/rbac';

export interface TourStepDef {
  id: string;
  target: string;
  title: string;
  text: string;
  position: 'top' | 'bottom' | 'left' | 'right';
  nextRoute?: string;
}

export interface TourDef {
  id: string;
  name: string;
  description: string;
  roles: UserRole[];
  /** Auto-starts on a first visit to the dashboard home for its roles (at most one per role). */
  onboarding?: boolean;
  steps: TourStepDef[];
}

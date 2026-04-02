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
  steps: TourStepDef[];
}

import type { UserRole } from '@/lib/rbac';

/**
 * A tour's copy lives in the message catalogue, not here: `Tours.<tour id>.name`,
 * `.description` and `.steps.<step id>.title` / `.text`, in en.json and ar.json.
 */
export interface TourStepDef {
  id: string;
  target: string;
  /** Shown instead when `target` is not on screen (the rail is hidden below 768 px). */
  mobileTarget?: string;
  position: 'top' | 'bottom' | 'left' | 'right';
  nextRoute?: string;
}

export interface TourDef {
  id: string;
  roles: UserRole[];
  /** Auto-starts on a first visit to the dashboard home for its roles (at most one per role). */
  onboarding?: boolean;
  steps: TourStepDef[];
}

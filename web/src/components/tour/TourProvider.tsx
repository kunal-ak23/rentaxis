'use client';

import {
  createContext,
  useContext,
  useCallback,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { ShepherdJourneyProvider, useShepherd } from 'react-shepherd';
import { useRouter } from '@/i18n/routing';
import { usePathname } from 'next/navigation';
import { useLocale, useTranslations } from 'next-intl';
import { isDashboardHome } from '@/lib/nav/routeGuard';
import { getOnboardingTour, getTourById, getToursForRole } from './tours';
import type { TourDef, TourStepDef } from './tours/types';
import type { UserRole } from '@/lib/rbac';

import 'shepherd.js/dist/css/shepherd.css';
import './tour-styles.css';

// ---------------------------------------------------------------------------
// localStorage helpers
// ---------------------------------------------------------------------------

const STORAGE_KEY = 'rentaxis_tours_completed';

function getCompletedTourIds(): string[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

function markTourCompleted(tourId: string) {
  const ids = getCompletedTourIds();
  if (!ids.includes(tourId)) {
    ids.push(tourId);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(ids));
  }
}

// Skipped or closed. Kept apart from "completed" so the Help centre and the
// welcome banner still offer the tour; it only stops the auto-start.
const DISMISSED_KEY = 'rentaxis_tours_dismissed';

function getDismissedTourIds(): string[] {
  if (typeof window === 'undefined') return [];
  try {
    const raw = localStorage.getItem(DISMISSED_KEY);
    return raw ? JSON.parse(raw) : [];
  } catch {
    return [];
  }
}

function markTourDismissed(tourId: string) {
  try {
    const ids = getDismissedTourIds();
    if (!ids.includes(tourId)) {
      ids.push(tourId);
      localStorage.setItem(DISMISSED_KEY, JSON.stringify(ids));
    }
  } catch {
    // storage blocked: the tour may auto-start again next time, nothing worse
  }
}

/**
 * The onboarding tour starts on the dashboard home, where its first step
 * lives. Break round 1: it used to auto-open its click-blocking overlay on
 * whatever page a first-time user landed on (e.g. a ticket detail page).
 */
export function isOnboardingHome(pathname: string | null): boolean {
  return isDashboardHome(pathname);
}

/** Check whether a tour has been completed (callable outside of React tree). */
export function isTourCompleted(tourId: string): boolean {
  return getCompletedTourIds().includes(tourId);
}

// ---------------------------------------------------------------------------
// Steps
// ---------------------------------------------------------------------------

const escapeHtml = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** First match that is on screen: the hidden desktop rail still matches below 768 px. */
function visible(selector: string): HTMLElement | undefined {
  return Array.from(document.querySelectorAll<HTMLElement>(selector)).find(el => el.getClientRects().length > 0);
}

/**
 * Resolved when the step shows (after any nextRoute navigation): the target, else
 * the step's phone fallback, else nothing, and Shepherd centres the step.
 */
export function stepElement(step: Pick<TourStepDef, 'target' | 'mobileTarget'>): HTMLElement | undefined {
  return visible(step.target) ?? (step.mobileTarget ? visible(step.mobileTarget) : undefined);
}

/**
 * The rail sits on the right under /ar, so a step that opens beside it opens on its
 * left. On a phone a step with a phone target opens below it: that target is in the
 * header, and beside it the step would cover it.
 */
export function stepPosition(step: Pick<TourStepDef, 'position' | 'mobileTarget'>, rtl: boolean, phone = false): TourStepDef['position'] {
  if (phone && step.mobileTarget) return 'bottom';
  const { position } = step;
  if (!rtl) return position;
  return position === 'right' ? 'left' : position === 'left' ? 'right' : position;
}

/** Below 768 px, where the rail lives in the drawer (MvpSidebar). */
const isPhone = () => typeof window !== 'undefined' && window.matchMedia?.('(max-width: 767px)').matches === true;

// ---------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------

interface TourContextValue {
  startTour: (tourId: string) => void;
  availableTours: TourDef[];
  completedTourIds: string[];
}

const TourContext = createContext<TourContextValue | null>(null);

export function useTour(): TourContextValue {
  const ctx = useContext(TourContext);
  if (!ctx) {
    throw new Error('useTour must be used within a TourProvider');
  }
  return ctx;
}

// ---------------------------------------------------------------------------
// Inner provider (must be inside ShepherdJourneyProvider to use useShepherd)
// ---------------------------------------------------------------------------

interface InnerProps {
  children: ReactNode;
  role?: UserRole;
  /** False while the layout shows access-denied or "select an organisation" instead of the page. */
  autoStart?: boolean;
}

function TourProviderInner({ children, role, autoStart = true }: InnerProps) {
  const Shepherd = useShepherd();
  const t = useTranslations('Tours');
  const rtl = useLocale() === 'ar';
  const router = useRouter();
  const pathname = usePathname();
  const activeTourRef = useRef<InstanceType<typeof Shepherd.Tour> | null>(null);
  const [completedTourIds, setCompletedTourIds] = useState<string[]>([]);
  const autoTriggeredRef = useRef(false);

  // Hydrate completed list on mount
  useEffect(() => {
    setCompletedTourIds(getCompletedTourIds());
  }, []);

  const availableTours = getToursForRole(role);

  const startTour = useCallback(
    (tourId: string) => {
      // Cancel any running tour
      if (activeTourRef.current) {
        try {
          activeTourRef.current.cancel();
        } catch {
          // ignore
        }
        activeTourRef.current = null;
      }

      const def = getTourById(tourId);
      if (!def) return;

      const phone = isPhone();
      const steps = def.steps.map((s, idx) => {
        const isLast = idx === def.steps.length - 1;
        const hasNextRoute = !!s.nextRoute;

        return {
          id: s.id,
          attachTo: { element: () => stepElement(s), on: stepPosition(s, rtl, phone) },
          title: t(`${def.id}.steps.${s.id}.title`),
          text: `<p>${escapeHtml(t(`${def.id}.steps.${s.id}.text`))}</p><div class="shepherd-progress">${escapeHtml(
            t('progress', { current: idx + 1, total: def.steps.length }),
          )}</div>`,
          canClickTarget: false,
          buttons: [
            ...(idx > 0
              ? [
                  {
                    text: t('back'),
                    classes: 'shepherd-button-secondary',
                    action: function (this: InstanceType<typeof Shepherd.Tour>) {
                      this.back();
                    },
                  },
                ]
              : [
                  {
                    text: t('skip'),
                    classes: 'shepherd-button-secondary',
                    action: function (this: InstanceType<typeof Shepherd.Tour>) {
                      this.cancel();
                    },
                  },
                ]),
            {
              text: isLast ? t('done') : t('next'),
              classes: 'shepherd-button-primary',
              action: function (this: InstanceType<typeof Shepherd.Tour>) {
                if (hasNextRoute) {
                  // Navigate first, then advance on next tick
                  router.push(s.nextRoute!);
                  setTimeout(() => {
                    this.next();
                  }, 600);
                } else {
                  this.next();
                }
              },
            },
          ],
        };
      });

      const tour = new Shepherd.Tour({
        useModalOverlay: true,
        // Explicit, not left to library defaults: Escape and the arrow keys
        // always work, and every step carries a visible close icon (below).
        exitOnEsc: true,
        keyboardNavigation: true,
        defaultStepOptions: {
          scrollTo: { behavior: 'smooth', block: 'center' },
          cancelIcon: { enabled: true },
        },
        steps,
      });

      tour.on('complete', () => {
        markTourCompleted(tourId);
        setCompletedTourIds(getCompletedTourIds());
        activeTourRef.current = null;
      });

      tour.on('cancel', () => {
        // Skip, the close icon or Escape: do not auto-open it again.
        markTourDismissed(tourId);
        activeTourRef.current = null;
      });

      activeTourRef.current = tour;
      tour.start();
    },
    [Shepherd, router, t, rtl],
  );

  // Latest startTour for the auto-start timer, so a re-render that hands us a
  // new function identity cannot cancel the pending start.
  const startTourRef = useRef(startTour);
  useEffect(() => {
    startTourRef.current = startTour;
  }, [startTour]);

  // Auto-trigger the onboarding tour on a first visit to the dashboard home only.
  useEffect(() => {
    if (autoTriggeredRef.current) return;
    if (!role || !autoStart) return;
    if (!isOnboardingHome(pathname)) return;

    // The role's own welcome tour: a Company User's covers only what their rail shows.
    const onboardingId = getOnboardingTour(role)?.id;

    if (
      onboardingId &&
      !getCompletedTourIds().includes(onboardingId) &&
      !getDismissedTourIds().includes(onboardingId)
    ) {
      const timer = setTimeout(() => {
        autoTriggeredRef.current = true;
        startTourRef.current(onboardingId);
      }, 1500);
      // Leaving the page before it fires cancels it.
      return () => clearTimeout(timer);
    }
  }, [role, pathname, autoStart]);

  return (
    <TourContext.Provider value={{ startTour, availableTours, completedTourIds }}>
      {children}
    </TourContext.Provider>
  );
}

// ---------------------------------------------------------------------------
// Public wrapper
// ---------------------------------------------------------------------------

interface TourProviderProps {
  children: ReactNode;
  role?: UserRole;
  autoStart?: boolean;
}

export default function TourProvider({ children, role, autoStart = true }: TourProviderProps) {
  return (
    <ShepherdJourneyProvider>
      <TourProviderInner role={role} autoStart={autoStart}>{children}</TourProviderInner>
    </ShepherdJourneyProvider>
  );
}

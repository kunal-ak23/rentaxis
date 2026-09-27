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
import { getTourById, getToursForRole } from './tours';
import type { TourDef } from './tours/types';
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
  return /^(?:\/(?:en|ar))?\/dashboard\/?$/.test(pathname ?? '');
}

/** Check whether a tour has been completed (callable outside of React tree). */
export function isTourCompleted(tourId: string): boolean {
  return getCompletedTourIds().includes(tourId);
}

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
}

function TourProviderInner({ children, role }: InnerProps) {
  const Shepherd = useShepherd();
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

      const steps = def.steps.map((s, idx) => {
        const isLast = idx === def.steps.length - 1;
        const hasNextRoute = !!s.nextRoute;

        return {
          id: s.id,
          attachTo: { element: s.target, on: s.position },
          title: s.title,
          text: `<p>${s.text}</p><div class="shepherd-progress">Step ${idx + 1} of ${def.steps.length}</div>`,
          canClickTarget: false,
          buttons: [
            ...(idx > 0
              ? [
                  {
                    text: 'Back',
                    classes: 'shepherd-button-secondary',
                    action: function (this: InstanceType<typeof Shepherd.Tour>) {
                      this.back();
                    },
                  },
                ]
              : [
                  {
                    text: 'Skip',
                    classes: 'shepherd-button-secondary',
                    action: function (this: InstanceType<typeof Shepherd.Tour>) {
                      this.cancel();
                    },
                  },
                ]),
            {
              text: isLast ? 'Done' : 'Next',
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
    [Shepherd, router],
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
    if (!role) return;
    if (!isOnboardingHome(pathname)) return;

    const onboardingId = 'admin-onboarding';
    const onboardingDef = getTourById(onboardingId);

    if (
      onboardingDef &&
      onboardingDef.roles.includes(role) &&
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
  }, [role, pathname]);

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
}

export default function TourProvider({ children, role }: TourProviderProps) {
  return (
    <ShepherdJourneyProvider>
      <TourProviderInner role={role}>{children}</TourProviderInner>
    </ShepherdJourneyProvider>
  );
}

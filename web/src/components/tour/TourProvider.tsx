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
        activeTourRef.current = null;
      });

      activeTourRef.current = tour;
      tour.start();
    },
    [Shepherd, router],
  );

  // Auto-trigger onboarding tour on first visit
  useEffect(() => {
    if (autoTriggeredRef.current) return;
    if (!role) return;

    const completed = getCompletedTourIds();
    const onboardingId = 'admin-onboarding';
    const onboardingDef = getTourById(onboardingId);

    if (
      onboardingDef &&
      onboardingDef.roles.includes(role) &&
      !completed.includes(onboardingId)
    ) {
      autoTriggeredRef.current = true;
      const timer = setTimeout(() => {
        startTour(onboardingId);
      }, 1500);
      return () => clearTimeout(timer);
    }
  }, [role, startTour]);

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

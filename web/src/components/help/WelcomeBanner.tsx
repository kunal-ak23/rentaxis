'use client';

import { useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { Sparkles, X, Play } from 'lucide-react';
import { useTour, isTourCompleted } from '@/components/tour/TourProvider';
import { useSession } from 'next-auth/react';
import { getToursForRole } from '@/components/tour/tours';
import type { UserRole } from '@/lib/rbac';
import { useTranslations } from 'next-intl';

const BANNER_DISMISSED_KEY = 'rentaxis_welcome_dismissed';

export default function WelcomeBanner() {
  const [visible, setVisible] = useState(false);
  const t = useTranslations('Help');
  const { startTour } = useTour();
  const { data: session } = useSession();
  const userRole = session?.user?.role as UserRole | undefined;

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const dismissed = localStorage.getItem(BANNER_DISMISSED_KEY);
    if (dismissed) return;

    const tours = getToursForRole(userRole);
    const onboarding = tours.find(t =>
      t.id.includes('onboarding') || t.id === 'renter-portal' || t.id === 'super-admin'
    );
    if (onboarding && !isTourCompleted(onboarding.id)) {
      setVisible(true);
    }
  }, [userRole]);

  const dismiss = () => {
    setVisible(false);
    localStorage.setItem(BANNER_DISMISSED_KEY, 'true');
  };

  const handleStartTour = () => {
    const tours = getToursForRole(userRole);
    const onboarding = tours.find(t =>
      t.id.includes('onboarding') || t.id === 'renter-portal' || t.id === 'super-admin'
    );
    if (onboarding) {
      dismiss();
      startTour(onboarding.id);
    }
  };

  return (
    <AnimatePresence>
      {visible && (
        <motion.div
          initial={{ opacity: 0, y: -10 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -10 }}
          className="mb-6 p-4 rounded-xl bg-gradient-to-r from-primary/10 to-accent/10 border border-primary/20"
        >
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-lg bg-primary/15 flex items-center justify-center">
                <Sparkles size={20} className="text-primary" />
              </div>
              <div>
                <h3 className="text-sm font-semibold text-gray-900">{t('welcomeTitle')}</h3>
                <p className="text-xs text-gray-500 mt-0.5">{t('welcomeDesc')}</p>
              </div>
            </div>
            <div className="flex items-center gap-2">
              <button
                onClick={handleStartTour}
                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-primary text-white text-xs font-medium hover:bg-primary/90 transition-colors cursor-pointer"
              >
                <Play size={12} />
                {t('startTour')}
              </button>
              <button
                onClick={dismiss}
                aria-label={t('dismiss')}
                className="p-1.5 rounded-lg text-gray-400 hover:text-gray-600 hover:bg-gray-100 transition-colors cursor-pointer"
              >
                <X size={14} />
              </button>
            </div>
          </div>
        </motion.div>
      )}
    </AnimatePresence>
  );
}

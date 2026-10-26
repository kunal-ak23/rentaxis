'use client';

import { useState, useRef, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { HelpCircle, BookOpen, Play, PlayCircle, X } from 'lucide-react';
import { useLocale, useTranslations } from 'next-intl';
import { useSession } from 'next-auth/react';
import { cn } from '@/lib/utils';
import { Link, usePathname } from '@/i18n/routing';
import { getContextualHelp } from '@/lib/help';
import { useTour } from '@/components/tour/TourProvider';
import { getTourById } from '@/components/tour/tours';
import { useAnyDialogOpen } from '@/lib/useAnyDialogOpen';
import type { UserRole } from '@/lib/rbac';
import { TUTORIALS, filterTutorialsByRole, publishedTutorials } from '@/lib/tutorials/catalog';

/** At most this many videos in the menu; the Help center lists the rest. */
const MAX_PAGE_VIDEOS = 3;

export default function HelpFAB() {
  const t = useTranslations('Help');
  const [open, setOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const pathname = usePathname();
  const { startTour } = useTour();
  const dialogOpen = useAnyDialogOpen();
  const { data: session } = useSession();
  const role = (session?.user as { role?: UserRole } | undefined)?.role;
  const lang: 'en' | 'ar' = useLocale() === 'ar' ? 'ar' : 'en';

  const ctx = getContextualHelp(pathname, role);
  const tour = ctx.tour ? getTourById(ctx.tour) : undefined;
  const hasTour = !!(tour && role && tour.roles.includes(role));
  const hasArticle = !!ctx.article;
  // The page's videos, in the map's order, that are published and made for this role.
  const published = filterTutorialsByRole(publishedTutorials(TUTORIALS), role);
  const videos = (ctx.videos ?? [])
    .map(id => published.find(v => v.id === id))
    .filter((v): v is (typeof published)[number] => !!v)
    .slice(0, MAX_PAGE_VIDEOS);

  // Close on outside click
  useEffect(() => {
    function handleMouseDown(e: MouseEvent) {
      if (wrapperRef.current && !wrapperRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    if (open) {
      document.addEventListener('mousedown', handleMouseDown);
    }
    return () => document.removeEventListener('mousedown', handleMouseDown);
  }, [open]);

  // #44: the FAB lives one tier below modal overlays (z-40 vs z-50), so any
  // fixed z-50 overlay — with or without role="dialog" — paints over it and
  // its footer buttons stay clickable. On top of that, get out of the way
  // entirely while a proper dialog is open. This must come after every hook
  // above so hook order stays stable across renders.
  if (dialogOpen) {
    return null;
  }

  return (
    <div ref={wrapperRef} data-tour="help-fab" className="fixed bottom-6 end-6 z-40">
      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ opacity: 0, y: 12, scale: 0.95 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 12, scale: 0.95 }}
            transition={{ duration: 0.18 }}
            className="absolute bottom-16 end-0 w-72 rounded-xl bg-white shadow-xl border border-gray-200 overflow-hidden"
          >
            {/* Header */}
            <div className="bg-[var(--ink-900)] px-4 py-3 text-white">
              <p className="font-semibold text-sm">{t('needHelp')}</p>
              <p className="text-xs text-white/70">{t('resourcesForPage')}</p>
            </div>

            {/* Options */}
            <div className="p-2 flex flex-col gap-1">
              {hasArticle && (
                <Link
                  href={`/dashboard/help/${ctx.article}`}
                  onClick={() => setOpen(false)}
                  className={cn(
                    'flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm text-foreground',
                    'hover:bg-[var(--sand-100)] transition-colors'
                  )}
                >
                  <BookOpen className="w-4 h-4 text-[var(--gold-500)] shrink-0" />
                  {t('viewPageHelp')}
                </Link>
              )}

              {hasTour && (
                <button
                  onClick={() => {
                    setOpen(false);
                    startTour(tour!.id);
                  }}
                  className={cn(
                    'flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm text-foreground w-full text-start',
                    'hover:bg-[var(--sand-100)] transition-colors'
                  )}
                >
                  <Play className="w-4 h-4 text-[var(--gold-500)] shrink-0" />
                  {t('takeTour')}
                </button>
              )}

              {videos.length > 0 && (
                <div data-testid="help-fab-videos" className="border-t border-gray-100 mt-1 pt-2">
                  <p className="px-3 pb-1 text-[11px] font-semibold uppercase tracking-wide text-[var(--ink-500)]">{t('pageVideos')}</p>
                  {videos.map(video => (
                    <Link
                      key={video.id}
                      href={`/dashboard/help/videos?play=${encodeURIComponent(video.id)}`}
                      onClick={() => setOpen(false)}
                      className={cn(
                        'flex items-start gap-3 px-3 py-2 rounded-lg text-sm text-foreground',
                        'hover:bg-[var(--sand-100)] transition-colors'
                      )}
                    >
                      <PlayCircle className="w-4 h-4 mt-0.5 text-[var(--gold-500)] shrink-0" aria-hidden />
                      <span>{video.title[lang]}</span>
                    </Link>
                  ))}
                </div>
              )}

              <Link
                href="/dashboard/help"
                onClick={() => setOpen(false)}
                className={cn(
                  'flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm text-foreground',
                  'hover:bg-[var(--sand-100)] transition-colors'
                )}
              >
                <HelpCircle className="w-4 h-4 text-[var(--gold-500)] shrink-0" />
                {t('title')}
              </Link>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      <motion.button
        whileHover={{ scale: 1.05 }}
        whileTap={{ scale: 0.95 }}
        onClick={() => setOpen(prev => !prev)}
        aria-label={open ? t('closeHelp') : t('openHelp')}
        className={cn(
          'w-12 h-12 rounded-full flex items-center justify-center shadow-lg transition-colors',
          open
            ? 'bg-[var(--ink-700)] hover:bg-[var(--ink-800)]'
            : 'bg-[var(--ink-900)] hover:bg-[var(--ink-800)]'
        )}
      >
        {open ? (
          <X className="w-5 h-5 text-white" />
        ) : (
          <HelpCircle className="w-5 h-5 text-white" />
        )}
      </motion.button>
    </div>
  );
}

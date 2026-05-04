'use client';

import { useState, useRef, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import { HelpCircle, BookOpen, Play, X } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Link, usePathname } from '@/i18n/routing';
import { getContextualHelp } from '@/lib/help';
import { useTour } from '@/components/tour/TourProvider';
import { getTourById } from '@/components/tour/tours';

export default function HelpFAB() {
  const [open, setOpen] = useState(false);
  const wrapperRef = useRef<HTMLDivElement>(null);
  const pathname = usePathname();
  const { startTour } = useTour();

  const ctx = getContextualHelp(pathname);
  const hasTour = !!(ctx.tour && getTourById(ctx.tour));
  const hasArticle = !!ctx.article;

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

  return (
    <div ref={wrapperRef} data-tour="help-fab" className="fixed bottom-6 right-6 z-50">
      <AnimatePresence>
        {open && (
          <motion.div
            initial={{ opacity: 0, y: 12, scale: 0.95 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 12, scale: 0.95 }}
            transition={{ duration: 0.18 }}
            className="absolute bottom-16 right-0 w-64 rounded-xl bg-white shadow-xl border border-gray-200 overflow-hidden"
          >
            {/* Header */}
            <div className="bg-[--ink-900] px-4 py-3 text-white">
              <p className="font-semibold text-sm">Need Help?</p>
              <p className="text-xs text-white/70">Resources for this page</p>
            </div>

            {/* Options */}
            <div className="p-2 flex flex-col gap-1">
              {hasArticle && (
                <Link
                  href={`/dashboard/help/${ctx.article}`}
                  onClick={() => setOpen(false)}
                  className={cn(
                    'flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm text-foreground',
                    'hover:bg-[--sand-100] transition-colors'
                  )}
                >
                  <BookOpen className="w-4 h-4 text-[--gold-500] shrink-0" />
                  View Page Help
                </Link>
              )}

              {hasTour && (
                <button
                  onClick={() => {
                    setOpen(false);
                    startTour(ctx.tour!);
                  }}
                  className={cn(
                    'flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm text-foreground w-full text-left',
                    'hover:bg-[--sand-100] transition-colors'
                  )}
                >
                  <Play className="w-4 h-4 text-[--gold-500] shrink-0" />
                  Take a Tour
                </button>
              )}

              <Link
                href="/dashboard/help"
                onClick={() => setOpen(false)}
                className={cn(
                  'flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm text-foreground',
                  'hover:bg-[--sand-100] transition-colors'
                )}
              >
                <HelpCircle className="w-4 h-4 text-[--gold-500] shrink-0" />
                Help Center
              </Link>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      <motion.button
        whileHover={{ scale: 1.05 }}
        whileTap={{ scale: 0.95 }}
        onClick={() => setOpen(prev => !prev)}
        className={cn(
          'w-12 h-12 rounded-full flex items-center justify-center shadow-lg transition-colors',
          open
            ? 'bg-[--ink-700] hover:bg-[--ink-800]'
            : 'bg-[--ink-900] hover:bg-[--ink-800]'
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

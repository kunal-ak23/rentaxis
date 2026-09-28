'use client';

import { useEffect, useId, useRef } from 'react';
import { X } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { embedUrl } from '@/lib/tutorials/catalog';

interface VideoPlayerDialogProps {
  youtubeId: string;
  title: string;
  onClose: () => void;
}

const FOCUSABLE = 'a[href], button:not([disabled]), iframe, [tabindex]:not([tabindex="-1"])';

/**
 * In-page player. Mounted only while open, so the YouTube iframe (privacy-
 * enhanced youtube-nocookie.com embed) is created on open and destroyed on
 * close. Esc closes; Tab is trapped (same pattern as ConfirmDialog); focus
 * returns to whatever opened it.
 */
export default function VideoPlayerDialog({ youtubeId, title, onClose }: VideoPlayerDialogProps) {
  const t = useTranslations('Help');
  const dialogRef = useRef<HTMLDivElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const headingId = useId();

  useEffect(() => {
    const opener = document.activeElement as HTMLElement | null;
    closeRef.current?.focus();
    return () => {
      if (opener && typeof opener.focus === 'function' && opener.isConnected) opener.focus();
    };
  }, []);

  useEffect(() => {
    const isTop = () => {
      const modals = document.querySelectorAll('[aria-modal="true"]');
      return modals[modals.length - 1] === dialogRef.current;
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || !isTop()) return;
      if (e.key === 'Escape') {
        e.preventDefault();
        onClose();
        return;
      }
      if (e.key !== 'Tab' || !dialogRef.current) return;
      const items = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(FOCUSABLE));
      if (items.length === 0) return;
      const first = items[0];
      const last = items[items.length - 1];
      const inside = dialogRef.current.contains(document.activeElement);
      if (e.shiftKey && (document.activeElement === first || !inside)) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (document.activeElement === last || !inside)) {
        e.preventDefault();
        first.focus();
      }
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-[105] flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/60" onClick={onClose} aria-hidden />
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={headingId}
        className="relative w-full max-w-4xl overflow-hidden rounded-xl bg-white shadow-2xl"
      >
        <div className="flex items-center justify-between gap-3 border-b border-gray-200 px-4 py-3">
          <h2 id={headingId} className="min-w-0 truncate text-sm font-semibold text-gray-900">{title}</h2>
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            aria-label={t('videos.close')}
            className="shrink-0 rounded-lg p-1.5 text-gray-500 hover:bg-gray-100 hover:text-gray-700 focus:outline-none focus:ring-2 focus:ring-primary/30"
          >
            <X size={18} aria-hidden />
          </button>
        </div>
        <div className="aspect-video w-full bg-black">
          <iframe
            src={embedUrl(youtubeId)}
            title={title}
            className="h-full w-full"
            allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture"
            referrerPolicy="strict-origin-when-cross-origin"
            allowFullScreen
          />
        </div>
      </div>
    </div>
  );
}

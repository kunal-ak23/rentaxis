'use client';

import { useEffect, useState } from 'react';

const DIALOG_SELECTOR = '[role="dialog"], [aria-modal="true"]';

/**
 * True while any modal/dialog (anything marked `role="dialog"` or
 * `aria-modal="true"`, e.g. the lease wizard, confirm dialogs, drawers) is
 * mounted anywhere in the document.
 *
 * This is the general mechanism for "is something covering the screen right
 * now" — chrome that must get out of the way of modals (the Help FAB, toasts,
 * etc.) should key off this instead of knowing about any specific dialog.
 */
export function useAnyDialogOpen(): boolean {
  const [open, setOpen] = useState(false);

  useEffect(() => {
    if (typeof document === 'undefined') return;

    const check = () => setOpen(document.querySelector(DIALOG_SELECTOR) !== null);
    check();

    const observer = new MutationObserver(check);
    observer.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['role', 'aria-modal'],
    });

    return () => observer.disconnect();
  }, []);

  return open;
}

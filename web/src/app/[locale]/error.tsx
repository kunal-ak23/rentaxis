"use client";

import { useEffect } from "react";
import { useTranslations } from "next-intl";

/**
 * Error boundary for everything under /[locale].
 *
 * The app previously had no error.tsx, not-found.tsx or loading.tsx anywhere
 * across its route segments, so any render exception left the viewer on a blank
 * white page with no way forward. App Router boundaries inherit, so this one
 * covers every segment that does not declare its own.
 *
 * This renders inside [locale]/layout.tsx, so NextIntlClientProvider is
 * available and the copy is translated. An error thrown by the root layout
 * itself cannot be caught here — global-error.tsx handles that case, and is
 * deliberately dependency-free.
 */
export default function LocaleError({
    error,
    reset,
}: {
    error: Error & { digest?: string };
    reset: () => void;
}) {
    const t = useTranslations("ErrorBoundary");

    useEffect(() => {
        // Nothing aggregates browser errors yet (#146). Until that exists the
        // console plus the server-side digest is the only trail there is.
        console.error("Unhandled render error:", error);
    }, [error]);

    return (
        <div className="flex min-h-[60vh] flex-col items-center justify-center gap-4 p-8 text-center">
            <h1 className="text-xl font-semibold text-gray-900">{t("title")}</h1>
            <p className="max-w-md text-sm text-gray-600">{t("description")}</p>
            {error.digest && (
                <p className="font-mono text-xs text-gray-400">{error.digest}</p>
            )}
            <button
                type="button"
                onClick={reset}
                className="rounded-md bg-gray-900 px-4 py-2 text-sm font-medium text-white hover:bg-gray-700"
            >
                {t("retry")}
            </button>
        </div>
    );
}

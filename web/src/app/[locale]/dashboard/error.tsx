"use client";

import { useEffect } from "react";
import { useTranslations } from "next-intl";
import Link from "next/link";

/**
 * Dashboard-scoped error boundary.
 *
 * Separate from the locale-level one so a failure inside a dashboard page keeps
 * the dashboard shell — sidebar, tenant switcher, navigation — rendered around
 * it. Losing the whole chrome on a single page's error is what made the blank
 * page so disorienting.
 */
export default function DashboardError({
    error,
    reset,
}: {
    error: Error & { digest?: string };
    reset: () => void;
}) {
    const t = useTranslations("ErrorBoundary");

    useEffect(() => {
        console.error("Unhandled dashboard render error:", error);
    }, [error]);

    return (
        <div className="flex min-h-[50vh] flex-col items-center justify-center gap-4 p-8 text-center">
            <h1 className="text-lg font-semibold text-gray-900">{t("title")}</h1>
            <p className="max-w-md text-sm text-gray-600">{t("description")}</p>
            {error.digest && (
                <p className="font-mono text-xs text-gray-400">{error.digest}</p>
            )}
            <div className="flex gap-3">
                <button
                    type="button"
                    onClick={reset}
                    className="rounded-md bg-gray-900 px-4 py-2 text-sm font-medium text-white hover:bg-gray-700"
                >
                    {t("retry")}
                </button>
                <Link
                    href="/dashboard"
                    className="rounded-md border border-gray-300 px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50"
                >
                    {t("backToDashboard")}
                </Link>
            </div>
        </div>
    );
}

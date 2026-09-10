"use client";

import { AlertCircle, RefreshCw } from "lucide-react";
import { useTranslations } from "next-intl";
import { cn } from "@/lib/utils";

/**
 * Inline banner for a failed data load, with a retry.
 *
 * Exists because roughly a third of the dashboard's pages had no failure path
 * on their initial GET at all: `if (res.ok) { setState(data) }` with no else
 * left the state at its initial `[]`, so a 401, a 403 or a backend outage
 * rendered identically to "there is nothing here". A user could not tell an
 * empty list from a broken one, and had nothing to click.
 *
 * The markup follows the banner the properties page already used, so the
 * pattern is one shared component rather than a shape per page.
 */
export function LoadErrorBanner({
    message,
    onRetry,
    className,
}: {
    message: string;
    onRetry?: () => void;
    className?: string;
}) {
    const t = useTranslations("Common");

    return (
        <div
            role="alert"
            className={cn(
                "mb-6 flex items-center justify-between gap-3 bg-error/10 border border-error/30 text-error rounded-xl px-5 py-3",
                className,
            )}
        >
            <div className="flex items-center gap-2">
                <AlertCircle size={16} className="shrink-0" />
                <span className="text-sm font-medium">{message}</span>
            </div>
            {onRetry && (
                <button
                    type="button"
                    onClick={onRetry}
                    className="cursor-pointer flex items-center gap-1.5 text-xs font-semibold bg-error/10 hover:bg-error/20 px-3 py-1.5 rounded-lg transition-colors shrink-0"
                >
                    <RefreshCw size={12} />
                    {t("retry")}
                </button>
            )}
        </div>
    );
}

export default LoadErrorBanner;

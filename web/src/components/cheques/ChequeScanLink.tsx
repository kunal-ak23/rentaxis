"use client";

import { useTranslations } from "next-intl";
import { FileImage } from "lucide-react";
import { chequeApi } from "@/lib/api/leasing";

/**
 * Opens a cheque's attached scan. The image is streamed by the app
 * (`GET /cheques/{id}/image`, scoped like the row itself) — never the stored
 * blob URL, which points into a private container and answers 403.
 *
 * `icon` sits beside a cheque number in a grid cell; `text` is a row action.
 */
export default function ChequeScanLink({
    chequeId,
    variant = "text",
    label,
    testId,
}: {
    chequeId: string;
    variant?: "icon" | "text";
    /** Extra words for the accessible name (e.g. the row number), so a list of icons stays distinguishable. */
    label?: string;
    testId?: string;
}) {
    const t = useTranslations("Cheques");
    const name = label ? `${t("viewScan")} ${label}` : t("viewScan");
    if (variant === "icon") {
        return (
            <a
                href={chequeApi.scanUrl(chequeId)}
                target="_blank"
                rel="noopener noreferrer"
                data-testid={testId}
                aria-label={name}
                title={t("viewScan")}
                className="shrink-0 inline-flex items-center justify-center min-w-6 min-h-6 text-primary hover:text-primary/80"
            >
                <FileImage size={13} />
            </a>
        );
    }
    return (
        <a
            href={chequeApi.scanUrl(chequeId)}
            target="_blank"
            rel="noopener noreferrer"
            data-testid={testId}
            aria-label={label ? name : undefined}
            className="inline-flex items-center gap-1 px-2 py-1 rounded-md text-[10px] font-bold text-primary hover:underline whitespace-nowrap"
        >
            <FileImage size={11} />
            {t("viewScan")}
        </a>
    );
}

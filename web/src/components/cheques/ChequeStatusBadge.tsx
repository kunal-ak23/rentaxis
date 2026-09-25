import { useTranslations } from "next-intl";
import { cn } from "@/lib/utils";
import type { ChequeStatus } from "@/lib/api/leasing";

const STATUS_COLORS: Record<ChequeStatus, string> = {
    DRAFT: "bg-input text-muted",
    REGISTERED: "bg-info/10 text-info",
    DEPOSITED: "bg-primary/10 text-primary",
    CLEARED: "bg-success/10 text-success",
    BOUNCED: "bg-error/10 text-error",
    REPLACED: "bg-input text-muted",
    CANCELLED: "bg-input text-muted",
    RETURNED: "bg-warning/10 text-warning",
    ONLINE_PENDING: "bg-warning/10 text-warning",
    TRANSFERRED: "bg-input text-muted",
};

/** A cheque's status, coloured the same way everywhere it appears — the
 * register, the collection list, the return/replace queue and the
 * post-dated book. */
export default function ChequeStatusBadge({ status, testId }: { status: ChequeStatus; testId?: string }) {
    const t = useTranslations("Cheques");
    return (
        <span
            data-testid={testId}
            className={cn(
                "inline-flex items-center px-2 py-0.5 rounded-md text-[9px] font-bold uppercase tracking-wider",
                STATUS_COLORS[status],
            )}
        >
            {t(`status.${status}`)}
        </span>
    );
}

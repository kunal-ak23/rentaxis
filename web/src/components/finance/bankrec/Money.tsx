import { fmtAmount } from "@/lib/api/ledger";

/** An amount, always LTR inside the RTL page. */
export function Money({ v, strong, testId }: { v: number | null | undefined; strong?: boolean; testId?: string }) {
    if (v === null || v === undefined) return <span className="text-muted">—</span>;
    return (
        <bdi dir="ltr" data-testid={testId} className={`tabular-nums ${strong ? "font-bold" : ""} ${v < 0 ? "text-error" : ""}`}>
            {fmtAmount(v)}
        </bdi>
    );
}

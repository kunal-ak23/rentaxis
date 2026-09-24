"use client";

import { useTranslations } from "next-intl";

/**
 * The notice + checkbox `useStatementCoverGuard` needs wherever it's used
 * (F14-20). `testIdPrefix` keeps each dialog's checkbox/notice ids distinct
 * where more than one of these can be on screen at once.
 */
export function StatementCoverNotice({
    notice,
    checked,
    onChange,
    testIdPrefix = "statement-cover",
}: {
    notice: string;
    checked: boolean;
    onChange: (v: boolean) => void;
    testIdPrefix?: string;
}) {
    const tCommon = useTranslations("Common");
    return (
        <div className="space-y-1.5" data-testid={`${testIdPrefix}-notice`}>
            <p className="text-[11px] text-warning">{notice}</p>
            <label className="flex items-center gap-2 text-[11px] cursor-pointer">
                <input
                    type="checkbox"
                    data-testid={`${testIdPrefix}-not-on-statement`}
                    checked={checked}
                    onChange={e => onChange(e.target.checked)}
                />
                {tCommon("notOnStatement")}
            </label>
        </div>
    );
}

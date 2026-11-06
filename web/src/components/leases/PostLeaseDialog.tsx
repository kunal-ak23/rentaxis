"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "./LeaseDialog";
import {
    ApiError,
    leaseApi,
    type IncomeFromOptions,
    type LeaseDetail,
    type PostLeaseDryRunResponse,
    type PostLeaseResponse,
    type TransferJournals,
} from "@/lib/api/leasing";
import { fmtAmount } from "@/lib/api/ledger";
import { serverText } from "@/components/finance/bankrec/serverText";
import { formatDate } from "@/lib/format";
import { isLeaseChanged } from "@/lib/leases/leaseVersion";

/**
 * The review step in front of the Post button.
 *
 * It calls the server's own dry run rather than re-deciding anything here: the
 * same validation the real post runs, with nothing written. That matters
 * because the two cannot then disagree — a screen that approves a post the
 * server refuses is worse than no review at all.
 *
 * Every error comes back as its own string and every one of them is rendered.
 * Showing the first and hiding the rest turns one round of corrections into
 * four.
 */

type Props = {
    open: boolean;
    lease: LeaseDetail;
    onClose: () => void;
    onPosted: (res: PostLeaseResponse) => void;
    /**
     * Break-it R2 F2: the contract changed since this dialog loaded it (409
     * lease.changed). The page reloads the lease; the new version re-prices the
     * dialog, so the user reviews the new figures before posting them.
     */
    onStale?: () => void;
};

export default function PostLeaseDialog({ open, lease, onClose, onPosted, onStale }: Props) {
    const t = useTranslations("Leasing");
    const tCommon = useTranslations("Common");
    const [dry, setDry] = useState<PostLeaseDryRunResponse | null>(null);
    const [loading, setLoading] = useState(false);
    const [posting, setPosting] = useState(false);
    const [error, setError] = useState<string | null>(null);
    // Break-it R2 F2: "this contract changed" outlives the re-pricing it triggers.
    const [stale, setStale] = useState<string | null>(null);
    // Back-dated contracts (client call, Nov 2026): the month income starts from.
    const backdated = isBackdated(lease);
    const [incomeFrom, setIncomeFrom] = useState<string | null>(null);
    const [income, setIncome] = useState<IncomeFromOptions | null>(null);

    useEffect(() => {
        if (!open || !backdated) {
            setIncome(null);
            setIncomeFrom(null);
            return;
        }
        let cancelled = false;
        Promise.resolve()
            .then(() => leaseApi.incomeFrom(lease.id, incomeFrom))
            .then(res => {
                if (cancelled) return;
                setIncome(res);
                if (incomeFrom == null && res.backdated) setIncomeFrom(res.defaultFrom);
            })
            .catch((e: unknown) => {
                if (!cancelled) setError(e instanceof ApiError ? serverText(tCommon, e) || e.message : t("postFailed"));
            });
        return () => {
            cancelled = true;
        };
    }, [open, backdated, lease.id, lease.version, incomeFrom, t, tCommon]);

    useEffect(() => {
        if (!open) {
            setDry(null);
            setError(null);
            setStale(null);
            return;
        }
        let cancelled = false;
        setLoading(true);
        setError(null);
        leaseApi
            .dryRunPost(lease.id)
            .then(res => {
                if (!cancelled) setDry(res);
            })
            .catch((e: unknown) => {
                if (!cancelled) setError(e instanceof ApiError ? e.message : t("postFailed"));
            })
            .finally(() => {
                if (!cancelled) setLoading(false);
            });
        return () => {
            cancelled = true;
        };
        // lease.version: a reload after a 409 lease.changed prices the contract again.
    }, [open, lease.id, lease.version, t]);

    const handlePost = async () => {
        setPosting(true);
        setError(null);
        setStale(null);
        try {
            // Break-it R2 F2: the version these figures were priced on; the server refuses a newer contract.
            const from = income?.backdated ? incomeFrom : null;
            const res = from
                ? await leaseApi.post(lease.id, lease.version, from)
                : await leaseApi.post(lease.id, lease.version);
            onPosted(res);
        } catch (e) {
            if (isLeaseChanged(e)) {
                setStale(serverText(tCommon, e) || (e as ApiError).message);
                onStale?.();
                return;
            }
            // F15-07: a coded refusal (e.g. a fee charged on both leases) in the user's language.
            setError(e instanceof ApiError ? serverText(tCommon, e) || e.message : t("postFailed"));
        } finally {
            setPosting(false);
        }
    };

    const ok = !!dry?.ok;

    return (
        <LeaseDialog
            open={open}
            title={t("dryRunTitle")}
            onClose={onClose}
            onConfirm={handlePost}
            confirmText={t("postLease")}
            cancelText={t("cancel")}
            confirmDisabled={!ok}
            busy={posting || loading}
            confirmTestId="post-lease-confirm"
        >
            <div data-testid="post-dry-run" className="space-y-3 text-xs">
                <p className="text-muted">
                    {lease.displayContractNumber
                        ? t("postConfirmNumbered", { number: lease.displayContractNumber })
                        : t("postConfirmUnnumbered")}
                </p>

                {loading && <p className="text-muted">{t("dryRunLoading")}</p>}

                {dry && (
                    <>
                        <dl className="space-y-1.5 rounded-xl border border-border bg-input/30 px-3 py-2.5">
                            <Row label={t("contractValue")} value={fmtAmount(dry.contractValue)} />
                            <Row label={t("vat")} value={fmtAmount(dry.contractValueInclVat - dry.contractValue)} />
                            <Row label={t("contractValueInclVat")} value={fmtAmount(dry.contractValueInclVat)} />
                            <Row label={t("chequeTotal")} value={fmtAmount(dry.chequeTotal)} testId="post-cheque-total" />
                            {dry.depositCarriedForward > 0 && (
                                <Row label={t("depositCarriedForward")} value={fmtAmount(dry.depositCarriedForward)} />
                            )}
                            {!!dry.carriedCheques?.length && (
                                <Row label={t("carriedChequesTotal", { count: dry.carriedCheques.length })}
                                     value={fmtAmount(dry.carriedCheques.reduce((sum, c) => sum + c.amount, 0))} testId="post-carried-total" />
                            )}
                        </dl>
                        {/* F15-13: the carried instruments the post registers on this lease. */}
                        {!!dry.carriedCheques?.length && (
                            <ul className="space-y-0.5 text-muted" data-testid="post-carried-cheques">
                                {dry.carriedCheques.map(c => (
                                    <li key={`${c.seqNo}-${c.chequeNumber}`} className="flex gap-2">
                                        <span>{t("carriedCheque", { number: c.chequeNumber ?? String(c.seqNo) })}</span>
                                        <bdi dir="ltr">{formatDate(c.chequeDate)}</bdi>
                                        <span className="ms-auto tabular-nums"><bdi dir="ltr">{fmtAmount(c.amount)}</bdi></span>
                                    </li>
                                ))}
                            </ul>
                        )}
                        {/* #100-f: the transfer's own journal groups, in posting order. */}
                        {dry.journals.transfer && (
                            <TransferGroups groups={dry.journals.transfer} />
                        )}
                        <p className="text-muted" data-testid="post-journals">
                            {t("dryRunJournals", {
                                tco: dry.journals.tco,
                                tcoLines: dry.journals.tcoLines,
                                pdr: dry.journals.pdr,
                            })}
                        </p>
                        {income?.backdated && income.earliest && income.latest && (
                            <IncomeFromSection
                                options={income}
                                value={incomeFrom}
                                onChange={setIncomeFrom}
                            />
                        )}
                        {!!dry.notices?.length && (
                            <ul data-testid="post-dry-run-notices" className="space-y-1 text-info list-disc ms-4">
                                {dry.notices.map((n, i) => (
                                    <li key={i}>{n}</li>
                                ))}
                            </ul>
                        )}
                        {ok ? (
                            <p className="text-success font-semibold" data-testid="post-dry-run-ok">
                                {t("dryRunOk")}
                            </p>
                        ) : (
                            <div>
                                <p className="text-error font-semibold mb-1">{t("dryRunErrors")}</p>
                                <ul data-testid="post-dry-run-errors" className="space-y-1 text-error list-disc ms-4">
                                    {dry.errors.map((e, i) => (
                                        <li key={i}>{e}</li>
                                    ))}
                                </ul>
                            </div>
                        )}
                    </>
                )}

                {(stale || error) && (
                    <p className="text-error" data-testid="post-error" role="alert">
                        {stale ?? error}
                    </p>
                )}
            </div>
        </LeaseDialog>
    );
}

/** The term started before the month of the contract date (the server decides; this only saves a call). */
export function isBackdated(lease: Pick<LeaseDetail, "startDate" | "contractDate">): boolean {
    if (!lease.contractDate || !lease.startDate) return false;
    return lease.startDate < `${lease.contractDate.slice(0, 7)}-01`;
}

/** Every first-of-month from `from` to `to` inclusive (yyyy-MM-01). */
export function monthsBetween(from: string, to: string): string[] {
    const out: string[] = [];
    let [y, m] = [Number(from.slice(0, 4)), Number(from.slice(5, 7))];
    const end = Number(to.slice(0, 4)) * 12 + Number(to.slice(5, 7));
    while (y * 12 + m <= end && out.length < 600) {
        out.push(`${y}-${String(m).padStart(2, "0")}-01`);
        m += 1;
        if (m > 12) { m = 1; y += 1; }
    }
    return out;
}

function IncomeFromSection({ options, value, onChange }: {
    options: IncomeFromOptions;
    value: string | null;
    onChange: (v: string) => void;
}) {
    const t = useTranslations("Leasing");
    const months = monthsBetween(options.earliest!, options.latest!);
    const label = (iso: string) => `${iso.slice(5, 7)}/${iso.slice(0, 4)}`;
    return (
        <div data-testid="post-income-from" className="space-y-1.5 rounded-xl border border-warning/40 bg-warning/5 px-3 py-2.5">
            <p className="font-semibold">{t("incomeFrom.title")}</p>
            <p className="text-muted">{t("incomeFrom.help")}</p>
            <label className="flex items-center gap-2">
                <span className="text-muted">{t("incomeFrom.label")}</span>
                <select
                    data-testid="post-income-from-select"
                    className="rounded-lg border border-border bg-input px-2 py-1"
                    value={value ?? options.defaultFrom ?? ""}
                    onChange={e => onChange(e.target.value)}
                >
                    {months.map(m => (
                        <option key={m} value={m}>{label(m)}</option>
                    ))}
                </select>
            </label>
            {options.foldedMonths > 0 ? (
                <p data-testid="post-income-from-fold">
                    {t("incomeFrom.fold", {
                        months: options.foldedMonths,
                        amount: fmtAmount(options.foldedAmount),
                        date: formatDate(options.catchUpOn ?? ""),
                    })}
                </p>
            ) : (
                <p className="text-muted" data-testid="post-income-from-fold">{t("incomeFrom.noFold")}</p>
            )}
        </div>
    );
}

function TransferGroups({ groups }: { groups: TransferJournals }) {
    const t = useTranslations("Leasing");
    const moveDate = formatDate(groups.moveDate);
    const jvDate = formatDate(groups.balanceCarriedDate);
    return (
        <div data-testid="post-transfer-journals">
            <p className="text-muted font-semibold mb-1">{t("dryRunTransfer.title")}</p>
            <dl className="space-y-1.5 rounded-xl border border-border bg-input/30 px-3 py-2.5">
                {groups.pdrReversals > 0 && (
                    <Row label={t("dryRunTransfer.pdrReversals", { count: groups.pdrReversals, date: moveDate })}
                         value={fmtAmount(groups.pdrReversalTotal)} testId="post-transfer-pdr" />
                )}
                {groups.unearned > 0 && (
                    <Row label={t("dryRunTransfer.tcr", { date: moveDate })} value={fmtAmount(groups.unearned)} testId="post-transfer-tcr" />
                )}
                {groups.unearnedVat > 0 && (
                    <Row label={t("dryRunTransfer.tcrVat")} value={fmtAmount(groups.unearnedVat)} testId="post-transfer-tcr-vat" />
                )}
                {groups.balanceCarried !== 0 && (
                    <Row label={t(groups.balanceCarried < 0 ? "dryRunTransfer.balancePrepaid" : "dryRunTransfer.balanceOwed", { date: jvDate })}
                         value={fmtAmount(Math.abs(groups.balanceCarried))} testId="post-transfer-balance" />
                )}
                {groups.depositCarried > 0 && (
                    <Row label={t("dryRunTransfer.deposit")} value={fmtAmount(groups.depositCarried)} testId="post-transfer-deposit" />
                )}
            </dl>
        </div>
    );
}

function Row({ label, value, testId }: { label: string; value: string; testId?: string }) {
    return (
        <div className="flex items-center justify-between gap-3">
            <dt className="text-muted">{label}</dt>
            <dd className="font-semibold tabular-nums" data-testid={testId}>
                {value}
            </dd>
        </div>
    );
}

"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LeaseDialog from "./LeaseDialog";
import {
    ApiError,
    leaseApi,
    type LeaseDetail,
    type PostLeaseDryRunResponse,
    type PostLeaseResponse,
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
            const res = await leaseApi.post(lease.id, lease.version);
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
                        <p className="text-muted" data-testid="post-journals">
                            {t("dryRunJournals", {
                                tco: dry.journals.tco,
                                tcoLines: dry.journals.tcoLines,
                                pdr: dry.journals.pdr,
                            })}
                        </p>
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

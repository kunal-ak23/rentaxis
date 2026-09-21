"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { AlertTriangle, Lock, RefreshCw, ShieldCheck, Upload } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount, ledgerApi } from "@/lib/api/ledger";
import {
    cutoverApi,
    type OpeningBalanceGrid,
    type OpeningBalanceRow,
    type SnapshotUploadResult,
} from "@/lib/api/cutover";
import {
    SNAPSHOT_ACCEPT, canEditOpeningBalanceRow, canPostOpeningBalances,
    canReplaceOpeningBalances, snapshotRefusal,
} from "@/lib/cutoverRules";
import { differenceOf, sumAmounts } from "@/lib/money";
import { hasPermission, type UserRole } from "@/lib/rbac";

/**
 * Step 2 of the cut-over (spec §10.3): the opening-balance grid, PACT's trial
 * balance, and the OB journal that opens the books balanced.
 *
 * **Two read-only columns, for two different reasons.** A DERIVED account is one
 * the contract import produces, and `OpeningBalanceService.setRow` 400s a
 * hand-typed figure for it. The OPENING_BALANCE_DIFFERENCE account is worse than
 * refused: `setRow` accepts a figure and `postFresh` then silently skips the
 * account, because the server works that number out itself from the gap between
 * the columns. Both are shown, neither can be typed into, and each says why —
 * a cell whose value is quietly discarded teaches people the screen lies.
 *
 * **Post and Replace are different acts.** `post()` refuses a second post;
 * `repost(reason)` reverses the live journal and writes a corrected one in one
 * transaction. Exactly one of the two buttons is ever on screen, decided by
 * `grid.posted`. Replace demands a reason, because the reversal's narration is
 * where that reason ends up.
 *
 * **Editing stays open after posting.** `setRow` has no posted check, and
 * correcting the snapshot while a journal is live is precisely how a Replace is
 * prepared. Disabling the grid there would refuse what the server allows.
 *
 * **Unsaved cells are never overwritten.** The grid reloads after an upload, a
 * post and a manual refresh; each reload keeps whatever is typed and unsaved
 * (`edits` below), because losing an accountant's half-finished column to a
 * background refetch is the plan 3 bug in a new place. Post is blocked while any
 * cell is unsaved, so the journal always matches what is on screen.
 */

const th = "text-start px-4 py-3 text-[11px] font-semibold text-muted uppercase tracking-wider";
const td = "px-4 py-2 text-xs text-foreground";
const field =
    "w-32 text-end bg-input border border-border rounded-lg px-3 py-1.5 text-xs text-foreground tabular-nums focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const fieldLabel = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5";

/** One cell the accountant has typed but not yet sent. */
type Edit = { debit: string; credit: string };

const num = (s: string) => {
    const n = Number.parseFloat(s);
    return Number.isFinite(n) ? n : 0;
};

/** The stored figure as text, with a true zero shown as an empty cell rather than "0". */
const asText = (n: number) => (n ? String(n) : "");

export default function OpeningBalancesPage() {
    const t = useTranslations("Cutover");
    const tLedger = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManageOpeningBalances");

    const [grid, setGrid] = useState<OpeningBalanceGrid | null>(null);
    const [differenceAccountId, setDifferenceAccountId] = useState<string | null>(null);
    const [edits, setEdits] = useState<Record<string, Edit>>({});
    const [loading, setLoading] = useState(true);
    const [busy, setBusy] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [actionError, setActionError] = useState<string | null>(null);
    const [success, setSuccess] = useState<string | null>(null);
    const [upload, setUpload] = useState<SnapshotUploadResult | null>(null);
    const [uploadError, setUploadError] = useState<string | null>(null);
    const [confirm, setConfirm] = useState<"post" | "replace" | null>(null);
    const [replaceReason, setReplaceReason] = useState("");
    const uploadRef = useRef<HTMLInputElement>(null);

    const load = useCallback(() => {
        setLoading(true);
        setLoadError(null);
        // `edits` is deliberately NOT cleared here: a reload must not take away
        // what is half-typed. Saving a row is what removes its edit.
        return cutoverApi.openingBalances
            .grid()
            .then(setGrid)
            .catch(e => setLoadError(e instanceof ApiError ? e.message : tCommon("loadFailed")))
            .finally(() => setLoading(false));
    }, [tCommon]);

    useEffect(() => {
        if (!userRole) return;
        if (!allowed) {
            setLoading(false);
            return;
        }
        load();
        // Which account absorbs the difference is a role mapping, not a grid
        // field, so it is read from the default accounts — the same endpoint the
        // settings screen uses, and the same three roles.
        ledgerApi.defaults
            .get()
            .then(rows => {
                const m = rows.find(r => r.role === "OPENING_BALANCE_DIFFERENCE");
                setDifferenceAccountId(m?.accountId ?? null);
            })
            .catch(() => setDifferenceAccountId(null));
    }, [userRole, allowed, load]);

    const valueOf = useCallback(
        (r: OpeningBalanceRow): Edit =>
            edits[r.accountId] ?? { debit: asText(r.enteredDebit), credit: asText(r.enteredCredit) },
        [edits],
    );

    /**
     * Totals over what is ON SCREEN — the saved figures with the unsaved edits
     * laid over them — so the footer always agrees with the column above it.
     * Added in fils (`sumAmounts`), never as plain floats.
     */
    const totals = useMemo(() => {
        const rows = grid?.rows ?? [];
        const debit = sumAmounts(rows.map(r => num(valueOf(r).debit)));
        const credit = sumAmounts(rows.map(r => num(valueOf(r).credit)));
        return { debit, credit, difference: differenceOf(debit, credit) };
    }, [grid, valueOf]);

    const unsavedCount = Object.keys(edits).length;

    const setEdit = (accountId: string, patch: Partial<Edit>, current: Edit) =>
        setEdits(e => ({ ...e, [accountId]: { ...current, ...patch } }));

    const run = async (fn: () => Promise<void>) => {
        setBusy(true);
        setActionError(null);
        try {
            await fn();
        } catch (e) {
            setActionError(e instanceof ApiError ? e.message : tCommon("loadFailed"));
        } finally {
            setBusy(false);
        }
    };

    const saveRow = (r: OpeningBalanceRow) =>
        run(async () => {
            const v = valueOf(r);
            const debit = num(v.debit);
            const credit = num(v.credit);
            // ManualOpeningBalanceDTO boxes both fields: null is "nothing on that
            // side", which is not the same as 0 and is what clears a row.
            await cutoverApi.openingBalances.setRow(r.accountId, {
                debit: debit || null,
                credit: credit || null,
            });
            setEdits(e => {
                const next = { ...e };
                delete next[r.accountId];
                return next;
            });
            await load();
        });

    const onUpload = (file: File) => {
        const refused = snapshotRefusal(file);
        if (refused) {
            // Checked here rather than after a 10MB round trip that can only fail.
            setUploadError(t(refused));
            setUpload(null);
            return;
        }
        setUploadError(null);
        return run(async () => {
            setUpload(await cutoverApi.openingBalances.uploadSnapshot(file));
            await load();
        });
    };

    const doPost = () =>
        run(async () => {
            const e = await cutoverApi.openingBalances.post();
            setConfirm(null);
            setSuccess(t("openingBalancesPosted", { number: e.entryNumber }));
            await load();
        });

    const doReplace = () =>
        run(async () => {
            const e = await cutoverApi.openingBalances.repost({ reason: replaceReason });
            setConfirm(null);
            setReplaceReason("");
            setSuccess(t("openingBalancesPosted", { number: e.entryNumber }));
            await load();
        });

    if (!userRole) {
        return <div data-testid="ob-loading" className="bg-input rounded-xl h-14 animate-pulse" />;
    }

    if (!allowed) {
        return (
            <div className="max-w-4xl" data-testid="ob-access-denied">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{tLedger("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted">{t("notAllowedOpeningBalances")}</p>
                </div>
            </div>
        );
    }

    const problems = grid?.problems ?? [];
    const blocker =
        problems.length > 0
            ? t("gridProblems")
            : unsavedCount > 0
              ? t("unsavedEdits", { n: unsavedCount })
              : null;

    return (
        <div>
            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            <div className="flex flex-wrap items-start justify-between gap-4 mb-6">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">
                        {t("openingBalances")}
                    </h1>
                    <p className="text-sm text-muted" data-testid="ob-as-of">
                        {/* The server's date (booksStartDate - 1), never this page's. */}
                        {t("openingBalancesDesc", { date: grid ? fmtIsoDate(grid.asOf, locale) : "…" })}
                    </p>
                </div>
                <div className="flex flex-wrap items-center gap-3">
                    <button
                        type="button"
                        data-testid="ob-refresh"
                        onClick={load}
                        disabled={busy}
                        className="border border-border px-3 py-2 rounded-lg text-xs font-semibold flex items-center gap-2 cursor-pointer text-foreground disabled:opacity-50"
                    >
                        <RefreshCw size={13} />
                        {tCommon("retry")}
                    </button>
                    <label className="border border-border px-4 py-2 rounded-lg text-xs font-bold flex items-center gap-2 cursor-pointer text-foreground">
                        <Upload size={14} />
                        {t("uploadTrialBalance")}
                        <input
                            ref={uploadRef}
                            type="file"
                            data-testid="ob-upload"
                            aria-label={t("uploadTrialBalance")}
                            className="hidden"
                            accept={SNAPSHOT_ACCEPT}
                            onChange={e => {
                                const f = e.target.files?.[0];
                                if (f) onUpload(f);
                            }}
                        />
                    </label>
                    {grid && canPostOpeningBalances(grid) && (
                        <button
                            type="button"
                            data-testid="ob-post"
                            disabled={busy || !!blocker}
                            aria-describedby={blocker ? "ob-blocker-reason" : undefined}
                            onClick={() => setConfirm("post")}
                            className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-bold cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            {t("postOpeningBalances")}
                        </button>
                    )}
                    {grid && canReplaceOpeningBalances(grid) && (
                        <button
                            type="button"
                            data-testid="ob-replace"
                            disabled={busy || !!blocker}
                            aria-describedby={blocker ? "ob-blocker-reason" : undefined}
                            onClick={() => {
                                setReplaceReason("");
                                setConfirm("replace");
                            }}
                            className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-bold cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            {t("replaceOpeningBalances")}
                        </button>
                    )}
                </div>
            </div>

            <p className="text-xs text-muted mb-4">{t("trialBalanceFormat")}</p>

            {success && (
                <div
                    role="status"
                    data-testid="ob-success"
                    className="mb-4 bg-success/10 border border-success/30 text-success rounded-xl px-5 py-3 text-xs font-medium"
                >
                    {success}
                </div>
            )}

            {grid?.posted && (
                <div
                    data-testid="ob-posted-banner"
                    className="mb-4 bg-input border border-border text-muted rounded-xl px-5 py-3 text-xs flex items-center gap-2"
                >
                    <Lock size={14} className="shrink-0" />
                    {t("alreadyPosted", { number: grid.journalNumber ?? "" })}
                </div>
            )}

            {problems.length > 0 && (
                <div
                    data-testid="ob-problems"
                    className="mb-4 bg-error/10 border border-error/30 text-error rounded-xl px-5 py-3 text-xs"
                >
                    <p className="font-bold mb-1">{t("gridProblems")}</p>
                    <ul className="list-disc ms-5 space-y-0.5">
                        {problems.map((p, i) => (
                            <li key={i}>{p}</li>
                        ))}
                    </ul>
                </div>
            )}

            {uploadError && (
                <p role="alert" data-testid="ob-upload-error" className="mb-4 text-xs font-semibold text-error">
                    {uploadError}
                </p>
            )}

            {upload && (
                <div className="mb-4 space-y-1 text-xs">
                    <p data-testid="ob-upload-stored" className="text-muted">
                        {t("uploadedRows", { n: upload.stored })}
                    </p>
                    {upload.unmatchedCodes.length > 0 && (
                        <p data-testid="ob-upload-unmatched" className="text-warning">
                            {t("unmatchedCodes", { n: upload.unmatchedCodes.length })}{" "}
                            <span className="font-mono">{upload.unmatchedCodes.join(", ")}</span>
                        </p>
                    )}
                    {upload.problems.length > 0 && (
                        <div data-testid="ob-upload-problems" className="text-error">
                            <p className="font-semibold">{t("uploadProblems", { n: upload.problems.length })}</p>
                            {/* Every rejected line, each with the number the accountant
                                has to look at. Scrolled rather than truncated: a list
                                that stops at ten hides the eleventh problem. */}
                            <ul className="list-disc ms-5 max-h-48 overflow-auto space-y-0.5">
                                {upload.problems.map((p, i) => (
                                    <li key={i}>{p}</li>
                                ))}
                            </ul>
                        </div>
                    )}
                </div>
            )}

            {actionError && (
                <p role="alert" data-testid="ob-error" className="mb-4 text-xs font-semibold text-error">
                    {actionError}
                </p>
            )}

            {loading && !grid && (
                <div className="space-y-3 animate-pulse">
                    {[1, 2, 3, 4, 5].map(i => (
                        <div key={i} className="bg-input rounded-xl h-12" />
                    ))}
                </div>
            )}

            {grid && (
                <>
                    <div className="bg-surface border border-border rounded-xl shadow-sm overflow-hidden">
                        <div className="overflow-x-auto max-h-[60vh]">
                            <table className="w-full" data-testid="ob-grid">
                                <thead className="bg-input/60 border-b border-border sticky top-0">
                                    <tr>
                                        <th className={th}>{tLedger("code")}</th>
                                        <th className={th}>{tLedger("account")}</th>
                                        <th className={`${th} text-end`}>{tLedger("debit")}</th>
                                        <th className={`${th} text-end`}>{tLedger("credit")}</th>
                                        <th className={th}>{t("source")}</th>
                                        <th className={`${th} text-end`} />
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border">
                                    {grid.rows.map(r => {
                                        const v = valueOf(r);
                                        const editable = canEditOpeningBalanceRow(r, differenceAccountId);
                                        const isDifference =
                                            !!differenceAccountId && r.accountId === differenceAccountId;
                                        const dirty = !!edits[r.accountId];
                                        return (
                                            <tr
                                                key={r.accountId}
                                                data-testid={`ob-row-${r.accountId}`}
                                                className={editable ? "hover:bg-input/30" : "bg-input/20"}
                                            >
                                                <td className={`${td} font-mono text-muted`}>{r.code}</td>
                                                <td className={td}>{r.name}</td>
                                                {editable ? (
                                                    <>
                                                        <td className={`${td} text-end`}>
                                                            <input
                                                                data-testid={`ob-debit-${r.accountId}`}
                                                                aria-label={`${r.code} ${t("enteredDebit")}`}
                                                                inputMode="decimal"
                                                                className={field}
                                                                value={v.debit}
                                                                onChange={e =>
                                                                    setEdit(r.accountId, { debit: e.target.value }, v)
                                                                }
                                                            />
                                                        </td>
                                                        <td className={`${td} text-end`}>
                                                            <input
                                                                data-testid={`ob-credit-${r.accountId}`}
                                                                aria-label={`${r.code} ${t("enteredCredit")}`}
                                                                inputMode="decimal"
                                                                className={field}
                                                                value={v.credit}
                                                                onChange={e =>
                                                                    setEdit(r.accountId, { credit: e.target.value }, v)
                                                                }
                                                            />
                                                        </td>
                                                    </>
                                                ) : (
                                                    <>
                                                        <td className={`${td} text-end tabular-nums text-muted`}>
                                                            {r.enteredDebit ? fmtAmount(r.enteredDebit) : "—"}
                                                        </td>
                                                        <td className={`${td} text-end tabular-nums text-muted`}>
                                                            {r.enteredCredit ? fmtAmount(r.enteredCredit) : "—"}
                                                        </td>
                                                    </>
                                                )}
                                                <td className={`${td} text-[10px] text-muted max-w-xs`}>
                                                    {editable ? (
                                                        t("manual")
                                                    ) : (
                                                        <span data-testid={`ob-readonly-${r.accountId}`}>
                                                            {isDifference
                                                                ? t("differenceAccountWhy")
                                                                : t("derivedWhy", { role: r.derivedRole ?? "" })}
                                                        </span>
                                                    )}
                                                </td>
                                                <td className={`${td} text-end whitespace-nowrap`}>
                                                    {dirty && (
                                                        <span className="inline-flex items-center gap-2">
                                                            <span
                                                                data-testid={`ob-unsaved-${r.accountId}`}
                                                                className="text-[10px] font-semibold text-warning"
                                                            >
                                                                {t("unsavedRow")}
                                                            </span>
                                                            <button
                                                                type="button"
                                                                data-testid={`ob-save-${r.accountId}`}
                                                                disabled={busy}
                                                                onClick={() => saveRow(r)}
                                                                className="text-primary hover:underline cursor-pointer font-semibold disabled:opacity-50"
                                                            >
                                                                {t("saveRow")}
                                                            </button>
                                                        </span>
                                                    )}
                                                </td>
                                            </tr>
                                        );
                                    })}
                                </tbody>
                                <tfoot className="bg-input/60 border-t border-border sticky bottom-0">
                                    <tr>
                                        <td className={`${td} font-bold`} colSpan={2}>
                                            {tLedger("reportTotal")}
                                        </td>
                                        <td
                                            data-testid="ob-total-debit"
                                            className={`${td} text-end tabular-nums font-bold`}
                                        >
                                            {fmtAmount(totals.debit)}
                                        </td>
                                        <td
                                            data-testid="ob-total-credit"
                                            className={`${td} text-end tabular-nums font-bold`}
                                        >
                                            {fmtAmount(totals.credit)}
                                        </td>
                                        <td colSpan={2} />
                                    </tr>
                                </tfoot>
                            </table>
                        </div>
                    </div>

                    <div className="mt-4 flex flex-wrap items-center justify-between gap-4">
                        {blocker ? (
                            <p
                                id="ob-blocker-reason"
                                data-testid="ob-blocker"
                                className="text-xs font-medium text-warning"
                            >
                                {blocker}
                            </p>
                        ) : (
                            <span />
                        )}
                        <div className="flex items-center gap-8 text-xs">
                            {/*
                             * A difference is normal, not an error: the OB journal
                             * closes it against OPENING_BALANCE_DIFFERENCE. The
                             * server accepts an unbalanced trial balance on purpose
                             * — "that disagreement is what the reconciliation report
                             * exists to show" — so this is a warning, never an alert.
                             */}
                            {totals.difference !== 0 && (
                                <span
                                    data-testid="ob-difference-warning"
                                    className="text-warning inline-flex items-center gap-1.5"
                                >
                                    <AlertTriangle size={12} />
                                    {t("differenceGoesToEquity")}
                                </span>
                            )}
                            <span className="font-bold text-foreground">
                                {t("difference")}:{" "}
                                <span data-testid="ob-difference" className="font-mono tabular-nums">
                                    {fmtAmount(totals.difference)}
                                </span>
                            </span>
                        </div>
                    </div>
                </>
            )}

            <ConfirmDialog
                isOpen={confirm === "post"}
                onClose={() => setConfirm(null)}
                onConfirm={doPost}
                isLoading={busy}
                title={t("postOpeningBalances")}
                description={t("confirmPostOpeningBalances", {
                    date: grid ? fmtIsoDate(grid.asOf, locale) : "",
                })}
                confirmText={t("postOpeningBalances")}
                cancelText={tLedger("cancel")}
                confirmTestId="confirm-ob-post"
            />

            <ConfirmDialog
                isOpen={confirm === "replace"}
                onClose={() => setConfirm(null)}
                onConfirm={doReplace}
                isLoading={busy}
                isDestructive
                title={t("replaceOpeningBalances")}
                description={t("confirmReplaceOpeningBalances", { number: grid?.journalNumber ?? "" })}
                confirmText={t("replaceOpeningBalances")}
                cancelText={tLedger("cancel")}
                confirmTestId="confirm-ob-replace"
                // repost's reason becomes the reversal's narration — an empty one
                // leaves the ledger saying only "Opening balances re-posted".
                confirmDisabled={!replaceReason.trim()}
            >
                <div>
                    <label className={fieldLabel} htmlFor="ob-replace-reason">
                        {t("replaceReason")}
                    </label>
                    <input
                        id="ob-replace-reason"
                        data-testid="ob-replace-reason"
                        className="w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none"
                        value={replaceReason}
                        onChange={e => setReplaceReason(e.target.value)}
                    />
                </div>
                {!replaceReason.trim() && (
                    <p data-testid="ob-replace-blocker" className="text-xs font-semibold text-warning">
                        {t("replaceReasonRequired")}
                    </p>
                )}
            </ConfirmDialog>
        </div>
    );
}

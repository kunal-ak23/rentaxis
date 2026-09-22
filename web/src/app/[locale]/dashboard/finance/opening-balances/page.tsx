"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { AlertTriangle, Lock, RefreshCw, ShieldCheck, Upload } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pagination } from "@/components/ui/Pagination";
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
    SNAPSHOT_ACCEPT, advisoryProblems, blockingProblems, canEditOpeningBalanceRow,
    canPostOpeningBalances, canReplaceOpeningBalances, canReverseOpeningBalances,
    gridDeclaresComputed, problemMessage, snapshotRefusal, type ComputedAccountSource,
} from "@/lib/cutoverRules";
import { differenceOf, parseAmount, sumAmounts } from "@/lib/money";
import { useUnsavedChangesWarning } from "@/hooks/useUnsavedChangesWarning";
import { hasPermission, type UserRole } from "@/lib/rbac";

/**
 * Step 2 of the cut-over (spec §10.3): the opening-balance grid, PACT's trial
 * balance, and the OB journal that opens the books balanced.
 *
 * **Three figures per row, and the subtraction between them is shown** (ruling
 * R25). What our books already hold at D − 1 with the opening entry taken out;
 * what PACT's trial balance says, which is the only one anybody types; and what
 * a post would WRITE, which is the second less the first. On almost every row
 * the first is nothing and the last two coincide — on the bank a cleared cheque
 * reached, or the output VAT a contract raised, they do not, and posting PACT's
 * figure gross there would count the cut-over twice. The footer totals and the
 * difference sentence read the POST figures, because the footer is what the
 * journal will do. An older backend sends no post figures and the screen falls
 * back to the entered ones, which is exactly what that backend would post.
 *
 * **Two read-only columns, for two different reasons.** A DERIVED account is one
 * the contract import produces, and `OpeningBalanceService.setRow` 400s a
 * hand-typed figure for it. The OPENING_BALANCE_DIFFERENCE account is worse than
 * refused: `setRow` accepts a figure and `postFresh` then silently skips the
 * account, because the server works that number out itself from the gap between
 * the columns. Both are shown, neither can be typed into, and each says why —
 * a cell whose value is quietly discarded teaches people the screen lies.
 *
 * **Post, Replace and Reverse are three different acts.** `post()` refuses a
 * second post; `repost(reason)` reverses the live journal and writes a corrected
 * one in one transaction; `reverse(reason)` takes the journal off and leaves the
 * figures, which is the only way back to "books not yet opened". Post is on
 * screen while the books are closed, Replace and Reverse while they are open —
 * decided by `grid.posted`, never by this page's memory of what it just did.
 * Both of the latter demand a reason, because it becomes the reversal's
 * narration and is all the ledger will say about why six months from now.
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

/** Enough to work through without scrolling; the rest are a page away, not hidden. */
const PROBLEMS_PER_PAGE = 20;

/** One cell the accountant has typed but not yet sent. */
type Edit = { debit: string; credit: string };

/**
 * A cell's value, or null when it holds text that is not an amount.
 *
 * Blank is 0 — an untouched row is the ordinary state of the grid. Anything
 * unreadable is null, NOT 0: a typo saved as a silent zero is a figure the
 * accountant never entered and will never be told about.
 */
const cellAmount = (s: string): number | null => (s.trim() === "" ? 0 : parseAmount(s));

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
    /**
     * Starts `pending`, which means NOTHING is editable. See
     * `canEditOpeningBalanceRow`: until the computed account is positively
     * identified, an editable cell is one whose value the server will accept and
     * then throw away.
     */
    const [computedSource, setComputedSource] = useState<ComputedAccountSource>({ kind: "pending" });
    const [lookupFailed, setLookupFailed] = useState(false);
    const [edits, setEdits] = useState<Record<string, Edit>>({});
    const [loading, setLoading] = useState(true);
    const [busy, setBusy] = useState(false);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [actionError, setActionError] = useState<string | null>(null);
    const [success, setSuccess] = useState<string | null>(null);
    const [upload, setUpload] = useState<SnapshotUploadResult | null>(null);
    const [uploadError, setUploadError] = useState<string | null>(null);
    const [problemPage, setProblemPage] = useState(0);
    const [confirm, setConfirm] = useState<"post" | "replace" | "reverse" | null>(null);
    const [replaceReason, setReplaceReason] = useState("");
    const [reverseReason, setReverseReason] = useState("");
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

    /**
     * Find the account the server computes for itself.
     *
     * A newer backend marks it on the row (`computed`), in which case the rows are
     * authoritative and this never runs. An older one does not, so it is looked up
     * through the OPENING_BALANCE_DIFFERENCE default mapping — and a lookup that
     * fails, or finds nothing, leaves the grid LOCKED rather than open.
     */
    const resolveComputedAccount = useCallback(() => {
        setLookupFailed(false);
        setComputedSource({ kind: "pending" });
        return ledgerApi.defaults
            .get()
            .then(rows => {
                const m = rows.find(r => r.role === "OPENING_BALANCE_DIFFERENCE");
                setComputedSource({ kind: "lookup", accountId: m?.accountId ?? null });
                if (!m?.accountId) setLookupFailed(true);
            })
            .catch(() => {
                setComputedSource({ kind: "lookup", accountId: null });
                setLookupFailed(true);
            });
    }, []);

    useEffect(() => {
        if (!userRole) return;
        if (!allowed) {
            setLoading(false);
            return;
        }
        load();
    }, [userRole, allowed, load]);

    useEffect(() => {
        if (!userRole || !allowed || !grid) return;
        // The rows say so themselves on a newer backend — no second request.
        if (gridDeclaresComputed(grid.rows)) {
            setComputedSource({ kind: "rows" });
            setLookupFailed(false);
            return;
        }
        if (computedSource.kind !== "pending") return;
        resolveComputedAccount();
    }, [userRole, allowed, grid, computedSource.kind, resolveComputedAccount]);

    const valueOf = useCallback(
        (r: OpeningBalanceRow): Edit =>
            edits[r.accountId] ?? { debit: asText(r.enteredDebit), credit: asText(r.enteredCredit) },
        [edits],
    );

    /** A row whose typed text is not an amount at all. */
    const rowIsInvalid = useCallback(
        (r: OpeningBalanceRow) => {
            const v = valueOf(r);
            return cellAmount(v.debit) === null || cellAmount(v.credit) === null;
        },
        [valueOf],
    );

    const invalidCount = useMemo(
        () => (grid?.rows ?? []).filter(r => !!edits[r.accountId] && rowIsInvalid(r)).length,
        [grid, edits, rowIsInvalid],
    );

    /**
     * Totals over what is ON SCREEN — the saved figures with the unsaved edits
     * laid over them — so the footer always agrees with the column above it.
     * Added in fils (`sumAmounts`), never as plain floats.
     */
    /**
     * The two figures this row contributes to the JOURNAL (ruling R25).
     *
     * `postDebit`/`postCredit` where the server sends them — PACT's figure less
     * what our books already hold, and the balancing figure on the difference
     * row. Where it does not, what is ON SCREEN: that is the older backend's
     * meaning of the entered column, and reading the screen rather than
     * `r.entered*` keeps the footer moving with an unsaved edit.
     */
    const postOf = useCallback(
        (r: OpeningBalanceRow): Edit | { debit: number; credit: number } => {
            if (r.postDebit !== undefined || r.postCredit !== undefined) {
                return { debit: r.postDebit ?? 0, credit: r.postCredit ?? 0 };
            }
            return valueOf(r);
        },
        [valueOf],
    );

    const totals = useMemo(() => {
        const rows = grid?.rows ?? [];
        // The POST figures, not the entered ones: the footer is what the journal
        // will do, and on the accounts the cut-over also writes to the two
        // differ. `sumAmounts` skips what it cannot read, so an in-progress typo
        // shows the total of the rest rather than "NaN"; `invalidCount` is what
        // stops that total being saved or posted.
        const debit = sumAmounts(rows.map(r => postOf(r).debit));
        const credit = sumAmounts(rows.map(r => postOf(r).credit));
        return { debit, credit, difference: differenceOf(debit, credit) };
    }, [grid, postOf]);

    const unsavedCount = Object.keys(edits).length;

    /**
     * What the OB journal will put on the Opening Balance Difference account.
     *
     * Read off the COMPUTED ROW's post figures, not off the gap between the
     * columns. That row carries the balancing figure — `OpeningBalanceRowDTO`
     * says so in as many words — so the columns always agree and the old
     * `difference !== 0` warning, which keyed off their gap, could never appear
     * at all: the one sentence explaining where an unbalanced trial balance goes
     * was unreachable.
     *
     * The fallback is that gap, for a grid with no computed row on screen — an
     * older backend, or a chart with no difference account, where what the
     * server would balance is exactly the gap.
     */
    const differenceLine = useMemo(() => {
        const computedRow = (grid?.rows ?? []).find(
            r =>
                r.computed === true
                || (computedSource.kind === "lookup" && computedSource.accountId === r.accountId),
        );
        if (!computedRow) return totals.difference;
        const post = postOf(computedRow);
        return differenceOf(post.debit, post.credit);
    }, [grid, computedSource, totals.difference, postOf]);

    /**
     * Does the server send what the posted cut-over contracts put on the derived
     * accounts? Two extra columns only where they exist — an older backend sends
     * neither, and inventing a "0.00" for it would say something untrue.
     */
    const showsImported = useMemo(
        () => (grid?.rows ?? []).some(r => r.derivedDebit !== undefined || r.derivedCredit !== undefined),
        [grid],
    );

    /**
     * And the same for what a post would WRITE. Shown on the same terms: where
     * the server does not send it the fallback makes the column a copy of the
     * entered one, and a column that restates its neighbour is noise.
     */
    const showsPost = useMemo(
        () => (grid?.rows ?? []).some(r => r.postDebit !== undefined || r.postCredit !== undefined),
        [grid],
    );

    // A refresh or tab close with cells typed loses them; Post is already blocked
    // in-app, which does not help against the browser's own chrome.
    useUnsavedChangesWarning(unsavedCount > 0);

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
            const debit = cellAmount(v.debit);
            const credit = cellAmount(v.credit);
            // Guarded here as well as on the button: null means the cell holds
            // text that is not an amount, and sending it as 0 is the silent
            // substitution this whole path exists to avoid.
            if (debit === null || credit === null) return;
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
            setProblemPage(0);
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

    /**
     * Takes the OB journal off the books and leaves the grid's figures alone, so
     * the tenant is back to "books not yet opened" with the snapshot intact.
     * `load()` afterwards rather than patching `grid`: the reversed state is the
     * server's — `posted` false, the difference row recomputed — and this screen
     * should not be guessing at either.
     */
    const doReverse = () =>
        run(async () => {
            const e = await cutoverApi.openingBalances.reverse({ reason: reverseReason });
            setConfirm(null);
            setReverseReason("");
            setSuccess(t("openingBalancesReversed", { number: e.entryNumber }));
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

    /**
     * Split by severity, never by count. `problems` is a mixed bag: one entry is
     * fatal (`postable`'s missing difference account, which `postFresh` throws
     * on) and the rest are advisory — including the one every real PACT export
     * carries. Gating Post on `problems.length > 0` greyed the button out on the
     * ordinary path with no escape but hand-editing the CSV.
     */
    const problems = grid?.problems ?? [];
    const blockers = blockingProblems(problems);
    const advisories = advisoryProblems(problems);
    /** The grid is locked until the computed account is positively identified. */
    const locked = computedSource.kind === "pending" || (computedSource.kind === "lookup" && !computedSource.accountId);
    const blocker =
        blockers.length > 0
            ? t("gridProblems")
            : locked
              ? t("differenceAccountUnknown")
              : invalidCount > 0
                ? t("invalidCells", { n: invalidCount })
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
                            data-primary={grid.changedSincePosted ? "true" : "false"}
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
                    {grid && canReverseOpeningBalances(grid) && (
                        <button
                            type="button"
                            data-testid="ob-reverse"
                            // `busy` only, deliberately: `blocker` is about the
                            // GRID — unsaved cells, unreadable ones, a fatal
                            // problem — and none of it reaches a reversal, which
                            // takes the live journal off and touches no figure.
                            // Gating it on the grid would refuse what the server
                            // allows, and would do it exactly when somebody is
                            // mid-correction and wants the books closed again.
                            disabled={busy}
                            onClick={() => {
                                setReverseReason("");
                                setConfirm("reverse");
                            }}
                            className="border border-error/40 text-error px-4 py-2 rounded-lg text-xs font-bold cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed"
                        >
                            {t("reverseOpeningBalances")}
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

            {blockers.length > 0 && (
                <div
                    data-testid="ob-problems"
                    className="mb-4 bg-error/10 border border-error/30 text-error rounded-xl px-5 py-3 text-xs"
                >
                    <p className="font-bold mb-1">{t("gridProblems")}</p>
                    <ul className="list-disc ms-5 space-y-0.5">
                        {blockers.map((p, i) => (
                            <li key={i}>{problemMessage(p)}</li>
                        ))}
                    </ul>
                </div>
            )}

            {/* Said, never in the way: the server posts over every one of these. */}
            {advisories.length > 0 && (
                <div
                    data-testid="ob-advisories"
                    className="mb-4 bg-warning/10 border border-warning/30 text-warning rounded-xl px-5 py-3 text-xs"
                >
                    <p className="font-bold mb-1">{t("gridAdvisories")}</p>
                    <ul className="list-disc ms-5 space-y-0.5">
                        {advisories.map((p, i) => (
                            <li key={i}>{problemMessage(p)}</li>
                        ))}
                    </ul>
                </div>
            )}

            {locked && grid && (
                <div
                    data-testid="ob-locked"
                    className="mb-4 bg-warning/10 border border-warning/30 text-warning rounded-xl px-5 py-3 text-xs flex items-start justify-between gap-3"
                >
                    <span className="flex items-start gap-2">
                        <AlertTriangle size={14} className="shrink-0 mt-0.5" />
                        {t("differenceAccountUnknown")}
                    </span>
                    {lookupFailed && (
                        <button
                            type="button"
                            data-testid="ob-locked-retry"
                            onClick={resolveComputedAccount}
                            className="shrink-0 font-semibold hover:underline cursor-pointer"
                        >
                            {t("retryLookup")}
                        </button>
                    )}
                </div>
            )}

            {grid?.changedSincePosted && grid.posted && (
                <div
                    data-testid="ob-changed-since-posted"
                    className="mb-4 bg-warning/10 border border-warning/30 text-warning rounded-xl px-5 py-3 text-xs flex items-start gap-2"
                >
                    <AlertTriangle size={14} className="shrink-0 mt-0.5" />
                    {t("changedSincePosted")}
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
                    {upload.balanced === false && (
                        <p data-testid="ob-upload-unbalanced" className="text-warning">
                            {t("uploadDoesNotBalance")}
                            {upload.totalDebit !== undefined && upload.totalCredit !== undefined && (
                                <span className="ms-2 font-mono tabular-nums">
                                    {t("uploadTotals", {
                                        debit: fmtAmount(upload.totalDebit),
                                        credit: fmtAmount(upload.totalCredit),
                                    })}
                                </span>
                            )}
                        </p>
                    )}
                    {upload.problems.length > 0 && (
                        <div data-testid="ob-upload-problems" className="text-error">
                            <p className="font-semibold">{t("uploadProblems", { n: upload.problems.length })}</p>
                            {/* Every rejected line, each with the number the accountant
                                has to look at — paged rather than scrolled, so a file
                                with two hundred bad rows is worked through instead of
                                flicked past. */}
                            <ul className="list-disc ms-5 space-y-0.5">
                                {upload.problems
                                    .slice(problemPage * PROBLEMS_PER_PAGE, problemPage * PROBLEMS_PER_PAGE + PROBLEMS_PER_PAGE)
                                    .map((p, i) => (
                                        <li key={problemPage * PROBLEMS_PER_PAGE + i}>{p}</li>
                                    ))}
                            </ul>
                            {upload.problems.length > PROBLEMS_PER_PAGE && (
                                <Pagination
                                    currentPage={problemPage + 1}
                                    totalItems={upload.problems.length}
                                    itemsPerPage={PROBLEMS_PER_PAGE}
                                    onPageChange={p => setProblemPage(p - 1)}
                                />
                            )}
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
                                        {showsImported && <th className={`${th} text-end`}>{t("derivedDebit")}</th>}
                                        {showsImported && <th className={`${th} text-end`}>{t("derivedCredit")}</th>}
                                        <th className={`${th} text-end`}>{tLedger("debit")}</th>
                                        <th className={`${th} text-end`}>{tLedger("credit")}</th>
                                        {showsPost && <th className={`${th} text-end`}>{t("postDebit")}</th>}
                                        {showsPost && <th className={`${th} text-end`}>{t("postCredit")}</th>}
                                        <th className={th}>{t("source")}</th>
                                        <th className={`${th} text-end`} />
                                    </tr>
                                </thead>
                                <tbody className="divide-y divide-border">
                                    {grid.rows.map(r => {
                                        const v = valueOf(r);
                                        const editable = canEditOpeningBalanceRow(r, computedSource);
                                        const isComputed =
                                            r.computed === true
                                            || (computedSource.kind === "lookup"
                                                && computedSource.accountId === r.accountId);
                                        const dirty = !!edits[r.accountId];
                                        return (
                                            <tr
                                                key={r.accountId}
                                                data-testid={`ob-row-${r.accountId}`}
                                                className={editable ? "hover:bg-input/30" : "bg-input/20"}
                                            >
                                                <td className={`${td} font-mono text-muted`}>{r.code}</td>
                                                <td className={td}>{r.name}</td>
                                                {showsImported && (
                                                    <>
                                                        <td
                                                            data-testid={`ob-derived-debit-${r.accountId}`}
                                                            className={`${td} text-end tabular-nums text-muted`}
                                                        >
                                                            {fmtAmount(r.derivedDebit ?? 0)}
                                                        </td>
                                                        <td
                                                            data-testid={`ob-derived-credit-${r.accountId}`}
                                                            className={`${td} text-end tabular-nums text-muted`}
                                                        >
                                                            {fmtAmount(r.derivedCredit ?? 0)}
                                                        </td>
                                                    </>
                                                )}
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
                                                {showsPost && (
                                                    <>
                                                        {/* What the journal writes for this row:
                                                            PACT's figure less what our books already
                                                            hold. Read-only because it is the server's
                                                            arithmetic, not an opinion. */}
                                                        <td
                                                            data-testid={`ob-post-debit-${r.accountId}`}
                                                            className={`${td} text-end tabular-nums font-medium`}
                                                        >
                                                            {fmtAmount(r.postDebit ?? 0)}
                                                        </td>
                                                        <td
                                                            data-testid={`ob-post-credit-${r.accountId}`}
                                                            className={`${td} text-end tabular-nums font-medium`}
                                                        >
                                                            {fmtAmount(r.postCredit ?? 0)}
                                                        </td>
                                                    </>
                                                )}
                                                <td className={`${td} text-[10px] text-muted max-w-xs`}>
                                                    {editable ? (
                                                        t("manual")
                                                    ) : (
                                                        <span data-testid={`ob-readonly-${r.accountId}`}>
                                                            {r.derived
                                                                ? t("derivedWhy", { role: r.derivedRole ?? "" })
                                                                : isComputed
                                                                  ? t("differenceAccountWhy")
                                                                  : t("differenceAccountUnknown")}
                                                        </span>
                                                    )}
                                                </td>
                                                <td className={`${td} text-end whitespace-nowrap`}>
                                                    {dirty && (
                                                        <span className="inline-flex items-center gap-2">
                                                            {rowIsInvalid(r) && (
                                                                <span
                                                                    data-testid={`ob-invalid-${r.accountId}`}
                                                                    className="text-[10px] font-semibold text-error"
                                                                >
                                                                    {t("invalidCell")}
                                                                </span>
                                                            )}
                                                            <span
                                                                data-testid={`ob-unsaved-${r.accountId}`}
                                                                className="text-[10px] font-semibold text-warning"
                                                            >
                                                                {t("unsavedRow")}
                                                            </span>
                                                            <button
                                                                type="button"
                                                                data-testid={`ob-save-${r.accountId}`}
                                                                disabled={busy || rowIsInvalid(r)}
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
                                        {/*
                                         * The totals sit under the LAST figure pair, which is the
                                         * one they total: the post columns where the server sends
                                         * them, the entered ones where it does not. Same rule, one
                                         * span — code + name + whichever pairs come before.
                                         */}
                                        <td
                                            className={`${td} font-bold`}
                                            colSpan={2 + (showsImported ? 2 : 0) + (showsPost ? 2 : 0)}
                                        >
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
                             * closes it against OPENING_BALANCE_DIFFERENCE, and the
                             * server accepts an unbalanced trial balance on purpose
                             * — "that disagreement is what the reconciliation report
                             * exists to show". So this states the figure rather than
                             * warning about it.
                             *
                             * The magnitude, not the signed number: debit-positive,
                             * a POSITIVE difference is a CREDIT to the difference
                             * account, and a minus sign that means the opposite of
                             * what it looks like helps nobody. Which side it lands
                             * on is on the account's own row, three lines up.
                             */}
                            {differenceLine !== 0 && (
                                <span data-testid="ob-difference-line" className="text-muted">
                                    {t("differencePostsToAccount", {
                                        amount: fmtAmount(Math.abs(differenceLine)),
                                    })}
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

            <ConfirmDialog
                isOpen={confirm === "reverse"}
                onClose={() => setConfirm(null)}
                onConfirm={doReverse}
                isLoading={busy}
                isDestructive
                title={t("reverseOpeningBalances")}
                description={t("confirmReverseOpeningBalances", { number: grid?.journalNumber ?? "" })}
                confirmText={t("reverseOpeningBalances")}
                cancelText={tLedger("cancel")}
                confirmTestId="confirm-ob-reverse"
                // The server defaults the narration, but a reversal of the
                // opening balances with nothing said about why is a mystery in
                // the ledger six months later.
                confirmDisabled={!reverseReason.trim()}
            >
                <div>
                    <label className={fieldLabel} htmlFor="ob-reverse-reason">
                        {t("reverseReason")}
                    </label>
                    <input
                        id="ob-reverse-reason"
                        data-testid="ob-reverse-reason"
                        className="w-full bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none"
                        value={reverseReason}
                        onChange={e => setReverseReason(e.target.value)}
                    />
                </div>
                {!reverseReason.trim() && (
                    <p data-testid="ob-reverse-blocker" className="text-xs font-semibold text-warning">
                        {t("reverseReasonRequired")}
                    </p>
                )}
            </ConfirmDialog>
        </div>
    );
}

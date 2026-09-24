"use client";

import { Fragment, useCallback, useEffect, useMemo, useState } from "react";
import { useTranslations } from "next-intl";
import { AlertTriangle, ArrowLeft, CheckCircle2, Eye, Filter, Send } from "lucide-react";
import { useRouter } from "@/i18n/routing";
import { loadAccounts } from "@/components/finance/AccountPicker";
import { useNameLookup } from "@/components/finance/useNameLookup";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount, type Account } from "@/lib/api/ledger";
import { useStatementCoverGuard } from "@/lib/statementCoverGuard";
import { StatementCoverNotice } from "@/components/finance/StatementCoverNotice";
import {
    approvedFrom,
    paymentRunsApi,
    type PaymentMethod,
    type PaymentRun,
    type PaymentRunInput,
    type RunCandidates,
    type RunPreview,
    type RunProblem,
} from "@/lib/api/payables";

const th = "px-3 py-2.5 text-[10px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap text-start";
const td = "px-3 py-2 text-xs";
const field = "bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const label = "text-[10px] font-semibold text-muted uppercase tracking-wider";
const button = "flex items-center gap-1.5 px-3 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold hover:bg-input transition-all cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed";
const primary = "flex items-center gap-1.5 px-4 py-2 rounded-lg bg-primary text-white text-xs font-bold hover:bg-primary/90 transition-all cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed";

const pad = (n: number) => String(n).padStart(2, "0");
function isoOf(d: Date): string {
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}
export function todayIso(): string {
    return isoOf(new Date());
}
/** Spec §2 step 1: due on or before today + 7 by default. */
export function defaultDueBefore(): string {
    const d = new Date();
    d.setDate(d.getDate() + 7);
    return isoOf(d);
}

function Amount({ v, strong }: { v: number | null | undefined; strong?: boolean }) {
    if (v === null || v === undefined) return <span className="text-muted">—</span>;
    return <bdi dir="ltr" className={`tabular-nums ${strong ? "font-bold" : ""}`}>{fmtAmount(v)}</bdi>;
}

const itemKey = (i: { kind: string; id: string }) => `${i.kind}:${i.id}`;
const num = (s: string) => {
    const n = Number(String(s).replace(/,/g, ""));
    return Number.isFinite(n) ? n : NaN;
};

/** One problem, in the reader's language when the code is known; the server's sentence otherwise. */
export function ProblemLine({ p }: { p: RunProblem }) {
    const t = useTranslations("PaymentRuns");
    const key = `problems.${p.code}`;
    const text = t.has(key) ? t(key, p.params) : p.message;
    const warn = p.severity === "WARNING";
    return (
        <li data-testid={`problem-${p.code}`}
            className={`flex items-start gap-2 text-xs ${warn ? "text-warning" : "text-danger"}`}>
            <AlertTriangle size={13} className="shrink-0 mt-0.5" />
            <span><span className="font-bold">{t(warn ? "warning" : "error")}:</span> {text}</span>
        </li>
    );
}

/**
 * The new-run wizard (finance-ops spec §2): 1. select due invoices across
 * vendors and set the header; 2. save and preview — one payment per vendor with
 * its items, advance, net, cheque number and journal lines, and every problem
 * at once; then post, all or nothing. Nothing is posted before the preview.
 * With {@code run} it edits that DRAFT.
 */
export function PaymentRunWizard({ run, onPosted }: { run?: PaymentRun; onPosted?: () => void }) {
    const t = useTranslations("PaymentRuns");
    const tCommon = useTranslations("Common");
    const router = useRouter();
    const properties = useNameLookup("properties");

    const [step, setStep] = useState<"select" | "preview">("select");
    // Editing a draft: the due filter starts late enough to list every item it
    // already holds (review P3-4), so nothing is sent that cannot be seen.
    const [dueBefore, setDueBefore] = useState(() => {
        const latest = (run?.items ?? []).map(i => i.dueDate ?? "").reduce((a, b) => (b > a ? b : a), "");
        const d = defaultDueBefore();
        return latest > d ? latest : d;
    });
    const [propertyId, setPropertyId] = useState("");
    const [vendorId, setVendorId] = useState("");
    const [includePartPaid, setIncludePartPaid] = useState(true);
    const [candidates, setCandidates] = useState<RunCandidates | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);

    const [payAccounts, setPayAccounts] = useState<Account[]>([]);
    const [paymentDate, setPaymentDate] = useState(run?.paymentDate ?? todayIso());
    const [paymentAccountId, setPaymentAccountId] = useState(run?.paymentAccountId ?? "");
    const [method, setMethod] = useState<PaymentMethod>(run?.method ?? "TRANSFER");
    const [chequeDate, setChequeDate] = useState(run?.chequeDate ?? "");
    const [firstCheque, setFirstCheque] = useState(run?.firstChequeNumber ?? "");
    const [narration, setNarration] = useState(run?.narration ?? "");
    /** Selected items by key → the amount typed. */
    const [selected, setSelected] = useState<Record<string, string>>(() =>
        Object.fromEntries((run?.items ?? []).map(i => [
            i.invoiceId ? `PISR:${i.invoiceId}` : `OPENING:${i.openingItemId}`, i.amount.toFixed(2)])));
    /** Vendors whose advance is NOT applied (the default is on). */
    const [noAdvance, setNoAdvance] = useState<Record<string, boolean>>(() =>
        Object.fromEntries((run?.items ?? []).filter(i => !i.applyAdvance).map(i => [i.vendorId, true])));

    const [runId, setRunId] = useState<string | null>(run?.id ?? null);
    const [preview, setPreview] = useState<RunPreview | null>(null);
    const [busy, setBusy] = useState(false);
    const [formError, setFormError] = useState<string | null>(null);
    const [confirmPost, setConfirmPost] = useState(false);
    const cover = useStatementCoverGuard(tCommon);

    const loadCandidates = useCallback(async () => {
        setLoading(true);
        setLoadError(null);
        try {
            setCandidates(await paymentRunsApi.candidates({
                dueBefore: dueBefore || undefined, propertyId: propertyId || undefined,
                includePartPaid, excludeRunId: runId ?? undefined,
            }));
        } catch (err) {
            setCandidates(null);
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        } finally {
            setLoading(false);
        }
    }, [dueBefore, propertyId, includePartPaid, runId, tCommon]);

    useEffect(() => {
        loadCandidates();
        // Filters apply on "Show"; the first load uses the defaults.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, []);

    useEffect(() => {
        let alive = true;
        loadAccounts()
            .then(rows => alive && setPayAccounts(rows.filter(a => !a.group && a.active && a.accountType === "ASSET"
                && (a.accountSubType === "BANK" || a.accountSubType === "CASH"))))
            .catch(() => {});
        return () => {
            alive = false;
        };
    }, []);

    // The vendor filter narrows the list, but never hides a row that is selected.
    const items = useMemo(() => {
        const rows = candidates?.items ?? [];
        return vendorId ? rows.filter(c => c.item.vendorId === vendorId || itemKey(c.item) in selected) : rows;
    }, [candidates, vendorId, selected]);
    const vendorOptions = useMemo(() => {
        const m = new Map<string, string>();
        for (const c of candidates?.items ?? []) m.set(c.item.vendorId, c.item.vendorName ?? "");
        return [...m.entries()].sort((a, b) => a[1].localeCompare(b[1]));
    }, [candidates]);
    const advanceOf = useMemo(
        () => Object.fromEntries((candidates?.advances ?? []).map(a => [a.vendorId, a.unallocated])),
        [candidates],
    );
    const openOf = useMemo(
        () => Object.fromEntries((candidates?.items ?? []).map(c => [itemKey(c.item), c.item])),
        [candidates],
    );

    const selectedRows = useMemo(() => Object.entries(selected).map(([k, v]) => ({ key: k, amount: num(v), item: openOf[k] })),
        [selected, openOf]);
    /** Selected items the open list no longer has (paid elsewhere, or outside the filters): shown, never sent silently. */
    const missing = useMemo(() => (candidates ? selectedRows.filter(r => !r.item) : []), [candidates, selectedRows]);
    // PR #352 re-review R3: a missing row is either no longer open at all, or only
    // outside the current filters. The unfiltered list tells them apart, so "Drop
    // them" never throws away a selection a filter merely hides.
    const missingKey = missing.map(r => r.key).sort().join(",");
    const [openAnywhere, setOpenAnywhere] = useState<Set<string> | null>(null);
    useEffect(() => {
        if (!missingKey) {
            setOpenAnywhere(null);
            return;
        }
        let alive = true;
        paymentRunsApi.candidates({ includePartPaid: true, excludeRunId: runId ?? undefined })
            .then(all => alive && setOpenAnywhere(new Set(all.items.map(c => itemKey(c.item)))))
            .catch(() => alive && setOpenAnywhere(new Set()));
        return () => {
            alive = false;
        };
    }, [missingKey, runId]);
    const hidden = openAnywhere ? missing.filter(r => openAnywhere.has(r.key)) : [];
    const gone = openAnywhere ? missing.filter(r => !openAnywhere.has(r.key)) : [];
    const widenFilters = () => {
        setDueBefore("");
        setPropertyId("");
        setVendorId("");
        setIncludePartPaid(true);
        setLoading(true);
        setLoadError(null);
        paymentRunsApi.candidates({ includePartPaid: true, excludeRunId: runId ?? undefined })
            .then(setCandidates)
            .catch(err => setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed")))
            .finally(() => setLoading(false));
    };
    const selectedTotal = useMemo(
        () => Math.round(selectedRows.reduce((s, r) => s + (Number.isFinite(r.amount) ? r.amount * 100 : 0), 0)) / 100,
        [selectedRows]);
    const tooHigh = (k: string) => {
        const o = openOf[k];
        const a = num(selected[k] ?? "");
        return !!o && Number.isFinite(a) && Math.round(a * 100) > Math.round(o.open * 100);
    };
    const account = payAccounts.find(a => a.id === paymentAccountId) ?? null;
    const blocker =
        selectedRows.length === 0 ? t("selectSomething")
        : missing.length > 0 ? t("missingSelected", { count: missing.length })
        : selectedRows.some(r => !(r.amount > 0)) ? t("amountRequired")
        : Object.keys(selected).some(tooHigh) ? t("amountTooHigh")
        : !paymentAccountId ? t("chooseAccount")
        : method === "CASH" ? (account && account.accountSubType !== "CASH" ? t("cashNeedsCash") : null)
        : account && account.accountSubType !== "BANK" ? t("bankNeedsBank")
        : method === "CHEQUE" && !/\d$/.test(firstCheque.trim()) ? t("firstChequeDigits")
        : method === "CHEQUE" && chequeDate && chequeDate < paymentDate ? t("chequeBeforePayment")
        : null;

    const toggle = (k: string, open: number) =>
        setSelected(s => {
            const next = { ...s };
            if (k in next) delete next[k];
            else next[k] = open.toFixed(2);
            return next;
        });

    const body = (): PaymentRunInput => ({
        paymentDate,
        paymentAccountId,
        method,
        chequeDate: method === "CHEQUE" ? chequeDate || null : null,
        firstChequeNumber: method === "CHEQUE" ? firstCheque.trim() : null,
        narration: narration.trim() || null,
        items: selectedRows.map(r => {
            const [kind, id] = r.key.split(":");
            const vendor = r.item?.vendorId ?? run?.items.find(i => (i.invoiceId ?? i.openingItemId) === id)?.vendorId ?? "";
            return {
                ...(kind === "PISR" ? { invoiceId: id } : { openingItemId: id }),
                amount: r.amount,
                applyAdvance: !noAdvance[vendor],
            };
        }),
    });

    const saveAndPreview = async () => {
        setBusy(true);
        setFormError(null);
        try {
            const saved = runId ? await paymentRunsApi.update(runId, body()) : await paymentRunsApi.create(body());
            setRunId(saved.id);
            setPreview(await paymentRunsApi.preview(saved.id));
            setStep("preview");
        } catch (err) {
            setFormError(err instanceof ApiError ? err.message : t("actionFailed"));
        } finally {
            setBusy(false);
        }
    };

    const post = async () => {
        if (!runId) return;
        setBusy(true);
        setFormError(null);
        try {
            if (!preview) return;
            // What the preview showed: the server refuses (409) if the run would now post anything else.
            await paymentRunsApi.post(runId, { ...approvedFrom(preview), notOnStatement: cover.notOnStatement || undefined });
            setConfirmPost(false);
            if (onPosted) onPosted();
            else router.push(`/dashboard/finance/payables/payment-runs/${runId}`);
        } catch (err) {
            if (cover.catchStatementCover(err)) {
                // The notice + checkbox is now showing in the confirm dialog; the
                // user resubmits — the dialog stays open, nothing else to do.
                return;
            }
            setConfirmPost(false);
            setFormError(err instanceof ApiError
                ? (err.status === 409 ? `${t("runChanged")} ${err.message}` : err.message)
                : t("actionFailed"));
            // The world may have moved (an invoice paid elsewhere): show the fresh preview.
            try { setPreview(await paymentRunsApi.preview(runId)); } catch { /* keep the old one */ }
        } finally {
            setBusy(false);
        }
    };

    const vendorsInSelection = useMemo(() => {
        const m = new Map<string, string>();
        for (const r of selectedRows) if (r.item) m.set(r.item.vendorId, r.item.vendorName ?? "");
        return [...m.entries()];
    }, [selectedRows]);

    return (
        <div>
            <ol className="flex gap-4 mb-5 text-xs font-bold" aria-label={t("steps")}>
                <li className={step === "select" ? "text-primary" : "text-muted"} aria-current={step === "select" ? "step" : undefined}>
                    {t("stepSelect")}
                </li>
                <li className={step === "preview" ? "text-primary" : "text-muted"} aria-current={step === "preview" ? "step" : undefined}>
                    {t("stepPreview")}
                </li>
            </ol>

            {formError && (
                <div role="alert" data-testid="run-error" className="mb-4 text-xs text-danger bg-danger/10 border border-danger/20 rounded-lg px-3 py-2">
                    {formError}
                </div>
            )}

            {step === "select" && (
                <>
                    <form className="flex flex-wrap items-end gap-3 mb-4" onSubmit={e => { e.preventDefault(); loadCandidates(); }}>
                        <label className={label}>
                            <span className="block mb-1">{t("dueBefore")}</span>
                            <input type="date" data-testid="run-due-before" className={field} value={dueBefore}
                                   onChange={e => setDueBefore(e.target.value)} />
                        </label>
                        <label className={label}>
                            <span className="block mb-1">{t("property")}</span>
                            <select data-testid="run-property" className={field} value={propertyId} onChange={e => setPropertyId(e.target.value)}>
                                <option value="">{t("allProperties")}</option>
                                {properties.options.map(p => <option key={p.id} value={p.id}>{p.label}</option>)}
                            </select>
                        </label>
                        <label className={label}>
                            <span className="block mb-1">{t("vendor")}</span>
                            <select data-testid="run-vendor" className={field} value={vendorId} onChange={e => setVendorId(e.target.value)}>
                                <option value="">{t("allVendors")}</option>
                                {vendorOptions.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
                            </select>
                        </label>
                        <label className={`${label} flex items-center gap-2 pb-2`}>
                            <input type="checkbox" data-testid="run-part-paid" checked={includePartPaid}
                                   onChange={e => setIncludePartPaid(e.target.checked)} />
                            {t("includePartPaid")}
                        </label>
                        <button type="submit" className={button} disabled={loading} data-testid="run-filter">
                            <Filter size={13} />{t("show")}
                        </button>
                    </form>

                    {loadError && <LoadErrorBanner message={loadError} onRetry={loadCandidates} />}

                    <div className="bg-surface rounded-xl border border-border overflow-x-auto mb-5">
                        <table className="w-full" data-testid="run-candidates">
                            <thead className="bg-background border-b border-border">
                                <tr>
                                    <th className={th}><span className="sr-only">{t("select")}</span></th>
                                    <th className={th}>{t("vendor")}</th>
                                    <th className={th}>{t("invoice")}</th>
                                    <th className={th}>{t("dueDate")}</th>
                                    <th className={`${th} text-end`}>{t("daysOverdue")}</th>
                                    <th className={`${th} text-end`}>{t("open")}</th>
                                    <th className={`${th} text-end`}>{t("payAmount")}</th>
                                </tr>
                            </thead>
                            <tbody>
                                {items.length === 0 ? (
                                    <tr><td colSpan={7} className="text-center text-xs text-muted py-10">{loading ? t("loading") : t("noCandidates")}</td></tr>
                                ) : items.map(({ item, draftRuns }) => {
                                    const k = itemKey(item);
                                    const on = k in selected;
                                    const label = item.invoiceNumber ?? item.docNumber ?? "";
                                    return (
                                        <tr key={k} className="border-b border-border last:border-0">
                                            <td className={td}>
                                                <input type="checkbox" data-testid={`run-pick-${label}`} checked={on}
                                                       aria-label={t("selectInvoice", { invoice: label })}
                                                       onChange={() => toggle(k, item.open)} />
                                            </td>
                                            <td className={td}>{item.vendorName}</td>
                                            <td className={td}>
                                                <div className="font-semibold">{label}</div>
                                                <div className="text-[10px] text-muted">{item.docNumber}</div>
                                                {draftRuns.length > 0 && (
                                                    <div className="text-[10px] text-warning" data-testid={`run-held-${label}`}>
                                                        {t("heldBy", { runs: draftRuns.join(", ") })}
                                                    </div>
                                                )}
                                            </td>
                                            <td className={td}><bdi dir="ltr">{item.dueDate}</bdi></td>
                                            <td className={`${td} text-end tabular-nums`}>{item.daysOverdue > 0 ? item.daysOverdue : "—"}</td>
                                            <td className={`${td} text-end`}><Amount v={item.open} /></td>
                                            <td className={`${td} text-end`}>
                                                {on && (
                                                    <input dir="ltr" inputMode="decimal" data-testid={`run-amount-${label}`}
                                                           aria-label={t("payAmountFor", { invoice: label })}
                                                           aria-invalid={tooHigh(k)}
                                                           className={`${field} w-28 text-end ${tooHigh(k) ? "border-danger" : ""}`}
                                                           value={selected[k]}
                                                           onChange={e => setSelected(s => ({ ...s, [k]: e.target.value }))} />
                                                )}
                                            </td>
                                        </tr>
                                    );
                                })}
                            </tbody>
                        </table>
                    </div>

                    {missing.length > 0 && (
                        <div role="alert" data-testid="run-missing" className="mb-5 text-xs text-warning bg-warning/10 border border-warning/20 rounded-lg px-3 py-2 space-y-2">
                            {!openAnywhere && <div>{t("missingSelected", { count: missing.length })}</div>}
                            {gone.length > 0 && (
                                <div className="flex flex-wrap items-center gap-3" data-testid="run-missing-gone">
                                    <span>{t("goneSelected", { count: gone.length })}</span>
                                    <button type="button" className={button} data-testid="run-drop-missing"
                                            onClick={() => {
                                                const drop = new Set(gone.map(r => r.key));
                                                setSelected(s => Object.fromEntries(Object.entries(s).filter(([k]) => !drop.has(k))));
                                            }}>
                                        {t("dropMissing")}
                                    </button>
                                </div>
                            )}
                            {hidden.length > 0 && (
                                <div className="flex flex-wrap items-center gap-3" data-testid="run-missing-hidden">
                                    <span>{t("hiddenSelected", { count: hidden.length })}</span>
                                    <button type="button" className={button} data-testid="run-widen-filters" onClick={widenFilters}>
                                        {t("widenFilters")}
                                    </button>
                                </div>
                            )}
                        </div>
                    )}

                    {vendorsInSelection.some(([id]) => (advanceOf[id] ?? 0) > 0) && (
                        <div className="mb-5 space-y-1">
                            {vendorsInSelection.filter(([id]) => (advanceOf[id] ?? 0) > 0).map(([id, name]) => (
                                <label key={id} className="flex items-center gap-2 text-xs">
                                    <input type="checkbox" data-testid={`run-advance-${name}`} checked={!noAdvance[id]}
                                           onChange={e => setNoAdvance(s => ({ ...s, [id]: !e.target.checked }))} />
                                    <span>{name}: {t("applyAdvance", { amount: fmtAmount(advanceOf[id]) })}</span>
                                </label>
                            ))}
                        </div>
                    )}

                    <div className="bg-surface rounded-xl border border-border p-4 grid grid-cols-1 md:grid-cols-3 gap-3 mb-4">
                        <label className={label}>
                            <span className="block mb-1">{t("paymentDate")}</span>
                            <input type="date" data-testid="run-payment-date" className={`${field} w-full`} value={paymentDate}
                                   onChange={e => setPaymentDate(e.target.value)} />
                        </label>
                        <label className={label}>
                            <span className="block mb-1">{t("method")}</span>
                            <select data-testid="run-method" className={`${field} w-full`} value={method}
                                    onChange={e => setMethod(e.target.value as PaymentMethod)}>
                                {(["TRANSFER", "CHEQUE", "CASH"] as const).map(m => <option key={m} value={m}>{t(`methods.${m}`)}</option>)}
                            </select>
                        </label>
                        <label className={label}>
                            <span className="block mb-1">{t("account")}</span>
                            <select data-testid="run-account" className={`${field} w-full`} value={paymentAccountId}
                                    onChange={e => setPaymentAccountId(e.target.value)}>
                                <option value="">{t("chooseAccount")}</option>
                                {payAccounts.filter(a => method === "CASH" ? a.accountSubType === "CASH" : a.accountSubType === "BANK")
                                    .map(a => <option key={a.id} value={a.id}>{a.code} {a.name}</option>)}
                            </select>
                        </label>
                        {method === "CHEQUE" && (
                            <>
                                <label className={label}>
                                    <span className="block mb-1">{t("chequeDate")}</span>
                                    <input type="date" data-testid="run-cheque-date" className={`${field} w-full`} value={chequeDate}
                                           placeholder={paymentDate} onChange={e => setChequeDate(e.target.value)} />
                                </label>
                                <label className={label}>
                                    <span className="block mb-1">{t("firstCheque")}</span>
                                    <input dir="ltr" data-testid="run-first-cheque" className={`${field} w-full`} value={firstCheque}
                                           onChange={e => setFirstCheque(e.target.value)} />
                                </label>
                                {chequeDate && chequeDate > paymentDate && (
                                    <p className="md:col-span-3 text-xs font-semibold text-primary" data-testid="run-pdc-note">{t("pdcHeld")}</p>
                                )}
                            </>
                        )}
                        <label className={`${label} md:col-span-3`}>
                            <span className="block mb-1">{t("narration")}</span>
                            <input data-testid="run-narration" className={`${field} w-full`} value={narration}
                                   onChange={e => setNarration(e.target.value)} />
                        </label>
                    </div>

                    <div className="flex flex-wrap items-center justify-between gap-3">
                        <p className="text-xs text-muted" data-testid="run-selected-total">
                            {t("selectedTotal", { count: selectedRows.length, amount: fmtAmount(selectedTotal) })}
                        </p>
                        <div className="flex items-center gap-3">
                            {blocker && <span className="text-xs text-warning" data-testid="run-blocker">{blocker}</span>}
                            <button type="button" className={primary} disabled={!!blocker || busy} onClick={saveAndPreview}
                                    data-testid="run-save-preview">
                                <Eye size={13} />{busy ? t("saving") : t("saveAndPreview")}
                            </button>
                        </div>
                    </div>
                </>
            )}

            {step === "preview" && preview && (
                <RunPreviewView preview={preview}>
                    <div className="flex flex-wrap items-center justify-between gap-3 mt-5">
                        <button type="button" className={button} onClick={() => setStep("select")} data-testid="run-back">
                            <ArrowLeft size={13} className="rtl:rotate-180" />{t("back")}
                        </button>
                        <div className="flex items-center gap-3">
                            {!preview.postable && <span className="text-xs text-danger">{t("notPostable")}</span>}
                            <button type="button" className={primary} disabled={!preview.postable || busy}
                                    onClick={() => { cover.reset(); setConfirmPost(true); }} data-testid="run-post">
                                <Send size={13} className="rtl:-scale-x-100" />{t("postRun")}
                            </button>
                        </div>
                    </div>
                </RunPreviewView>
            )}

            <ConfirmDialog
                isOpen={confirmPost}
                onClose={() => setConfirmPost(false)}
                onConfirm={post}
                title={t("postRun")}
                description={t("confirmPost", { number: preview?.runNumber ?? "", amount: fmtAmount(preview?.netPayment ?? 0) })}
                confirmText={t("postRun")}
                cancelText={t("close")}
                isLoading={busy}
                confirmTestId="run-confirm-post"
            >
                {cover.notice && (
                    <StatementCoverNotice
                        notice={cover.notice}
                        checked={cover.notOnStatement}
                        onChange={cover.setNotOnStatement}
                        testIdPrefix="run-post"
                    />
                )}
            </ConfirmDialog>
        </div>
    );
}

/** The preview (spec §2 step 3): problems, then one block per vendor with its journal. */
export function RunPreviewView({ preview, children }: { preview: RunPreview; children?: React.ReactNode }) {
    const t = useTranslations("PaymentRuns");
    return (
        <div data-testid="run-preview">
            {preview.problems.length > 0 ? (
                <div className="mb-4 bg-surface rounded-xl border border-border p-4">
                    <h2 className="text-xs font-bold text-foreground mb-2">{t("problemsTitle")}</h2>
                    <ul className="space-y-1">{preview.problems.map((p, i) => <ProblemLine key={i} p={p} />)}</ul>
                </div>
            ) : (
                <p className="mb-4 flex items-center gap-2 text-xs font-semibold text-success" data-testid="run-no-problems">
                    <CheckCircle2 size={14} />{t("noProblems")}
                </p>
            )}

            <div className="bg-surface rounded-xl border border-border overflow-x-auto">
                <table className="w-full">
                    <thead className="bg-background border-b border-border">
                        <tr>
                            <th className={th}>{t("vendor")}</th>
                            <th className={th}>{t("invoice")}</th>
                            <th className={`${th} text-end`}>{t("amount")}</th>
                            <th className={`${th} text-end`}>{t("advanceApplied")}</th>
                            <th className={`${th} text-end`}>{t("netPayment")}</th>
                        </tr>
                    </thead>
                    <tbody>
                        {preview.vendors.map(v => (
                            <Fragment key={v.vendorId}>
                                <tr className="bg-background/60 border-b border-border" data-testid={`run-vendor-${v.vendorName}`}>
                                    <td className={`${td} font-bold`} colSpan={2}>
                                        {v.vendorName}
                                        <span className="ms-2 text-[10px] font-normal text-muted">
                                            {v.iban ? <>{t("iban")} <bdi dir="ltr">{v.iban}</bdi></> : t("noIban")}
                                        </span>
                                        {v.chequeNumber && (
                                            <span className="ms-2 text-[10px] font-semibold" data-testid={`run-cheque-${v.vendorName}`}>
                                                {t("chequeNo")} <bdi dir="ltr">{v.chequeNumber}</bdi>
                                                {v.chequeDate && <> · <bdi dir="ltr">{v.chequeDate}</bdi></>}
                                            </span>
                                        )}
                                        {v.postDated && <span className="ms-2 text-[10px] text-primary font-semibold">{t("pdcHeld")}</span>}
                                    </td>
                                    <td className={`${td} text-end`}><Amount v={v.itemsTotal} strong /></td>
                                    <td className={`${td} text-end`}><Amount v={v.advanceApplied} /></td>
                                    <td className={`${td} text-end`} data-testid={`run-net-${v.vendorName}`}><Amount v={v.netPayment} strong /></td>
                                </tr>
                                {v.items.map(i => (
                                    <tr key={i.itemId} className="border-b border-border">
                                        <td className={td} />
                                        <td className={td}>
                                            {i.invoiceNumber ?? i.docNumber}
                                            <span className="ms-2 text-[10px] text-muted">{t("openNow")} <Amount v={i.openNow} /></span>
                                        </td>
                                        <td className={`${td} text-end`}><Amount v={i.amount} /></td>
                                        <td className={`${td} text-end`}><Amount v={i.advanceApplied} /></td>
                                        <td className={`${td} text-end`}><Amount v={i.paid} /></td>
                                    </tr>
                                ))}
                                {v.journal.length > 0 && (
                                    <tr className="border-b border-border">
                                        <td className={td} />
                                        <td className={td} colSpan={4}>
                                            <div className="text-[10px] font-semibold text-muted uppercase mb-1">{t("journal")}</div>
                                            <table className="text-[11px]" data-testid={`run-journal-${v.vendorName}`}>
                                                <tbody>
                                                    {v.journal.map((j, n) => (
                                                        <tr key={n}>
                                                            <td className="pe-4"><bdi dir="ltr">{j.accountCode}</bdi> {j.accountName}</td>
                                                            <td className="pe-4 text-end">{j.debit > 0 && <>{t("debit")} <Amount v={j.debit} /></>}</td>
                                                            <td className="text-end">{j.credit > 0 && <>{t("credit")} <Amount v={j.credit} /></>}</td>
                                                        </tr>
                                                    ))}
                                                </tbody>
                                            </table>
                                        </td>
                                    </tr>
                                )}
                            </Fragment>
                        ))}
                    </tbody>
                    <tfoot>
                        <tr className="bg-background font-bold">
                            <td className={td} colSpan={2}>{t("total")}</td>
                            <td className={`${td} text-end`}><Amount v={preview.itemsTotal} strong /></td>
                            <td className={`${td} text-end`}><Amount v={preview.advanceApplied} strong /></td>
                            <td className={`${td} text-end`} data-testid="run-net-total"><Amount v={preview.netPayment} strong /></td>
                        </tr>
                    </tfoot>
                </table>
            </div>
            {children}
        </div>
    );
}

export function RunStatusChip({ status }: { status: PaymentRun["status"] }) {
    const t = useTranslations("PaymentRuns");
    const tone = status === "POSTED" ? "bg-success/10 text-success" : status === "DRAFT" ? "bg-warning/10 text-warning" : "bg-muted/10 text-muted";
    return <span className={`px-2 py-0.5 rounded-full text-[10px] font-bold ${tone}`}>{t(`statuses.${status}`)}</span>;
}

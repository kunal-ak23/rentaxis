"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { AlertTriangle, Ban, CheckCircle2, Filter, Plus, RotateCcw, ScrollText, ShieldCheck, Trash2 } from "lucide-react";
import { Link } from "@/i18n/routing";
import { loadAccounts } from "@/components/finance/AccountPicker";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount, type Account } from "@/lib/api/ledger";
import { formatDate, formatDatesInText } from "@/lib/format";
import {
    issuedChequesApi,
    type IssuedCheque,
    type IssuedChequeStatus,
    type IssuedChequeSummary,
} from "@/lib/api/payables";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { useStatementCoverGuard } from "@/lib/statementCoverGuard";
import { StatementCoverNotice } from "@/components/finance/StatementCoverNotice";

const th = "px-3 py-2.5 text-[10px] font-semibold text-muted uppercase tracking-wider whitespace-nowrap text-start";
const td = "px-3 py-2 text-xs";
const field = "bg-input border border-border rounded-lg px-3 py-2 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";
const label = "text-[10px] font-semibold text-muted uppercase tracking-wider";
const button = "flex items-center gap-1.5 px-3 py-2 rounded-lg bg-surface text-foreground border border-border text-xs font-bold hover:bg-input transition-all cursor-pointer disabled:opacity-50";
const small = "inline-flex items-center gap-1 px-2 py-1 rounded-md border border-border text-[10px] font-bold hover:bg-input cursor-pointer";

const pad = (n: number) => String(n).padStart(2, "0");
function todayIso(): string {
    const d = new Date();
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

function Amount({ v, strong }: { v: number | null | undefined; strong?: boolean }) {
    if (v === null || v === undefined) return <span className="text-muted">—</span>;
    return <bdi dir="ltr" className={`tabular-nums ${strong ? "font-bold" : ""}`}>{fmtAmount(v)}</bdi>;
}

type Action = { kind: "present" | "cancel" | "unpresent" | "delete"; cheque: IssuedCheque };
type VendorRow = { id: string; nameEn: string; active: boolean };

/**
 * Finance → Payables → Issued cheques (finance-ops spec §2): post-dated cheques
 * written to suppliers, held in PDC payable until the bank pays them.
 * Present posts a BPC (Dr PDC payable / Cr the bank) on the date given; nothing
 * posts on the cheque date by itself. Cancel reverses the cheque's payment
 * voucher, so the invoices it settled show as unpaid again. Unpresent reverses
 * the BPC when the bank returns a cheque unpaid. The tie-out compares what is
 * outstanding with the PDC payable balance, per bank; the cut-over check
 * compares cut-over cheques with the opening balance on PDC payable.
 */
export default function IssuedChequesPage() {
    const t = useTranslations("IssuedCheques");
    const tCommon = useTranslations("Common");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canManagePayables");

    const [status, setStatus] = useState<IssuedChequeStatus | "">("ISSUED");
    const [bankAccountId, setBankAccountId] = useState("");
    const [duePresent, setDuePresent] = useState(false);
    const [rows, setRows] = useState<IssuedCheque[] | null>(null);
    const [summary, setSummary] = useState<IssuedChequeSummary | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [banks, setBanks] = useState<Account[]>([]);
    const [vendors, setVendors] = useState<VendorRow[]>([]);

    const [action, setAction] = useState<Action | null>(null);
    const [actionDate, setActionDate] = useState(todayIso);
    const [reason, setReason] = useState("");
    const [actionError, setActionError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const cover = useStatementCoverGuard(tCommon);

    const [opening, setOpening] = useState({ vendorId: "", bankAccountId: "", chequeNumber: "", chequeDate: "", amount: "" });
    const [openingError, setOpeningError] = useState<string | null>(null);

    const load = useCallback(async () => {
        setLoadError(null);
        try {
            const [list, s] = await Promise.all([
                issuedChequesApi.list({ status: status || undefined, bankAccountId: bankAccountId || undefined, duePresent }),
                issuedChequesApi.summary(),
            ]);
            setRows(list);
            setSummary(s);
        } catch (err) {
            setRows([]);
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        }
    }, [status, bankAccountId, duePresent, tCommon]);

    useEffect(() => {
        if (!allowed) return;
        load();
        // Filters apply on "Show".
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [allowed]);

    useEffect(() => {
        if (!allowed) return;
        let alive = true;
        loadAccounts()
            .then(a => alive && setBanks(a.filter(x => !x.group && x.active && x.accountSubType === "BANK")))
            .catch(() => {});
        fetch("/api/proxy/v1/vendors")
            .then(r => (r.ok ? r.json() : []))
            .then((v: VendorRow[]) => alive && setVendors(Array.isArray(v) ? v : []))
            .catch(() => {});
        return () => {
            alive = false;
        };
    }, [allowed]);

    const openAction = (kind: Action["kind"], cheque: IssuedCheque) => {
        setAction({ kind, cheque });
        setActionDate(kind === "present" && cheque.chequeDate > todayIso() ? cheque.chequeDate : todayIso());
        setReason("");
        setActionError(null);
        cover.reset();
    };

    const needsReason = action?.kind === "cancel" || action?.kind === "unpresent";
    const early = action?.kind === "present" && actionDate && actionDate < action.cheque.chequeDate;
    const future = action?.kind === "present" && actionDate > todayIso();

    const runAction = async () => {
        if (!action) return;
        setBusy(true);
        setActionError(null);
        try {
            const id = action.cheque.id;
            if (action.kind === "present") {
                await issuedChequesApi.present(id, { date: actionDate, notOnStatement: cover.notOnStatement || undefined });
            } else if (action.kind === "cancel") await issuedChequesApi.cancel(id, actionDate, reason.trim());
            else if (action.kind === "unpresent") await issuedChequesApi.unpresent(id, actionDate, reason.trim());
            else await issuedChequesApi.removeOpening(id);
            setAction(null);
            await load();
        } catch (err) {
            if (action.kind === "present" && cover.catchStatementCover(err)) {
                // The notice + checkbox is now showing; the user resubmits.
            } else {
                setActionError(err instanceof ApiError ? err.message : t("actionFailed"));
            }
        } finally {
            setBusy(false);
        }
    };

    const addOpening = async () => {
        setOpeningError(null);
        try {
            await issuedChequesApi.createOpening({
                vendorId: opening.vendorId, bankAccountId: opening.bankAccountId, chequeNumber: opening.chequeNumber.trim(),
                chequeDate: opening.chequeDate, amount: Number(opening.amount),
            });
            setOpening({ vendorId: "", bankAccountId: opening.bankAccountId, chequeNumber: "", chequeDate: "", amount: "" });
            await load();
        } catch (err) {
            setOpeningError(err instanceof ApiError ? err.message : t("actionFailed"));
        }
    };
    const openingReady = opening.vendorId && opening.bankAccountId && opening.chequeNumber.trim() && opening.chequeDate
        && Number(opening.amount) > 0;

    const vendorOptions = useMemo(() => [...vendors].filter(v => v.active !== false)
        .sort((a, b) => a.nameEn.localeCompare(b.nameEn)), [vendors]);

    if (userRole && !allowed) {
        return (
            <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center max-w-4xl">
                <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                <p className="text-sm text-muted">{t("accessDenied")}</p>
            </div>
        );
    }

    const tied = summary && Math.abs(summary.difference) < 0.005;

    return (
        <div>
            <div className="mb-6">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                    <ScrollText size={20} className="text-primary" />{t("title")}
                </h1>
                <p className="text-xs text-muted font-medium">{t("desc")}</p>
            </div>

            {summary && (
                <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-5" data-testid="cheque-summary">
                    <div className="bg-surface rounded-xl border border-border p-3">
                        <div className={label}>{t("outstanding")}</div>
                        <div className="text-sm mt-1" data-testid="cheque-outstanding"><Amount v={summary.outstandingTotal} strong /></div>
                    </div>
                    <div className="bg-surface rounded-xl border border-border p-3">
                        <div className={label}>{t("pdcBalance")}</div>
                        <div className="text-sm mt-1"><Amount v={summary.pdcPayableBalance} strong /></div>
                    </div>
                    <div className={`bg-surface rounded-xl border p-3 ${tied ? "border-border" : "border-warning"}`}>
                        <div className={label}>{t("difference")}</div>
                        <div className={`text-sm mt-1 flex items-center gap-1 ${tied ? "text-success" : "text-warning"}`} data-testid="cheque-difference">
                            {tied ? <CheckCircle2 size={13} /> : <AlertTriangle size={13} />}<Amount v={summary.difference} strong />
                        </div>
                    </div>
                    <div className="bg-surface rounded-xl border border-border p-3">
                        <div className={label}>{t("duePresent")}</div>
                        <div className="text-sm mt-1 font-bold tabular-nums" data-testid="cheque-due-count">{summary.duePresentCount}</div>
                    </div>
                </div>
            )}

            {summary && summary.perBank.length > 0 && (
                <div className="bg-surface rounded-xl border border-border relative overflow-x-auto mb-5">
                    <table className="w-full" data-testid="cheque-per-bank">
                        <thead className="bg-background border-b border-border">
                            <tr>
                                <th className={th}>{t("bank")}</th>
                                <th className={`${th} text-end`}>{t("count")}</th>
                                <th className={`${th} text-end`}>{t("outstanding")}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {summary.perBank.map(b => (
                                <tr key={b.bankAccountId} className="border-b border-border last:border-0">
                                    <td className={td}><bdi dir="ltr">{b.bankAccountCode}</bdi> {b.bankAccountName}</td>
                                    <td className={`${td} text-end tabular-nums`}>{b.count}</td>
                                    <td className={`${td} text-end`}><Amount v={b.amount} /></td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}

            <form className="flex flex-wrap items-end gap-3 mb-4" onSubmit={e => { e.preventDefault(); load(); }}>
                <label className={label}>
                    <span className="block mb-1">{t("status")}</span>
                    <select data-testid="cheque-status" className={field} value={status}
                            onChange={e => setStatus(e.target.value as IssuedChequeStatus | "")}>
                        <option value="">{t("allStatuses")}</option>
                        {(["ISSUED", "PRESENTED", "CANCELLED"] as const).map(s => <option key={s} value={s}>{t(`statuses.${s}`)}</option>)}
                    </select>
                </label>
                <label className={label}>
                    <span className="block mb-1">{t("bank")}</span>
                    <select data-testid="cheque-bank" className={field} value={bankAccountId} onChange={e => setBankAccountId(e.target.value)}>
                        <option value="">{t("allBanks")}</option>
                        {banks.map(b => <option key={b.id} value={b.id}>{b.code} {b.name}</option>)}
                    </select>
                </label>
                <label className={`${label} flex items-center gap-2 pb-2`}>
                    <input type="checkbox" data-testid="cheque-due-only" checked={duePresent} onChange={e => setDuePresent(e.target.checked)} />
                    {t("duePresent")}
                </label>
                <button type="submit" className={button} data-testid="cheque-filter"><Filter size={13} />{t("show")}</button>
            </form>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}

            <div className="bg-surface rounded-xl border border-border relative overflow-x-auto mb-8">
                <table className="w-full" data-testid="cheque-register">
                    <thead className="bg-background border-b border-border">
                        <tr>
                            <th className={th}>{t("chequeNumber")}</th>
                            <th className={th}>{t("chequeDate")}</th>
                            <th className={th}>{t("vendor")}</th>
                            <th className={th}>{t("bank")}</th>
                            <th className={`${th} text-end`}>{t("amount")}</th>
                            <th className={th}>{t("voucher")}</th>
                            <th className={th}>{t("status")}</th>
                            <th className={th}><span className="sr-only">{t("actions")}</span></th>
                        </tr>
                    </thead>
                    <tbody>
                        {rows === null ? (
                            <tr><td colSpan={8} className="text-center text-xs text-muted py-10">{t("loading")}</td></tr>
                        ) : rows.length === 0 ? (
                            <tr><td colSpan={8} className="text-center text-xs text-muted py-10">{t("noCheques")}</td></tr>
                        ) : rows.map(c => (
                            <tr key={c.id} className="border-b border-border last:border-0" data-testid={`cheque-row-${c.chequeNumber}`}>
                                <td className={`${td} font-bold`}><bdi dir="ltr">{c.chequeNumber}</bdi>
                                    {c.opening && <span className="ms-2 text-[10px] font-semibold text-muted">{t("opening")}</span>}</td>
                                <td className={td}>
                                    <bdi dir="ltr">{formatDate(c.chequeDate)}</bdi>
                                    {c.duePresent && <span className="ms-2 text-[10px] font-bold text-warning" data-testid={`cheque-due-${c.chequeNumber}`}>{t("pastDate")}</span>}
                                </td>
                                <td className={td}>{c.vendorName}</td>
                                <td className={td}>{c.bankAccountName}</td>
                                <td className={`${td} text-end`}><Amount v={c.amount} /></td>
                                <td className={td}>
                                    {c.voucherId && (
                                        <Link href={`/dashboard/finance/vouchers/payment?id=${c.voucherId}`} className="text-primary hover:underline">
                                            <bdi dir="ltr">{c.voucherNumber}</bdi>
                                        </Link>
                                    )}
                                </td>
                                <td className={td}>
                                    <span className="font-semibold">{t(`statuses.${c.status}`)}</span>
                                    {c.presentedOn && <div className="text-[10px] text-muted">{t("presentedOn")} <bdi dir="ltr">{formatDate(c.presentedOn)}</bdi> · <bdi dir="ltr">{c.bpcNumber}</bdi></div>}
                                    {c.cancelledOn && <div className="text-[10px] text-muted">{t("cancelledOn")} <bdi dir="ltr">{formatDate(c.cancelledOn)}</bdi></div>}
                                </td>
                                <td className={`${td} whitespace-nowrap`}>
                                    <span className="flex gap-1">
                                        {c.status === "ISSUED" && (
                                            <>
                                                <button type="button" className={small} onClick={() => openAction("present", c)} data-testid={`present-${c.chequeNumber}`}>
                                                    <CheckCircle2 size={11} />{t("present")}
                                                </button>
                                                <button type="button" className={small} onClick={() => openAction("cancel", c)} data-testid={`cancel-${c.chequeNumber}`}>
                                                    <Ban size={11} />{t("cancel")}
                                                </button>
                                                {c.opening && (
                                                    <button type="button" className={small} onClick={() => openAction("delete", c)} data-testid={`delete-${c.chequeNumber}`}
                                                            aria-label={t("deleteOpening")}>
                                                        <Trash2 size={11} />
                                                    </button>
                                                )}
                                            </>
                                        )}
                                        {c.status === "PRESENTED" && (
                                            <button type="button" className={small} onClick={() => openAction("unpresent", c)} data-testid={`unpresent-${c.chequeNumber}`}>
                                                <RotateCcw size={11} />{t("unpresent")}
                                            </button>
                                        )}
                                    </span>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            <section aria-labelledby="cutover-title">
                <h2 id="cutover-title" className="text-sm font-bold text-foreground mb-1">{t("openingTitle")}</h2>
                <p className="text-xs text-muted mb-3">{t("openingDesc")}</p>
                {summary && (
                    <p className="text-xs mb-3" data-testid="opening-check">
                        {t("openingCheck", {
                            items: fmtAmount(summary.openingTotal), balance: fmtAmount(summary.openingBalance),
                            difference: fmtAmount(summary.openingDifference),
                        })}
                    </p>
                )}
                <div className="bg-surface rounded-xl border border-border p-4 grid grid-cols-1 md:grid-cols-6 gap-3 items-end">
                    <label className={`${label} md:col-span-2`}>
                        <span className="block mb-1">{t("vendor")}</span>
                        <select data-testid="opening-vendor" className={`${field} w-full`} value={opening.vendorId}
                                onChange={e => setOpening(o => ({ ...o, vendorId: e.target.value }))}>
                            <option value="">{t("chooseVendor")}</option>
                            {vendorOptions.map(v => <option key={v.id} value={v.id}>{v.nameEn}</option>)}
                        </select>
                    </label>
                    <label className={label}>
                        <span className="block mb-1">{t("bank")}</span>
                        <select data-testid="opening-bank" className={`${field} w-full`} value={opening.bankAccountId}
                                onChange={e => setOpening(o => ({ ...o, bankAccountId: e.target.value }))}>
                            <option value="">{t("chooseBank")}</option>
                            {banks.map(b => <option key={b.id} value={b.id}>{b.code} {b.name}</option>)}
                        </select>
                    </label>
                    <label className={label}>
                        <span className="block mb-1">{t("chequeNumber")}</span>
                        <input dir="ltr" data-testid="opening-number" className={`${field} w-full`} value={opening.chequeNumber}
                               onChange={e => setOpening(o => ({ ...o, chequeNumber: e.target.value }))} />
                    </label>
                    <label className={label}>
                        <span className="block mb-1">{t("chequeDate")}</span>
                        <input type="date" data-testid="opening-date" className={`${field} w-full`} value={opening.chequeDate}
                               onChange={e => setOpening(o => ({ ...o, chequeDate: e.target.value }))} />
                    </label>
                    <label className={label}>
                        <span className="block mb-1">{t("amount")}</span>
                        <input dir="ltr" inputMode="decimal" data-testid="opening-amount" className={`${field} w-full text-end`} value={opening.amount}
                               onChange={e => setOpening(o => ({ ...o, amount: e.target.value }))} />
                    </label>
                    <div className="md:col-span-6 flex items-center justify-end gap-3">
                        {openingError && <span role="alert" className="text-xs text-danger">{openingError}</span>}
                        <button type="button" className={button} disabled={!openingReady} onClick={addOpening} data-testid="opening-add">
                            <Plus size={13} />{t("addOpening")}
                        </button>
                    </div>
                </div>
            </section>

            <ConfirmDialog
                isOpen={action !== null}
                onClose={() => setAction(null)}
                onConfirm={runAction}
                title={action ? t(`${action.kind}Title`, { number: action.cheque.chequeNumber }) : ""}
                description={action ? t(`${action.kind}Hint`) : ""}
                confirmText={action ? t(action.kind === "delete" ? "deleteOpening" : action.kind) : ""}
                cancelText={t("close")}
                isDestructive={action?.kind !== "present"}
                isLoading={busy}
                confirmTestId="cheque-confirm"
                confirmDisabled={(needsReason && !reason.trim()) || !!early || !!future || (action?.kind !== "delete" && !actionDate)}
            >
                {action && action.kind !== "delete" && (
                    <div className="space-y-2">
                        <label className={`${label} block`}>
                            <span className="block mb-1">{t("date")}</span>
                            <input type="date" data-testid="cheque-action-date" className={`${field} w-full`} value={actionDate}
                                   onChange={e => setActionDate(e.target.value)} />
                        </label>
                        {early && <p className="text-xs text-danger" data-testid="cheque-early">{t("tooEarly", { date: formatDate(action.cheque.chequeDate) })}</p>}
                        {future && <p className="text-xs text-danger">{t("inFuture")}</p>}
                        {action.kind === "present" && cover.notice && (
                            <StatementCoverNotice
                                notice={cover.notice}
                                checked={cover.notOnStatement}
                                onChange={cover.setNotOnStatement}
                                testIdPrefix="cheque-present"
                            />
                        )}
                        {needsReason && (
                            <label className={`${label} block`}>
                                <span className="block mb-1">{t("reason")}</span>
                                <input data-testid="cheque-action-reason" className={`${field} w-full`} value={reason}
                                       onChange={e => setReason(e.target.value)} />
                            </label>
                        )}
                        {actionError && <p role="alert" className="text-xs text-danger" data-testid="cheque-action-error">{formatDatesInText(actionError)}</p>}
                    </div>
                )}
                {action?.kind === "delete" && actionError && <p role="alert" className="text-xs text-danger">{formatDatesInText(actionError)}</p>}
            </ConfirmDialog>
        </div>
    );
}

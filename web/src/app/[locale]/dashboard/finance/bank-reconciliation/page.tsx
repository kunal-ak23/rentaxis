"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { AlertTriangle, FileUp, Landmark, Layers, ListChecks, Lock, RotateCcw, Trash2 } from "lucide-react";
import { Link } from "@/i18n/routing";
import { loadAccounts } from "@/components/finance/AccountPicker";
import { StatementImportDialog } from "@/components/finance/bankrec/StatementImportDialog";
import { Modal } from "@/components/finance/bankrec/Modal";
import { button, field, primary, small, td, th } from "@/components/finance/bankrec/styles";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import type { Account } from "@/lib/api/ledger";
import { bankRecApi, dmy, type BankAccountRow, type ImportRow } from "@/lib/api/bankRec";
import { hasPermission, type UserRole } from "@/lib/rbac";

/**
 * Finance → Bank reconciliation (finance-ops spec §3): every bank account with
 * its ledger accounts, last import and unmatched lines; import a statement,
 * set the ledger-account set and the bank's TRN, open the matching workspace.
 */
export default function BankReconciliationPage() {
    const t = useTranslations("BankRec");
    const tCommon = useTranslations("Common");
    const { data: session } = useSession();
    const allowed = hasPermission(session?.user?.role as UserRole | undefined, "canReconcileBank");
    const canReopen = hasPermission(session?.user?.role as UserRole | undefined, "canReopenBankRec");

    const [rows, setRows] = useState<BankAccountRow[] | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [importing, setImporting] = useState<BankAccountRow | null>(null);
    const [editing, setEditing] = useState<BankAccountRow | null>(null);
    const [history, setHistory] = useState<BankAccountRow | null>(null);
    const [reopening, setReopening] = useState<BankAccountRow | null>(null);

    const load = useCallback(async () => {
        setLoadError(null);
        try {
            setRows(await bankRecApi.accounts());
        } catch (err) {
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        }
    }, [tCommon]);

    useEffect(() => {
        if (allowed) load();
    }, [allowed, load]);

    if (!allowed) {
        return <div className="p-6 text-sm text-muted" data-testid="bankrec-denied">{t("accessDenied")}</div>;
    }

    return (
        <div className="p-4 md:p-6 space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                    <h1 className="text-xl font-bold text-foreground tracking-tight flex items-center gap-2"><Landmark size={18} />{t("title")}</h1>
                    <p className="text-xs text-muted">{t("subtitle")}</p>
                </div>
            </div>
            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}
            <div className="bg-surface border border-border rounded-xl overflow-x-auto">
                <table className="w-full" data-testid="bankrec-accounts">
                    <thead className="bg-input"><tr>
                        <th className={th}>{t("bankAccount")}</th>
                        <th className={th}>{t("ledgerAccounts")}</th>
                        <th className={th}>{t("lastImport")}</th>
                        <th className={th}>{t("lastLine")}</th>
                        <th className={`${th} text-end`}>{t("unmatched")}</th>
                        <th className={th}>{t("reconciledThrough")}</th>
                        <th className={th} />
                    </tr></thead>
                    <tbody className="divide-y divide-border">
                        {rows && rows.length === 0 && (
                            <tr><td colSpan={7} className="text-center text-xs text-muted py-10">{t("noAccounts")}</td></tr>
                        )}
                        {rows?.map(r => (
                            <tr key={r.id} data-testid={`bankrec-row-${r.accountNumber}`}>
                                <td className={td}>
                                    <div className="font-bold">{r.bankName}</div>
                                    <div className="text-muted"><bdi dir="ltr">{r.iban ?? r.accountNumber}</bdi></div>
                                    <div className="text-[10px] text-muted">{t("bankTrn")}: {r.bankTrn ?? "—"}</div>
                                </td>
                                <td className={td}>
                                    {r.needsLeaf ? (
                                        <span className="text-warning flex items-center gap-1"><AlertTriangle size={12} />{t("needsLeaf")}</span>
                                    ) : (
                                        <div className="flex flex-wrap gap-1">{r.leaves.map(l => (
                                            <span key={l.id} className="px-2 py-0.5 rounded-full bg-input text-[10px]">{l.name}</span>
                                        ))}</div>
                                    )}
                                </td>
                                <td className={td}>{r.lastImportFile ?? "—"}</td>
                                <td className={td}><bdi dir="ltr">{dmy(r.lastLineDate) || "—"}</bdi></td>
                                <td className={`${td} text-end font-bold`}>{r.unmatchedLines}</td>
                                <td className={td} data-testid={`reconciled-${r.accountNumber}`}>
                                    {r.reconciledThrough ? (
                                        <span className="inline-flex items-center gap-1"><Lock size={11} /><bdi dir="ltr">{dmy(r.reconciledThrough)}</bdi></span>
                                    ) : "—"}
                                    {canReopen && r.latestFinalizedReconciliationId && (
                                        <button type="button" className={`${small} ms-1`} data-testid={`reopen-${r.accountNumber}`}
                                                onClick={() => setReopening(r)}><RotateCcw size={11} />{t("reopen")}</button>
                                    )}
                                </td>
                                <td className={`${td} text-end`}>
                                    <div className="flex flex-wrap justify-end gap-1">
                                        <button type="button" className={small} data-testid={`import-${r.accountNumber}`} onClick={() => setImporting(r)}>
                                            <FileUp size={12} />{t("import")}
                                        </button>
                                        <button type="button" className={small} onClick={() => setEditing(r)}><Layers size={12} />{t("editLeaves")}</button>
                                        <button type="button" className={small} onClick={() => setHistory(r)}>{t("history")}</button>
                                        <Link href={`/dashboard/finance/bank-reconciliation/${r.id}`} className={small}>
                                            <ListChecks size={12} />{t("open")}
                                        </Link>
                                    </div>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
            {importing && (
                <StatementImportDialog bankAccountId={importing.id} bankName={importing.bankName}
                                       onClose={() => setImporting(null)} onImported={load} />
            )}
            {editing && <LeavesDialog row={editing} onClose={() => setEditing(null)} onSaved={() => { setEditing(null); load(); }} />}
            {history && <HistoryDialog row={history} onClose={() => setHistory(null)} onChanged={load} />}
            {reopening && <ReopenDialog row={reopening} onClose={() => setReopening(null)} onDone={() => { setReopening(null); load(); }} />}
        </div>
    );
}

/** The ledger-account set and the bank's TRN. */
function LeavesDialog({ row, onClose, onSaved }: { row: BankAccountRow; onClose: () => void; onSaved: () => void }) {
    const t = useTranslations("BankRec");
    const [leaves, setLeaves] = useState<Account[]>([]);
    const [picked, setPicked] = useState<Set<string>>(new Set(row.leaves.map(l => l.id)));
    const [trn, setTrn] = useState(row.bankTrn ?? "");
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);

    useEffect(() => {
        loadAccounts().then(a => setLeaves(a.filter(x => !x.group && x.active && x.accountSubType === "BANK"))).catch(() => {});
    }, []);

    const save = async () => {
        const clean = trn.replace(/\s/g, "");
        if (clean && !/^\d{15}$/.test(clean)) {
            setError(t("bankTrnInvalid"));
            return;
        }
        setBusy(true);
        setError(null);
        try {
            await bankRecApi.setLeaves(row.id, [...picked]);
            await bankRecApi.setBankTrn(row.id, clean || null);
            onSaved();
        } catch (err) {
            setError(err instanceof ApiError ? err.message : String(err));
        } finally {
            setBusy(false);
        }
    };

    return (
        <Modal title={`${t("editLeaves")} — ${row.bankName}`} onClose={onClose} testId="leaves-dialog">
            <p className="text-xs text-muted mb-3">{t("leavesHint")}</p>
            <div className="space-y-1 max-h-64 overflow-y-auto mb-4">
                {leaves.map(a => (
                    <label key={a.id} className="flex items-center gap-2 text-xs">
                        <input type="checkbox" checked={picked.has(a.id)} data-testid={`leaf-${a.code}`}
                               onChange={e => setPicked(s => {
                                   const n = new Set(s);
                                   if (e.target.checked) n.add(a.id); else n.delete(a.id);
                                   return n;
                               })} />
                        <span className="text-muted">{a.code}</span> {a.name}
                    </label>
                ))}
            </div>
            <label className="block text-xs mb-1 font-semibold">{t("bankTrn")}</label>
            <input className={`${field} w-full mb-1`} value={trn} onChange={e => setTrn(e.target.value)} data-testid="bank-trn" dir="ltr" />
            <p className="text-[10px] text-muted mb-4">{t("bankTrnHint")}</p>
            {error && <div role="alert" className="text-xs text-error mb-3">{error}</div>}
            <div className="flex gap-2">
                <button type="button" className={primary} disabled={busy} onClick={save} data-testid="leaves-save">{t("save")}</button>
                <button type="button" className={button} onClick={onClose}>{t("cancel")}</button>
            </div>
        </Modal>
    );
}

function HistoryDialog({ row, onClose, onChanged }: { row: BankAccountRow; onClose: () => void; onChanged: () => void }) {
    const t = useTranslations("BankRec");
    const [items, setItems] = useState<ImportRow[] | null>(null);
    const [error, setError] = useState<string | null>(null);
    const load = useCallback(() => {
        bankRecApi.imports(row.id).then(setItems).catch(err => setError(err instanceof ApiError ? err.message : String(err)));
    }, [row.id]);
    useEffect(load, [load]);
    const remove = async (id: string) => {
        if (!window.confirm(t("deleteImportConfirm"))) return;
        try {
            await bankRecApi.deleteImport(id);
            load();
            onChanged();
        } catch (err) {
            setError(err instanceof ApiError ? err.message : String(err));
        }
    };
    return (
        <Modal title={`${t("history")} — ${row.bankName}`} onClose={onClose} wide>
            {error && <div role="alert" className="text-xs text-error mb-3">{error}</div>}
            <div className="overflow-x-auto">
                <table className="w-full">
                    <thead className="bg-input"><tr>
                        <th className={th}>{t("lastImport")}</th><th className={th}>{t("from")}</th><th className={th}>{t("to")}</th>
                        <th className={th}>{t("linesCol")}</th><th className={th} />
                    </tr></thead>
                    <tbody className="divide-y divide-border">
                        {items?.map(i => (
                            <tr key={i.id}>
                                <td className={td}>{i.fileName}</td>
                                <td className={td}><bdi dir="ltr">{dmy(i.firstDate)}</bdi></td>
                                <td className={td}><bdi dir="ltr">{dmy(i.lastDate)}</bdi></td>
                                <td className={td}>{t("counts", { fresh: i.linesNew, dup: i.linesDuplicate })}</td>
                                <td className={`${td} text-end`}>
                                    <button type="button" className={small} disabled={!i.deletable} onClick={() => remove(i.id)}
                                            title={i.deletable ? t("deleteImport") : t("deleteBlocked")} aria-label={t("deleteImport")}>
                                        <Trash2 size={12} />
                                    </button>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </Modal>
    );
}

/** Reopen the bank account's latest finalized reconciliation (spec §4: admins only, with a reason). */
function ReopenDialog({ row, onClose, onDone }: { row: BankAccountRow; onClose: () => void; onDone: () => void }) {
    const t = useTranslations("BankRec");
    const [reason, setReason] = useState("");
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const go = async () => {
        setBusy(true);
        setError(null);
        try {
            await bankRecApi.reopenReconciliation(row.latestFinalizedReconciliationId!, reason.trim());
            onDone();
        } catch (err) {
            setError(err instanceof ApiError ? err.message : String(err));
        } finally {
            setBusy(false);
        }
    };
    return (
        <Modal title={`${t("reopen")} — ${row.bankName}`} onClose={onClose} testId="reopen-dialog">
            <p className="text-xs text-muted mb-3">{t("reopenHint", { date: dmy(row.reconciledThrough) })}</p>
            <textarea className={`${field} w-full mb-3`} rows={3} value={reason} placeholder={t("reopenReason")}
                      onChange={e => setReason(e.target.value)} data-testid="reopen-reason" />
            {error && <div role="alert" className="text-xs text-error mb-3">{error}</div>}
            <div className="flex gap-2">
                <button type="button" className={primary} disabled={busy || !reason.trim()} onClick={go} data-testid="reopen-go">{t("reopen")}</button>
                <button type="button" className={button} onClick={onClose}>{t("cancel")}</button>
            </div>
        </Modal>
    );
}

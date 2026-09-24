"use client";

import { useCallback, useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { AlertTriangle, CheckCircle2, Download, FileText, Lock, Plus, RotateCcw, Trash2, XCircle } from "lucide-react";
import { ApiError } from "@/lib/api/facilities";
import {
    bankRecApi,
    dmy,
    failingChecks,
    type OpeningItem,
    type RecItem,
    type Reconciliation,
    type ReconciliationRow,
} from "@/lib/api/bankRec";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Modal } from "./Modal";
import { Money } from "./Money";
import { button, field, label, panel, primary, small, td, th } from "./styles";

/** yyyy-MM-dd ± days, in UTC so no time zone moves the day. */
export function addDays(iso: string, days: number): string {
    const d = new Date(`${iso.slice(0, 10)}T00:00:00Z`);
    d.setUTCDate(d.getUTCDate() + days);
    return d.toISOString().slice(0, 10);
}

/**
 * The reconciliation statement of one bank account (finance-ops spec §4): start
 * a period, read its figures (each opens its item list), see the finalize
 * checklist, finalize when every precondition holds, and — for an admin —
 * reopen the latest finalized one. The first reconciliation also takes the
 * items outstanding at its start.
 */
export function ReconciliationPanel({ bankAccountId, onChanged, refreshKey }: {
    bankAccountId: string;
    onChanged?: () => void;
    /**
     * Bumped by the workspace after its own mutations (a confirmed match, a
     * manual match, a line action such as booking a bank charge) so this
     * panel's figures and finalize checklist don't go stale until the page
     * is reloaded (F14-47) — those mutations happen outside this component
     * and only touch the workspace's own state, so `load` below has to be
     * re-run from here too.
     */
    refreshKey?: number;
}) {
    const t = useTranslations("BankRec");
    const { data: session } = useSession();
    const canReopen = hasPermission(session?.user?.role as UserRole | undefined, "canReopenBankRec");

    const [rows, setRows] = useState<ReconciliationRow[] | null>(null);
    const [rec, setRec] = useState<Reconciliation | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [list, setList] = useState<{ title: string; items: RecItem[] } | null>(null);
    const [confirmingFinalize, setConfirmingFinalize] = useState(false);

    const load = useCallback(async () => {
        try {
            const rs = await bankRecApi.reconciliations(bankAccountId);
            setRows(rs);
            const draft = rs.find(r => r.status === "DRAFT");
            setRec(draft ? await bankRecApi.reconciliation(draft.id) : null);
        } catch (err) {
            setError(err instanceof ApiError ? err.message : String(err));
        }
    }, [bankAccountId]);

    useEffect(() => { load(); }, [load, refreshKey]);

    const run = async (fn: () => Promise<unknown>) => {
        setBusy(true);
        setError(null);
        try {
            await fn();
            await load();
            onChanged?.();
        } catch (err) {
            setError(err instanceof ApiError ? err.message : String(err));
        } finally {
            setBusy(false);
        }
    };

    const finalized = (rows ?? []).filter(r => r.status === "FINALIZED").sort((a, b) => b.periodTo.localeCompare(a.periodTo));
    const latest = finalized[0] ?? null;
    const failing = rec ? failingChecks(rec) : [];

    return (
        <section className={`${panel} p-3 space-y-3`} data-testid="rec-panel">
            <div className="flex flex-wrap items-center justify-between gap-2">
                <h2 className="text-sm font-bold flex items-center gap-2"><FileText size={14} />{t("recTitle")}</h2>
                {latest && (
                    <span className="text-xs text-muted flex items-center gap-1" data-testid="rec-locked-through">
                        <Lock size={12} />{t("reconciledThrough")}: <bdi dir="ltr">{dmy(latest.periodTo)}</bdi>
                    </span>
                )}
            </div>
            {error && <div role="alert" className="text-xs text-error bg-error/10 rounded-lg px-3 py-2" data-testid="rec-error">{error}</div>}

            {rows && !rec && (
                <StartForm first={!latest} nextFrom={latest ? addDays(latest.periodTo, 1) : ""} busy={busy}
                           onStart={body => run(() => bankRecApi.createReconciliation(bankAccountId, body))} />
            )}

            {rec && (
                <div className="space-y-3" data-testid="rec-draft">
                    <div className="text-xs">
                        {t("recPeriod")}: <bdi dir="ltr" className="font-bold">{dmy(rec.periodFrom)} – {dmy(rec.periodTo)}</bdi>
                        {rec.first && <span className="ms-2 px-2 py-0.5 rounded-full bg-input text-[10px]">{t("recFirst")}</span>}
                    </div>
                    <Figures rec={rec} onOpen={(title, items) => setList({ title, items })} />
                    {rec.withoutEvidenceCount > 0 && (
                        <div className="text-xs text-warning bg-warning/10 rounded-lg px-3 py-2 flex items-center gap-1" data-testid="rec-without-evidence">
                            <AlertTriangle size={12} />{t("withoutEvidence", { count: rec.withoutEvidenceCount })}
                        </div>
                    )}
                    <ul className="space-y-1" data-testid="rec-checklist">
                        {rec.checks.map(c => (
                            <li key={c.code} className={`text-xs flex items-start gap-1.5 ${c.ok ? "text-success" : "text-error"}`}
                                data-testid={`rec-check-${c.code}`} data-ok={c.ok}>
                                {c.ok ? <CheckCircle2 size={13} className="shrink-0 mt-0.5" /> : <XCircle size={13} className="shrink-0 mt-0.5" />}
                                <span><b>{t(`check_${c.code}`)}</b> — {c.message}</span>
                            </li>
                        ))}
                    </ul>
                    <div className="flex flex-wrap gap-2 items-center">
                        <button type="button" className={primary} disabled={busy || !rec.canFinalize} data-testid="rec-finalize"
                                title={failing.length ? failing.map(c => `• ${c.message}`).join("\n") : t("finalizeHint")}
                                onClick={() => setConfirmingFinalize(true)}><Lock size={13} />{t("finalize")}</button>
                        {failing.length > 0 && <span className="text-[11px] text-muted" data-testid="rec-blocked">{t("finalizeBlocked", { count: failing.length })}</span>}
                        <DraftEdit rec={rec} busy={busy}
                                   onSave={body => run(() => bankRecApi.updateReconciliation(rec.id, body))} />
                        <button type="button" className={small} disabled={busy} data-testid="rec-discard"
                                onClick={() => { if (window.confirm(t("discardConfirm"))) run(() => bankRecApi.discardReconciliation(rec.id)); }}>
                            <Trash2 size={12} />{t("discard")}
                        </button>
                        <Downloads id={rec.id} />
                    </div>
                    {rec.first && <OpeningItems bankAccountId={bankAccountId} rec={rec} busy={busy} run={run} />}
                </div>
            )}

            <History rows={rows ?? []} latestId={latest?.id ?? null} canReopen={canReopen} busy={busy}
                     onReopen={(id, reason) => run(() => bankRecApi.reopenReconciliation(id, reason))} />

            {list && (
                <Modal title={list.title} onClose={() => setList(null)} wide testId="rec-items">
                    <ItemsTable items={list.items} />
                </Modal>
            )}

            {rec && (
                <ConfirmDialog
                    isOpen={confirmingFinalize}
                    onClose={() => setConfirmingFinalize(false)}
                    onConfirm={() => { setConfirmingFinalize(false); run(() => bankRecApi.finalizeReconciliation(rec.id)); }}
                    isLoading={busy}
                    title={t("finalize")}
                    description={t("finalizeConfirm", { date: dmy(rec.periodTo) })}
                    confirmText={t("finalize")}
                    cancelText={t("cancel")}
                    confirmTestId="rec-finalize-confirm"
                />
            )}
        </section>
    );
}

function StartForm({ first, nextFrom, busy, onStart }: {
    first: boolean; nextFrom: string; busy: boolean;
    onStart: (body: { periodFrom?: string | null; periodTo: string; statementOpening?: number | null; statementClosing?: number | null }) => void;
}) {
    const t = useTranslations("BankRec");
    const [from, setFrom] = useState(nextFrom);
    const [to, setTo] = useState("");
    const [opening, setOpening] = useState("");
    const [closing, setClosing] = useState("");
    useEffect(() => { setFrom(nextFrom); }, [nextFrom]);
    return (
        <form className="flex flex-wrap items-end gap-3" data-testid="rec-start"
              onSubmit={e => {
                  e.preventDefault();
                  onStart({
                      periodFrom: first ? from : null, periodTo: to,
                      statementOpening: first && opening ? Number(opening) : null,
                      statementClosing: closing ? Number(closing) : null,
                  });
              }}>
            <label className="text-xs"><span className={`${label} block mb-1`}>{t("from")}</span>
                <input type="date" className={field} value={from} disabled={!first} required={first}
                       onChange={e => setFrom(e.target.value)} data-testid="rec-from" /></label>
            <label className="text-xs"><span className={`${label} block mb-1`}>{t("to")}</span>
                <input type="date" className={field} value={to} required onChange={e => setTo(e.target.value)} data-testid="rec-to" /></label>
            {first && (
                <label className="text-xs"><span className={`${label} block mb-1`}>{t("statementOpeningTyped")}</span>
                    <input type="number" step="0.01" dir="ltr" className={field} value={opening} onChange={e => setOpening(e.target.value)}
                           data-testid="rec-opening" /></label>
            )}
            <label className="text-xs"><span className={`${label} block mb-1`}>{t("statementClosingTyped")}</span>
                <input type="number" step="0.01" dir="ltr" className={field} value={closing} onChange={e => setClosing(e.target.value)}
                       data-testid="rec-closing" /></label>
            <button type="submit" className={primary} disabled={busy || !to || (first && !from)} data-testid="rec-create">
                <Plus size={13} />{first ? t("startFirst") : t("startNext")}
            </button>
            <p className="w-full text-[11px] text-muted">{first ? t("startFirstHint") : t("startNextHint")}</p>
        </form>
    );
}

/** The statement's figures; each with items opens its list. */
function Figures({ rec, onOpen }: { rec: Reconciliation; onOpen: (title: string, items: RecItem[]) => void }) {
    const t = useTranslations("BankRec");
    const unrecorded = rec.unrecordedCredits - rec.unrecordedDebits;
    const cells: { key: string; value: number | null; items?: RecItem[]; strong?: boolean }[] = [
        { key: "statementClosing", value: rec.statementClosing },
        { key: "depositsInTransit", value: rec.depositsInTransit, items: rec.depositsInTransitItems },
        { key: "unpresentedPayments", value: -rec.unpresentedPayments, items: rec.unpresentedItems },
        ...(rec.bookedAfterPeriod !== 0 ? [{ key: "bookedAfterPeriod", value: -rec.bookedAfterPeriod, items: rec.bookedAfterItems }] : []),
        { key: "adjustedBank", value: rec.adjustedBank, strong: true },
        { key: "bookBalance", value: rec.bookBalance },
        { key: "unrecorded", value: unrecorded, items: rec.unrecordedItems },
        { key: "adjustedBook", value: rec.adjustedBook, strong: true },
        { key: "difference", value: rec.difference, strong: true },
    ];
    return (
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-2" data-testid="rec-figures">
            {cells.map(c => {
                const body = (
                    <>
                        <span className={`${label} block`}>{t(`fig_${c.key}`)}</span>
                        <Money v={c.value} strong={c.strong} testId={`rec-figure-${c.key}`} />
                        {c.items && <span className="block text-[10px] text-muted">{t("itemCount", { count: c.items.length })}</span>}
                    </>
                );
                return c.items ? (
                    <button key={c.key} type="button" className="text-start bg-input rounded-lg px-3 py-2 hover:ring-2 hover:ring-primary/20 cursor-pointer"
                            onClick={() => onOpen(t(`fig_${c.key}`), c.items!)} data-testid={`rec-open-${c.key}`}>{body}</button>
                ) : (
                    <div key={c.key} className={`rounded-lg px-3 py-2 ${c.key === "difference" && c.value !== 0 ? "bg-error/10" : "bg-input"}`}>{body}</div>
                );
            })}
        </div>
    );
}

function ItemsTable({ items }: { items: RecItem[] }) {
    const t = useTranslations("BankRec");
    if (items.length === 0) return <p className="text-xs text-muted">{t("noItems")}</p>;
    return (
        <div className="overflow-x-auto">
            <table className="w-full">
                <thead className="bg-input"><tr>
                    <th className={th}>{t("date")}</th><th className={th}>{t("docNumber")}</th><th className={th}>{t("narration")}</th>
                    <th className={th}>{t("chequeNo")}</th><th className={`${th} text-end`}>{t("amount")}</th>
                </tr></thead>
                <tbody className="divide-y divide-border">
                    {items.map(i => (
                        <tr key={`${i.kind}-${i.id}`} data-testid={`rec-item-${i.id}`}>
                            <td className={td}><bdi dir="ltr">{dmy(i.date)}</bdi></td>
                            <td className={td}>{i.kind === "OPENING" ? t("openingItem") : i.document}</td>
                            <td className={td}>
                                {i.narration}
                                {i.withoutEvidence && <span className="ms-1 px-1.5 rounded bg-warning/20 text-[10px]">{t("noStatementLine")}</span>}
                            </td>
                            <td className={td}>{i.chequeNo ?? ""}</td>
                            <td className={`${td} text-end`}><Money v={i.amount} /></td>
                        </tr>
                    ))}
                </tbody>
            </table>
        </div>
    );
}

function DraftEdit({ rec, busy, onSave }: {
    rec: Reconciliation; busy: boolean;
    onSave: (body: { periodFrom?: string | null; periodTo: string; statementOpening?: number | null; statementClosing?: number | null }) => void;
}) {
    const t = useTranslations("BankRec");
    const [open, setOpen] = useState(false);
    const [to, setTo] = useState(rec.periodTo);
    const [closing, setClosing] = useState(rec.closingTyped && rec.statementClosing !== null ? String(rec.statementClosing) : "");
    if (!open) return <button type="button" className={small} onClick={() => setOpen(true)} data-testid="rec-edit">{t("editPeriod")}</button>;
    return (
        <span className="inline-flex flex-wrap items-center gap-2 text-xs">
            <input type="date" className={field} value={to} onChange={e => setTo(e.target.value)} data-testid="rec-edit-to" />
            <input type="number" step="0.01" dir="ltr" className={field} placeholder={t("statementClosingTyped")} value={closing}
                   onChange={e => setClosing(e.target.value)} data-testid="rec-edit-closing" />
            <button type="button" className={small} disabled={busy || !to} data-testid="rec-edit-save"
                    onClick={() => { setOpen(false); onSave({ periodTo: to, statementClosing: closing ? Number(closing) : null }); }}>{t("save")}</button>
            <button type="button" className={small} onClick={() => setOpen(false)}>{t("cancel")}</button>
        </span>
    );
}

function Downloads({ id }: { id: string }) {
    const t = useTranslations("BankRec");
    return (
        <span className="inline-flex gap-1">
            <a className={small} href={bankRecApi.reconciliationPdfUrl(id, "en")} download data-testid={`rec-pdf-en-${id}`}><Download size={12} />PDF EN</a>
            <a className={small} href={bankRecApi.reconciliationPdfUrl(id, "ar")} download data-testid={`rec-pdf-ar-${id}`}><Download size={12} />PDF عربي</a>
            <a className={small} href={bankRecApi.reconciliationCsvUrl(id)} download><Download size={12} />{t("exportCsv")}</a>
        </span>
    );
}

/** The first reconciliation's "Outstanding at <start>" step, with the live check. */
function OpeningItems({ bankAccountId, rec, busy, run }: {
    bankAccountId: string; rec: Reconciliation; busy: boolean; run: (fn: () => Promise<unknown>) => Promise<void>;
}) {
    const t = useTranslations("BankRec");
    const [items, setItems] = useState<OpeningItem[]>([]);
    const [date, setDate] = useState("");
    const [description, setDescription] = useState("");
    const [reference, setReference] = useState("");
    const [chequeNo, setChequeNo] = useState("");
    const [amount, setAmount] = useState("");
    const reload = useCallback(() => { bankRecApi.openingItems(bankAccountId).then(setItems).catch(() => {}); }, [bankAccountId]);
    useEffect(reload, [reload, rec]);
    const check = rec.checks.find(c => c.code === "OPENING_ITEMS");
    const start = addDays(rec.periodFrom, -1);
    return (
        <div className="border-t border-border pt-3 space-y-2" data-testid="rec-opening-items">
            <h3 className="text-xs font-bold">{t("openingTitle", { date: dmy(start) })}</h3>
            <p className="text-[11px] text-muted">{t("openingHint")}</p>
            {check && (
                <div className={`text-xs ${check.ok ? "text-success" : "text-error"}`} data-testid="rec-opening-check">{check.message}</div>
            )}
            <table className="w-full">
                <thead className="bg-input"><tr>
                    <th className={th}>{t("date")}</th><th className={th}>{t("description")}</th><th className={th}>{t("reference")}</th>
                    <th className={th}>{t("chequeNo")}</th><th className={`${th} text-end`}>{t("amount")}</th><th className={th} />
                </tr></thead>
                <tbody className="divide-y divide-border">
                    {items.map(o => (
                        <tr key={o.id} data-testid={`opening-${o.id}`}>
                            <td className={td}><bdi dir="ltr">{dmy(o.itemDate)}</bdi></td>
                            <td className={td}>{o.description}</td>
                            <td className={td}>{o.reference ?? ""}</td>
                            <td className={td}>{o.chequeNo ?? ""}</td>
                            <td className={`${td} text-end`}><Money v={o.amount} /></td>
                            <td className={`${td} text-end`}>
                                <button type="button" className={small} disabled={busy || !!o.matchId} aria-label={t("delete")}
                                        onClick={() => run(async () => { await bankRecApi.deleteOpeningItem(bankAccountId, o.id); reload(); })}>
                                    <Trash2 size={12} />
                                </button>
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>
            <form className="flex flex-wrap items-end gap-2" data-testid="opening-add"
                  onSubmit={e => {
                      e.preventDefault();
                      run(async () => {
                          await bankRecApi.addOpeningItem(bankAccountId, {
                              itemDate: date, description, reference: reference || null, chequeNo: chequeNo || null, amount: Number(amount),
                          });
                          setDescription(""); setReference(""); setChequeNo(""); setAmount("");
                          reload();
                      });
                  }}>
                <input type="date" className={field} value={date} max={start} required onChange={e => setDate(e.target.value)} data-testid="opening-date" aria-label={t("date")} />
                <input className={field} value={description} required placeholder={t("description")} onChange={e => setDescription(e.target.value)} data-testid="opening-description" />
                <input className={field} value={reference} placeholder={t("reference")} onChange={e => setReference(e.target.value)} />
                <input className={field} value={chequeNo} placeholder={t("chequeNo")} dir="ltr" onChange={e => setChequeNo(e.target.value)} data-testid="opening-cheque" />
                <input type="number" step="0.01" className={field} dir="ltr" value={amount} required placeholder={t("openingAmountHint")}
                       onChange={e => setAmount(e.target.value)} data-testid="opening-amount" />
                <button type="submit" className={button} disabled={busy} data-testid="opening-save"><Plus size={12} />{t("addOpeningItem")}</button>
            </form>
        </div>
    );
}

function History({ rows, latestId, canReopen, busy, onReopen }: {
    rows: ReconciliationRow[]; latestId: string | null; canReopen: boolean; busy: boolean;
    onReopen: (id: string, reason: string) => void;
}) {
    const t = useTranslations("BankRec");
    const [asking, setAsking] = useState<string | null>(null);
    const [reason, setReason] = useState("");
    const done = rows.filter(r => r.status !== "DRAFT");
    if (done.length === 0) return null;
    return (
        <div className="border-t border-border pt-3" data-testid="rec-history">
            <h3 className="text-xs font-bold mb-2">{t("recHistory")}</h3>
            <div className="overflow-x-auto">
                <table className="w-full">
                    <thead className="bg-input"><tr>
                        <th className={th}>{t("recPeriod")}</th><th className={th}>{t("status")}</th>
                        <th className={`${th} text-end`}>{t("fig_statementClosing")}</th><th className={`${th} text-end`}>{t("fig_difference")}</th>
                        <th className={th}>{t("finalizedBy")}</th><th className={th} />
                    </tr></thead>
                    <tbody className="divide-y divide-border">
                        {done.map(r => (
                            <tr key={r.id} data-testid={`rec-row-${r.id}`}>
                                <td className={td}><bdi dir="ltr">{dmy(r.periodFrom)} – {dmy(r.periodTo)}</bdi></td>
                                <td className={td}>
                                    {t(`recStatus_${r.status}`)}
                                    {r.status === "REOPENED" && r.reopenReason && (
                                        <span className="block text-[10px] text-muted">{r.reopenedByName ?? ""}: {r.reopenReason}</span>
                                    )}
                                </td>
                                <td className={`${td} text-end`}><Money v={r.statementClosing} /></td>
                                <td className={`${td} text-end`}><Money v={r.difference} /></td>
                                <td className={td}>{r.finalizedByName ?? "—"} <bdi dir="ltr" className="text-muted">{dmy(r.finalizedAt)}</bdi></td>
                                <td className={`${td} text-end whitespace-nowrap`}>
                                    <Downloads id={r.id} />
                                    {canReopen && r.id === latestId && asking !== r.id && (
                                        <button type="button" className={`${small} ms-1`} disabled={busy} data-testid={`rec-reopen-${r.id}`}
                                                onClick={() => { setReason(""); setAsking(r.id); }}><RotateCcw size={12} />{t("reopen")}</button>
                                    )}
                                    {asking === r.id && (
                                        <span className="inline-flex items-center gap-1 ms-1">
                                            <input className={field} value={reason} placeholder={t("reopenReason")} onChange={e => setReason(e.target.value)}
                                                   data-testid="rec-reopen-reason" />
                                            <button type="button" className={small} disabled={busy || !reason.trim()} data-testid="rec-reopen-go"
                                                    onClick={() => { setAsking(null); onReopen(r.id, reason.trim()); }}>{t("reopen")}</button>
                                            <button type="button" className={small} onClick={() => setAsking(null)}>{t("cancel")}</button>
                                        </span>
                                    )}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </div>
    );
}

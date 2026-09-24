"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { ArrowLeft, CheckCheck, Download, Link2, MoreHorizontal, Sparkles, Undo2 } from "lucide-react";
import { Link } from "@/i18n/routing";
import { LoadErrorBanner } from "@/components/ui/LoadErrorBanner";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import {
    bankRecApi,
    dmy,
    sumCents,
    type BookItem,
    type Match,
    type StatementLine,
    type Workspace,
} from "@/lib/api/bankRec";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { LineActionDialog, actionsFor } from "./LineActionDialog";
import { Money } from "./Money";
import { button, field, label, primary, small, td, th } from "./styles";

type State = "UNMATCHED" | "SUGGESTED" | "ALL";

/** Text or amount: "50,000" and "50000" both find 50,000.00. */
export function matchesQuery(q: string, text: (string | null | undefined)[], amount: number): boolean {
    const s = q.trim().toLowerCase();
    if (!s) return true;
    const n = Number(s.replace(/,/g, ""));
    if (!Number.isNaN(n) && Math.abs(Math.abs(amount) - Math.abs(n)) < 0.005) return true;
    return text.some(x => x && x.toLowerCase().includes(s));
}

/**
 * Two panes: statement lines on the start side (left in English, right in
 * Arabic — the grid follows the reading direction), book items on the end
 * side. Suggestions are tinted and share a badge with their pair; selecting
 * rows on both sides shows the Σ footer with Match.
 */
export function BankReconciliationWorkspace({ bankAccountId }: { bankAccountId: string }) {
    const t = useTranslations("BankRec");
    const tCommon = useTranslations("Common");
    const { data: session } = useSession();
    const allowed = hasPermission(session?.user?.role as UserRole | undefined, "canReconcileBank");

    const [from, setFrom] = useState("");
    const [to, setTo] = useState("");
    const [state, setState] = useState<State>("ALL");
    const [query, setQuery] = useState("");
    const [ws, setWs] = useState<Workspace | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [notice, setNotice] = useState<string | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [busy, setBusy] = useState(false);
    const [selLines, setSelLines] = useState<Set<string>>(new Set());
    const [selItems, setSelItems] = useState<Set<string>>(new Set());
    const [acting, setActing] = useState<StatementLine[] | null>(null);

    const load = useCallback(async () => {
        setLoadError(null);
        try {
            setWs(await bankRecApi.workspace(bankAccountId, { from, to, state }));
            setSelLines(new Set());
            setSelItems(new Set());
        } catch (err) {
            setLoadError(err instanceof ApiError ? err.message : tCommon("loadFailed"));
        }
    }, [bankAccountId, from, to, state, tCommon]);

    useEffect(() => {
        if (allowed) load();
    }, [allowed, load]);

    const badge = useMemo(() => {
        const m = new Map<string, number>();
        ws?.matches.forEach((x, i) => m.set(x.id, i + 1));
        return m;
    }, [ws]);
    const matchById = useMemo(() => new Map((ws?.matches ?? []).map(m => [m.id, m])), [ws]);

    const lines = (ws?.statementLines ?? []).filter(l => matchesQuery(query, [l.description, l.reference, l.chequeNo], l.amount));
    const items = (ws?.bookItems ?? []).filter(i => matchesQuery(query, [i.narration, i.entryNumber, i.counterAccount, i.chequeNo], i.amount));
    const sumS = sumCents((ws?.statementLines ?? []).filter(l => selLines.has(l.id)).map(l => l.amount));
    const sumB = sumCents((ws?.bookItems ?? []).filter(i => selItems.has(i.journalLineId)).map(i => i.amount));
    const selected = selLines.size + selItems.size;
    const oneSided = selLines.size === 0 || selItems.size === 0;
    const canMatch = selected >= 2 && (oneSided ? (selLines.size === 0 ? sumB : sumS) === 0 : sumS === sumB);
    const suggestions = (ws?.matches ?? []).filter(m => m.status === "SUGGESTED");

    const act = async (fn: () => Promise<string | null>) => {
        setBusy(true);
        setError(null);
        setNotice(null);
        try {
            const msg = await fn();
            if (msg) setNotice(msg);
            await load();
        } catch (err) {
            setError(err instanceof ApiError ? err.message : String(err));
        } finally {
            setBusy(false);
        }
    };

    const toggle = (set: Set<string>, id: string, update: (s: Set<string>) => void) => {
        const n = new Set(set);
        if (n.has(id)) n.delete(id); else n.add(id);
        update(n);
    };

    if (!allowed) return <div className="p-6 text-sm text-muted">{t("accessDenied")}</div>;

    const rowTone = (status: string | null) =>
        status === "SUGGESTED" ? "bg-warning/10" : status === "CONFIRMED" ? "bg-success/5" : "";

    const Badge = ({ matchId }: { matchId: string | null }) => {
        if (!matchId) return null;
        const m = matchById.get(matchId);
        return (
            <span data-testid={`badge-${badge.get(matchId)}`} title={m ? t(`method_${m.method}`) : undefined}
                  className={`inline-block min-w-5 text-center px-1 rounded text-[10px] font-bold ${m?.status === "SUGGESTED" ? "bg-warning/30" : "bg-success/20"}`}>
                #{badge.get(matchId)}
            </span>
        );
    };

    const lineActions = (l: StatementLine) => {
        const inSel = selLines.has(l.id) && selLines.size > 1;
        const ls = inSel ? (ws?.statementLines ?? []).filter(x => selLines.has(x.id)) : [l];
        return actionsFor(ls).length > 0 ? ls : null;
    };

    return (
        <div className="p-4 md:p-6 space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="flex items-center gap-2">
                    <Link href="/dashboard/finance/bank-reconciliation" className={small} aria-label={t("title")}><ArrowLeft size={12} className="rtl:rotate-180" /></Link>
                    <h1 className="text-xl font-bold tracking-tight">{t("title")}</h1>
                </div>
                <div className="flex flex-wrap gap-2">
                    <button type="button" className={button} disabled={busy || !!ws?.needsLeaf} data-testid="auto-match"
                            onClick={() => act(async () => {
                                const r = await bankRecApi.autoMatch(bankAccountId, { from, to });
                                return t("autoMatched", { count: r.proposed });
                            })}><Sparkles size={13} />{t("autoMatch")}</button>
                    <button type="button" className={button} disabled={busy || suggestions.length === 0} data-testid="confirm-high"
                            onClick={() => act(async () => {
                                const r = await bankRecApi.confirmAll(bankAccountId, { from, to });
                                return t("confirmedCount", { count: r.confirmed });
                            })}><CheckCheck size={13} />{t("confirmAllHigh")}</button>
                    <a className={button} href={bankRecApi.linesCsvUrl(bankAccountId, { from, to })} download><Download size={13} />{t("exportCsv")}</a>
                </div>
            </div>

            <form className="flex flex-wrap items-end gap-3" onSubmit={e => { e.preventDefault(); load(); }}>
                <label className="text-xs"><span className={`${label} block mb-1`}>{t("from")}</span>
                    <input type="date" className={field} value={from} onChange={e => setFrom(e.target.value)} data-testid="ws-from" /></label>
                <label className="text-xs"><span className={`${label} block mb-1`}>{t("to")}</span>
                    <input type="date" className={field} value={to} onChange={e => setTo(e.target.value)} data-testid="ws-to" /></label>
                <label className="text-xs"><span className={`${label} block mb-1`}>{t("state")}</span>
                    <select className={field} value={state} onChange={e => setState(e.target.value as State)} data-testid="ws-state">
                        {(["UNMATCHED", "SUGGESTED", "ALL"] as State[]).map(s => <option key={s} value={s}>{t(`state_${s}`)}</option>)}
                    </select></label>
                <label className="text-xs flex-1 min-w-40"><span className={`${label} block mb-1`}>{t("search")}</span>
                    <input className={`${field} w-full`} value={query} onChange={e => setQuery(e.target.value)} data-testid="ws-search" /></label>
            </form>

            {loadError && <LoadErrorBanner message={loadError} onRetry={load} />}
            {ws?.needsLeaf && <div className="text-xs text-warning bg-warning/10 rounded-lg px-3 py-2">{t("needsLeaf")}</div>}
            {notice && <div className="text-xs text-success bg-success/10 rounded-lg px-3 py-2" data-testid="ws-notice">{notice}</div>}
            {error && <div role="alert" className="text-xs text-error bg-error/10 rounded-lg px-3 py-2" data-testid="ws-error">{error}</div>}

            {suggestions.length > 0 && (
                <div className="bg-surface border border-border rounded-xl p-3 space-y-1" data-testid="suggestions">
                    <div className={label}>{t("suggested")}</div>
                    {suggestions.map(m => <SuggestionRow key={m.id} m={m} n={badge.get(m.id) ?? 0} busy={busy}
                        onConfirm={() => act(async () => { await bankRecApi.confirm(m.id); return null; })}
                        onReject={() => act(async () => { await bankRecApi.undo(m.id, { reason: "Rejected" }); return null; })} />)}
                </div>
            )}

            <div className="grid grid-cols-1 xl:grid-cols-2 gap-4" data-testid="panes">
                <section className="bg-surface border border-border rounded-xl overflow-x-auto" data-testid="pane-statement">
                    <h2 className="px-3 pt-3 text-sm font-bold">{t("statement")}</h2>
                    <table className="w-full">
                        <thead><tr>
                            <th className={th} /><th className={th}>{t("date")}</th><th className={th}>{t("valueDate")}</th>
                            <th className={th}>{t("description")}</th><th className={th}>{t("reference")}</th>
                            <th className={th}>{t("chequeNo")}</th><th className={`${th} text-end`}>{t("amount")}</th><th className={th} />
                        </tr></thead>
                        <tbody className="divide-y divide-border">
                            {lines.map(l => (
                                <tr key={l.id} className={rowTone(l.matchStatus)} data-testid={`sl-${l.description}`}>
                                    <td className={td}>
                                        {l.matchId ? <Badge matchId={l.matchId} /> : (
                                            <input type="checkbox" aria-label={l.description} checked={selLines.has(l.id)} data-testid={`sel-sl-${l.description}`}
                                                   onChange={() => toggle(selLines, l.id, setSelLines)} />
                                        )}
                                    </td>
                                    <td className={td}><bdi dir="ltr">{dmy(l.txnDate)}</bdi></td>
                                    <td className={td}><bdi dir="ltr">{dmy(l.valueDate)}</bdi></td>
                                    <td className={td}>{l.description}</td>
                                    <td className={td}>{l.reference ?? ""}</td>
                                    <td className={td}>{l.chequeNo ?? ""}</td>
                                    <td className={`${td} text-end`}><Money v={l.amount} /></td>
                                    <td className={`${td} text-end whitespace-nowrap`}>
                                        {l.matchStatus === "CONFIRMED" && (
                                            <UndoButton m={matchById.get(l.matchId!)} busy={busy}
                                                onUndo={(reverse, on) => act(async () => {
                                                    await bankRecApi.undo(l.matchId!, reverse ? { reverseCreated: true, reverseOn: on } : {});
                                                    return null;
                                                })} />
                                        )}
                                        {lineActions(l) && (
                                            <button type="button" className={small} aria-label={t("actions")} data-testid={`act-${l.description}`}
                                                    onClick={() => setActing(lineActions(l))}><MoreHorizontal size={12} /></button>
                                        )}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </section>
                <section className="bg-surface border border-border rounded-xl overflow-x-auto" data-testid="pane-books">
                    <h2 className="px-3 pt-3 text-sm font-bold">{t("books")}</h2>
                    <table className="w-full">
                        <thead><tr>
                            <th className={th} /><th className={th}>{t("date")}</th><th className={th}>{t("docNumber")}</th>
                            <th className={th}>{t("narration")}</th><th className={th}>{t("counterAccount")}</th>
                            <th className={`${th} text-end`}>{t("amount")}</th>
                        </tr></thead>
                        <tbody className="divide-y divide-border">
                            {items.map(i => (
                                <tr key={i.journalLineId} className={rowTone(i.matchStatus)} data-testid={`bi-${i.entryNumber}`}>
                                    <td className={td}>
                                        {i.matchId ? <Badge matchId={i.matchId} /> : (
                                            <input type="checkbox" aria-label={i.entryNumber} checked={selItems.has(i.journalLineId)}
                                                   data-testid={`sel-bi-${i.entryNumber}`} onChange={() => toggle(selItems, i.journalLineId, setSelItems)} />
                                        )}
                                    </td>
                                    <td className={td}><bdi dir="ltr">{dmy(i.entryDate)}</bdi></td>
                                    <td className={td}>{i.entryNumber}</td>
                                    <td className={td}>{i.narration ?? ""}</td>
                                    <td className={td}>{i.counterAccount ?? ""}</td>
                                    <td className={`${td} text-end`}><BookAmount i={i} /></td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </section>
            </div>

            <div className="sticky bottom-0 bg-surface border border-border rounded-xl px-4 py-3 flex flex-wrap items-center gap-4 text-xs" data-testid="sigma-footer">
                <span>{t("sumStatement")}: <Money v={sumS / 100} strong testId="sum-statement" /></span>
                <span>{t("sumBook")}: <Money v={sumB / 100} strong testId="sum-book" /></span>
                <span>{t("difference")}: <Money v={(sumS - sumB) / 100} strong testId="sum-difference" /></span>
                <span className="flex-1 text-muted">{selected === 0 ? t("selectToMatch") : ""}</span>
                <button type="button" className={primary} disabled={busy || !canMatch} data-testid="match"
                        onClick={() => act(async () => {
                            await bankRecApi.match({ statementLineIds: [...selLines], journalLineIds: [...selItems] });
                            return null;
                        })}><Link2 size={13} />{t("match")}</button>
            </div>

            {acting && (
                <LineActionDialog lines={acting} onClose={() => setActing(null)}
                                  onDone={r => { setActing(null); setNotice(t("posted", { numbers: r.entryNumbers.join(", ") })); load(); }} />
            )}
        </div>
    );
}

function BookAmount({ i }: { i: BookItem }) {
    return <Money v={i.amount} />;
}

function SuggestionRow({ m, n, busy, onConfirm, onReject }: {
    m: Match; n: number; busy: boolean; onConfirm: () => void; onReject: () => void;
}) {
    const t = useTranslations("BankRec");
    return (
        <div className="flex flex-wrap items-center gap-2 text-xs" data-testid={`suggestion-${n}`}>
            <span className="font-bold">#{n}</span>
            <span>{t(`method_${m.method}`)}</span>
            {m.confidence && <span className="text-muted">{t(`confidence_${m.confidence}`)}</span>}
            <bdi dir="ltr" className="tabular-nums">{fmtAmount(m.statementLineIds.length ? m.statementTotal : m.bookTotal)}</bdi>
            <button type="button" className={small} disabled={busy} onClick={onConfirm} data-testid={`confirm-${n}`}>{t("confirm")}</button>
            <button type="button" className={small} disabled={busy} onClick={onReject} data-testid={`reject-${n}`}>{t("reject")}</button>
        </div>
    );
}

/**
 * Undo, and — only where the server can do it (a BNK or a BPC; PR #353 review) —
 * undo and reverse, after a confirmation that shows and lets the user change the
 * reversal date (the entry's own date while its period is open, else today).
 */
function UndoButton({ m, busy, onUndo }: { m: Match | undefined; busy: boolean; onUndo: (reverse: boolean, on?: string) => void }) {
    const t = useTranslations("BankRec");
    const [asking, setAsking] = useState(false);
    const [on, setOn] = useState(m?.reverseOnDefault ?? "");
    if (!m) return null;
    return (
        <span className="inline-flex gap-1 me-1 items-center">
            <button type="button" className={small} disabled={busy} onClick={() => onUndo(false)} title={t("undo")} aria-label={t("undo")}
                    data-testid={`undo-${m.id}`}>
                <Undo2 size={12} />
            </button>
            {m.reverseOnDefault && !asking && (
                <button type="button" className={small} disabled={busy} onClick={() => { setOn(m.reverseOnDefault ?? ""); setAsking(true); }}
                        data-testid={`undo-reverse-${m.id}`}>{t("undoReverse")}</button>
            )}
            {asking && (
                <span className="inline-flex items-center gap-1 text-[11px]" data-testid={`reverse-confirm-${m.id}`}>
                    {t("reverseOn")}
                    <input type="date" className={`${field} py-1`} value={on} onChange={e => setOn(e.target.value)} data-testid="reverse-on" />
                    <button type="button" className={small} disabled={busy || !on} data-testid="reverse-go"
                            onClick={() => { setAsking(false); onUndo(true, on); }}>{t("reverseConfirm")}</button>
                    <button type="button" className={small} onClick={() => setAsking(false)}>{t("cancel")}</button>
                </span>
            )}
        </span>
    );
}

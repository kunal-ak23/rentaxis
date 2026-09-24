"use client";

import { useEffect, useMemo, useState } from "react";
import { useTranslations } from "next-intl";
import { loadAccounts } from "@/components/finance/AccountPicker";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount, type Account } from "@/lib/api/ledger";
import {
    bankRecApi,
    chargeSplit,
    dmy,
    sumCents,
    type ActionResult,
    type Candidate,
    type LineCandidates,
    type StatementLine,
} from "@/lib/api/bankRec";
import { Modal } from "./Modal";
import { Money } from "./Money";
import { button, field, label, primary } from "./styles";

export type LineAction = "clear" | "receive" | "receiveSuspense" | "bounce" | "present" | "charge" | "interest" | "suspense" | "other";

/** Which actions a selection of lines offers (spec §3 "Offered when"). */
export function actionsFor(lines: StatementLine[]): LineAction[] {
    if (lines.length === 0) return [];
    const credit = lines.every(l => l.amount > 0);
    const debit = lines.every(l => l.amount < 0);
    const unmatched = lines.every(l => !l.matchId);
    if (!unmatched) return lines.length === 1 && credit && lines[0].matchStatus === "CONFIRMED" ? ["receiveSuspense"] : [];
    if (credit) return lines.length === 1 ? ["clear", "receive", "interest", "suspense", "other"] : ["clear", "other"];
    if (debit) return lines.length === 1 ? ["bounce", "present", "charge", "other"] : lines.length === 2 ? ["charge", "other"] : ["other"];
    return ["other"];
}

const CONTROL = new Set(["RECEIVABLE", "PDC_RECEIVABLE", "DEPOSIT_HELD", "PAYABLE", "PDC_PAYABLE", "ADVANCE"]);

/**
 * A create-from-line action: the candidate documents, the posting preview, and
 * the call. Each goes through the existing service server-side and leaves a
 * confirmed match.
 */
export function LineActionDialog({ lines, initial, onClose, onDone }: {
    lines: StatementLine[]; initial?: LineAction; onClose: () => void; onDone: (r: ActionResult) => void;
}) {
    const t = useTranslations("BankRec");
    const offered = actionsFor(lines);
    const [action, setAction] = useState<LineAction>(initial && offered.includes(initial) ? initial : offered[0]);
    const [cands, setCands] = useState<LineCandidates | null>(null);
    const [picked, setPicked] = useState<Set<string>>(new Set());
    const [leafId, setLeafId] = useState<string>("");
    const [shared, setShared] = useState<boolean | null>(null);
    const [vatIncluded, setVatIncluded] = useState(false);
    const [reason, setReason] = useState("BOUNCE");
    const [accountId, setAccountId] = useState("");
    const [narration, setNarration] = useState("");
    const [accounts, setAccounts] = useState<Account[]>([]);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);

    const first = lines[0];
    const total = sumCents(lines.map(l => l.amount)) / 100;

    useEffect(() => {
        bankRecApi.candidates(first.id).then(c => {
            setCands(c);
            if (c.leaves.length === 1) setLeafId(c.leaves[0].id);
        }).catch(err => setError(err instanceof ApiError ? err.message : String(err)));
        loadAccounts().then(a => setAccounts(a.filter(x => !x.group && x.active && !CONTROL.has(x.accountSubType ?? "")))).catch(() => {});
    }, [first.id]);

    const list: Candidate[] = useMemo(() => {
        if (!cands) return [];
        switch (action) {
            case "clear": return cands.clear;
            case "receive": case "receiveSuspense": return cands.receive;
            case "bounce": return cands.bounce;
            case "present": return cands.present;
            default: return [];
        }
    }, [cands, action]);

    useEffect(() => {
        setPicked(new Set(list.filter(c => c.preselected).map(c => c.id)));
    }, [list]);

    const multi = action === "clear";
    const pickedTotal = sumCents(list.filter(c => picked.has(c.id)).map(c => c.amount)) / 100;
    const needsLeaf = ["charge", "interest", "suspense", "other"].includes(action) && (cands?.leaves.length ?? 0) > 1;
    const bankTrnSet = !!cands?.bankTrnSet;
    const split = chargeSplit(lines.map(l => l.amount), vatIncluded, bankTrnSet);
    const leafName = cands?.leaves.find(l => l.id === leafId)?.name ?? t("gross");
    const abs = Math.abs(total);

    const preview: [string, string, number][] = (() => {
        switch (action) {
            case "clear": case "receive": return [[t("dr"), leafName, abs], [t("cr"), t("pdcReceivable"), abs]];
            case "receiveSuspense": return [[t("dr"), t("suspenseAccount"), picked.size ? pickedTotal : 0], [t("cr"), t("pdcReceivable"), picked.size ? pickedTotal : 0]];
            case "bounce": return [[t("dr"), t("receivable"), abs], [t("cr"), leafName, abs]];
            case "present": return [[t("dr"), t("pdcPayable"), abs], [t("cr"), leafName, abs]];
            case "charge": {
                const out: [string, string, number][] = [[t("dr"), t("net"), split.net]];
                if (split.vat > 0) out.push([t("dr"), t("vat"), split.vat]);
                out.push([t("cr"), leafName, split.gross]);
                return out;
            }
            case "interest": return [[t("dr"), leafName, abs], [t("cr"), t("interestIncome"), abs]];
            case "suspense": return [[t("dr"), leafName, abs], [t("cr"), t("suspenseAccount"), abs]];
            case "other": {
                const acc = accounts.find(a => a.id === accountId)?.name ?? t("account");
                return total > 0 ? [[t("dr"), leafName, abs], [t("cr"), acc, abs]] : [[t("dr"), acc, abs], [t("cr"), leafName, abs]];
            }
        }
    })();

    const ready = (() => {
        if (needsLeaf && !leafId) return false;
        switch (action) {
            case "clear": return picked.size > 0 && Math.round(pickedTotal * 100) === Math.round(total * 100);
            case "receive": case "receiveSuspense": case "bounce": case "present": return picked.size === 1;
            case "other": return !!accountId;
            default: return true;
        }
    })();

    const submit = async () => {
        setBusy(true);
        setError(null);
        const ids = [...picked];
        try {
            let r: ActionResult;
            switch (action) {
                case "clear": r = await bankRecApi.clearCheques(lines.map(l => l.id), ids); break;
                case "receive": r = await bankRecApi.receive({ statementLineId: first.id, chequeId: ids[0], bankLeafId: leafId || null }); break;
                case "receiveSuspense": r = await bankRecApi.receive({ statementLineId: first.id, chequeId: ids[0], fromSuspense: true }); break;
                case "bounce": r = await bankRecApi.bounce({ statementLineId: first.id, chequeId: ids[0], reason }); break;
                case "present": r = await bankRecApi.present({ statementLineId: first.id, issuedChequeId: ids[0] }); break;
                default:
                    r = await bankRecApi.post({
                        statementLineIds: lines.map(l => l.id),
                        kind: action === "charge" ? "CHARGE" : action === "interest" ? "INTEREST" : action === "suspense" ? "SUSPENSE" : "OTHER",
                        vatIncluded, accountId: action === "other" ? accountId : null, bankLeafId: leafId || null,
                        shared: shared ?? undefined, narration: narration || null,
                    });
            }
            onDone(r);
        } catch (err) {
            setError(err instanceof ApiError ? err.message : String(err));
        } finally {
            setBusy(false);
        }
    };

    return (
        <Modal title={`${t(`action_${action}`)} — ${first.description}`} onClose={onClose} testId="line-action">
            <div className="space-y-3">
                <div className="flex flex-wrap gap-1" role="tablist">
                    {offered.map(a => (
                        <button key={a} type="button" role="tab" aria-selected={a === action} data-testid={`tab-${a}`}
                                className={`px-2 py-1 rounded-md text-[11px] font-bold border cursor-pointer ${a === action ? "bg-primary text-primary-foreground border-primary" : "border-border"}`}
                                onClick={() => setAction(a)}>{t(`action_${a}`)}</button>
                    ))}
                </div>
                <div className="text-xs flex gap-3 flex-wrap">
                    {lines.map(l => (
                        <span key={l.id}><bdi dir="ltr">{dmy(l.valueDate ?? l.txnDate)}</bdi> · {l.description} · <Money v={l.amount} strong /></span>
                    ))}
                </div>

                {["clear", "receive", "receiveSuspense", "bounce", "present"].includes(action) && (
                    <div>
                        <div className={`${label} mb-1`}>{t("candidates")}</div>
                        {action === "clear" && <p className="text-[11px] text-muted mb-1">{t("selectCheques")}</p>}
                        {action === "receiveSuspense" && cands && (
                            <p className="text-[11px] text-muted mb-1">{t("suspenseBalance", { amount: fmtAmount(cands.suspenseBalance) })}</p>
                        )}
                        {list.length === 0 ? <p className="text-xs text-muted" data-testid="no-candidates">{t("noCandidates")}</p> : (
                            <div className="max-h-56 overflow-y-auto border border-border rounded-lg divide-y divide-border" data-testid="candidates">
                                {list.map(c => (
                                    <label key={c.id} className="flex items-center gap-2 px-2 py-1.5 text-xs">
                                        <input type={multi ? "checkbox" : "radio"} name="cand" checked={picked.has(c.id)}
                                               data-testid={`cand-${c.chequeNo ?? c.id}`}
                                               onChange={e => setPicked(s => {
                                                   if (!multi) return new Set([c.id]);
                                                   const n = new Set(s);
                                                   if (e.target.checked) n.add(c.id); else n.delete(c.id);
                                                   return n;
                                               })} />
                                        <span className="flex-1">{c.label}</span>
                                        <bdi dir="ltr" className="text-muted">{dmy(c.date)}</bdi>
                                        <Money v={c.amount} />
                                    </label>
                                ))}
                            </div>
                        )}
                        {multi && list.length > 0 && (
                            <p className="text-[11px] mt-1" data-testid="cheques-total">
                                {t("chequesTotal", { total: fmtAmount(pickedTotal), line: fmtAmount(total) })}
                            </p>
                        )}
                    </div>
                )}

                {action === "bounce" && (
                    <label className="block text-xs"><span className={`${label} block mb-1`}>{t("returnReason")}</span>
                        <select className={field} value={reason} onChange={e => setReason(e.target.value)}>
                            {["BOUNCE", "SIGNATURE_MISMATCH", "ACCOUNT_CLOSED"].map(r => <option key={r} value={r}>{t(`reason_${r}`)}</option>)}
                        </select>
                    </label>
                )}
                {action === "charge" && (
                    <div className="text-xs space-y-1">
                        {lines.length === 1 && (
                            <label className="flex items-center gap-2">
                                <input type="checkbox" checked={vatIncluded} onChange={e => setVatIncluded(e.target.checked)} data-testid="vat-included" />
                                {t("vatIncluded")}
                            </label>
                        )}
                        {!bankTrnSet && <p className="text-warning" data-testid="no-trn">{t("noTrnNoVat")}</p>}
                    </div>
                )}
                {action === "other" && (
                    <label className="block text-xs"><span className={`${label} block mb-1`}>{t("account")}</span>
                        <select className={`${field} w-full`} value={accountId} onChange={e => setAccountId(e.target.value)} data-testid="other-account">
                            <option value="" />
                            {accounts.map(a => <option key={a.id} value={a.id}>{a.code} {a.name}</option>)}
                        </select>
                    </label>
                )}
                {(needsLeaf || (action === "receive" && (cands?.leaves.length ?? 0) > 1)) && (
                    <label className="block text-xs"><span className={`${label} block mb-1`}>{t("bankLeaf")}</span>
                        <select className={`${field} w-full`} value={leafId} onChange={e => setLeafId(e.target.value)} data-testid="bank-leaf">
                            <option value="" />
                            {cands?.leaves.map(l => <option key={l.id} value={l.id}>{l.name}</option>)}
                        </select>
                    </label>
                )}
                {["charge", "interest", "suspense", "other"].includes(action) && (
                    <>
                        {(cands?.leaves.length ?? 0) > 1 && (
                            <label className="flex items-center gap-2 text-xs">
                                <input type="checkbox" checked={shared ?? true} onChange={e => setShared(e.target.checked)} />{t("shared")}
                            </label>
                        )}
                        <input className={`${field} w-full`} placeholder={t("narration")} value={narration}
                               onChange={e => setNarration(e.target.value)} maxLength={500} />
                    </>
                )}

                <div className="bg-input rounded-lg p-2 text-xs" data-testid="posting-preview">
                    <div className={`${label} mb-1`}>{t("postingPreview")}</div>
                    {preview.map(([side, acc, amt], i) => (
                        <div key={i} className="flex justify-between gap-2">
                            <span><span className="font-bold">{side}</span> {acc}</span><Money v={amt} />
                        </div>
                    ))}
                </div>
                {error && <div role="alert" className="text-xs text-error">{error}</div>}
                <div className="flex gap-2">
                    <button type="button" className={primary} disabled={busy || !ready} onClick={submit} data-testid="action-submit">{t("book")}</button>
                    <button type="button" className={button} onClick={onClose}>{t("cancel")}</button>
                </div>
            </div>
        </Modal>
    );
}

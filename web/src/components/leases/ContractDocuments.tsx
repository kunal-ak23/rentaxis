"use client";

import { useCallback, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { Download, FileCheck2, FileText, Loader2, Stamp } from "lucide-react";

type ContractDocument = { id: string; type: string; label?: string; createdAt?: string | null };

/**
 * The contract's document history (Kunal, 2026-09-28, "Executed copy at
 * posting"): the signed contract the renter agreed to, and — once the lease is
 * posted and the organisation has a stamp — the executed copy, the same contract
 * with the digital stamp. The signed PDF is never changed; downloads default to
 * the executed copy (`GET /leases/{id}/contract`).
 *
 * `canIssue` (an admin on a posted lease) offers "Issue executed copy" when none
 * exists: the automatic issue at posting never blocks posting, so a failure there
 * (or a stamp added later) is put right here.
 */
export function ContractDocuments({ leaseId, canIssue, leaseStatus }: { leaseId: string; canIssue: boolean; leaseStatus?: string }) {
    const t = useTranslations("ContractDocuments");
    const locale = useLocale();
    const [docs, setDocs] = useState<ContractDocument[] | null>(null);
    const [issuing, setIssuing] = useState(false);
    const [message, setMessage] = useState<string | null>(null);

    const load = useCallback(async () => {
        const res = await fetch(`/api/proxy/v1/leases/${leaseId}/documents`);
        if (!res.ok) { setDocs([]); return; }
        const all = (await res.json()) as ContractDocument[];
        setDocs(all.filter(d => d.type === "CONTRACT" || d.type === "EXECUTED_COPY")
            .sort((a, b) => (a.type === "EXECUTED_COPY" ? -1 : 0) - (b.type === "EXECUTED_COPY" ? -1 : 0)));
    }, [leaseId]);

    useEffect(() => { void load(); }, [load]);

    const download = async (doc: ContractDocument) => {
        const res = await fetch(`/api/proxy/v1/leases/documents/${doc.id}/download`);
        if (!res.ok) return;
        const href = URL.createObjectURL(await res.blob());
        const a = document.createElement("a");
        a.href = href;
        a.download = `${doc.type === "EXECUTED_COPY" ? "executed-copy" : "signed-contract"}-${leaseId.slice(0, 8)}.pdf`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(href);
    };

    const issue = async () => {
        setIssuing(true);
        setMessage(null);
        try {
            const res = await fetch(`/api/proxy/v1/leases/${leaseId}/executed-copy`, { method: "POST" });
            if (res.ok) await load();
            else setMessage(res.status === 409 ? t("notDue") : t("issueFailed"));
        } finally {
            setIssuing(false);
        }
    };

    if (docs === null) return null;
    // Not "signed" while nobody has signed it yet (R3 minor 4).
    const unsigned = leaseStatus === "DRAFT" || leaseStatus === "PENDING_SIGNATURE";
    const nameOf = (doc: ContractDocument) =>
        doc.type === "EXECUTED_COPY" ? t("executedCopy") : unsigned ? t("contract") : t("signedContract");
    const hasSigned = docs.some(d => d.type === "CONTRACT");
    const hasExecuted = docs.some(d => d.type === "EXECUTED_COPY");
    if (!hasSigned && !hasExecuted) return null;

    return (
        <div data-testid="contract-documents" className="space-y-2">
            <p className="text-[10px] font-semibold uppercase tracking-wider text-muted">{t("title")}</p>
            <ul className="space-y-2">
                {docs.map(doc => (
                    <li key={doc.id} data-testid={`contract-doc-${doc.type}`}
                        className="flex items-center gap-3 rounded-lg border border-border px-3 py-2">
                        {doc.type === "EXECUTED_COPY"
                            ? <FileCheck2 size={16} className="shrink-0 text-[var(--gold-500)]" />
                            : <FileText size={16} className="shrink-0 text-muted" />}
                        <div className="min-w-0 flex-1">
                            <p className="truncate text-sm font-medium text-foreground">
                                {nameOf(doc)}
                            </p>
                            {doc.createdAt && (
                                <p className="text-[11px] text-muted">
                                    {new Date(doc.createdAt).toLocaleDateString(locale === "ar" ? "ar-AE" : "en-GB")}
                                </p>
                            )}
                        </div>
                        <button type="button" onClick={() => download(doc)}
                            aria-label={`${t("download")} — ${nameOf(doc)}`}
                            className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-xs font-semibold text-primary hover:bg-primary/5">
                            <Download size={14} /> {t("download")}
                        </button>
                    </li>
                ))}
            </ul>
            {canIssue && hasSigned && !hasExecuted && (
                <button type="button" onClick={issue} disabled={issuing} data-testid="issue-executed-copy"
                    className="inline-flex items-center gap-2 rounded-xl border border-border px-3 py-1.5 text-xs font-semibold text-foreground hover:bg-input disabled:opacity-50">
                    {issuing ? <Loader2 size={14} className="animate-spin" /> : <Stamp size={14} />}
                    {t("issue")}
                </button>
            )}
            {message && <p role="alert" className="text-xs text-error">{message}</p>}
        </div>
    );
}

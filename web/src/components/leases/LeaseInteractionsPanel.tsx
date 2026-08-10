"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import LogInteractionDialog from "./LogInteractionDialog";

type Interaction = {
  id: string;
  leaseId: string;
  opportunityId: string | null;
  type: string;
  direction: string;
  occurredAt: string;
  summary: string;
  outcome: string | null;
  followUpDate: string | null;
  createdBy: string;
  createdByName: string | null;
  createdAt: string;
};

export default function LeaseInteractionsPanel({ leaseId }: { leaseId: string }) {
  const t = useTranslations("interactions");
  const [items, setItems] = useState<Interaction[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [dialogOpen, setDialogOpen] = useState(false);

  const load = async () => {
    setLoading(true);
    setError(false);
    try {
      const res = await fetch(`/api/proxy/v1/leases/${leaseId}/interactions?size=50&sort=occurredAt,desc`);
      if (!res.ok) throw new Error(`Failed to load interactions: ${res.status}`);
      const body = await res.json();
      setItems(body.content ?? []);
    } catch {
      setError(true);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void load(); }, [leaseId]);

  return (
    <section className="bg-surface rounded-[var(--radius-lg)] border border-border overflow-hidden">
      <div className="px-5 py-3.5 border-b border-border bg-[var(--sand-50)] flex items-center justify-between">
        <h2 className="text-xs font-semibold text-muted uppercase tracking-wider">{t("panelTitle")}</h2>
        <button type="button" onClick={() => setDialogOpen(true)} className="rounded border border-border px-2 py-1 text-xs hover:bg-input/40">
          + {t("logInteraction")}
        </button>
      </div>
      <div className="p-4">
        {loading ? <p className="text-xs text-muted">{t("loading")}</p>
         : error ? (
          <div className="flex items-center gap-3">
            <p role="alert" className="text-xs text-red-500">{t("errors.loadFailed")}</p>
            <button type="button" onClick={() => void load()} className="rounded border border-border px-2 py-1 text-xs hover:bg-input/40">
              {t("retry")}
            </button>
          </div>
        )
         : items.length === 0 ? <p className="text-xs text-muted">{t("noInteractions")}</p>
         : (
          <ul className="space-y-3">
            {items.map(i => (
              <li key={i.id} className={"border-l-2 pl-3 " + (i.type === "SYSTEM_INTENT" ? "border-blue-400 opacity-80" : "border-primary")}>
                <p className="text-[11px] text-muted">
                  {new Date(i.occurredAt).toLocaleString()} · {t(`type.${i.type}` as any)} · {t(`direction.${i.direction}` as any)} · {i.createdByName ?? ""}
                </p>
                <p className="text-sm whitespace-pre-wrap">{i.summary}</p>
                {(i.outcome || i.followUpDate) && (
                  <p className="text-[11px] text-muted mt-1">
                    {i.outcome ? `${t(`outcome.${i.outcome}` as any)}` : ""}
                    {i.outcome && i.followUpDate ? " · " : ""}
                    {i.followUpDate ? `${t("fields.followUpLabel")}: ${i.followUpDate}` : ""}
                  </p>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>
      {dialogOpen && (
        <LogInteractionDialog leaseId={leaseId}
                              onClose={() => setDialogOpen(false)}
                              onSuccess={() => { setDialogOpen(false); void load(); }} />
      )}
    </section>
  );
}

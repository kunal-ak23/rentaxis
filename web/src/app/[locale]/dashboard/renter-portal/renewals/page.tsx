"use client";
import { useEffect, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import RenewalCard from "@/components/renewals/RenewalCard";

type LeaseRenewalView = {
  leaseId: string;
  unitNumber: string | null;
  propertyNameEn: string | null;
  endDate: string;
  daysRemaining: number;
  opportunityId: string | null;
  stage: string | null;
  intent: string | null;
  reminders: { slot: number; status: string; sentAt: string | null }[];
  /** Break-it R3 portal3 F8: the contract is over — shown as ended, no renewal choice. */
  ended?: boolean;
  endedDaysAgo?: number;
};

export default function RenterRenewalsPage() {
  const t = useTranslations("renewals");
  const [data, setData] = useState<{ leases: LeaseRenewalView[] } | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  // Break-it R3 portal3 F3: one answer in flight at a time — a double tap used to
  // post twice and notify staff twice.
  const [saving, setSaving] = useState<string | null>(null);
  const inFlight = useRef(false);

  const load = async () => {
    try {
      const res = await fetch("/api/proxy/v1/me/renewals");
      if (!res.ok) { setLoadError(true); return; }
      setData(await res.json());
      setLoadError(false);
    } catch {
      setLoadError(true);
    }
  };
  useEffect(() => { void load(); }, []);

  const setIntent = async (opportunityId: string, intent: string) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setSaving(opportunityId);
    setActionError(null);
    try {
      const res = await fetch(`/api/proxy/v1/me/renewals/${opportunityId}/intent`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ intent }),
      });
      if (!res.ok) {
        // 400: the contract has ended (renewal.contractEnded), or the manager
        // already closed this renewal; anything else (stale id, transient
        // failure) gets the generic message.
        const body = await res.json().catch(() => null);
        setActionError(body?.code === "renewal.contractEnded" ? t("contractEnded")
          : res.status === 400 ? t("intentAlreadyResolved") : t("genericError"));
      }
    } catch {
      setActionError(t("genericError"));
    }
    try {
      await load();
    } finally {
      inFlight.current = false;
      setSaving(null);
    }
  };

  if (!data) {
    if (loadError) {
      return (
        <div className="p-6 space-y-3">
          <h1 className="text-lg font-semibold">{t("pageTitle")}</h1>
          <p className="text-sm text-error">{t("loadFailed")}</p>
          <button onClick={() => void load()} className="rounded border border-border px-3 py-1 text-xs">
            {t("retry")}
          </button>
        </div>
      );
    }
    return <p className="p-6 text-sm text-muted">{t("loading")}</p>;
  }

  const live = data.leases.filter(l => !l.ended);
  const ended = data.leases.filter(l => l.ended);
  const buckets = [
    { key: "within30", filter: (d: number) => d <= 30 },
    { key: "within60", filter: (d: number) => d > 30 && d <= 60 },
    { key: "within90", filter: (d: number) => d > 60 && d <= 90 },
    { key: "beyond90", filter: (d: number) => d > 90 },
  ];

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-lg font-semibold">{t("pageTitle")}</h1>
      {actionError && <p className="text-xs text-error">{actionError}</p>}
      {loadError && (
        <div className="flex items-center gap-3">
          <p className="text-xs text-error">{t("loadFailed")}</p>
          <button onClick={() => void load()} className="rounded border border-border px-3 py-1 text-xs">
            {t("retry")}
          </button>
        </div>
      )}
      {buckets.map(b => {
        const leases = live.filter(l => b.filter(l.daysRemaining));
        return (
          <section key={b.key}>
            <h2 className="text-sm font-medium mb-2">{t(b.key as any)}</h2>
            {leases.length === 0
              ? <p className="text-xs text-muted">{t("emptyBucket")}</p>
              : <div className="space-y-2">
                  {leases.map(l => (
                    <RenewalCard key={l.leaseId} {...l} busy={saving !== null}
                                 onSetIntent={(i) => l.opportunityId && setIntent(l.opportunityId, i)} />
                  ))}
                </div>}
          </section>
        );
      })}
      {ended.length > 0 && (
        <section>
          <h2 className="text-sm font-medium mb-2">{t("endedSection")}</h2>
          <div className="space-y-2">
            {ended.map(l => <RenewalCard key={l.leaseId} {...l} />)}
          </div>
        </section>
      )}
    </div>
  );
}

"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import RenewalCard from "@/components/renewals/RenewalCard";

type LeaseRenewalView = {
  leaseId: string;
  unitNumber: string;
  propertyNameEn: string;
  endDate: string;
  daysRemaining: number;
  opportunityId: string | null;
  stage: string | null;
  intent: string | null;
  reminders: { slot: number; status: string; sentAt: string | null }[];
};

export default function RenterRenewalsPage() {
  const t = useTranslations("renewals");
  const [data, setData] = useState<{ leases: LeaseRenewalView[] } | null>(null);

  const load = async () => {
    const res = await fetch("/api/proxy/v1/me/renewals");
    if (!res.ok) { setData({ leases: [] }); return; }
    setData(await res.json());
  };
  useEffect(() => { void load(); }, []);

  const setIntent = async (opportunityId: string, intent: string) => {
    await fetch(`/api/proxy/v1/me/renewals/${opportunityId}/intent`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ intent }),
    });
    await load();
  };

  if (!data) return <p className="p-6 text-sm text-muted">{t("loading")}</p>;

  const buckets = [
    { key: "within30", filter: (d: number) => d <= 30 },
    { key: "within60", filter: (d: number) => d > 30 && d <= 60 },
    { key: "within90", filter: (d: number) => d > 60 && d <= 90 },
    { key: "beyond90", filter: (d: number) => d > 90 },
  ];

  return (
    <div className="p-6 space-y-6">
      <h1 className="text-lg font-semibold">{t("pageTitle")}</h1>
      {buckets.map(b => {
        const leases = data.leases.filter(l => b.filter(l.daysRemaining));
        return (
          <section key={b.key}>
            <h2 className="text-sm font-medium mb-2">{t(b.key as any)}</h2>
            {leases.length === 0
              ? <p className="text-xs text-muted">{t("emptyBucket")}</p>
              : <div className="space-y-2">
                  {leases.map(l => (
                    <RenewalCard key={l.leaseId} {...l}
                                 onSetIntent={(i) => l.opportunityId && setIntent(l.opportunityId, i)} />
                  ))}
                </div>}
          </section>
        );
      })}
    </div>
  );
}

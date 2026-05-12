"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";

export default function RenewalBanner() {
  const t = useTranslations("renewals");
  const [active, setActive] = useState<{ days: number } | null>(null);

  useEffect(() => {
    (async () => {
      const res = await fetch("/api/proxy/v1/me/renewals");
      if (!res.ok) return;
      const body = await res.json();
      const inWindow = (body.leases ?? [])
        .filter((l: any) => l.opportunityId)
        .sort((a: any, b: any) => a.daysRemaining - b.daysRemaining)[0];
      if (inWindow) setActive({ days: inWindow.daysRemaining });
    })();
  }, []);

  if (!active) return null;
  return (
    <div className="rounded border border-border bg-[var(--sand-50)] p-3 mb-4 flex items-center justify-between">
      <p className="text-sm">⏰ {t("bannerMessage", { days: active.days })}</p>
      <Link href="/dashboard/renter-portal/renewals" className="text-xs underline">{t("bannerCta")}</Link>
    </div>
  );
}

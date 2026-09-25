// src/components/settings/OrganisationSection.tsx
"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";

/** Read-only: GET /v1/tenant/info (name, slug) — the only organisation fields an endpoint returns today. */
export default function OrganisationSection() {
    const t = useTranslations("SettingsPage");
    const [info, setInfo] = useState<{ name: string; slug: string } | null>(null);
    const [failed, setFailed] = useState(false);
    useEffect(() => {
        fetch("/api/proxy/v1/tenant/info")
            .then(r => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
            .then(setInfo)
            .catch(() => setFailed(true));
    }, []);
    if (failed) return <p className="text-xs text-error" role="alert">{t("orgLoadFailed")}</p>;
    return (
        <dl className="bg-surface rounded-xl border border-border p-5 grid grid-cols-1 sm:grid-cols-2 gap-4" data-testid="organisation-section">
            <div><dt className="text-[11px] text-muted">{t("orgName")}</dt><dd className="text-sm font-semibold">{info?.name ?? "—"}</dd></div>
            <div><dt className="text-[11px] text-muted">{t("orgSlug")}</dt><dd className="text-sm font-mono"><bdi dir="ltr">{info?.slug ?? "—"}</bdi></dd></div>
        </dl>
    );
}

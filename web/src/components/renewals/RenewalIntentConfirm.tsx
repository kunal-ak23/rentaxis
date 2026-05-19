"use client";
import { useState } from "react";
import { useTranslations } from "next-intl";

export default function RenewalIntentConfirm({ token, intent }: { token: string; intent: string }) {
  const t = useTranslations("renewals");
  const [status, setStatus] = useState<"idle"|"submitting"|"done"|"expired"|"error">("idle");

  const submit = async () => {
    setStatus("submitting");
    try {
      const res = await fetch("/api/proxy/v1/public/renewal-intent", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token }),
      });
      if (res.status === 410) setStatus("expired");
      else if (!res.ok) setStatus("error");
      else {
        setStatus("done");
        const body = await res.json();
        setTimeout(() => { window.location.href = body.redirectTo ?? "/dashboard/renter-portal/renewals"; }, 1200);
      }
    } catch {
      setStatus("error");
    }
  };

  return (
    <div className="rounded border border-border p-6 max-w-md mx-auto mt-12">
      <h2 className="text-lg font-semibold">{t("confirmIntent")}: {intent ? t(`intent.${intent}` as any) : ""}</h2>
      {status === "expired" && <p className="mt-2 text-sm text-red-700">{t("tokenExpired")}</p>}
      {status === "error" && <p className="mt-2 text-sm text-red-700">{t("genericError")}</p>}
      {status !== "done" && status !== "expired" && (
        <button onClick={submit} disabled={status === "submitting"}
                className="mt-4 rounded bg-primary text-primary-foreground px-4 py-2 text-sm disabled:opacity-50">
          {status === "submitting" ? "…" : t("confirm")}
        </button>
      )}
      {status === "done" && <p className="mt-3 text-sm text-green-700">{t("captured")}</p>}
    </div>
  );
}

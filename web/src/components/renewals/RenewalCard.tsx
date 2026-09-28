"use client";
import { useTranslations } from "next-intl";

type Props = {
  leaseId: string;
  unitNumber: string | null;
  propertyNameEn: string | null;
  endDate: string;
  daysRemaining: number;
  opportunityId: string | null;
  stage: string | null;
  intent: string | null;
  reminders: { slot: number; status: string; sentAt: string | null }[];
  /** Break-it R3 portal3 F8: the contract is over (past its end, or terminated). */
  ended?: boolean;
  endedDaysAgo?: number;
  /** An answer is being saved: every choice is disabled (F3, no double post). */
  busy?: boolean;
  onSetIntent?: (intent: "RENEW" | "MOVE_OUT" | "DISCUSS") => void;
};

export default function RenewalCard(p: Props) {
  const t = useTranslations("renewals");
  if (!p.opportunityId) {
    return (
      <div className="rounded border border-border p-3 text-xs text-muted">
        {t("beyond90Hint")}
      </div>
    );
  }
  const noIntent = p.intent == null;
  const title = [p.propertyNameEn, p.unitNumber].filter(Boolean).join(" · ") || t("unitFallback");
  const choose = (i: "RENEW" | "MOVE_OUT" | "DISCUSS") => { if (!p.busy) p.onSetIntent?.(i); };
  if (p.ended) {
    // No countdown and no choice: the contract is over (the server refuses an answer too).
    return (
      <div className="rounded border border-border p-4">
        <p className="text-sm font-medium">{title}</p>
        <p className="text-xs text-error">{t("endedDaysAgo", { date: p.endDate, days: Math.max(0, p.endedDaysAgo ?? 0) })}</p>
        <p className="text-xs text-muted mt-2">{t("endedHint")}</p>
      </div>
    );
  }
  return (
    <div className="rounded border border-border p-4">
      <p className="text-sm font-medium">{title}</p>
      <p className="text-xs text-muted">{t("endDateLine", { date: p.endDate, days: Math.max(0, p.daysRemaining) })}</p>
      {noIntent
        ? <div className="mt-3 flex gap-2 flex-wrap">
            <button disabled={p.busy} onClick={() => choose("RENEW")} className="rounded bg-primary text-primary-foreground px-3 py-1 text-xs disabled:opacity-60">{t("renew")}</button>
            <button disabled={p.busy} onClick={() => choose("MOVE_OUT")} className="rounded border border-border px-3 py-1 text-xs disabled:opacity-60">{t("moveOut")}</button>
            <button disabled={p.busy} onClick={() => choose("DISCUSS")} className="rounded border border-border px-3 py-1 text-xs disabled:opacity-60">{t("discuss")}</button>
          </div>
        : <div className="mt-2">
            <p className="text-xs text-green-700">✅ {t("statusYouSelected", { intent: t(`intent.${p.intent}` as any) })}</p>
            <p className="text-xs text-muted mt-2">{t("changeYourMind")}</p>
            <div className="mt-1 flex gap-2 flex-wrap">
              {["RENEW", "MOVE_OUT", "DISCUSS"].filter(i => i !== p.intent).map(i => (
                <button key={i} disabled={p.busy} onClick={() => choose(i as "RENEW" | "MOVE_OUT" | "DISCUSS")} className="rounded border border-border px-2 py-0.5 text-[11px] disabled:opacity-60">{t(`intent.${i}` as any)}</button>
              ))}
            </div>
          </div>}
      <p className="mt-3 text-[11px] text-muted">
        {t("remindersSentLabel")}: {p.reminders.filter(r => r.status === "SENT").map(r => `${r.slot}d (${r.sentAt})`).join(", ") || "—"}
      </p>
    </div>
  );
}

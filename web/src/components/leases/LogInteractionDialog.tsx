"use client";

import { useEffect, useRef, useState } from "react";
import { X } from "lucide-react";
import { useTranslations } from "next-intl";

const DIALOG_TITLE_ID = "log-interaction-dialog-title";

type Props = {
  leaseId: string;
  onClose: () => void;
  onSuccess: () => void;
};

type InteractionType = "CALL" | "SMS" | "EMAIL" | "WHATSAPP" | "MEETING" | "NOTE";
type Direction = "INBOUND" | "OUTBOUND" | "INTERNAL";
type Outcome = "POSITIVE" | "NEUTRAL" | "NEGATIVE" | "NO_RESPONSE";

function nowLocalDatetimeValue(): string {
  const now = new Date();
  // Format: YYYY-MM-DDTHH:mm  (what datetime-local expects)
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    now.getFullYear() +
    "-" +
    pad(now.getMonth() + 1) +
    "-" +
    pad(now.getDate()) +
    "T" +
    pad(now.getHours()) +
    ":" +
    pad(now.getMinutes())
  );
}

function todayDateValue(): string {
  const now = new Date();
  const pad = (n: number) => String(n).padStart(2, "0");
  return (
    now.getFullYear() + "-" + pad(now.getMonth() + 1) + "-" + pad(now.getDate())
  );
}

export default function LogInteractionDialog({ leaseId, onClose, onSuccess }: Props) {
  const t = useTranslations("interactions");

  const dialogRef = useRef<HTMLDivElement | null>(null);
  const submittingRef = useRef(false);
  const onCloseRef = useRef(onClose);

  const [type, setType] = useState<InteractionType>("CALL");
  const [direction, setDirection] = useState<Direction>("OUTBOUND");
  const [occurredAt, setOccurredAt] = useState<string>(nowLocalDatetimeValue());
  const [summary, setSummary] = useState<string>("");
  const [outcome, setOutcome] = useState<Outcome | "">("");
  const [followUpDate, setFollowUpDate] = useState<string>("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Keep refs in sync so the mount-once a11y effect can read latest values.
  useEffect(() => { submittingRef.current = submitting; }, [submitting]);
  useEffect(() => { onCloseRef.current = onClose; }, [onClose]);

  // a11y: Escape to close, focus restoration, Tab focus trap. Runs once on
  // mount — re-running on submitting would clobber previouslyFocused and
  // cause a focus jump mid-submit.
  useEffect(() => {
    const previouslyFocused = (typeof document !== "undefined" ? document.activeElement : null) as HTMLElement | null;

    const focusables = () =>
      dialogRef.current
        ? Array.from(
            dialogRef.current.querySelectorAll<HTMLElement>(
              'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
            )
          )
        : [];

    // Focus the first focusable on open.
    queueMicrotask(() => focusables()[0]?.focus());

    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !submittingRef.current) {
        e.preventDefault();
        onCloseRef.current();
        return;
      }
      if (e.key === "Tab") {
        const items = focusables();
        if (items.length === 0) return;
        const first = items[0];
        const last = items[items.length - 1];
        const active = document.activeElement as HTMLElement | null;
        // If focus has escaped the trap entirely, redirect into the dialog.
        if (!active || !dialogRef.current?.contains(active) || !items.includes(active)) {
          e.preventDefault();
          (e.shiftKey ? last : first).focus();
          return;
        }
        if (e.shiftKey && active === first) {
          e.preventDefault();
          last.focus();
        } else if (!e.shiftKey && active === last) {
          e.preventDefault();
          first.focus();
        }
      }
    };

    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      previouslyFocused?.focus?.();
    };
    // Mount-once: latest submitting/onClose read via refs above.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!summary.trim()) {
      setError(t("errors.summaryRequired"));
      return;
    }
    setSubmitting(true);
    submittingRef.current = true;
    setError(null);
    try {
      const body: Record<string, unknown> = {
        type,
        direction,
        occurredAt: new Date(occurredAt).toISOString(),
        summary: summary.trim(),
        outcome: outcome || null,
        followUpDate: followUpDate || null,
      };
      const res = await fetch(`/api/proxy/v1/leases/${leaseId}/interactions`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!res.ok) {
        const msg = await res.text().catch(() => "Unknown error");
        throw new Error(msg || `HTTP ${res.status}`);
      }
      onSuccess();
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : t("errors.saveFailed"));
    } finally {
      setSubmitting(false);
      submittingRef.current = false;
    }
  }

  const today = todayDateValue();

  const TYPES: InteractionType[] = ["CALL", "SMS", "EMAIL", "WHATSAPP", "MEETING", "NOTE"];
  const DIRECTIONS: Direction[] = ["INBOUND", "OUTBOUND", "INTERNAL"];
  const OUTCOMES: Outcome[] = ["POSITIVE", "NEUTRAL", "NEGATIVE", "NO_RESPONSE"];

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4">
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={DIALOG_TITLE_ID}
        className="w-full max-w-lg rounded-xl border border-border bg-background shadow-lg"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border px-5 py-4">
          <h3 id={DIALOG_TITLE_ID} className="text-base font-semibold">
            {t("logInteraction")}
          </h3>
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            aria-label={t("actions.close")}
            className="rounded p-1 text-muted hover:bg-input/40 disabled:opacity-50"
          >
            <X size={16} />
          </button>
        </div>

        {/* Form */}
        <form onSubmit={handleSubmit} className="space-y-4 px-5 py-4">
          {/* Type */}
          <div>
            <label htmlFor="log-type" className="mb-1 block text-sm font-medium">
              {t("fields.type")}
            </label>
            <select
              id="log-type"
              value={type}
              onChange={e => setType(e.target.value as InteractionType)}
              required
              className="w-full rounded border border-border bg-background px-3 py-2 text-sm"
            >
              {TYPES.map(tp => (
                <option key={tp} value={tp}>
                  {t(`type.${tp}` as Parameters<typeof t>[0])}
                </option>
              ))}
            </select>
          </div>

          {/* Direction */}
          <fieldset>
            <legend className="mb-1 text-sm font-medium">{t("fields.direction")}</legend>
            <div className="flex gap-4">
              {DIRECTIONS.map(dir => (
                <label key={dir} className="flex items-center gap-1.5 text-sm">
                  <input
                    type="radio"
                    name="direction"
                    value={dir}
                    checked={direction === dir}
                    onChange={() => setDirection(dir)}
                    required
                  />
                  {t(`direction.${dir}` as Parameters<typeof t>[0])}
                </label>
              ))}
            </div>
          </fieldset>

          {/* Occurred At */}
          <div>
            <label htmlFor="log-occurred-at" className="mb-1 block text-sm font-medium">
              {t("fields.occurredAt")}
            </label>
            <input
              id="log-occurred-at"
              type="datetime-local"
              value={occurredAt}
              onChange={e => setOccurredAt(e.target.value)}
              required
              className="w-full rounded border border-border bg-background px-3 py-2 text-sm"
            />
          </div>

          {/* Summary */}
          <div>
            <label htmlFor="log-summary" className="mb-1 block text-sm font-medium">
              {t("fields.summary")}
            </label>
            <textarea
              id="log-summary"
              value={summary}
              onChange={e => setSummary(e.target.value)}
              required
              rows={3}
              className="w-full rounded border border-border bg-background px-3 py-2 text-sm"
            />
          </div>

          {/* Outcome (optional) */}
          <div>
            <label htmlFor="log-outcome" className="mb-1 block text-sm font-medium">
              {t("fields.outcome")} <span className="text-muted text-xs">{t("fields.optional")}</span>
            </label>
            <select
              id="log-outcome"
              value={outcome}
              onChange={e => setOutcome(e.target.value as Outcome | "")}
              className="w-full rounded border border-border bg-background px-3 py-2 text-sm"
            >
              <option value="">{t("fields.outcomeNone")}</option>
              {OUTCOMES.map(o => (
                <option key={o} value={o}>
                  {t(`outcome.${o}` as Parameters<typeof t>[0])}
                </option>
              ))}
            </select>
          </div>

          {/* Follow-up Date (optional) */}
          <div>
            <label htmlFor="log-follow-up" className="mb-1 block text-sm font-medium">
              {t("fields.followUpDate")} <span className="text-muted text-xs">{t("fields.optional")}</span>
            </label>
            <input
              id="log-follow-up"
              type="date"
              value={followUpDate}
              onChange={e => setFollowUpDate(e.target.value)}
              min={today}
              className="w-full rounded border border-border bg-background px-3 py-2 text-sm"
            />
          </div>

          {/* Error */}
          {error && (
            <p role="alert" className="text-sm text-red-500">
              {error}
            </p>
          )}

          {/* Actions */}
          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              className="rounded px-4 py-2 text-sm hover:bg-input/40 disabled:opacity-50"
            >
              {t("actions.cancel")}
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="rounded bg-primary px-4 py-2 text-sm font-medium text-primary-foreground disabled:opacity-50"
            >
              {submitting ? t("actions.saving") : t("actions.save")}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

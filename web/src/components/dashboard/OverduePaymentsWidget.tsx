"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { formatCurrencyCompact } from "@/lib/format";
import { chequeApi, type Cheque } from "@/lib/api/leasing";

/**
 * `GET /cheques/due` returns every matured, unpaid row — due-today and
 * overdue alike, each carrying its own `overdue` flag (spec §7.5). There is
 * no server-side "overdue only" filter, so the widget fetches a bounded page
 * (the same default sort as the register: nearest maturity first) and keeps
 * the ones already past grace.
 */
const FETCH_SIZE = 50;
const DISPLAY_LIMIT = 5;

export default function OverduePaymentsWidget() {
  const t = useTranslations("Dashboard");
  const [items, setItems] = useState<Cheque[]>([]);
  const [count, setCount] = useState(0);

  useEffect(() => {
    chequeApi
      .due({ page: 0, size: FETCH_SIZE })
      .then(page => {
        const overdue = (page.content ?? []).filter(c => c.overdue);
        setItems(overdue.slice(0, DISPLAY_LIMIT));
        setCount(overdue.length);
      })
      .catch(() => {});
  }, []);

  return (
    <div className="bg-surface rounded-[var(--radius-lg)] border border-border p-4">
      <div className="flex items-center justify-between mb-2">
        <h3 className="text-xs font-semibold text-muted uppercase tracking-wider">
          {t("overduePaymentsTitle")} {count > 0 && `(${count})`}
        </h3>
        {count > 0 && (
          <Link
            href="/dashboard/collections?tab=overdue"
            className="text-[11px] font-medium text-primary hover:underline"
          >
            {t("viewAll")}
          </Link>
        )}
      </div>
      {items.length === 0 ? (
        <p className="text-xs text-muted">{t("noOverduePayments")}</p>
      ) : (
        <ul className="space-y-2">
          {items.map((c) => (
            <li key={c.id} className="text-xs">
              <Link
                href={`/dashboard/leases/${c.leaseId}`}
                className="flex items-center justify-between gap-2 hover:underline"
              >
                <span className="truncate">
                  {c.unitIdentifier ?? "—"} · {c.renterName ?? "—"}
                </span>
                <span className="font-semibold text-error tabular-nums whitespace-nowrap">
                  {formatCurrencyCompact(c.amount)}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { formatCurrencyCompact } from "@/lib/format";
import { chequeApi, type Cheque } from "@/lib/api/leasing";

export default function ChequesToDepositWidget() {
  const t = useTranslations("Dashboard");
  const [items, setItems] = useState<Cheque[]>([]);
  const [count, setCount] = useState(0);

  useEffect(() => {
    chequeApi
      .toDeposit({ page: 0, size: 5 })
      .then(page => {
        setItems(page.content ?? []);
        setCount(page.totalElements ?? (page.content ?? []).length);
      })
      .catch(() => {});
  }, []);

  return (
    <div className="bg-surface rounded-[var(--radius-lg)] border border-border p-4">
      <div className="flex items-center justify-between mb-2">
        <h3 className="text-xs font-semibold text-muted uppercase tracking-wider">
          {t("chequesToDepositTitle")} {count > 0 && `(${count})`}
        </h3>
        {count > 0 && (
          <Link
            href="/dashboard/collections?tab=deposit"
            className="text-[11px] font-medium text-primary hover:underline"
          >
            {t("viewAll")}
          </Link>
        )}
      </div>
      {items.length === 0 ? (
        <p className="text-xs text-muted">{t("noChequesDueToday")}</p>
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
                  {c.chequeNumber ? ` · #${c.chequeNumber}` : ""}
                </span>
                <span className="font-semibold text-foreground tabular-nums whitespace-nowrap">
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

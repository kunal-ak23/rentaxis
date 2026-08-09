"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";

type FollowUp = {
  id: string;
  leaseId: string;
  summary: string;
  followUpDate: string | null;
};

export default function FollowUpsWidget() {
  const t = useTranslations("followUpsWidget");
  const [items, setItems] = useState<FollowUp[]>([]);
  const [count, setCount] = useState(0);
  const [error, setError] = useState(false);

  useEffect(() => {
    (async () => {
      try {
        const res = await fetch("/api/proxy/v1/renewals/follow-ups");
        if (!res.ok) {
          setError(true);
          return;
        }
        const body = await res.json();
        const list = Array.isArray(body) ? body : [];
        setItems(list.slice(0, 5));
        setCount(list.length);
      } catch {
        setError(true);
      }
    })();
  }, []);

  return (
    <div className="bg-surface rounded-[var(--radius-lg)] border border-border p-4">
      <h3 className="text-xs font-semibold text-muted uppercase tracking-wider mb-2">
        {t("title")} {count > 0 && `(${count})`}
      </h3>
      {error ? <p className="text-xs text-muted">{t("loadError")}</p>
       : items.length === 0 ? <p className="text-xs text-muted">{t("empty")}</p>
       : <ul className="space-y-2">
           {items.map(i => (
             <li key={i.id} className="text-xs">
               <Link href={`/dashboard/leases/${i.leaseId}`} className="hover:underline">
                 {i.followUpDate} · {(i.summary ?? "").slice(0, 60)}
               </Link>
             </li>
           ))}
         </ul>}
    </div>
  );
}

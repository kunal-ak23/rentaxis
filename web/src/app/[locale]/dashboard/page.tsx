"use client";

import { useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { formatCurrencyCompact } from "@/lib/format";
import { activityText, isolate, type ActivityItem } from "@/components/dashboard/activityText";
import { collectionTile, type CollectionSummary } from "@/components/dashboard/collectionTile";
import { cn } from "@/lib/utils";
import { Activity, Calendar, Plus, TrendingDown, TrendingUp } from "lucide-react";
import { hasPermission, type UserRole } from "@/lib/rbac";
import FollowUpsWidget from "@/components/dashboard/FollowUpsWidget";
import OverduePaymentsWidget from "@/components/dashboard/OverduePaymentsWidget";
import ChequesToDepositWidget from "@/components/dashboard/ChequesToDepositWidget";
import RecognitionBehindWidget from "@/components/dashboard/RecognitionBehindWidget";

type DashboardSummary = {
  totalProperties: number;
  totalUnits: number;
  occupiedUnits: number;
  reservedUnits: number;
  vacantUnits: number;
  occupancyRate: number;
  activeLeases: number;
  draftLeases: number;
  expiringLeases: number;
  totalRentRevenue: number;
  collectedAmount: number;
  pendingAmount: number;
  pendingThisMonthAmount: number;
  overdueAmount: number;
  recentActivity: ActivityItem[];
} & CollectionSummary;

type MonthlyPoint = {
  month: string;
  ym: string;
  expected: number;
  collected: number;
};

// Takes the translator and locale rather than reaching for a hook: this is a
// module-level helper, not a component.
function formatTimeAgo(
  timestamp: string,
  t: (key: string, values?: Record<string, string | number | Date>) => string,
  locale: string,
): string {
  const now = new Date();
  const date = new Date(timestamp);
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);
  const diffDays = Math.floor(diffMs / 86400000);

  if (diffMins < 1) return t("justNow");
  if (diffMins < 60) return t("minutesAgo", { n: diffMins });
  if (diffHours < 24) return t("hoursAgo", { n: diffHours });
  if (diffDays < 7) return t("daysAgo", { n: diffDays });
  return date.toLocaleDateString(locale);
}

function Sparkline({ data, color }: { data: number[]; color: string }) {
  const w = 100;
  const h = 28;
  const max = Math.max(...data);
  const min = Math.min(...data);
  const span = max - min || 1;
  const points = data.map((d, i) => {
    const x = (i / (data.length - 1)) * w;
    const y = h - ((d - min) / span) * (h - 4) - 2;
    return `${x},${y}`;
  });
  const path = points.map((p, i) => `${i === 0 ? "M" : "L"}${p}`).join(" ");
  const area = `${path} L${w},${h} L0,${h} Z`;

  return (
    <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" className="w-full" style={{ height: h }}>
      <path d={area} fill={color} opacity="0.12" />
      <path d={path} fill="none" stroke={color} strokeWidth="1.5" strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}

function StatCard({
  label,
  value,
  unit,
  delta,
  deltaPos = true,
  sub,
  note,
  sparkData,
  sparkColor,
}: {
  label: string;
  value: string;
  unit?: string;
  delta?: string;
  deltaPos?: boolean;
  sub?: string;
  /** A second, quieter line under `sub`. */
  note?: string;
  sparkData?: number[];
  sparkColor?: string;
}) {
  return (
    <div className="bg-surface border border-border rounded-[var(--radius-lg)] p-5 flex flex-col gap-2.5">
      <div className="flex items-center justify-between">
        <span className="text-[12.5px] text-[var(--ink-500)] font-medium">{label}</span>
        {delta && (
          <span className={cn("flex items-center gap-1 text-[12px] font-semibold", deltaPos ? "text-[var(--green-600)]" : "text-[var(--red-600)]")}>
            {deltaPos ? <TrendingUp size={11} /> : <TrendingDown size={11} />} {delta}
          </span>
        )}
      </div>
      <div className="flex items-baseline gap-1.5">
        <span className="font-serif text-[28px] font-semibold tracking-tight leading-none">{value}</span>
        {unit && <span className="text-[13px] text-[var(--ink-500)] font-medium">{unit}</span>}
      </div>
      {sub && <p className="text-[12px] text-[var(--ink-500)]">{sub}</p>}
      {note && <p className="text-[11.5px] text-[var(--ink-500)]">{note}</p>}
      {sparkData && <Sparkline data={sparkData} color={sparkColor ?? "var(--accent)"} />}
    </div>
  );
}

function CollectionChart({ data }: { data: MonthlyPoint[] }) {
  const t = useTranslations("Dashboard");
  if (data.length === 0) {
    return (
      <div className="bg-surface border border-border rounded-[var(--radius-lg)] p-5">
        <div className="text-[13px] text-[var(--ink-500)] mb-0.5">{t("collectionVsExpected")}</div>
        <div className="font-serif text-[20px] font-semibold text-foreground mb-3">{t("twelveMonthPerformance")}</div>
        <p className="text-sm text-[var(--ink-500)] py-16 text-center">{t("noCollectionData")}</p>
      </div>
    );
  }
  const months = data.map((d) => d.month);
  const expected = data.map((d) => d.expected);
  const collected = data.map((d) => d.collected);
  // Headroom above the largest value so the lines don't touch the top edge.
  const max = Math.max(1, ...expected, ...collected) * 1.1;
  const w = 600;
  const h = 200;
  const p = { l: 40, r: 12, t: 12, b: 28 };
  const cw = w - p.l - p.r;
  const ch = h - p.t - p.b;
  const x = (i: number) => p.l + (i / (months.length - 1)) * cw;
  const y = (v: number) => p.t + ch - (v / max) * ch;

  const expPath = expected.map((v, i) => `${i === 0 ? "M" : "L"}${x(i)},${y(v)}`).join(" ");
  const colPath = collected.map((v, i) => `${i === 0 ? "M" : "L"}${x(i)},${y(v)}`).join(" ");
  const colArea = `${colPath} L${x(months.length - 1)},${p.t + ch} L${x(0)},${p.t + ch} Z`;

  return (
    <div className="bg-surface border border-border rounded-[var(--radius-lg)] p-5">
      <div className="flex justify-between items-start mb-3.5">
        <div>
          <div className="text-[13px] text-[var(--ink-500)] mb-0.5">{t("collectionVsExpected")}</div>
          <div className="font-serif text-[20px] font-semibold text-foreground">{t("twelveMonthPerformance")}</div>
        </div>
      </div>
      <svg viewBox={`0 0 ${w} ${h}`} className="w-full h-[200px] block">
        <path d={colArea} fill="var(--ink-900)" opacity="0.06" />
        <path d={expPath} fill="none" stroke="var(--gold-500)" strokeWidth="1.5" strokeDasharray="4 3" />
        <path d={colPath} fill="none" stroke="var(--ink-900)" strokeWidth="2" />
      </svg>
    </div>
  );
}

function OccupancyDonut({
  occupied,
  reserved,
  vacant,
  rate,
}: {
  occupied: number;
  reserved: number;
  vacant: number;
  rate: number;
}) {
  const t = useTranslations("Dashboard");
  const total = occupied + reserved + vacant;
  const size = 128;
  const stroke = 16;
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const occFrac = total > 0 ? occupied / total : 0;
  const resFrac = total > 0 ? reserved / total : 0;
  const cx = size / 2;
  const cy = size / 2;
  return (
    <div className="flex items-center gap-5">
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`} className="shrink-0">
        <circle cx={cx} cy={cy} r={r} fill="none" stroke="var(--ink-500)" strokeOpacity="0.16" strokeWidth={stroke} />
        {total > 0 && (
          <>
            <circle
              cx={cx}
              cy={cy}
              r={r}
              fill="none"
              stroke="var(--teal-600)"
              strokeWidth={stroke}
              strokeLinecap="butt"
              strokeDasharray={`${occFrac * c} ${c}`}
              transform={`rotate(-90 ${cx} ${cy})`}
            />
            {reserved > 0 && (
              <circle
                cx={cx}
                cy={cy}
                r={r}
                fill="none"
                stroke="var(--gold-500)"
                strokeWidth={stroke}
                strokeLinecap="butt"
                strokeDasharray={`${resFrac * c} ${c}`}
                strokeDashoffset={-occFrac * c}
                transform={`rotate(-90 ${cx} ${cy})`}
              />
            )}
          </>
        )}
        <text x={cx} y={cy - 1} textAnchor="middle" className="fill-foreground" style={{ fontSize: 22, fontWeight: 600 }}>
          {rate.toFixed(0)}%
        </text>
        <text x={cx} y={cy + 16} textAnchor="middle" className="fill-[var(--ink-500)]" style={{ fontSize: 10 }}>
          {t("occupiedShort")}
        </text>
      </svg>
      <div className="space-y-2 text-[13px] min-w-[120px]">
        <div className="flex items-center gap-2">
          <span className="inline-block w-2.5 h-2.5 rounded-full" style={{ background: "var(--teal-600)" }} />
          <span className="text-[var(--ink-500)]">{t("occupied")}</span>
          <span className="font-semibold ms-auto">{occupied}</span>
        </div>
        <div className="flex items-center gap-2">
          <span className="inline-block w-2.5 h-2.5 rounded-full" style={{ background: "var(--gold-500)" }} />
          <span className="text-[var(--ink-500)]">{t("reservedLabel")}</span>
          <span className="font-semibold ms-auto">{reserved}</span>
        </div>
        <div className="flex items-center gap-2">
          <span className="inline-block w-2.5 h-2.5 rounded-full" style={{ background: "var(--ink-500)", opacity: 0.45 }} />
          <span className="text-[var(--ink-500)]">{t("vacantLabel")}</span>
          <span className="font-semibold ms-auto">{vacant}</span>
        </div>
      </div>
    </div>
  );
}

export default function DashboardPage() {
  const t = useTranslations("Dashboard");
  const tCheques = useTranslations("Cheques");
  const locale = useLocale();
  const { data: session } = useSession();
  const canManageLeases = hasPermission(session?.user?.role as UserRole | undefined, "canManageLeases");
  const canRunRecognition = hasPermission(session?.user?.role as UserRole | undefined, "canRunRecognition");
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [monthly, setMonthly] = useState<MonthlyPoint[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const load = async () => {
      try {
        const [summaryRes, monthlyRes] = await Promise.all([
          fetch("/api/proxy/v1/dashboard/summary"),
          fetch("/api/proxy/v1/dashboard/monthly-collections"),
        ]);
        if (summaryRes.ok) setSummary(await summaryRes.json());
        if (monthlyRes.ok) {
          const data = await monthlyRes.json();
          setMonthly(Array.isArray(data) ? data : []);
        }
      } catch (err) {
        console.error(err);
      } finally {
        setLoading(false);
      }
    };

    load();
  }, []);

  if (loading) {
    return <div className="p-7 text-sm text-[var(--ink-500)]">Loading dashboard…</div>;
  }

  if (!summary) {
    return (
      <div className="p-7">
        <div className="text-center py-24 bg-surface border border-dashed border-border rounded-2xl flex flex-col items-center">
          <div className="w-16 h-16 bg-[var(--sand-100)] rounded-2xl flex items-center justify-center text-muted mb-6">
            <Activity size={32} />
          </div>
          <p className="text-sm font-semibold text-muted tracking-wide">{t("unableToLoad")}</p>
        </div>
      </div>
    );
  }

  const firstName = session?.user?.name?.split(" ")[0] ?? "there";
  const now = new Date();
  // The greeting was hardcoded "Good morning" at every hour of the day.
  const hour = now.getHours();
  const greetingKey = hour < 12 ? "greetingMorning" : hour < 18 ? "greetingAfternoon" : "greetingEvening";
  // `undefined` here meant the browser's locale, not the app's, so an Arabic
  // page still rendered its dates in whatever the browser was set to (#177).
  const dayLabel = now.toLocaleDateString(locale, {
    weekday: "long",
    day: "2-digit",
    month: "short",
    year: "numeric",
  });

  // Collected against this month's dues (gap #59): both sides by due date, so
  // a catch-up banking run of old cheques shows as arrears rather than as a
  // multiple of the month. The sparkline is the monthly series, which is by
  // due month too.
  const tile = collectionTile(summary);
  // Older summaries (and test fixtures) may not carry the field yet.
  const reservedUnits = summary.reservedUnits ?? 0;
  const money = (v: number) => isolate(formatCurrencyCompact(v));
  const collectedSub =
    tile.percent == null
      ? t("nothingDueThisMonth")
      : t("ofDueThisMonth", { amount: money(tile.due), percent: tile.percent });
  let collectedNote: string | undefined;
  if (tile.arrears > 0 && tile.advance > 0) {
    collectedNote = t("collectedArrearsAndAdvance", { arrears: money(tile.arrears), advance: money(tile.advance) });
  } else if (tile.arrears > 0) {
    collectedNote = t("collectedArrearsOnly", { amount: money(tile.arrears) });
  } else if (tile.advance > 0) {
    collectedNote = t("collectedAdvanceOnly", { amount: money(tile.advance) });
  }
  const collectedSpark = monthly.map((m) => m.collected);

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-end justify-between">
        <div>
          <p className="text-[12.5px] text-[var(--ink-500)] mb-1">{dayLabel}</p>
          <h1 className="font-serif text-[28px] font-semibold tracking-tight m-0">{t(greetingKey, { name: firstName })}</h1>
          <p className="text-[13.5px] text-[var(--ink-600)] mt-1">
            <span className="text-[var(--gold-700)] font-semibold">{t("expiringLeasesCount", { count: summary.expiringLeases })}</span>
            {summary.overdueAmount > 0 ? <span className="text-[var(--red-600)] font-semibold ml-1.5">· {t("overdueSuffix", { amount: formatCurrencyCompact(summary.overdueAmount) })}</span> : null}
          </p>
        </div>
        <div className="flex gap-2">
          {/* The month is a label, not a picker: the tiles are always this month. */}
          <span className="flex items-center gap-1.5 h-8 px-3 text-[12.5px] font-medium border border-border rounded-[var(--radius)] bg-surface">
            <Calendar size={13} />
            {now.toLocaleDateString(locale, { month: "short", year: "numeric" })}
          </span>
          {canManageLeases && (
          <Link href="/dashboard/leases?new=1" className="flex items-center gap-1.5 h-8 px-3 text-[12.5px] font-semibold rounded-[var(--radius)] bg-[var(--ink-900)] text-white">
            <Plus size={13} /> {t("newLease")}
          </Link>
          )}
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-3.5">
        <StatCard
          label={t("collectedAgainstDues")}
          value={formatCurrencyCompact(tile.collected)}
          sub={collectedSub}
          note={collectedNote}
          sparkData={collectedSpark.length ? collectedSpark : undefined}
          sparkColor="var(--green-600)"
        />
        <StatCard
          label={t("pendingThisMonth")}
          value={formatCurrencyCompact(summary.pendingThisMonthAmount)}
          sub={t("dueThisMonthUnpaid")}
        />
        {/* Home › Unit Status lands here (/dashboard#unit-status). */}
        <div id="unit-status" className="scroll-mt-6 [&>div]:h-full">
          <StatCard
            label={t("occupancy")}
            value={summary.occupancyRate.toFixed(1)}
            unit="%"
            sub={t("unitsLeased", { occupied: summary.occupiedUnits, total: summary.totalUnits })}
            note={reservedUnits > 0 ? t("reservedSubline", { count: reservedUnits }) : undefined}
          />
        </div>
        <Link
          href="/dashboard/collections?tab=overdue"
          aria-label={t("viewOverduePayments")}
          className="block rounded-[var(--radius-lg)] transition-all hover:shadow-sm focus:outline-none focus:ring-2 focus:ring-primary/30 [&>div]:hover:border-primary"
        >
          <StatCard
            label={t("overdue")}
            value={formatCurrencyCompact(summary.overdueAmount)}
            sub={t("requiresFollowUp")}
          />
        </Link>
      </div>

      <div className="grid gap-3.5" style={{ gridTemplateColumns: "1.6fr 1fr" }}>
        <CollectionChart data={monthly} />
        <div className="bg-surface border border-border rounded-[var(--radius-lg)] p-5">
          <div className="text-[13px] text-[var(--ink-500)]">{t("portfolioSnapshot")}</div>
          <div className="font-serif text-[20px] font-semibold text-foreground mb-3">{t("currentTotals")}</div>
          <div className="mb-4 pb-4 border-b border-border">
            <OccupancyDonut
              occupied={summary.occupiedUnits}
              reserved={reservedUnits}
              vacant={summary.vacantUnits}
              rate={summary.occupancyRate}
            />
          </div>
          <div className="space-y-2.5 text-[13px]">
            <div className="flex justify-between"><span className="text-[var(--ink-500)]">{t("properties")}</span><span className="font-semibold">{summary.totalProperties}</span></div>
            <div className="flex justify-between"><span className="text-[var(--ink-500)]">{t("activeLeasesLabel")}</span><span className="font-semibold">{summary.activeLeases}</span></div>
            <div className="flex justify-between"><span className="text-[var(--ink-500)]">{t("draftLeases")}</span><span className="font-semibold">{summary.draftLeases}</span></div>
            <div className="flex justify-between"><span className="text-[var(--ink-500)]">{t("reservedUnits")}</span><span className="font-semibold">{reservedUnits}</span></div>
            <div className="flex justify-between"><span className="text-[var(--ink-500)]">{t("vacantUnits")}</span><span className="font-semibold">{summary.vacantUnits}</span></div>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 gap-3.5">
        <OverduePaymentsWidget />
        <ChequesToDepositWidget />
      </div>

      {canRunRecognition && <RecognitionBehindWidget />}

      <FollowUpsWidget />

      <div className="bg-surface border border-border rounded-[var(--radius-lg)] p-5">
        <div className="font-serif text-[20px] font-semibold text-foreground mb-3">{t("recentActivity")}</div>
        {summary.recentActivity.length === 0 ? (
          <p className="text-[13px] text-[var(--ink-500)]">{t("noActivityYet")}</p>
        ) : (
          <div className="space-y-2">
            {summary.recentActivity.slice(0, 8).map((item, index) => (
              <div key={`${item.timestamp}-${index}`} className="flex items-center gap-3 py-2 border-t border-border first:border-t-0">
                <div className="w-7 h-7 rounded-full bg-[var(--sand-100)] flex items-center justify-center text-[10px] font-semibold text-[var(--ink-700)]">
                  {item.type.slice(0, 2)}
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-[13px] text-foreground truncate">{activityText(item, t, tCheques)}</p>
                </div>
                <div className="text-[11px] text-[var(--ink-500)] whitespace-nowrap">{formatTimeAgo(item.timestamp, t, locale)}</div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

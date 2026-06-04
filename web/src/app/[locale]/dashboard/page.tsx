"use client";

import { useEffect, useState } from "react";
import { useSession } from "next-auth/react";
import { Link } from "@/i18n/routing";
import { formatCurrencyCompact } from "@/lib/format";
import { cn } from "@/lib/utils";
import { Activity, Calendar, Download, Plus, TrendingDown, TrendingUp } from "lucide-react";
import FollowUpsWidget from "@/components/dashboard/FollowUpsWidget";

type DashboardSummary = {
  totalProperties: number;
  totalUnits: number;
  occupiedUnits: number;
  vacantUnits: number;
  occupancyRate: number;
  activeLeases: number;
  draftLeases: number;
  expiringLeases: number;
  totalRentRevenue: number;
  collectedAmount: number;
  pendingAmount: number;
  overdueAmount: number;
  recentActivity: {
    type: string;
    description: string;
    timestamp: string;
  }[];
};

function formatTimeAgo(timestamp: string): string {
  const now = new Date();
  const date = new Date(timestamp);
  const diffMs = now.getTime() - date.getTime();
  const diffMins = Math.floor(diffMs / 60000);
  const diffHours = Math.floor(diffMs / 3600000);
  const diffDays = Math.floor(diffMs / 86400000);

  if (diffMins < 1) return "Just now";
  if (diffMins < 60) return `${diffMins}m ago`;
  if (diffHours < 24) return `${diffHours}h ago`;
  if (diffDays < 7) return `${diffDays}d ago`;
  return date.toLocaleDateString();
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
  sparkData,
  sparkColor,
}: {
  label: string;
  value: string;
  unit?: string;
  delta?: string;
  deltaPos?: boolean;
  sub?: string;
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
      {sparkData && <Sparkline data={sparkData} color={sparkColor ?? "var(--accent)"} />}
    </div>
  );
}

function CollectionChart() {
  const months = ["Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec", "Jan", "Feb", "Mar", "Apr", "May"];
  const expected = [1850, 1900, 1920, 1950, 1980, 2000, 2010, 2050, 2080, 2090, 2100, 2100];
  const collected = [1780, 1810, 1850, 1890, 1920, 1940, 1950, 1990, 2010, 2040, 2050, 1840];
  const max = 2200;
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
          <div className="text-[13px] text-[var(--ink-500)] mb-0.5">Collection vs expected</div>
          <div className="font-serif text-[20px] font-semibold text-foreground">12-month performance</div>
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

export default function DashboardPage() {
  const { data: session } = useSession();
  const [summary, setSummary] = useState<DashboardSummary | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const fetchSummary = async () => {
      try {
        const res = await fetch("/api/proxy/v1/dashboard/summary");
        if (res.ok) {
          const data = await res.json();
          setSummary(data);
        }
      } catch (err) {
        console.error(err);
      } finally {
        setLoading(false);
      }
    };

    fetchSummary();
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
          <p className="text-sm font-semibold text-muted tracking-wide">Unable to load dashboard data</p>
        </div>
      </div>
    );
  }

  const firstName = session?.user?.name?.split(" ")[0] ?? "there";
  const now = new Date();
  const dayLabel = now.toLocaleDateString(undefined, {
    weekday: "long",
    day: "2-digit",
    month: "short",
    year: "numeric",
  });

  return (
    <div className="flex flex-col gap-5">
      <div className="flex items-end justify-between">
        <div>
          <p className="text-[12.5px] text-[var(--ink-500)] mb-1">{dayLabel}</p>
          <h1 className="font-serif text-[28px] font-semibold tracking-tight m-0">Good morning, {firstName}</h1>
          <p className="text-[13.5px] text-[var(--ink-600)] mt-1">
            <span className="text-[var(--gold-700)] font-semibold">{summary.expiringLeases} expiring lease{summary.expiringLeases === 1 ? '' : 's'}</span>
            {summary.overdueAmount > 0 ? <span className="text-[var(--red-600)] font-semibold ml-1.5">· {formatCurrencyCompact(summary.overdueAmount)} overdue</span> : null}
          </p>
        </div>
        <div className="flex gap-2">
          <button className="flex items-center gap-1.5 h-8 px-3 text-[12.5px] font-medium border border-border rounded-[var(--radius)] bg-surface">
            <Calendar size={13} />
            {now.toLocaleDateString(undefined, { month: "short", year: "numeric" })}
          </button>
          <button className="flex items-center gap-1.5 h-8 px-3 text-[12.5px] font-medium border border-border rounded-[var(--radius)] bg-surface">
            <Download size={13} /> Export
          </button>
          <Link href="/dashboard/leases/new" className="flex items-center gap-1.5 h-8 px-3 text-[12.5px] font-semibold rounded-[var(--radius)] bg-[var(--ink-900)] text-white">
            <Plus size={13} /> New lease
          </Link>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-4 gap-3.5">
        <StatCard
          label="Collected this month"
          value={formatCurrencyCompact(summary.collectedAmount)}
          delta="+12.4%"
          deltaPos
          sub={`of ${formatCurrencyCompact(summary.totalRentRevenue)} expected`}
          sparkData={[20, 28, 24, 32, 30, 38, 42, 40, 48, 52, 49, 58]}
          sparkColor="var(--green-600)"
        />
        <StatCard
          label="Pending payments"
          value={formatCurrencyCompact(summary.pendingAmount)}
          delta="+3.0%"
          deltaPos={false}
          sub="Awaiting collection"
          sparkData={[8, 10, 9, 12, 15, 14, 16, 18]}
          sparkColor="var(--gold-600)"
        />
        <StatCard
          label="Occupancy"
          value={summary.occupancyRate.toFixed(1)}
          unit="%"
          delta="+1.2%"
          deltaPos
          sub={`${summary.occupiedUnits} of ${summary.totalUnits} units leased`}
          sparkData={[88, 89, 90, 91, 92, 93, 94, 94.3]}
          sparkColor="var(--teal-600)"
        />
        <Link
          href="/dashboard/finance/payments?status=OVERDUE"
          aria-label="View overdue payments"
          className="block rounded-[var(--radius-lg)] transition-all hover:border-primary hover:shadow-sm focus:outline-none focus:ring-2 focus:ring-primary/30 [&>div]:hover:border-primary"
        >
          <StatCard
            label="Overdue"
            value={formatCurrencyCompact(summary.overdueAmount)}
            delta="-2.1%"
            deltaPos
            sub="Requires follow-up"
            sparkData={[140, 128, 118, 110, 102, 98, 92, 87]}
            sparkColor="var(--red-600)"
          />
        </Link>
      </div>

      <div className="grid gap-3.5" style={{ gridTemplateColumns: "1.6fr 1fr" }}>
        <CollectionChart />
        <div className="bg-surface border border-border rounded-[var(--radius-lg)] p-5">
          <div className="text-[13px] text-[var(--ink-500)]">Portfolio snapshot</div>
          <div className="font-serif text-[20px] font-semibold text-foreground mb-3">Current totals</div>
          <div className="space-y-2.5 text-[13px]">
            <div className="flex justify-between"><span className="text-[var(--ink-500)]">Properties</span><span className="font-semibold">{summary.totalProperties}</span></div>
            <div className="flex justify-between"><span className="text-[var(--ink-500)]">Active leases</span><span className="font-semibold">{summary.activeLeases}</span></div>
            <div className="flex justify-between"><span className="text-[var(--ink-500)]">Draft leases</span><span className="font-semibold">{summary.draftLeases}</span></div>
            <div className="flex justify-between"><span className="text-[var(--ink-500)]">Vacant units</span><span className="font-semibold">{summary.vacantUnits}</span></div>
          </div>
        </div>
      </div>

      <FollowUpsWidget />

      <div className="bg-surface border border-border rounded-[var(--radius-lg)] p-5">
        <div className="font-serif text-[20px] font-semibold text-foreground mb-3">Recent activity</div>
        {summary.recentActivity.length === 0 ? (
          <p className="text-[13px] text-[var(--ink-500)]">No activity yet.</p>
        ) : (
          <div className="space-y-2">
            {summary.recentActivity.slice(0, 8).map((item, index) => (
              <div key={`${item.timestamp}-${index}`} className="flex items-center gap-3 py-2 border-t border-border first:border-t-0">
                <div className="w-7 h-7 rounded-full bg-[var(--sand-100)] flex items-center justify-center text-[10px] font-semibold text-[var(--ink-700)]">
                  {item.type.slice(0, 2)}
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-[13px] text-foreground truncate">{item.description}</p>
                </div>
                <div className="text-[11px] text-[var(--ink-500)] whitespace-nowrap">{formatTimeAgo(item.timestamp)}</div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { AlertTriangle, CheckCircle2, DoorOpen, Loader2, LogIn, LogOut, RefreshCw, ShieldCheck, UserPlus, XCircle } from "lucide-react";
import { cn } from "@/lib/utils";
import { Pagination } from "@/components/ui/Pagination";
import { LoadFailedState } from "@/components/ui/PageStates";
import {
    ErrorLine, GatePassPage, GatePassTabs, StatusBadge, inputClass, labelClass, pageOf, primaryButton, secondaryButton, useDateTime,
} from "@/components/gatepass/shared";
import {
    ApiError, VISITOR_TYPES, admitWalkIn, createWalkIn, errorText, fetchDestinations, fetchExpectedToday, fetchMyGuardProperties,
    fetchWalkInStatus, fetchWalkInsToday, scanCode, scanReasonKey, toE164,
    type Destination, type GatePassSummary, type GateVisitorType, type GuardProperty, type ScanDirection, type ScanResponse, type WalkInPass,
} from "@/lib/api/gatepass";

type TabId = "verify" | "expected" | "walkins";

/**
 * The security guard's gate desk: verify a pass by its code and record the
 * entry or exit, see who is expected today, and register a walk-in visitor for
 * the resident to approve. Every call is the guard's own and the server bounds
 * it to the properties they are posted to — nothing here can widen that.
 * There is no camera scanner on the web; a hand-held scanner that types the
 * QR token into the code field works, as does the numeric code.
 */
export default function GateDeskPage() {
    return (
        <GatePassPage permission="canWorkGate">
            <GateDesk />
        </GatePassPage>
    );
}

function GateDesk() {
    const t = useTranslations("GatePass");
    const { status: sessionStatus } = useSession();
    const [tab, setTab] = useState<TabId>("verify");
    const [posting, setPosting] = useState<GuardProperty[] | null>(null);
    const [postingFailed, setPostingFailed] = useState(false);

    const loadPosting = useCallback(async () => {
        try {
            const fetched = await fetchMyGuardProperties();
            setPosting(fetched);
            setPostingFailed(false);
        } catch {
            setPostingFailed(true);
        }
    }, []);

    useEffect(() => {
        if (sessionStatus !== "authenticated") return;
        let alive = true;
        fetchMyGuardProperties()
            .then(rows => { if (alive) setPosting(rows); })
            .catch(() => { if (alive) setPostingFailed(true); });
        return () => { alive = false; };
    }, [sessionStatus]);

    if (postingFailed) return <LoadFailedState onRetry={loadPosting} message={t("postingLoadError")} />;
    if (!posting) {
        return <div className="flex items-center justify-center py-24"><Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" /></div>;
    }

    return (
        <div>
            <GatePassTabs />
            <div className="mb-4">
                <h1 className="text-lg font-bold text-foreground">{t("gateTitle")}</h1>
                <p className="text-xs text-muted mt-0.5">
                    {posting.length > 0 ? t("postedAt", { properties: posting.map(p => p.name ?? "—").join(", ") }) : t("gateDescription")}
                </p>
            </div>
            {posting.length === 0 && (
                <div role="alert" data-testid="gate-unposted" className="mb-4 flex items-start gap-2 text-xs text-warning bg-warning/5 border border-warning/20 rounded-lg px-3 py-2">
                    <AlertTriangle size={14} className="shrink-0 mt-0.5" /> {t("notPosted")}
                </div>
            )}
            <div className="flex gap-1 border-b border-border mb-5 overflow-x-auto" role="tablist">
                {([["verify", t("tabVerify")], ["expected", t("tabExpected")], ["walkins", t("tabWalkIns")]] as [TabId, string][]).map(([id, label]) => (
                    <button key={id} type="button" role="tab" aria-selected={tab === id} data-testid={`gate-tab-${id}`} onClick={() => setTab(id)}
                        className={cn("cursor-pointer px-3 py-2 text-xs font-semibold border-b-2 -mb-px whitespace-nowrap",
                            tab === id ? "border-primary text-foreground" : "border-transparent text-muted hover:text-foreground")}>
                        {label}
                    </button>
                ))}
            </div>
            {tab === "verify" && <VerifyPanel />}
            {tab === "expected" && <ExpectedPanel />}
            {tab === "walkins" && <WalkInsPanel posting={posting} />}
        </div>
    );
}

function VerifyPanel() {
    const t = useTranslations("GatePass");
    const { dateTime } = useDateTime();
    const [code, setCode] = useState("");
    const [busy, setBusy] = useState<ScanDirection | null>(null);
    const [result, setResult] = useState<(ScanResponse & { direction: ScanDirection }) | null>(null);
    const [error, setError] = useState<string | null>(null);

    const scan = async (direction: ScanDirection) => {
        const value = code.trim();
        setError(null);
        setResult(null);
        if (!value) return setError(t("errEnterCode"));
        setBusy(direction);
        try {
            setResult({ ...(await scanCode(value, direction)), direction });
            setCode("");
        } catch (err) {
            // The scan endpoint is rate limited per gate (PublicRateLimitFilter).
            setError(err instanceof ApiError && err.status === 429 ? t("errTooManyScans") : errorText(err, t("scanError"), t));
        } finally {
            setBusy(null);
        }
    };

    const reasonKey = scanReasonKey(result?.reason);
    const allowed = result?.result === "ALLOWED";

    return (
        <div className="grid gap-5 lg:grid-cols-2">
            <form className="bg-surface rounded-xl border border-border p-4 space-y-3 h-fit" onSubmit={e => { e.preventDefault(); scan("ENTRY"); }}>
                <label htmlFor="gate-code" className={labelClass}>{t("passCode")}</label>
                <input id="gate-code" value={code} autoComplete="off" dir="ltr" inputMode="text" maxLength={64}
                    onChange={e => setCode(e.target.value)} placeholder="123456"
                    className={cn(inputClass, "text-2xl tracking-[0.25em] text-center tabular-nums")} data-testid="gate-code" />
                <p className="text-[11px] text-muted">{t("passCodeHint")}</p>
                <div className="grid grid-cols-2 gap-2">
                    <button type="submit" disabled={!!busy} className={primaryButton} data-testid="gate-check-in">
                        {busy === "ENTRY" ? <Loader2 size={14} className="animate-spin" /> : <LogIn size={14} />} {t("checkIn")}
                    </button>
                    <button type="button" disabled={!!busy} onClick={() => scan("EXIT")} className={secondaryButton} data-testid="gate-check-out">
                        {busy === "EXIT" ? <Loader2 size={14} className="animate-spin" /> : <LogOut size={14} />} {t("checkOut")}
                    </button>
                </div>
                <ErrorLine message={error} testId="gate-scan-error" />
            </form>

            {result && (
                <div data-testid="gate-result" role="status"
                    className={cn("rounded-xl border p-4 space-y-3", allowed ? "border-success/40 bg-success/5" : "border-error/40 bg-error/5")}>
                    <div className="flex items-center gap-2">
                        {allowed ? <CheckCircle2 size={22} className="text-success" /> : <XCircle size={22} className="text-error" />}
                        <p className={cn("text-base font-bold", allowed ? "text-success" : "text-error")} data-testid="gate-verdict">
                            {allowed ? (result.direction === "ENTRY" ? t("verdictEntryAllowed") : t("verdictExitRecorded")) : t("verdictRejected")}
                        </p>
                    </div>
                    {!allowed && (
                        <p className="text-xs text-error" data-testid="gate-reason">
                            {reasonKey ? t(reasonKey) : result.reason ?? t("reasonUnknown")}
                        </p>
                    )}
                    {result.guestName && (
                        <dl className="grid grid-cols-2 gap-3 text-xs">
                            <Item label={t("colGuestName")} value={result.guestName} />
                            <Item label={t("colGuestPhone")} value={result.guestPhone ?? "—"} ltr />
                            <Item label={t("colUnit")} value={result.unitNumber ?? "—"} />
                            <Item label={t("colPassType")} value={result.passType ? t(result.passType) : "—"} />
                            <Item label={t("validFrom")} value={dateTime(result.validFrom)} />
                            <Item label={t("validTo")} value={dateTime(result.validTo)} />
                            <Item label={t("colVehicle")} value={result.vehicleNumber ?? "—"} ltr />
                            <Item label={t("colPurpose")} value={result.purpose ?? "—"} />
                        </dl>
                    )}
                    <p className="text-[11px] text-muted">{t("compareWithVisitor")}</p>
                </div>
            )}
        </div>
    );
}

function Item({ label, value, ltr }: { label: string; value: string; ltr?: boolean }) {
    return (
        <div>
            <dt className="text-[10px] font-semibold text-muted uppercase tracking-wider">{label}</dt>
            <dd className="text-foreground mt-0.5" dir={ltr ? "ltr" : undefined}>{value}</dd>
        </div>
    );
}

function ExpectedPanel() {
    const t = useTranslations("GatePass");
    const { dateTime } = useDateTime();
    const [rows, setRows] = useState<GatePassSummary[] | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(10);

    const load = useCallback(async () => {
        try {
            const fetched = await fetchExpectedToday();
            setRows(fetched);
            setError(null);
        } catch {
            setError(t("expectedLoadError"));
        }
    }, [t]);
    useEffect(() => {
        let alive = true;
        fetchExpectedToday()
            .then(r => { if (alive) setRows(r); })
            .catch(() => { if (alive) setError(t("expectedLoadError")); });
        return () => { alive = false; };
    }, [t]);

    if (error) return <LoadFailedState onRetry={load} message={error} />;
    if (!rows) return <div className="flex justify-center py-16"><Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" /></div>;

    return (
        <div className="space-y-3">
            <div className="flex items-center justify-between gap-2 flex-wrap">
                <p className="text-xs text-muted tabular-nums">{t("expectedCount", { count: rows.length })}</p>
                <button type="button" onClick={load} className={secondaryButton}><RefreshCw size={13} /> {t("refresh")}</button>
            </div>
            {rows.length === 0 ? (
                <div className="bg-surface rounded-xl border border-border text-center py-14 text-muted" data-testid="expected-empty">
                    <ShieldCheck size={28} className="mx-auto mb-3 opacity-40" />
                    <p className="text-xs">{t("noExpected")}</p>
                </div>
            ) : (
                <>
                    <div className="bg-surface rounded-xl border border-border overflow-x-auto">
                        <table className="w-full" data-testid="expected-table">
                            <thead>
                                <tr className="bg-input/50">
                                    {[t("colGuestName"), t("colProperty"), t("colUnit"), t("colPassType"), t("colValidity"), t("colVehicle"), t("colStatus")].map(h => (
                                        <th key={h} className="px-4 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider text-start whitespace-nowrap">{h}</th>
                                    ))}
                                </tr>
                            </thead>
                            <tbody>
                                {pageOf(rows, page, perPage).map(r => (
                                    <tr key={r.id} className="border-b border-border">
                                        <td className="px-4 py-2.5 text-xs">
                                            <p className="font-medium text-foreground">{r.guestName}</p>
                                            <p className="text-[11px] text-muted" dir="ltr">{r.guestPhone}</p>
                                        </td>
                                        <td className="px-4 py-2.5 text-xs text-muted">{r.propertyName ?? "—"}</td>
                                        <td className="px-4 py-2.5 text-xs text-muted">{r.unitNumber ?? "—"}</td>
                                        <td className="px-4 py-2.5 text-xs text-muted whitespace-nowrap">{t(r.passType)}</td>
                                        <td className="px-4 py-2.5 text-xs text-muted whitespace-nowrap tabular-nums">{dateTime(r.validFrom)}<br />{dateTime(r.validTo)}</td>
                                        <td className="px-4 py-2.5 text-xs text-muted" dir="ltr">{r.vehicleNumber || "—"}</td>
                                        <td className="px-4 py-2.5"><StatusBadge status={r.status} /></td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                    <Pagination currentPage={page} totalItems={rows.length} itemsPerPage={perPage} onPageChange={setPage}
                        onItemsPerPageChange={n => { setPerPage(n); setPage(1); }} />
                </>
            )}
        </div>
    );
}

function WalkInsPanel({ posting }: { posting: GuardProperty[] }) {
    const t = useTranslations("GatePass");
    const { dateTime } = useDateTime();
    const [rows, setRows] = useState<WalkInPass[] | null>(null);
    const [listError, setListError] = useState<string | null>(null);
    const [rowError, setRowError] = useState<string | null>(null);
    const [busyId, setBusyId] = useState<string | null>(null);
    const [page, setPage] = useState(1);
    const [perPage, setPerPage] = useState(10);

    const load = useCallback(async () => {
        try {
            const fetched = await fetchWalkInsToday();
            setRows(fetched);
            setListError(null);
        } catch {
            setListError(t("walkInsLoadError"));
        }
    }, [t]);
    useEffect(() => { load(); }, [load]);

    const replace = (w: WalkInPass) => setRows(rs => (rs ?? []).map(r => (r.id === w.id ? w : r)));

    // `keepError`: the refresh that follows a failed Admit must leave the Admit
    // refusal on screen, or the guard sees an ACTIVE row and a live button and
    // believes the entry was recorded.
    const refreshOne = async (id: string, keepError = false) => {
        setBusyId(id);
        if (!keepError) setRowError(null);
        try {
            const fetched = await fetchWalkInStatus(id);
            replace(fetched);
        } catch (err) {
            if (!keepError) setRowError(errorText(err, t("walkInStatusError"), t));
        } finally {
            setBusyId(null);
        }
    };

    const admit = async (id: string) => {
        setBusyId(id);
        setRowError(null);
        try {
            const fetched = await admitWalkIn(id);
            replace(fetched);
        } catch (err) {
            setRowError(errorText(err, t("admitError"), t));
            await refreshOne(id, true);
        } finally {
            setBusyId(null);
        }
    };

    return (
        <div className="grid gap-5 xl:grid-cols-[minmax(0,380px)_1fr]">
            <WalkInForm posting={posting} onCreated={w => setRows(rs => [w, ...(rs ?? [])])} />
            <div className="space-y-3 min-w-0">
                <div className="flex items-center justify-between gap-2 flex-wrap">
                    <h2 className="text-sm font-semibold text-foreground">{t("todaysWalkIns")}</h2>
                    <button type="button" onClick={load} className={secondaryButton} data-testid="walkins-refresh"><RefreshCw size={13} /> {t("refresh")}</button>
                </div>
                <ErrorLine message={listError ?? rowError} testId="walkins-error" />
                {!rows ? (
                    listError ? null : <div className="flex justify-center py-10"><Loader2 className="w-5 h-5 animate-spin text-primary opacity-60" /></div>
                ) : rows.length === 0 ? (
                    <div className="bg-surface rounded-xl border border-border text-center py-10 text-muted" data-testid="walkins-empty">
                        <p className="text-xs">{t("noWalkIns")}</p>
                    </div>
                ) : (
                    <>
                        <div className="bg-surface rounded-xl border border-border overflow-x-auto">
                            <table className="w-full" data-testid="walkins-table">
                                <thead>
                                    <tr className="bg-input/50">
                                        {[t("colVisitor"), t("colUnit"), t("colVisitorType"), t("colStatus"), t("colRegisteredAt"), ""].map((h, i) => (
                                            <th key={i} className="px-4 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider text-start whitespace-nowrap">{h}</th>
                                        ))}
                                    </tr>
                                </thead>
                                <tbody>
                                    {pageOf(rows, page, perPage).map(w => (
                                        <tr key={w.id} className="border-b border-border">
                                            <td className="px-4 py-2.5 text-xs">
                                                <p className="font-medium text-foreground">{w.guestName}</p>
                                                <p className="text-[11px] text-muted" dir="ltr">{w.guestPhone}</p>
                                            </td>
                                            <td className="px-4 py-2.5 text-xs text-muted">{w.unitNumber ?? "—"}</td>
                                            <td className="px-4 py-2.5 text-xs text-muted">{w.visitorType ? t(`visitorType.${w.visitorType}`) : "—"}</td>
                                            <td className="px-4 py-2.5"><StatusBadge status={w.status} /></td>
                                            <td className="px-4 py-2.5 text-xs text-muted whitespace-nowrap">{dateTime(w.createdAt)}</td>
                                            <td className="px-4 py-2.5 whitespace-nowrap text-end">
                                                <button type="button" disabled={busyId === w.id} onClick={() => refreshOne(w.id)} className={cn(secondaryButton, "me-2")} aria-label={t("refreshStatusOf", { name: w.guestName })}>
                                                    <RefreshCw size={13} />
                                                </button>
                                                {w.status === "ACTIVE" && (
                                                    <button type="button" disabled={busyId === w.id} onClick={() => admit(w.id)} className={primaryButton} data-testid={`admit-${w.id}`}>
                                                        <DoorOpen size={13} /> {t("admit")}
                                                    </button>
                                                )}
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                        <Pagination currentPage={page} totalItems={rows.length} itemsPerPage={perPage} onPageChange={setPage}
                            onItemsPerPageChange={n => { setPerPage(n); setPage(1); }} />
                    </>
                )}
            </div>
        </div>
    );
}

function WalkInForm({ posting, onCreated }: { posting: GuardProperty[]; onCreated: (w: WalkInPass) => void }) {
    const t = useTranslations("GatePass");
    const [propertyId, setPropertyId] = useState(posting.length === 1 ? posting[0].id : "");
    const [destinations, setDestinations] = useState<Destination[]>([]);
    const [destError, setDestError] = useState<string | null>(null);
    const [unitId, setUnitId] = useState("");
    const [name, setName] = useState("");
    const [phone, setPhone] = useState("");
    const [visitorType, setVisitorType] = useState<GateVisitorType>("GUEST");
    const [purpose, setPurpose] = useState("");
    const [vehicle, setVehicle] = useState("");
    const [photo, setPhoto] = useState<File | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [notice, setNotice] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [formKey, setFormKey] = useState(0);

    useEffect(() => {
        if (!propertyId) return;
        let alive = true;
        fetchDestinations(propertyId)
            .then(d => { if (alive) { setDestinations(d); setDestError(null); } })
            .catch(() => { if (alive) { setDestinations([]); setDestError(t("destinationsLoadError")); } });
        return () => { alive = false; };
    }, [propertyId, t]);

    const unitOptions = useMemo(() => destinations.map(d => ({
        id: d.unitId, label: d.buildingName ? `${d.buildingName} · ${d.unitNumber}` : d.unitNumber,
    })), [destinations]);

    if (posting.length === 0) {
        return (
            <div className="bg-surface rounded-xl border border-border p-4 text-xs text-muted h-fit" data-testid="walkin-unposted">{t("notPosted")}</div>
        );
    }

    const submit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError(null);
        setNotice(null);
        if (!propertyId) return setError(t("errChooseProperty"));
        if (!unitId) return setError(t("errChooseUnit"));
        if (!name.trim()) return setError(t("errVisitorName"));
        const e164 = toE164(phone);
        if (!e164) return setError(t("errPhone"));
        setSubmitting(true);
        try {
            const created = await createWalkIn({
                propertyId, unitId, name: name.trim(), phone: e164, visitorType,
                purpose: purpose.trim() || undefined, vehicleNumber: vehicle.trim() || undefined, photo,
            });
            onCreated(created);
            setNotice(created.status === "ACTIVE" ? t("walkInActiveNotice", { name: created.guestName }) : t("walkInPendingNotice", { name: created.guestName }));
            setName(""); setPhone(""); setPurpose(""); setVehicle(""); setPhoto(null); setUnitId("");
            setFormKey(k => k + 1);
        } catch (err) {
            setError(errorText(err, t("walkInError"), t));
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <form key={formKey} onSubmit={submit} noValidate className="bg-surface rounded-xl border border-border p-4 space-y-3 h-fit" data-testid="walkin-form">
            <h2 className="text-sm font-semibold text-foreground flex items-center gap-2"><UserPlus size={15} /> {t("registerWalkIn")}</h2>
            {posting.length > 1 && (
                <div>
                    <label htmlFor="wi-property" className={labelClass}>{t("property")}</label>
                    <select id="wi-property" value={propertyId} onChange={e => { setPropertyId(e.target.value); setUnitId(""); }} className={inputClass}>
                        <option value="">{t("chooseProperty")}</option>
                        {posting.map(p => <option key={p.id} value={p.id}>{p.name ?? p.id.slice(0, 8)}</option>)}
                    </select>
                </div>
            )}
            <div>
                <label htmlFor="wi-unit" className={labelClass}>{t("destinationUnit")}</label>
                <select id="wi-unit" value={unitId} onChange={e => setUnitId(e.target.value)} className={inputClass} disabled={!propertyId}>
                    <option value="">{t("chooseUnit")}</option>
                    {unitOptions.map(u => <option key={u.id} value={u.id}>{u.label}</option>)}
                </select>
                {destError && <p className="text-[11px] text-error mt-1">{destError}</p>}
            </div>
            <div>
                <label htmlFor="wi-name" className={labelClass}>{t("visitorName")}</label>
                <input id="wi-name" value={name} maxLength={160} onChange={e => setName(e.target.value)} className={inputClass} />
            </div>
            <div>
                <label htmlFor="wi-phone" className={labelClass}>{t("visitorPhone")}</label>
                <input id="wi-phone" type="tel" dir="ltr" value={phone} maxLength={32} placeholder="+971 50 123 4567" onChange={e => setPhone(e.target.value)} className={inputClass} />
            </div>
            <div>
                <label htmlFor="wi-type" className={labelClass}>{t("colVisitorType")}</label>
                <select id="wi-type" value={visitorType} onChange={e => setVisitorType(e.target.value as GateVisitorType)} className={inputClass}>
                    {VISITOR_TYPES.map(v => <option key={v} value={v}>{t(`visitorType.${v}`)}</option>)}
                </select>
            </div>
            <div>
                <label htmlFor="wi-purpose" className={labelClass}>{t("purposeOptional")}</label>
                <input id="wi-purpose" value={purpose} maxLength={240} onChange={e => setPurpose(e.target.value)} className={inputClass} />
            </div>
            <div>
                <label htmlFor="wi-vehicle" className={labelClass}>{t("vehicleOptional")}</label>
                <input id="wi-vehicle" dir="ltr" value={vehicle} maxLength={32} onChange={e => setVehicle(e.target.value)} className={inputClass} />
            </div>
            <div>
                <label htmlFor="wi-photo" className={labelClass}>{t("visitorPhotoOptional")}</label>
                <input id="wi-photo" type="file" accept="image/*" capture="environment" onChange={e => setPhoto(e.target.files?.[0] ?? null)} className="text-xs text-muted w-full" />
                <p className="text-[11px] text-muted mt-1">{t("photoHint")}</p>
            </div>
            <ErrorLine message={error} testId="walkin-error" />
            {notice && <p role="status" className="text-xs text-success bg-success/5 border border-success/20 rounded-lg px-3 py-2" data-testid="walkin-notice">{notice}</p>}
            <button type="submit" disabled={submitting} className={cn(primaryButton, "w-full")} data-testid="walkin-submit">
                {submitting && <Loader2 size={14} className="animate-spin" />} {t("submitWalkIn")}
            </button>
        </form>
    );
}

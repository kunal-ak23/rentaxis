"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { Loader2, Save, ShieldCheck, UserCheck, Users } from "lucide-react";
import { cn } from "@/lib/utils";
import { useBuildings } from "@/hooks/useBuildings";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { LoadFailedState } from "@/components/ui/PageStates";
import { Pagination } from "@/components/ui/Pagination";
import {
    ErrorLine, GatePassPage, GatePassTabs, inputClass, labelClass, pageOf, primaryButton, secondaryButton,
} from "@/components/gatepass/shared";
import {
    VISITOR_TYPES, errorText, fetchEffectivePolicy, fetchGuardProperties, localInstant, registerVisitor, saveGuardProperties,
    savePolicy, toE164, type GatePolicy, type GateVisitorType, type PolicyResponse,
} from "@/lib/api/gatepass";

/** GET /api/v1/properties wraps each property in a portfolio row (see the report page). */
type PropertyRow = { property: { id: string; nameEn: string; nameAr: string | null } };
type UnitRow = { id: string; unitNumber: string };
/** The slice of UserResponseDTO read here — note `phoneNumber`, not `phone`. */
type StaffUser = { id: string; name: string | null; email: string | null; phoneNumber?: string | null; role: string };

/**
 * Where an administrator sets how the gate behaves: the access policy for a
 * property or one of its towers, the regular visitors (maids, vendors) who may
 * come in without the resident deciding each time, and which properties each
 * guard is posted to. Every endpoint here is scoped by the server — a property
 * manager reaches only the buildings they manage.
 */
export default function GatePassSettingsPage() {
    return (
        <GatePassPage permission="canManageGatePolicy">
            <Settings />
        </GatePassPage>
    );
}

function Settings() {
    const t = useTranslations("GatePass");
    const locale = useLocale();
    const { data: session, status } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    const [properties, setProperties] = useState<PropertyRow[] | null>(null);
    const [failed, setFailed] = useState(false);
    const [propertyId, setPropertyId] = useState("");

    const load = useCallback(async () => {
        setFailed(false);
        try {
            const res = await fetch("/api/proxy/v1/properties");
            if (!res.ok) throw new Error(String(res.status));
            const rows: PropertyRow[] = await res.json();
            setProperties(rows);
            setPropertyId(p => p || rows[0]?.property.id || "");
        } catch {
            setFailed(true);
        }
    }, []);
    useEffect(() => { if (status === "authenticated") load(); }, [status, load]);

    const nameOf = useCallback((row: PropertyRow) =>
        locale === "ar" && row.property.nameAr ? row.property.nameAr : row.property.nameEn, [locale]);

    if (failed) return <LoadFailedState onRetry={load} message={t("propertiesLoadError")} />;
    if (!properties) return <div className="flex justify-center py-24"><Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" /></div>;

    return (
        <div>
            <GatePassTabs />
            <div className="mb-5">
                <h1 className="text-lg font-bold text-foreground">{t("settingsTitle")}</h1>
                <p className="text-xs text-muted mt-0.5">{t("settingsDescription")}</p>
            </div>
            {properties.length === 0 ? (
                <div className="bg-surface rounded-xl border border-border text-center py-14 text-muted text-xs" data-testid="settings-no-properties">{t("noPropertiesToManage")}</div>
            ) : (
                <div className="space-y-6">
                    <div className="max-w-sm">
                        <label htmlFor="gs-property" className={labelClass}>{t("property")}</label>
                        <select id="gs-property" value={propertyId} onChange={e => setPropertyId(e.target.value)} className={inputClass} data-testid="settings-property">
                            {properties.map(p => <option key={p.property.id} value={p.property.id}>{nameOf(p)}</option>)}
                        </select>
                    </div>
                    {propertyId && <PolicySection key={`policy-${propertyId}`} propertyId={propertyId} />}
                    {propertyId && <RegularVisitorSection key={`visitor-${propertyId}`} propertyId={propertyId} />}
                    {hasPermission(role, "canAssignGuards")
                        ? <GuardsSection properties={properties} nameOf={nameOf} />
                        : <p className="text-xs text-muted" data-testid="guards-admin-only">{t("guardsAdminOnly")}</p>}
                </div>
            )}
        </div>
    );
}

const POLICY_FLAGS: (keyof Omit<GatePolicy, "approvalTimeoutMinutes">)[] =
    ["requireUnregisteredApproval", "requireRegisteredApproval", "notifyRegisteredEntry", "requireFreshPhoto"];

function PolicySection({ propertyId }: { propertyId: string }) {
    const t = useTranslations("GatePass");
    const locale = useLocale();
    const { buildings } = useBuildings(propertyId);
    const [buildingId, setBuildingId] = useState("");
    const [policy, setPolicy] = useState<PolicyResponse | null>(null);
    const [draft, setDraft] = useState<GatePolicy | null>(null);
    const [loadError, setLoadError] = useState<string | null>(null);
    const [saveError, setSaveError] = useState<string | null>(null);
    const [saved, setSaved] = useState(false);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        let alive = true;
        setLoadError(null);
        setSaved(false);
        fetchEffectivePolicy(propertyId, buildingId || null)
            .then(p => { if (alive) { setPolicy(p); setDraft(p); } })
            .catch(err => { if (alive) setLoadError(errorText(err, t("policyLoadError"), t)); });
        return () => { alive = false; };
    }, [propertyId, buildingId, t]);

    const save = async () => {
        if (!draft) return;
        setSaveError(null);
        setSaved(false);
        const minutes = Number(draft.approvalTimeoutMinutes);
        if (!Number.isInteger(minutes) || minutes < 1 || minutes > 1440) return setSaveError(t("errTimeout"));
        setSaving(true);
        try {
            const p = await savePolicy(propertyId, buildingId || null, { ...draft, approvalTimeoutMinutes: minutes });
            setPolicy(p);
            setDraft(p);
            setSaved(true);
        } catch (err) {
            setSaveError(errorText(err, t("policySaveError"), t));
        } finally {
            setSaving(false);
        }
    };

    return (
        <section className="bg-surface rounded-xl border border-border p-4 space-y-4" data-testid="policy-section">
            <div className="flex items-start justify-between gap-3 flex-wrap">
                <div>
                    <h2 className="text-sm font-semibold text-foreground flex items-center gap-2"><ShieldCheck size={15} /> {t("policyTitle")}</h2>
                    <p className="text-xs text-muted mt-0.5">{t("policyDescription")}</p>
                </div>
                {buildings.length > 0 && (
                    <div>
                        <label htmlFor="gs-tower" className={labelClass}>{t("tower")}</label>
                        <select id="gs-tower" value={buildingId} onChange={e => {
                            // A new scope drops the old one's values: saving them before the new
                            // read lands would write the property's policy onto this tower.
                            setPolicy(null);
                            setDraft(null);
                            setLoadError(null);
                            setBuildingId(e.target.value);
                        }} className={cn(inputClass, "w-auto text-xs")}>
                            <option value="">{t("wholeProperty")}</option>
                            {buildings.map(b => <option key={b.id} value={b.id}>{(locale === "ar" ? b.nameAr || b.nameEn : b.nameEn || b.nameAr) ?? b.id.slice(0, 8)}</option>)}
                        </select>
                    </div>
                )}
            </div>
            <ErrorLine message={loadError} testId="policy-load-error" />
            {draft && policy && (
                <>
                    {policy.inherited && <p className="text-[11px] text-muted" data-testid="policy-inherited">{buildingId ? t("policyInheritedTower") : t("policyInheritedDefaults")}</p>}
                    <div className="grid gap-2 sm:grid-cols-2">
                        {POLICY_FLAGS.map(flag => (
                            <label key={flag} className="flex items-start gap-2 border border-border rounded-lg px-3 py-2 cursor-pointer">
                                <input type="checkbox" checked={draft[flag]} onChange={e => { setDraft({ ...draft, [flag]: e.target.checked }); setSaved(false); }} className="mt-0.5" data-testid={`policy-${flag}`} />
                                <span>
                                    <span className="block text-xs font-medium text-foreground">{t(`policy.${flag}`)}</span>
                                    <span className="block text-[11px] text-muted">{t(`policy.${flag}Hint`)}</span>
                                </span>
                            </label>
                        ))}
                    </div>
                    <div className="max-w-[220px]">
                        <label htmlFor="gs-timeout" className={labelClass}>{t("policy.approvalTimeoutMinutes")}</label>
                        <input id="gs-timeout" type="number" min={1} max={1440} value={draft.approvalTimeoutMinutes}
                            onChange={e => { setDraft({ ...draft, approvalTimeoutMinutes: e.target.value === "" ? 0 : Number(e.target.value) }); setSaved(false); }}
                            className={inputClass} data-testid="policy-timeout" />
                    </div>
                    <ErrorLine message={saveError} testId="policy-save-error" />
                    {saved && <p role="status" className="text-xs text-success" data-testid="policy-saved">{t("policySaved")}</p>}
                    <button type="button" onClick={save} disabled={saving} className={primaryButton} data-testid="policy-save">
                        {saving ? <Loader2 size={14} className="animate-spin" /> : <Save size={14} />} {t("savePolicy")}
                    </button>
                </>
            )}
        </section>
    );
}

function RegularVisitorSection({ propertyId }: { propertyId: string }) {
    const t = useTranslations("GatePass");
    const [units, setUnits] = useState<UnitRow[]>([]);
    const [unitsError, setUnitsError] = useState<string | null>(null);
    const [unitId, setUnitId] = useState("");
    const [name, setName] = useState("");
    const [phone, setPhone] = useState("");
    const [visitorType, setVisitorType] = useState<GateVisitorType>("MAID");
    const [validFrom, setValidFrom] = useState("");
    const [validTo, setValidTo] = useState("");
    const [error, setError] = useState<string | null>(null);
    const [notice, setNotice] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        let alive = true;
        fetch(`/api/proxy/v1/units/property/${encodeURIComponent(propertyId)}`)
            .then(r => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
            .then((rows: UnitRow[]) => { if (alive) setUnits([...rows].sort((a, b) => a.unitNumber.localeCompare(b.unitNumber, undefined, { numeric: true }))); })
            .catch(() => { if (alive) setUnitsError(t("unitsLoadError")); });
        return () => { alive = false; };
    }, [propertyId, t]);

    const submit = async (e: React.FormEvent) => {
        e.preventDefault();
        setError(null);
        setNotice(null);
        if (!unitId) return setError(t("errChooseUnit"));
        if (!name.trim()) return setError(t("errVisitorName"));
        const e164 = toE164(phone);
        if (!e164) return setError(t("errPhone"));
        if (validFrom && validTo && validTo < validFrom) return setError(t("errLastDayAfterFirst"));
        setSaving(true);
        try {
            await registerVisitor({
                propertyId, unitId, name: name.trim(), phone: e164, visitorType,
                validFrom: validFrom ? localInstant(validFrom, "00:00") : null,
                validTo: validTo ? new Date(new Date(localInstant(validTo, "23:59")).getTime() + 59_000).toISOString() : null,
                active: true,
            });
            setNotice(t("visitorRegistered", { name: name.trim() }));
            setName(""); setPhone(""); setValidFrom(""); setValidTo("");
        } catch (err) {
            setError(errorText(err, t("visitorRegisterError"), t));
        } finally {
            setSaving(false);
        }
    };

    return (
        <section className="bg-surface rounded-xl border border-border p-4" data-testid="visitor-section">
            <h2 className="text-sm font-semibold text-foreground flex items-center gap-2"><UserCheck size={15} /> {t("regularVisitorsTitle")}</h2>
            <p className="text-xs text-muted mt-0.5 mb-4">{t("regularVisitorsDescription")}</p>
            <form onSubmit={submit} noValidate className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3" data-testid="visitor-form">
                <div>
                    <label htmlFor="rv-unit" className={labelClass}>{t("colUnit")}</label>
                    <select id="rv-unit" value={unitId} onChange={e => setUnitId(e.target.value)} className={inputClass}>
                        <option value="">{t("chooseUnit")}</option>
                        {units.map(u => <option key={u.id} value={u.id}>{u.unitNumber}</option>)}
                    </select>
                    {unitsError && <p className="text-[11px] text-error mt-1">{unitsError}</p>}
                </div>
                <div>
                    <label htmlFor="rv-name" className={labelClass}>{t("visitorName")}</label>
                    <input id="rv-name" value={name} maxLength={160} onChange={e => setName(e.target.value)} className={inputClass} />
                </div>
                <div>
                    <label htmlFor="rv-phone" className={labelClass}>{t("visitorPhone")}</label>
                    <input id="rv-phone" type="tel" dir="ltr" value={phone} maxLength={32} placeholder="+971 50 123 4567" onChange={e => setPhone(e.target.value)} className={inputClass} />
                </div>
                <div>
                    <label htmlFor="rv-type" className={labelClass}>{t("colVisitorType")}</label>
                    <select id="rv-type" value={visitorType} onChange={e => setVisitorType(e.target.value as GateVisitorType)} className={inputClass}>
                        {VISITOR_TYPES.map(v => <option key={v} value={v}>{t(`visitorType.${v}`)}</option>)}
                    </select>
                </div>
                <div>
                    <label htmlFor="rv-from" className={labelClass}>{t("firstDayOptional")}</label>
                    <input id="rv-from" type="date" value={validFrom} onChange={e => setValidFrom(e.target.value)} className={inputClass} />
                </div>
                <div>
                    <label htmlFor="rv-to" className={labelClass}>{t("lastDayOptional")}</label>
                    <input id="rv-to" type="date" value={validTo} min={validFrom || undefined} onChange={e => setValidTo(e.target.value)} className={inputClass} />
                </div>
                <div className="sm:col-span-2 lg:col-span-3 space-y-2">
                    <ErrorLine message={error} testId="visitor-error" />
                    {notice && <p role="status" className="text-xs text-success" data-testid="visitor-notice">{notice}</p>}
                    <button type="submit" disabled={saving} className={primaryButton} data-testid="visitor-submit">
                        {saving && <Loader2 size={14} className="animate-spin" />} {t("registerVisitor")}
                    </button>
                </div>
            </form>
        </section>
    );
}

function GuardsSection({ properties, nameOf }: { properties: PropertyRow[]; nameOf: (p: PropertyRow) => string }) {
    const t = useTranslations("GatePass");
    const [guards, setGuards] = useState<StaffUser[] | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [editing, setEditing] = useState<string | null>(null);
    const [page, setPage] = useState(1);
    const perPage = 10;

    useEffect(() => {
        let alive = true;
        fetch("/api/proxy/admin/users")
            .then(r => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
            .then((users: StaffUser[]) => { if (alive) setGuards(users.filter(u => u.role === "SECURITY_GUARD")); })
            .catch(() => { if (alive) setError(t("guardsLoadError")); });
        return () => { alive = false; };
    }, [t]);

    return (
        <section className="bg-surface rounded-xl border border-border p-4" data-testid="guards-section">
            <h2 className="text-sm font-semibold text-foreground flex items-center gap-2"><Users size={15} /> {t("guardsTitle")}</h2>
            <p className="text-xs text-muted mt-0.5 mb-4">{t("guardsDescription")}</p>
            <ErrorLine message={error} testId="guards-error" />
            {!guards ? (!error && <Loader2 className="w-5 h-5 animate-spin text-primary opacity-60" />) : guards.length === 0 ? (
                <p className="text-xs text-muted" data-testid="guards-empty">{t("noGuards")}</p>
            ) : (
                <>
                    <div className="overflow-x-auto">
                        <table className="w-full" data-testid="guards-table">
                            <thead>
                                <tr className="bg-input/50">
                                    {[t("colGuardName"), t("colContact"), ""].map((h, i) => (
                                        <th key={i} className="px-4 py-2.5 text-[11px] font-semibold text-muted uppercase tracking-wider text-start">{h}</th>
                                    ))}
                                </tr>
                            </thead>
                            <tbody>
                                {pageOf(guards, page, perPage).map(g => (
                                    <tr key={g.id} className="border-b border-border align-top">
                                        <td className="px-4 py-2.5 text-xs text-foreground font-medium">{g.name ?? "—"}</td>
                                        <td className="px-4 py-2.5 text-xs text-muted" dir="ltr">{g.phoneNumber || g.email || "—"}</td>
                                        <td className="px-4 py-2.5 text-end">
                                            {editing === g.id ? (
                                                <GuardPosting guardId={g.id} properties={properties} nameOf={nameOf} onDone={() => setEditing(null)} />
                                            ) : (
                                                <button type="button" onClick={() => setEditing(g.id)} className={secondaryButton} data-testid={`guard-edit-${g.id}`}>{t("editPosting")}</button>
                                            )}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                    {guards.length > perPage && <Pagination currentPage={page} totalItems={guards.length} itemsPerPage={perPage} onPageChange={setPage} />}
                </>
            )}
        </section>
    );
}

function GuardPosting({ guardId, properties, nameOf, onDone }: {
    guardId: string; properties: PropertyRow[]; nameOf: (p: PropertyRow) => string; onDone: () => void;
}) {
    const t = useTranslations("GatePass");
    const [selected, setSelected] = useState<Set<string> | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);

    useEffect(() => {
        let alive = true;
        fetchGuardProperties(guardId)
            .then(ids => { if (alive) setSelected(new Set(ids)); })
            .catch(err => { if (alive) setError(errorText(err, t("postingLoadError"), t)); });
        return () => { alive = false; };
    }, [guardId, t]);

    const sorted = useMemo(() => [...properties].sort((a, b) => nameOf(a).localeCompare(nameOf(b))), [properties, nameOf]);

    const save = async () => {
        if (!selected) return;
        setSaving(true);
        setError(null);
        try {
            await saveGuardProperties(guardId, [...selected]);
            onDone();
        } catch (err) {
            setError(errorText(err, t("postingSaveError"), t));
        } finally {
            setSaving(false);
        }
    };

    return (
        <div className="text-start space-y-2 min-w-[220px]" data-testid="guard-posting">
            <ErrorLine message={error} testId="guard-posting-error" />
            {!selected ? (!error && <Loader2 className="w-4 h-4 animate-spin text-primary opacity-60" />) : (
                <>
                    {sorted.map(p => (
                        <label key={p.property.id} className="flex items-center gap-2 text-xs text-foreground">
                            <input type="checkbox" checked={selected.has(p.property.id)} onChange={e => {
                                const next = new Set(selected);
                                if (e.target.checked) next.add(p.property.id); else next.delete(p.property.id);
                                setSelected(next);
                            }} />
                            {nameOf(p)}
                        </label>
                    ))}
                    <div className="flex gap-2 pt-1">
                        <button type="button" onClick={save} disabled={saving} className={primaryButton} data-testid="guard-posting-save">
                            {saving && <Loader2 size={13} className="animate-spin" />} {t("savePosting")}
                        </button>
                        <button type="button" onClick={onDone} className={secondaryButton}>{t("back")}</button>
                    </div>
                </>
            )}
        </div>
    );
}

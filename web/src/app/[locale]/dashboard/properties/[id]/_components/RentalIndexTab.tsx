"use client";

import { useCallback, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { ExternalLink, Pencil, Plus, Trash2, Upload } from "lucide-react";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { ApiError } from "@/lib/api/facilities";
import { fmtAmount } from "@/lib/api/ledger";
import {
    UNIT_TYPES, deleteRentalIndexRange, dewaPremiseNumberValid, dldRentalIndexUrl, fetchRentalIndex, importRentalIndex,
    parseRentalIndexCsv, setUnitDewaPremiseNumber, upsertRentalIndexRange,
    type ParsedCsvRow, type RentalIndexRange, type RentalIndexRangeInput,
} from "@/lib/api/rentalIndex";

export type RentalIndexUnit = { id: string; unitNumber: string; type?: string | null; dewaPremiseNumber?: string | null };

interface Props {
    propertyId: string;
    /** The property's units, for the DEWA premise numbers; the page already holds them. */
    units: RentalIndexUnit[];
    canManage: boolean;
    /** After a DEWA number is saved, so the page's unit list follows. */
    onUnitsChanged?: () => void;
}

const EMPTY_FORM = { unitType: "BHK1", indexYear: String(new Date().getFullYear() + 1), minValue: "", maxValue: "", sourceNote: "" };

const field = "w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none";
const label = "block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1";

/**
 * The property's DLD rental index ranges (one per unit type × contract-end year)
 * and its units' DEWA premise numbers — what the renewal's Decree 43 cap is
 * computed from. Everything here is typed in by a manager from the DLD
 * calculator; the tab only links out to it.
 */
export function RentalIndexTab({ propertyId, units, canManage, onUnitsChanged }: Props) {
    const t = useTranslations("RentalIndex");
    const tm = useTranslations("MasterData");
    const locale = useLocale();
    const [rows, setRows] = useState<RentalIndexRange[]>([]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [showForm, setShowForm] = useState(false);
    const [editing, setEditing] = useState<RentalIndexRange | null>(null);
    const [form, setForm] = useState(EMPTY_FORM);
    const [formError, setFormError] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [deleting, setDeleting] = useState<RentalIndexRange | null>(null);
    const [showImport, setShowImport] = useState(false);
    const [parsed, setParsed] = useState<ParsedCsvRow[]>([]);
    const [importErrors, setImportErrors] = useState<string[]>([]);
    const [importNotice, setImportNotice] = useState<string | null>(null);
    const [importing, setImporting] = useState(false);
    const [dewaEditing, setDewaEditing] = useState<string | null>(null);
    const [dewaValue, setDewaValue] = useState("");
    const [dewaError, setDewaError] = useState<string | null>(null);

    const unitTypeLabel = (ut: string) => (tm.has(`unitType${ut}` as never) ? tm(`unitType${ut}` as never) : ut);
    const dateOf = (iso: string) => new Date(iso).toLocaleDateString(locale === "ar" ? "ar-AE" : "en-GB");

    const load = useCallback(async () => {
        setLoading(true);
        setError(null);
        try {
            setRows(await fetchRentalIndex(propertyId));
        } catch (err) {
            setError(err instanceof ApiError ? err.message : t("loadError"));
        } finally {
            setLoading(false);
        }
    }, [propertyId, t]);

    useEffect(() => { void load(); }, [load]);

    const openAdd = () => { setEditing(null); setForm(EMPTY_FORM); setFormError(null); setShowForm(true); setShowImport(false); };
    const openEdit = (r: RentalIndexRange) => {
        setEditing(r);
        setForm({ unitType: r.unitType, indexYear: String(r.indexYear), minValue: String(r.minValue), maxValue: String(r.maxValue), sourceNote: r.sourceNote ?? "" });
        setFormError(null);
        setShowForm(true);
        setShowImport(false);
    };

    const submit = async (e: React.FormEvent) => {
        e.preventDefault();
        const min = Number(form.minValue), max = Number(form.maxValue);
        if (!/^\d{4}$/.test(form.indexYear)) { setFormError(t("yearInvalid")); return; }
        if (!(min > 0) || !(max >= min)) { setFormError(t("minMaxInvalid")); return; }
        setSubmitting(true);
        setFormError(null);
        try {
            const body: RentalIndexRangeInput = {
                unitType: form.unitType, indexYear: Number(form.indexYear), minValue: min, maxValue: max,
                sourceNote: form.sourceNote.trim() || null,
            };
            await upsertRentalIndexRange(propertyId, body);
            setShowForm(false);
            await load();
        } catch (err) {
            setFormError(err instanceof ApiError ? err.message : t("saveFailed"));
        } finally {
            setSubmitting(false);
        }
    };

    const confirmDelete = async () => {
        if (!deleting) return;
        try {
            await deleteRentalIndexRange(propertyId, deleting.id);
            setDeleting(null);
            await load();
        } catch (err) {
            setError(err instanceof ApiError ? err.message : t("deleteFailed"));
            setDeleting(null);
        }
    };

    const onFile = async (file: File | null) => {
        setImportErrors([]);
        setImportNotice(null);
        if (!file) { setParsed([]); return; }
        const text = await file.text();
        const result = parseRentalIndexCsv(text);
        setParsed(result);
        setImportErrors(result.filter(r => r.error).map(r => `Row ${r.row}: ${r.error}`));
    };

    const runImport = async () => {
        const inputs = parsed.filter(r => r.input).map(r => r.input!);
        if (inputs.length === 0) { setImportErrors([t("csvEmpty")]); return; }
        setImporting(true);
        setImportErrors([]);
        setImportNotice(null);
        try {
            const result = await importRentalIndex(propertyId, inputs);
            if (result.errors.length > 0) {
                setImportErrors(result.errors);
            } else {
                setImportNotice(t("imported", { count: result.saved }));
                setParsed([]);
                await load();
            }
        } catch (err) {
            setImportErrors([err instanceof ApiError ? err.message : t("importFailed")]);
        } finally {
            setImporting(false);
        }
    };

    const saveDewa = async (unit: RentalIndexUnit) => {
        if (!dewaPremiseNumberValid(dewaValue)) { setDewaError(tm("dewaPremiseNumberInvalid")); return; }
        try {
            await setUnitDewaPremiseNumber(unit.id, dewaValue.trim() || null);
            setDewaEditing(null);
            setDewaError(null);
            onUnitsChanged?.();
        } catch (err) {
            setDewaError(err instanceof ApiError ? err.message : t("dewaSaveFailed"));
        }
    };

    const pending = parsed.filter(r => r.input).length;

    return (
        <div data-testid="rental-index-tab" className="space-y-6">
            <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="max-w-2xl">
                    <h2 className="text-lg font-bold">{t("title")}</h2>
                    <p className="text-xs text-muted mt-1">{t("intro")}</p>
                    <a href={dldRentalIndexUrl(locale)} target="_blank" rel="noopener noreferrer"
                        className="inline-flex items-center gap-1 text-xs text-primary underline mt-2">
                        <ExternalLink size={12} /> {t("openDld")}
                    </a>
                </div>
                {canManage && (
                    <div className="flex items-center gap-2">
                        <button type="button" onClick={openAdd} data-testid="rental-index-add"
                            className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-full text-xs font-bold cursor-pointer">
                            <Plus size={14} /> {t("addRange")}
                        </button>
                        <button type="button" onClick={() => { setShowImport(s => !s); setShowForm(false); }} data-testid="rental-index-import-toggle"
                            className="flex items-center gap-2 bg-background text-foreground px-4 py-2 rounded-full text-xs font-bold cursor-pointer border border-border">
                            <Upload size={14} /> {t("importCsv")}
                        </button>
                    </div>
                )}
            </div>

            {showForm && (
                <form onSubmit={submit} data-testid="rental-index-form" className="bg-surface p-4 rounded-xl border border-border grid grid-cols-2 md:grid-cols-5 gap-3">
                    <div>
                        <label className={label} htmlFor="ri-unit-type">{t("unitType")}</label>
                        <select id="ri-unit-type" className={field} value={form.unitType} disabled={!!editing}
                            onChange={e => setForm({ ...form, unitType: e.target.value })}>
                            {UNIT_TYPES.map(ut => <option key={ut} value={ut}>{unitTypeLabel(ut)}</option>)}
                        </select>
                    </div>
                    <div>
                        <label className={label} htmlFor="ri-year">{t("year")}</label>
                        <input id="ri-year" className={field} inputMode="numeric" dir="ltr" value={form.indexYear} disabled={!!editing}
                            onChange={e => setForm({ ...form, indexYear: e.target.value })} />
                    </div>
                    <div>
                        <label className={label} htmlFor="ri-min">{t("minValue")}</label>
                        <input id="ri-min" className={field} inputMode="decimal" dir="ltr" value={form.minValue}
                            onChange={e => setForm({ ...form, minValue: e.target.value })} />
                    </div>
                    <div>
                        <label className={label} htmlFor="ri-max">{t("maxValue")}</label>
                        <input id="ri-max" className={field} inputMode="decimal" dir="ltr" value={form.maxValue}
                            onChange={e => setForm({ ...form, maxValue: e.target.value })} />
                    </div>
                    <div>
                        <label className={label} htmlFor="ri-note">{t("sourceNote")}</label>
                        <input id="ri-note" className={field} value={form.sourceNote} placeholder={t("sourceNotePlaceholder")}
                            onChange={e => setForm({ ...form, sourceNote: e.target.value })} />
                    </div>
                    {formError && <p role="alert" className="col-span-2 md:col-span-5 text-xs text-error">{formError}</p>}
                    <div className="col-span-2 md:col-span-5 flex justify-end gap-2">
                        <button type="button" onClick={() => setShowForm(false)} className="px-4 py-2 text-xs font-bold text-muted cursor-pointer">{t("cancel")}</button>
                        <button type="submit" disabled={submitting} data-testid="rental-index-save"
                            className="px-4 py-2 bg-primary text-primary-foreground rounded-lg text-xs font-bold cursor-pointer disabled:opacity-50">
                            {submitting ? t("saving") : t("save")}
                        </button>
                    </div>
                </form>
            )}

            {showImport && (
                <div data-testid="rental-index-import" className="bg-surface p-4 rounded-xl border border-border space-y-3">
                    <div>
                        <label className={label} htmlFor="ri-csv">{t("csvFile")}</label>
                        <input id="ri-csv" type="file" accept=".csv,text/csv" className="text-xs" data-testid="rental-index-csv"
                            onChange={e => void onFile(e.target.files?.[0] ?? null)} />
                        <p className="text-[10px] text-muted mt-1">{t("csvColumns")}</p>
                    </div>
                    {pending > 0 && <p className="text-xs" data-testid="rental-index-csv-preview">{t("csvPreview", { count: pending })}</p>}
                    {importErrors.length > 0 && (
                        <div role="alert" data-testid="rental-index-import-errors" className="bg-error/10 border border-error/30 rounded-lg px-3 py-2 text-xs text-error">
                            <p className="font-semibold">{t("csvErrors")}</p>
                            <ul className="list-disc ms-4">{importErrors.map((e, i) => <li key={i}>{e}</li>)}</ul>
                        </div>
                    )}
                    {importNotice && <p role="status" className="text-xs text-success" data-testid="rental-index-imported">{importNotice}</p>}
                    <div className="flex justify-end">
                        <button type="button" onClick={() => void runImport()} disabled={importing || pending === 0} data-testid="rental-index-import-run"
                            className="px-4 py-2 bg-primary text-primary-foreground rounded-lg text-xs font-bold cursor-pointer disabled:opacity-50">
                            {importing ? t("importing") : t("import")}
                        </button>
                    </div>
                </div>
            )}

            {error && <p role="alert" className="text-xs text-error">{error}</p>}

            <div className="bg-surface border border-border rounded-xl overflow-hidden">
                <table className="w-full text-start text-sm" data-testid="rental-index-table">
                    <thead className="bg-background text-muted text-xs font-semibold uppercase tracking-[0.15em]">
                        <tr>
                            <th className="p-3 text-start">{t("colUnitType")}</th>
                            <th className="p-3 text-start">{t("colYear")}</th>
                            <th className="p-3 text-end">{t("colMin")}</th>
                            <th className="p-3 text-end">{t("colMax")}</th>
                            <th className="p-3 text-end">{t("colAverage")}</th>
                            <th className="p-3 text-start">{t("colSource")}</th>
                            <th className="p-3 text-start">{t("colEntered")}</th>
                            {canManage && <th className="p-3 text-end">{t("colActions")}</th>}
                        </tr>
                    </thead>
                    <tbody>
                        {!loading && rows.length === 0 && (
                            <tr><td colSpan={8} className="p-4 text-xs text-muted">{t("empty")}</td></tr>
                        )}
                        {rows.map(r => (
                            <tr key={r.id} className="border-t border-border" data-testid="rental-index-row">
                                <td className="p-3">{unitTypeLabel(r.unitType)}</td>
                                <td className="p-3 tabular-nums"><bdi dir="ltr">{r.indexYear}</bdi></td>
                                <td className="p-3 text-end tabular-nums"><bdi dir="ltr">{fmtAmount(r.minValue)}</bdi></td>
                                <td className="p-3 text-end tabular-nums"><bdi dir="ltr">{fmtAmount(r.maxValue)}</bdi></td>
                                <td className="p-3 text-end tabular-nums"><bdi dir="ltr">{fmtAmount((r.minValue + r.maxValue) / 2)}</bdi></td>
                                <td className="p-3 text-xs text-muted">{r.sourceNote ?? ""}</td>
                                <td className="p-3 text-xs text-muted">{dateOf(r.enteredAt)}</td>
                                {canManage && (
                                    <td className="p-3 text-end whitespace-nowrap">
                                        <button type="button" onClick={() => openEdit(r)} aria-label={t("editRange")} className="p-1 text-muted hover:text-foreground cursor-pointer"><Pencil size={14} /></button>
                                        <button type="button" onClick={() => setDeleting(r)} aria-label={t("delete")} className="p-1 text-muted hover:text-error cursor-pointer"><Trash2 size={14} /></button>
                                    </td>
                                )}
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            <div>
                <h3 className="text-sm font-bold">{t("unitsTitle")}</h3>
                <p className="text-xs text-muted mt-1 mb-3">{t("unitsIntro")}</p>
                <div className="bg-surface border border-border rounded-xl overflow-hidden">
                    <table className="w-full text-start text-sm" data-testid="rental-index-units">
                        <thead className="bg-background text-muted text-xs font-semibold uppercase tracking-[0.15em]">
                            <tr>
                                <th className="p-3 text-start">{t("colUnit")}</th>
                                <th className="p-3 text-start">{t("colUnitType")}</th>
                                <th className="p-3 text-start">{t("colDewa")}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {units.length === 0 && <tr><td colSpan={3} className="p-4 text-xs text-muted">{t("noUnits")}</td></tr>}
                            {units.map(u => (
                                <tr key={u.id} className="border-t border-border">
                                    <td className="p-3">{u.unitNumber}</td>
                                    <td className="p-3">{u.type ? unitTypeLabel(u.type) : ""}</td>
                                    <td className="p-3">
                                        {dewaEditing === u.id ? (
                                            <span className="inline-flex items-center gap-2">
                                                <input aria-label={t("editDewa", { unit: u.unitNumber })} className={`${field} w-36`} inputMode="numeric" dir="ltr" maxLength={9}
                                                    value={dewaValue} onChange={e => setDewaValue(e.target.value)} placeholder={tm("dewaPremiseNumberPlaceholder")} />
                                                <button type="button" onClick={() => void saveDewa(u)} className="text-xs font-bold text-primary cursor-pointer">{t("save")}</button>
                                                <button type="button" onClick={() => { setDewaEditing(null); setDewaError(null); }} className="text-xs text-muted cursor-pointer">{t("cancel")}</button>
                                                {dewaError && <span role="alert" className="text-xs text-error">{dewaError}</span>}
                                            </span>
                                        ) : (
                                            <span className="inline-flex items-center gap-2">
                                                <bdi dir="ltr" className={u.dewaPremiseNumber ? "tabular-nums" : "text-muted text-xs"}>{u.dewaPremiseNumber || t("notEntered")}</bdi>
                                                {canManage && (
                                                    <button type="button" aria-label={t("editDewa", { unit: u.unitNumber })}
                                                        onClick={() => { setDewaEditing(u.id); setDewaValue(u.dewaPremiseNumber ?? ""); setDewaError(null); }}
                                                        className="p-1 text-muted hover:text-foreground cursor-pointer"><Pencil size={12} /></button>
                                                )}
                                            </span>
                                        )}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            </div>

            {deleting && (
                <ConfirmDialog
                    isOpen
                    title={t("delete")}
                    description={t("deleteConfirm", { unitType: unitTypeLabel(deleting.unitType), year: deleting.indexYear })}
                    confirmText={t("delete")}
                    cancelText={t("cancel")}
                    isDestructive
                    confirmTestId="rental-index-delete-confirm"
                    onConfirm={() => void confirmDelete()}
                    onClose={() => setDeleting(null)}
                />
            )}
        </div>
    );
}

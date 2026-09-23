"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { useRouter } from "@/i18n/routing";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import {
    AlertTriangle, ArrowLeft, ArrowRight, Building2, Calendar, Check, CreditCard,
    FileText, ListChecks, Loader2, Save, Sparkles, User, X,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { fmtAmount } from "@/lib/api/ledger";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { SearchableSelect } from "@/components/ui/SearchableSelect";
import { NumberInput } from "@/components/ui/NumberInput";
import { useLeasePartyOptions } from "@/hooks/useLeasePartyOptions";
import LeaseLinesGrid from "@/components/leases/LeaseLinesGrid";
import ChequeGrid, { draftRowsAreValid, toChequeRows } from "@/components/leases/ChequeGrid";
import { blankLine, linesAreValid, splitLineErrors, toInputs, toRows, todayIso, totalsOf, type LineRow } from "@/components/leases/leaseMath";
import {
    ApiError, chargeTypeApi, leaseApi,
    type ChargeType, type Cheque, type DraftLeaseInput, type DraftPaymentMethod,
    type GenerateChequesRequest, type InstallmentDistribution, type LeaseDetail,
    type PostLeaseDryRunResponse,
} from "@/lib/api/leasing";

/**
 * Drafting a tenancy contract, in the order the client's accountant fills one
 * in: Parties → Terms → Charges → Cheques → Review.
 *
 * The draft is SAVED at the end of the Charges step, not at the end of the
 * wizard. Everything after that point operates on a real lease: the cheque
 * grid is cut server-side from the saved lines, numbered server-side, and the
 * review step asks the server what a post would do rather than guessing. A
 * wizard that held all five steps in memory and posted once at the end could
 * not do any of it — there would be nothing for the generator to cut from.
 *
 * Leaving and coming back resumes the same draft: re-saving the charges is a
 * PUT, not a second POST. The backend drops DRAFT cheques whenever the lines
 * change, so when that happens the grid says so instead of silently emptying.
 */

type Unit = {
    id: string;
    unitNumber: string;
    status: string;
    expectedRent?: number | null;
    property?: { id: string; nameEn?: string; nameAr?: string; type?: string };
};

type Renter = { id: string; nameEn: string; nameAr: string; email?: string };

type Terms = {
    agreementDate: string;
    contractDate: string;
    startDate: string;
    endDate: string;
    gracePeriodDays: number;
    paymentTerms: number;
    firstDueDate: string;
    installmentDistribution: InstallmentDistribution;
    paymentMethod: DraftPaymentMethod;
    depositPaymentMethod: DraftPaymentMethod;
    ejariNumber: string;
    paymentReferenceNumber: string;
    rentVatApplicable: boolean;
};

const initialTerms: Terms = {
    agreementDate: "",
    contractDate: todayIso(),
    startDate: "",
    endDate: "",
    gracePeriodDays: 0,
    paymentTerms: 4,
    firstDueDate: "",
    installmentDistribution: "LAST_LARGER",
    paymentMethod: "CHEQUE",
    depositPaymentMethod: "CHEQUE",
    ejariNumber: "",
    paymentReferenceNumber: "",
    rentVatApplicable: false,
};

const STEPS = [
    { key: "parties", labelKey: "stepParties", icon: User },
    { key: "terms", labelKey: "stepTerms", icon: Calendar },
    { key: "lines", labelKey: "stepLines", icon: ListChecks },
    { key: "cheques", labelKey: "stepCheques", icon: CreditCard },
    { key: "review", labelKey: "stepReview", icon: FileText },
] as const;

const field = "w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";

type Props = {
    open: boolean;
    units: Unit[];
    renters: Renter[];
    onClose: () => void;
    onCreated: () => void;
};

export default function LeaseWizard({ open, units, renters, onClose, onCreated }: Props) {
    const t = useTranslations("Leasing");
    const router = useRouter();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canPost = hasPermission(userRole, "canPostLeases");

    const [stepIdx, setStepIdx] = useState(0);
    const [unitId, setUnitId] = useState("");
    const [renterId, setRenterId] = useState("");
    const [terms, setTerms] = useState<Terms>(initialTerms);
    // Once the operator edits the contract date directly, stop following the
    // agreement date — see #45. Before that, they're the same field.
    const [contractDateTouched, setContractDateTouched] = useState(false);
    const [rows, setRows] = useState<LineRow[]>([blankLine(0)]);
    const [chargeTypes, setChargeTypes] = useState<ChargeType[]>([]);

    const [lease, setLease] = useState<LeaseDetail | null>(null);
    const [cheques, setCheques] = useState<Cheque[]>([]);
    const [chequeNotice, setChequeNotice] = useState<string | null>(null);
    const [chequeError, setChequeError] = useState<string | null>(null);
    const [dry, setDry] = useState<PostLeaseDryRunResponse | null>(null);

    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [serverErrors, setServerErrors] = useState<string[]>([]);

    const reset = useCallback(() => {
        setStepIdx(0);
        setUnitId("");
        setRenterId("");
        setTerms({ ...initialTerms, contractDate: todayIso() });
        setContractDateTouched(false);
        setRows([blankLine(0)]);
        setLease(null);
        setCheques([]);
        setChequeNotice(null);
        setChequeError(null);
        setDry(null);
        setBusy(false);
        setError(null);
        setServerErrors([]);
    }, []);

    useEffect(() => {
        if (open) reset();
    }, [open, reset]);

    useEffect(() => {
        if (!open) return;
        let cancelled = false;
        chargeTypeApi
            .list(true)
            .then(list => {
                if (!cancelled) setChargeTypes(list);
            })
            .catch(() => {
                if (!cancelled) setChargeTypes([]);
            });
        return () => {
            cancelled = true;
        };
    }, [open]);

    const selectedUnit = useMemo(() => units.find(u => u.id === unitId), [units, unitId]);
    const selectedRenter = useMemo(() => renters.find(r => r.id === renterId), [renters, renterId]);
    const { unitOptions, renterOptions } = useLeasePartyOptions(units, renters, unitId);
    const totals = totalsOf(rows, chargeTypes);
    const { rest: bannerErrors } = splitLineErrors(serverErrors);

    const body = (): DraftLeaseInput => ({
        unitId,
        renterId,
        startDate: terms.startDate,
        endDate: terms.endDate,
        contractDate: terms.contractDate || null,
        gracePeriodDays: terms.gracePeriodDays,
        firstDueDate: terms.firstDueDate || null,
        ejariNumber: terms.ejariNumber || null,
        paymentTerms: terms.paymentTerms,
        installmentDistribution: terms.installmentDistribution,
        paymentMethod: terms.paymentMethod,
        depositPaymentMethod: terms.depositPaymentMethod,
        paymentReferenceNumber: terms.paymentReferenceNumber || null,
        agreementDate: terms.agreementDate || null,
        rentVatApplicable: terms.rentVatApplicable,
        lines: toInputs(rows, { keepPeriods: false }),
    });

    const stepError = (idx: number): string | null => {
        switch (STEPS[idx].key) {
            case "parties":
                if (!unitId) return t("errSelectUnit");
                if (!renterId) return t("errSelectRenter");
                return null;
            case "terms":
                if (!terms.startDate || !terms.endDate) return t("errDatesRequired");
                if (terms.endDate <= terms.startDate) return t("errEndAfterStart");
                return null;
            case "lines":
                if (rows.length === 0) return t("errLinesRequired");
                if (rows.some(r => !r.chargeTypeId)) return t("errLineNeedsType");
                // The specific messages above catch the two mistakes an
                // accountant is likeliest to make; `linesAreValid` — the same
                // gate the amend/renew/extend dialogs use — is the backstop
                // that also refuses a non-positive amount or a negative
                // discount, which this step let through before.
                if (!linesAreValid(rows)) return t("errDiscountOverAmount");
                return null;
            default:
                return null;
        }
    };

    /**
     * Save (or re-save) the draft and step into the cheque grid. Re-saving an
     * existing draft is a PUT — coming back to the charges must not leave a
     * second abandoned lease behind.
     */
    const saveLines = async () => {
        setBusy(true);
        setError(null);
        setServerErrors([]);
        try {
            const had = cheques.length > 0;
            const saved = lease ? await leaseApi.updateDraft(lease.id, body()) : await leaseApi.createDraft(body());
            setLease(saved);
            // The server fills each line's credit account from the property's
            // role mapping; reading the lines back is how the grid learns it.
            setRows(toRows(saved.lines));
            const grid = await leaseApi.cheques(saved.id);
            setCheques(grid);
            setChequeNotice(had && grid.length === 0 ? t("chequesClearedNotice") : null);
            onCreated();
            setStepIdx(3);
        } catch (e) {
            if (e instanceof ApiError) {
                setServerErrors([e.message]);
                setError(e.message);
            } else {
                setError(t("saveFailed"));
            }
        } finally {
            setBusy(false);
        }
    };

    const runCheques = async (fn: () => Promise<Cheque[]>) => {
        setBusy(true);
        setChequeError(null);
        try {
            setCheques(await fn());
            setChequeNotice(null);
        } catch (e) {
            setChequeError(e instanceof ApiError ? e.message : t("saveFailed"));
        } finally {
            setBusy(false);
        }
    };

    const generate = (req: GenerateChequesRequest) => {
        if (!lease) return;
        runCheques(() => leaseApi.generateCheques(lease.id, req));
    };
    const generateNumbers = (startingNumber: string) => {
        if (!lease) return;
        runCheques(() => leaseApi.generateChequeNumbers(lease.id, startingNumber));
    };
    const saveCheques = () => {
        if (!lease) return;
        runCheques(() => leaseApi.saveCheques(lease.id, toChequeRows(cheques)));
    };

    const toReview = async () => {
        if (!lease) return;
        setBusy(true);
        setError(null);
        try {
            setDry(await leaseApi.dryRunPost(lease.id));
            setStepIdx(4);
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("postFailed"));
        } finally {
            setBusy(false);
        }
    };

    const post = async () => {
        if (!lease) return;
        setBusy(true);
        setError(null);
        try {
            const res = await leaseApi.post(lease.id);
            onCreated();
            router.push(`/dashboard/leases/${res.lease.id}?posted=${encodeURIComponent(res.tcoEntryNumber)}`);
            onClose();
        } catch (e) {
            setError(e instanceof ApiError ? e.message : t("postFailed"));
        } finally {
            setBusy(false);
        }
    };

    if (!open) return null;

    const step = STEPS[stepIdx];
    const patch = (next: Partial<Terms>) => {
        setTerms(prev => ({ ...prev, ...next }));
        setError(null);
    };

    const goNext = () => {
        const e = stepError(stepIdx);
        if (e) {
            setError(e);
            return;
        }
        setError(null);
        if (step.key === "lines") {
            saveLines();
            return;
        }
        if (step.key === "cheques") {
            toReview();
            return;
        }
        setStepIdx(i => Math.min(i + 1, STEPS.length - 1));
    };

    return (
        <div
            data-testid="lease-wizard"
            role="dialog"
            aria-modal="true"
            className="fixed inset-0 z-50 flex items-stretch justify-center bg-black/60 p-0 sm:p-6"
        >
            <div className="bg-surface w-full max-w-6xl rounded-none sm:rounded-2xl border border-border shadow-2xl overflow-hidden flex flex-col">
                <div className="flex items-center justify-between px-6 py-4 border-b border-border shrink-0">
                    <div>
                        <h2 className="text-sm font-semibold text-foreground flex items-center gap-2">
                            <Sparkles size={14} className="text-primary" /> {t("newContract")} — {t(step.labelKey)}
                        </h2>
                        <p className="text-[11px] text-muted mt-0.5">
                            {t("stepCounter", { current: stepIdx + 1, total: STEPS.length })}
                            {lease?.displayContractNumber ? ` · ${lease.displayContractNumber}` : ""}
                        </p>
                    </div>
                    <button onClick={onClose} aria-label={t("close")} className="p-2 rounded-lg text-muted hover:bg-input hover:text-foreground cursor-pointer">
                        <X size={16} />
                    </button>
                </div>

                <div className="px-6 py-3 border-b border-border bg-input/30 shrink-0">
                    <div className="flex items-center gap-1.5 overflow-x-auto">
                        {STEPS.map((s, i) => {
                            const Icon = s.icon;
                            const done = i < stepIdx;
                            return (
                                <div key={s.key} className="flex items-center gap-1.5">
                                    <div
                                        className={cn(
                                            "flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-semibold",
                                            done ? "bg-success/15 text-success" : i === stepIdx ? "bg-primary/15 text-primary" : "bg-input text-muted",
                                        )}
                                    >
                                        {done ? <Check size={11} /> : <Icon size={11} />}
                                        <span className="whitespace-nowrap">{i + 1}. {t(s.labelKey)}</span>
                                    </div>
                                    {i < STEPS.length - 1 && <span className="text-muted/50">›</span>}
                                </div>
                            );
                        })}
                    </div>
                </div>

                <div className="flex-1 overflow-y-auto px-6 py-5">
                    {step.key === "parties" && (
                        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                            <Field label={`${t("unit")} *`}>
                                <SearchableSelect
                                    options={unitOptions}
                                    value={unitId}
                                    onChange={id => {
                                        setUnitId(id);
                                        const u = units.find(x => x.id === id);
                                        patch({ rentVatApplicable: u?.property?.type === "COMMERCIAL" });
                                    }}
                                    placeholder={t("unit")}
                                    searchPlaceholder={t("unit")}
                                />
                                {selectedUnit && (
                                    <p className="text-[11px] text-muted mt-1.5 flex items-center gap-1">
                                        <Building2 size={11} /> {selectedUnit.property?.nameEn} • {selectedUnit.property?.type || "RESIDENTIAL"}
                                    </p>
                                )}
                            </Field>
                            <Field label={`${t("renter")} *`}>
                                <SearchableSelect
                                    options={renterOptions}
                                    value={renterId}
                                    onChange={setRenterId}
                                    placeholder={t("renter")}
                                    searchPlaceholder={t("renter")}
                                />
                            </Field>
                            <Field label={t("agreementDate")}>
                                <input
                                    type="date"
                                    data-testid="wizard-agreement-date"
                                    className={field}
                                    value={terms.agreementDate}
                                    onChange={e => {
                                        const value = e.target.value;
                                        patch(
                                            contractDateTouched
                                                ? { agreementDate: value }
                                                : { agreementDate: value, contractDate: value },
                                        );
                                    }}
                                />
                            </Field>
                        </div>
                    )}

                    {step.key === "terms" && (
                        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                            <Field label={t("contractDate")}>
                                <input
                                    type="date"
                                    data-testid="wizard-contract-date"
                                    className={field}
                                    value={terms.contractDate}
                                    onChange={e => {
                                        setContractDateTouched(true);
                                        patch({ contractDate: e.target.value });
                                    }}
                                />
                            </Field>
                            <Field label={`${t("startDate")} *`}>
                                <input type="date" data-testid="wizard-start-date" className={field} value={terms.startDate} onChange={e => patch({ startDate: e.target.value })} />
                            </Field>
                            <Field label={`${t("endDate")} *`}>
                                <input type="date" data-testid="wizard-end-date" className={field} value={terms.endDate} onChange={e => patch({ endDate: e.target.value })} />
                            </Field>
                            <Field label={t("gracePeriodDays")}>
                                <NumberInput showZero min={0} max={90} className={field} value={terms.gracePeriodDays} onChange={v => patch({ gracePeriodDays: v })} />
                            </Field>
                            <Field label={t("paymentTerms")}>
                                <NumberInput showZero min={1} max={36} className={field} value={terms.paymentTerms} onChange={v => patch({ paymentTerms: Math.max(1, v) })} />
                            </Field>
                            <Field label={t("firstDueDate")}>
                                <input type="date" className={field} value={terms.firstDueDate} onChange={e => patch({ firstDueDate: e.target.value })} />
                            </Field>
                            <Field label={t("distribution")}>
                                <select className={field} value={terms.installmentDistribution} onChange={e => patch({ installmentDistribution: e.target.value as InstallmentDistribution })}>
                                    <option value="UNIFORM">{t("distributionUniform")}</option>
                                    <option value="FIRST_LARGER">{t("distributionFirstLarger")}</option>
                                    <option value="LAST_LARGER">{t("distributionLastLarger")}</option>
                                    <option value="FIRST_AND_LAST_LARGER">{t("distributionBothLarger")}</option>
                                </select>
                            </Field>
                            <Field label={t("paymentMethod")}>
                                <select className={field} value={terms.paymentMethod} onChange={e => patch({ paymentMethod: e.target.value as DraftPaymentMethod })}>
                                    <option value="CHEQUE">{t("methodCheque")}</option>
                                    <option value="ONLINE">{t("methodOnline")}</option>
                                </select>
                            </Field>
                            <Field label={t("depositPaymentMethod")}>
                                <select className={field} value={terms.depositPaymentMethod} onChange={e => patch({ depositPaymentMethod: e.target.value as DraftPaymentMethod })}>
                                    <option value="CHEQUE">{t("methodCheque")}</option>
                                    <option value="ONLINE">{t("methodOnline")}</option>
                                </select>
                            </Field>
                            <Field label={t("ejariNumber")}>
                                <input className={field} value={terms.ejariNumber} onChange={e => patch({ ejariNumber: e.target.value })} />
                            </Field>
                            <Field label={t("paymentReference")}>
                                <input className={field} value={terms.paymentReferenceNumber} onChange={e => patch({ paymentReferenceNumber: e.target.value })} />
                            </Field>
                            <label className="flex items-end gap-2 text-xs text-foreground pb-3">
                                <input type="checkbox" checked={terms.rentVatApplicable} onChange={e => patch({ rentVatApplicable: e.target.checked })} />
                                {t("rentVat")}
                            </label>
                        </div>
                    )}

                    {step.key === "lines" && (
                        <div className="space-y-3">
                            <LeaseLinesGrid
                                lines={rows}
                                chargeTypes={chargeTypes}
                                propertyId={selectedUnit?.property?.id ?? null}
                                editable
                                onChange={setRows}
                                errors={serverErrors}
                            />
                            {bannerErrors.length > 0 && (
                                <ul className="text-[11px] text-error space-y-1" data-testid="wizard-line-errors">
                                    {bannerErrors.map((e, i) => (
                                        <li key={i}>{e}</li>
                                    ))}
                                </ul>
                            )}
                        </div>
                    )}

                    {step.key === "cheques" && lease && (
                        <div className="space-y-3">
                            <p className="text-[11px] text-muted">{t("draftSavedGenerateCheques")}</p>
                            <ChequeGrid
                                cheques={cheques}
                                editable
                                onChange={setCheques}
                                onGenerate={generate}
                                onGenerateNumbers={generateNumbers}
                                propertyId={lease.propertyId}
                                contractValueInclVat={totals.inclVat}
                                defaultInstallments={terms.paymentTerms}
                                defaultFirstDueDate={terms.firstDueDate || terms.startDate}
                                busy={busy}
                                error={chequeError}
                                notice={chequeNotice}
                            />
                            <button
                                type="button"
                                data-testid="wizard-save-cheques"
                                onClick={saveCheques}
                                disabled={busy || cheques.length === 0 || !draftRowsAreValid(cheques)}
                                className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer disabled:opacity-50"
                            >
                                <Save size={12} /> {t("saveCheques")}
                            </button>
                        </div>
                    )}

                    {step.key === "review" && lease && (
                        <div className="space-y-4" data-testid="wizard-review">
                            <dl className="grid grid-cols-1 md:grid-cols-2 gap-x-8 gap-y-2 text-xs rounded-xl border border-border bg-input/30 px-4 py-3">
                                <Summary label={t("unit")} value={selectedUnit ? `${selectedUnit.unitNumber} • ${selectedUnit.property?.nameEn ?? ""}` : lease.unitIdentifier ?? "—"} />
                                <Summary label={t("renter")} value={selectedRenter?.nameEn ?? lease.renterName ?? "—"} />
                                <Summary label={t("startDate")} value={lease.startDate} />
                                <Summary label={t("endDate")} value={lease.endDate} />
                                {dry && (
                                    <>
                                        <Summary label={t("contractValue")} value={fmtAmount(dry.contractValue)} />
                                        <Summary label={t("vat")} value={fmtAmount(dry.contractValueInclVat - dry.contractValue)} />
                                        <Summary label={t("contractValueInclVat")} value={fmtAmount(dry.contractValueInclVat)} />
                                        <Summary label={t("chequeTotal")} value={fmtAmount(dry.chequeTotal)} />
                                        {dry.depositCarriedForward > 0 && (
                                            <Summary label={t("depositCarriedForward")} value={fmtAmount(dry.depositCarriedForward)} />
                                        )}
                                    </>
                                )}
                            </dl>

                            {dry && (
                                <p className="text-xs text-muted" data-testid="wizard-journals">
                                    {t("dryRunJournals", { tco: dry.journals.tco, tcoLines: dry.journals.tcoLines, pdr: dry.journals.pdr })}
                                </p>
                            )}

                            {dry && !dry.ok && (
                                <div>
                                    <p className="text-xs text-error font-semibold mb-1">{t("dryRunErrors")}</p>
                                    <ul data-testid="wizard-dry-run-errors" className="text-[11px] text-error space-y-1 list-disc ms-4">
                                        {dry.errors.map((e, i) => (
                                            <li key={i}>{e}</li>
                                        ))}
                                    </ul>
                                </div>
                            )}

                            {dry?.ok && <p className="text-xs text-success font-semibold" data-testid="wizard-dry-run-ok">{t("dryRunOk")}</p>}

                            {!canPost && (
                                <p className="text-xs text-muted" data-testid="wizard-needs-accountant">
                                    {t("savedAsDraftNeedsAccountant")}
                                </p>
                            )}
                        </div>
                    )}
                </div>

                <div className="flex items-center justify-between px-6 py-4 border-t border-border bg-surface shrink-0">
                    {error ? (
                        <span className="inline-flex items-center gap-1.5 text-[11px] text-error" data-testid="wizard-error">
                            <AlertTriangle size={12} /> {error}
                        </span>
                    ) : (
                        <span />
                    )}
                    <div className="flex items-center gap-2">
                        <button
                            onClick={() => {
                                setError(null);
                                setStepIdx(i => Math.max(i - 1, 0));
                            }}
                            disabled={stepIdx === 0 || busy}
                            className="px-4 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40 disabled:opacity-40 cursor-pointer"
                        >
                            <span className="inline-flex items-center gap-1.5"><ArrowLeft size={12} /> {t("back")}</span>
                        </button>

                        {step.key !== "review" && (
                            <button
                                onClick={goNext}
                                disabled={busy}
                                data-testid="wizard-next"
                                className="px-4 py-2 rounded-lg text-xs font-semibold bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50 cursor-pointer"
                            >
                                <span className="inline-flex items-center gap-1.5">
                                    {busy ? <Loader2 size={12} className="animate-spin" /> : null}
                                    {step.key === "lines" ? t("saveDraft") : t("next")}
                                    <ArrowRight size={12} />
                                </span>
                            </button>
                        )}

                        {step.key === "review" && lease && (
                            <>
                                <button
                                    onClick={() => {
                                        router.push(`/dashboard/leases/${lease.id}`);
                                        onClose();
                                    }}
                                    className="px-4 py-2 rounded-lg text-xs font-semibold border border-border text-foreground hover:bg-input/40 cursor-pointer"
                                >
                                    {t("openContract")}
                                </button>
                                {canPost && (
                                    <button
                                        onClick={post}
                                        disabled={busy || !dry?.ok}
                                        data-testid="wizard-post"
                                        className="px-4 py-2 rounded-lg text-xs font-semibold bg-accent text-accent-foreground hover:brightness-110 disabled:opacity-50 disabled:cursor-not-allowed cursor-pointer"
                                    >
                                        <span className="inline-flex items-center gap-1.5">
                                            {busy ? <Loader2 size={12} className="animate-spin" /> : <Check size={12} />}
                                            {t("postLease")}
                                        </span>
                                    </button>
                                )}
                            </>
                        )}
                    </div>
                </div>
            </div>
        </div>
    );
}

function Field({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
    return (
        <div>
            <label className="block text-[10px] font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ms-1">{label}</label>
            {children}
            {hint && <p className="text-[10px] text-muted mt-1 ms-1">{hint}</p>}
        </div>
    );
}

function Summary({ label, value }: { label: string; value: string }) {
    return (
        <div className="flex items-baseline justify-between gap-3">
            <dt className="text-muted shrink-0">{label}</dt>
            <dd className="text-foreground font-medium truncate tabular-nums" title={value}>{value}</dd>
        </div>
    );
}

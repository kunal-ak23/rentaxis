"use client";

import { focusFirstInvalidMoney } from "@/components/ui/NumberInput";
import { useCallback, useEffect, useState } from "react";
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
import { UnitPicker } from "@/components/pickers/UnitPicker";
import { RenterPicker } from "@/components/pickers/RenterPicker";
import { NumberInput } from "@/components/ui/NumberInput";
import { GraceDaysField, usePropertyDefaultGrace } from "@/components/leases/GraceDaysField";
import LeaseLinesGrid from "@/components/leases/LeaseLinesGrid";
import ChequeGrid, { draftRowsAreValid, toChequeRows } from "@/components/leases/ChequeGrid";
import WizardReviewSchedules from "@/components/leases/WizardReviewSchedules";
import { blankLine, defaultTermEnd, followRentVat, linesAreValid, rentAmountOf, splitLineErrors, termMonthsCeil, toInputs, toRows, todayIso, totalsOf, withRentAmount, withRentVat, type LineRow } from "@/components/leases/leaseMath";
import {
    ApiError, chargeTypeApi, leaseApi,
    type ChargeType, type Cheque, type ChequeWrite, type DraftLeaseInput, type DraftPaymentMethod,
    type GenerateChequesRequest, type InstallmentDistribution, type LeaseDetail,
    type PostLeaseDryRunResponse,
} from "@/lib/api/leasing";
import type { RenterOption, UnitOption } from "@/lib/api/lookup";
import { isLeaseChanged, withVersion } from "@/lib/leases/leaseVersion";
import { serverText } from "@/components/finance/bankrec/serverText";
import { CONFIRM_TERM_YEARS, MAX_TERM_YEARS, termExceedsYears, termYears } from "@/lib/leaseTerm";
import { isBeyondManualPostingWindow, maxManualPostingDateIso } from "@/lib/businessDate";
import { formatDate } from "@/lib/format";

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

type Terms = {
    agreementDate: string;
    contractDate: string;
    startDate: string;
    endDate: string;
    /** Null means the property's default (gap #65). */
    gracePeriodDays: number | null;
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
    gracePeriodDays: null,
    // Owner request (2026-09-29): one cheque a month of the term — 12 for the
    // default 12-month term — until the user types a count. (Was a hard-coded 4
    // here, not a per-property setting.)
    paymentTerms: 12,
    firstDueDate: "",
    installmentDistribution: "LAST_LARGER",
    paymentMethod: "CHEQUE",
    depositPaymentMethod: "CHEQUE",
    ejariNumber: "",
    paymentReferenceNumber: "",
    rentVatApplicable: false,
};

/** Only the two the draft endpoint admits — see `DraftPaymentMethod`. */
function asDraftMethod(v: string | null | undefined): DraftPaymentMethod {
    return v === "ONLINE" ? "ONLINE" : "CHEQUE";
}

/**
 * Review A M4: the Terms step as the saved draft has it. After a 409 lease.changed
 * the wizard re-reads the draft; keeping the header it had would write those stale
 * dates and payment terms back with the new version on the next save.
 */
function termsFromLease(lease: LeaseDetail): Terms {
    return {
        agreementDate: (lease.agreementDate || "").slice(0, 10),
        contractDate: (lease.contractDate || "").slice(0, 10),
        startDate: (lease.startDate || "").slice(0, 10),
        endDate: (lease.endDate || "").slice(0, 10),
        // An inherited grace stays null so it keeps inheriting (as the draft editor does).
        gracePeriodDays: lease.gracePeriodOverridden === false ? null : lease.gracePeriodDays ?? null,
        paymentTerms: lease.paymentTerms ?? initialTerms.paymentTerms,
        firstDueDate: (lease.firstDueDate || "").slice(0, 10),
        installmentDistribution: lease.installmentDistribution ?? initialTerms.installmentDistribution,
        paymentMethod: asDraftMethod(lease.paymentMethod),
        depositPaymentMethod: asDraftMethod(lease.depositPaymentMethod),
        ejariNumber: lease.ejariNumber ?? "",
        paymentReferenceNumber: lease.paymentReferenceNumber ?? "",
        rentVatApplicable: !!lease.rentVatApplicable,
    };
}

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
    onClose: () => void;
    onCreated: () => void;
};

export default function LeaseWizard({ open, onClose, onCreated }: Props) {
    const t = useTranslations("Leasing");
    const tCommon = useTranslations("Common");
    const router = useRouter();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canPost = hasPermission(userRole, "canPostLeases");

    const [stepIdx, setStepIdx] = useState(0);
    const [unitId, setUnitId] = useState("");
    const [renterId, setRenterId] = useState("");
    // The picked rows: the pickers search the server, so the wizard keeps what
    // it later reads (property id, name and type; the renter's name) itself.
    const [selectedUnit, setSelectedUnit] = useState<UnitOption | null>(null);
    const [selectedRenter, setSelectedRenter] = useState<RenterOption | null>(null);
    const [terms, setTerms] = useState<Terms>(initialTerms);
    // Once the operator edits the contract date directly, stop following the
    // agreement date — see #45. Before that, they're the same field.
    const [contractDateTouched, setContractDateTouched] = useState(false);
    // Owner request (2026-09-29): the end date and the number of cheques follow the
    // start date while they still hold the value the wizard put there; a value the
    // user typed is theirs. Null = the user's own (or a saved draft's).
    const [autoEnd, setAutoEnd] = useState<string | null>(null);
    const [autoCount, setAutoCount] = useState<number | null>(initialTerms.paymentTerms);
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
    // Break round 1: a term over CONFIRM_TERM_YEARS asks "runs N years —
    // continue?" once per start/end pair; `longTermAck` is the pair confirmed.
    const [longTermPrompt, setLongTermPrompt] = useState<number | null>(null);
    const [longTermAck, setLongTermAck] = useState<string | null>(null);

    const reset = useCallback(() => {
        setStepIdx(0);
        setUnitId("");
        setRenterId("");
        setSelectedUnit(null);
        setSelectedRenter(null);
        setTerms({ ...initialTerms, contractDate: todayIso() });
        setContractDateTouched(false);
        setAutoEnd(null);
        setAutoCount(initialTerms.paymentTerms);
        setRows([blankLine(0)]);
        setLease(null);
        setCheques([]);
        setChequeNotice(null);
        setChequeError(null);
        setDry(null);
        setBusy(false);
        setError(null);
        setServerErrors([]);
        setLongTermPrompt(null);
        setLongTermAck(null);
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

    const propertyDefaultGrace = usePropertyDefaultGrace(selectedUnit?.propertyId ?? undefined);
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
            case "terms": {
                if (!terms.startDate || !terms.endDate) return t("errDatesRequired");
                if (terms.endDate <= terms.startDate) return t("errEndAfterStart");
                // Break-it R4 money4 F2: the contract date is the contract's posting
                // date; the server refuses one more than a year ahead (LEASE_POST).
                if (isBeyondManualPostingWindow(terms.contractDate)) {
                    return t("errContractDateTooFar", { max: formatDate(maxManualPostingDateIso()) });
                }
                // Same bound (and wording) as the backend's LeaseService.requireSaneTerm.
                if (termExceedsYears(terms.startDate, terms.endDate, MAX_TERM_YEARS)) {
                    return t("errTermTooLong", { max: MAX_TERM_YEARS });
                }
                // Owner request (2026-09-29): the rent for the full term is asked here.
                if (!(rentAmountOf(rows, chargeTypes) > 0)) return t("errRentRequired");
                // At most one cheque a month of the term.
                const maxCheques = termMonthsCeil(terms.startDate, terms.endDate);
                if (terms.paymentTerms > maxCheques) return t("errTooManyCheques", { max: maxCheques });
                return null;
            }
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
    /**
     * Break-it R2 F2/F3: the draft changed elsewhere since this wizard last read it
     * (409 lease.changed). Read it back — its lines, grid and (at review) its
     * figures — so the user reviews what is saved now rather than overwriting it.
     */
    const reloadChanged = async (id: string) => {
        const fresh = await leaseApi.get(id);
        setLease(fresh);
        // Review A M4: the header too — parties and terms — not only the lines.
        setTerms(termsFromLease(fresh));
        setContractDateTouched(true);
        setAutoEnd(null);
        setAutoCount(null);
        setLongTermAck(`${(fresh.startDate || "").slice(0, 10)}|${(fresh.endDate || "").slice(0, 10)}`);
        if (fresh.unitId !== unitId) {
            setUnitId(fresh.unitId);
            setSelectedUnit({
                id: fresh.unitId, unitNumber: fresh.unitIdentifier ?? "", propertyId: fresh.propertyId,
                propertyName: fresh.propertyName, propertyType: null, buildingId: null, buildingName: null, status: null,
            });
        }
        if (fresh.renterId !== renterId) {
            setRenterId(fresh.renterId);
            setSelectedRenter({ id: fresh.renterId, nameEn: fresh.renterName ?? "", nameAr: null, phone: null, email: null });
        }
        setRows(toRows(fresh.lines));
        setCheques(await leaseApi.cheques(id));
        if (STEPS[stepIdx].key === "review") setDry(await leaseApi.dryRunPost(id));
    };

    const saveLines = async () => {
        // Break-it round 1 (money) F1: a refused amount (1000.555, "1,5") reports 0;
        // it must not be saved as that 0 — take the user to it instead.
        if (focusFirstInvalidMoney(document)) return;
        setBusy(true);
        setError(null);
        setServerErrors([]);
        try {
            const had = cheques.length > 0;
            const saved = lease
                ? await leaseApi.updateDraft(lease.id, { ...body(), version: lease.version ?? null })
                : await leaseApi.createDraft(body());
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
            if (lease && isLeaseChanged(e)) {
                const m = serverText(tCommon, e) || (e as ApiError).message;
                setServerErrors([m]);
                setError(m);
                await reloadChanged(lease.id).catch(() => undefined);
            } else if (e instanceof ApiError) {
                setServerErrors([e.message]);
                setError(e.message);
            } else {
                setError(t("saveFailed"));
            }
        } finally {
            setBusy(false);
        }
    };

    const runCheques = async (fn: () => Promise<Cheque[] | ChequeWrite>) => {
        setBusy(true);
        setChequeError(null);
        try {
            const r = await fn();
            if (Array.isArray(r)) {
                setCheques(r);
            } else {
                setCheques(r.cheques);
                // Review A M3: the grid write moved the draft's version; the next save and the post name the new one.
                const version = r.version;
                if (version != null) setLease(prev => (prev ? withVersion(prev, version) : prev));
            }
            setChequeNotice(null);
        } catch (e) {
            if (lease && isLeaseChanged(e)) {
                setChequeError(serverText(tCommon, e) || (e as ApiError).message);
                await reloadChanged(lease.id).catch(() => undefined);
                return;
            }
            setChequeError(e instanceof ApiError ? e.message : t("saveFailed"));
        } finally {
            setBusy(false);
        }
    };

    const generate = (req: GenerateChequesRequest) => {
        if (!lease) return;
        runCheques(() => leaseApi.generateCheques(lease.id, req, lease.version));
    };
    const generateNumbers = (startingNumber: string) => {
        if (!lease) return;
        runCheques(() => leaseApi.generateChequeNumbers(lease.id, startingNumber));
    };
    /**
     * Owner requests (2026-09-29): what "Generate cheques" is asked for — the Terms
     * step's count, first due date (the start date when empty) and distribution, with
     * one-time charges folded into cheque 1 (the Cheques step's default). The Review
     * step's suggestion and "Use this schedule" use exactly this.
     */
    const chequeRequest = (): GenerateChequesRequest => ({
        installments: terms.paymentTerms,
        firstDueDate: terms.firstDueDate || terms.startDate || null,
        distribution: terms.installmentDistribution,
        foldDepositsAndFeesIntoFirst: true,
    });
    const applySchedule = async (req: GenerateChequesRequest) => {
        if (!lease) return;
        await runCheques(() => leaseApi.generateCheques(lease.id, req, lease.version));
        try {
            setDry(await leaseApi.dryRunPost(lease.id));
        } catch {
            // The review keeps its previous answer; Post re-checks anyway.
        }
    };
    // Owner request (2026-09-29): a count changed after cheques were generated is not
    // applied silently — the Cheques step asks before regenerating.
    const [keptCountFor, setKeptCountFor] = useState<number | null>(null);
    const rentRowCount = cheques.filter(c => c.rowKind === "RENT" || c.rowKind === "MIXED").length
        || cheques.filter(c => c.rowKind == null).length;
    const countChanged = !!lease && lease.status === "DRAFT" && cheques.length > 0
        && rentRowCount !== terms.paymentTerms && keptCountFor !== terms.paymentTerms;

    const saveCheques = () => {
        if (!lease || focusFirstInvalidMoney(document)) return;
        runCheques(() => leaseApi.saveCheques(lease.id, toChequeRows(cheques), lease.version));
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
            // Break-it R2 F2: the version the review step priced.
            const res = await leaseApi.post(lease.id, lease.version);
            onCreated();
            router.push(`/dashboard/leases/${res.lease.id}?posted=${encodeURIComponent(res.tcoEntryNumber)}`);
            onClose();
        } catch (e) {
            if (isLeaseChanged(e)) {
                setError(serverText(tCommon, e) || (e as ApiError).message);
                await reloadChanged(lease.id).catch(() => undefined);
                return;
            }
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
        if ("startDate" in next || "endDate" in next) setLongTermPrompt(null);
    };
    const termKey = `${terms.startDate}|${terms.endDate}`;

    /** The count that follows the term while it is automatic. */
    const followCount = (start: string, end: string): Partial<Terms> => {
        if (autoCount === null || terms.paymentTerms !== autoCount || !start || !end || end < start) return {};
        const n = termMonthsCeil(start, end);
        setAutoCount(n);
        return { paymentTerms: n };
    };
    const onStartChange = (startDate: string) => {
        const endIsAuto = !terms.endDate || terms.endDate === autoEnd;
        const endDate = endIsAuto ? defaultTermEnd(startDate) : terms.endDate;
        setAutoEnd(endIsAuto ? endDate : autoEnd);
        patch({ startDate, endDate, ...followCount(startDate, endDate) });
    };
    const onEndChange = (endDate: string) => {
        setAutoEnd(null);
        patch({ endDate, ...followCount(terms.startDate, endDate) });
    };
    const rentAmount = rentAmountOf(rows, chargeTypes);
    const setRentAmount = (amount: number) => {
        setRows(prev => withRentAmount(prev, chargeTypes, amount, terms.rentVatApplicable));
        setError(null);
    };
    // #54: the header's "Rent carries VAT" flag drives every RENT line's VAT
    // box — when the flag changes (here, or via the unit's property type), and
    // when a line is pointed at a RENT charge. A line whose own box the
    // operator ticked or unticked afterwards is `vatTouched` and keeps that
    // choice through later header changes (M-3), until its charge type is
    // picked again.
    const setRentVat = (rentVatApplicable: boolean) => {
        patch({ rentVatApplicable });
        setRows(prev => withRentVat(prev, chargeTypes, rentVatApplicable));
    };
    const onLinesChange = (next: LineRow[]) =>
        setRows(prev => followRentVat(prev, next, chargeTypes, terms.rentVatApplicable));

    const goNext = () => {
        const e = stepError(stepIdx);
        if (e) {
            setError(e);
            return;
        }
        setError(null);
        if (step.key === "terms" && longTermAck !== termKey
            && termExceedsYears(terms.startDate, terms.endDate, CONFIRM_TERM_YEARS)) {
            setLongTermPrompt(termYears(terms.startDate, terms.endDate));
            return;
        }
        setLongTermPrompt(null);
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
                                <UnitPicker
                                    status="VACANT"
                                    value={unitId}
                                    onChange={(id, u) => {
                                        setUnitId(id);
                                        setSelectedUnit(u);
                                        setRentVat(u?.propertyType === "COMMERCIAL");
                                    }}
                                    placeholder={t("unit")}
                                    testId="wizard-unit"
                                />
                                {selectedUnit && (
                                    <p data-testid="wizard-unit-property" className="text-[11px] text-muted mt-1.5 flex items-center gap-1">
                                        <Building2 size={11} /> {selectedUnit.propertyName} • {selectedUnit.propertyType || "RESIDENTIAL"}
                                    </p>
                                )}
                            </Field>
                            <Field label={`${t("renter")} *`}>
                                <RenterPicker
                                    value={renterId}
                                    onChange={(id, r) => {
                                        setRenterId(id);
                                        setSelectedRenter(r);
                                    }}
                                    placeholder={t("renter")}
                                    testId="wizard-renter"
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
                                                : { agreementDate: value, contractDate: value || todayIso() },
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
                                    max={maxManualPostingDateIso()}
                                    onChange={e => {
                                        setContractDateTouched(true);
                                        patch({ contractDate: e.target.value });
                                    }}
                                />
                            </Field>
                            <Field label={`${t("startDate")} *`}>
                                <input type="date" data-testid="wizard-start-date" className={field} value={terms.startDate} onChange={e => onStartChange(e.target.value)} />
                            </Field>
                            <Field label={`${t("endDate")} *`}>
                                <input type="date" data-testid="wizard-end-date" className={field} value={terms.endDate} onChange={e => onEndChange(e.target.value)} />
                            </Field>
                            <Field label={`${t("rentFullTerm")} *`}>
                                <NumberInput money data-testid="wizard-rent" className={field} value={rentAmount}
                                    aria-label={t("rentFullTerm")} onChange={v => setRentAmount(v)} />
                            </Field>
                            <Field label={t("gracePeriodDays")}>
                                <GraceDaysField className={field} value={terms.gracePeriodDays} propertyDefault={propertyDefaultGrace} onChange={v => patch({ gracePeriodDays: v })} />
                            </Field>
                            <Field label={t("paymentTerms")}>
                                <NumberInput showZero min={1}
                                    max={terms.startDate && terms.endDate ? termMonthsCeil(terms.startDate, terms.endDate) : 36}
                                    data-testid="wizard-payment-terms" className={field} value={terms.paymentTerms}
                                    onChange={v => { setAutoCount(null); patch({ paymentTerms: Math.max(1, v) }); }} />
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
                                <input type="checkbox" data-testid="wizard-rent-vat" checked={terms.rentVatApplicable} onChange={e => setRentVat(e.target.checked)} />
                                {t("rentVat")}
                            </label>
                        </div>
                    )}

                    {step.key === "lines" && (
                        <div className="space-y-3">
                            <LeaseLinesGrid
                                lines={rows}
                                chargeTypes={chargeTypes}
                                propertyId={selectedUnit?.propertyId ?? null}
                                editable
                                onChange={onLinesChange}
                                errors={serverErrors}
                                rentVat={terms.rentVatApplicable}
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
                            {countChanged && (
                                <div role="alert" data-testid="wizard-regenerate-prompt"
                                    className="flex flex-wrap items-center gap-2 rounded-lg border border-warning/40 bg-warning/5 px-3 py-2 text-[11px] text-warning">
                                    <AlertTriangle size={12} />
                                    <span>{t("regenerateChequesPrompt", { count: terms.paymentTerms, had: rentRowCount })}</span>
                                    <button type="button" data-testid="wizard-regenerate-confirm" disabled={busy}
                                        onClick={() => generate(chequeRequest())}
                                        className="px-2.5 py-1 rounded-md bg-primary text-primary-foreground font-semibold cursor-pointer disabled:opacity-50">
                                        {t("regenerateCheques")}
                                    </button>
                                    <button type="button" data-testid="wizard-regenerate-keep" onClick={() => setKeptCountFor(terms.paymentTerms)}
                                        className="px-2.5 py-1 rounded-md border border-border text-foreground font-semibold cursor-pointer">
                                        {t("keepCheques")}
                                    </button>
                                </div>
                            )}
                            <ChequeGrid
                                cheques={cheques}
                                editable
                                onChange={setCheques}
                                onGenerate={generate}
                                onGenerateNumbers={generateNumbers}
                                propertyId={lease.propertyId}
                                contractValueInclVat={totals.inclVat}
                                contractVat={totals.vat}
                                defaultInstallments={terms.paymentTerms}
                                defaultFirstDueDate={terms.firstDueDate || terms.startDate}
                                defaultDistribution={terms.installmentDistribution}
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
                                <Summary label={t("unit")} value={selectedUnit ? `${selectedUnit.unitNumber} • ${selectedUnit.propertyName ?? ""}` : lease.unitIdentifier ?? "—"} />
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

                            <WizardReviewSchedules
                                leaseId={lease.id}
                                cheques={cheques}
                                request={chequeRequest()}
                                firstDueDefaulted={!terms.firstDueDate}
                                onUseSchedule={req => { void applySchedule(req); }}
                                busy={busy}
                            />
                            {chequeError && <p role="alert" className="text-xs text-error">{chequeError}</p>}

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
                    ) : longTermPrompt !== null && step.key === "terms" ? (
                        <span className="inline-flex flex-wrap items-center gap-2 text-[11px] text-warning" role="alert" data-testid="wizard-long-term-confirm">
                            <AlertTriangle size={12} /> {t("longTermConfirm", { years: longTermPrompt })}
                            <button
                                type="button"
                                data-testid="wizard-long-term-continue"
                                onClick={() => {
                                    setLongTermAck(termKey);
                                    setLongTermPrompt(null);
                                    setStepIdx(i => Math.min(i + 1, STEPS.length - 1));
                                }}
                                className="px-2.5 py-1 rounded-md border border-warning/40 font-semibold text-foreground hover:bg-warning/10 cursor-pointer"
                            >
                                {t("longTermContinue")}
                            </button>
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

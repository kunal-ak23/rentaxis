"use client";

import { useEffect, useRef, useState } from "react";
import { useTranslations, useLocale } from "next-intl";
import { X, Loader2, Wallet } from "lucide-react";

type Account = {
    id: string;
    code: string;
    name: string;
    accountType: string;
};

type Vendor = {
    id: string;
    nameEn: string;
    nameAr?: string | null;
    active?: boolean;
};

type Property = {
    id: string;
    nameEn: string;
};

type UnitData = {
    id: string;
    unitNumber: string;
};

type Props = {
    open: boolean;
    onClose: () => void;
    onSuccess: () => void;
    vendor?: Vendor | null;
    vendors?: Vendor[];
};

const DIALOG_TITLE_ID = "vendor-payment-dialog-title";

export default function VendorPaymentDialog({ open, onClose, onSuccess, vendor, vendors }: Props) {
    const t = useTranslations("Vendors");
    const locale = useLocale();
    const dialogRef = useRef<HTMLDivElement | null>(null);
    const onCloseRef = useRef(onClose);
    const submittingRef = useRef(false);

    const vendorDisplayName = (v: { nameEn?: string | null; nameAr?: string | null } | null | undefined) => {
        if (!v) return "";
        return locale === "ar" ? (v.nameAr || v.nameEn || "") : (v.nameEn || v.nameAr || "");
    };

    const [accounts, setAccounts] = useState<Account[]>([]);
    const [vendorList, setVendorList] = useState<Vendor[]>(vendors ?? []);
    const [properties, setProperties] = useState<Property[]>([]);
    const [units, setUnits] = useState<UnitData[]>([]);

    const [vendorId, setVendorId] = useState<string>(vendor?.id ?? "");
    const [accountId, setAccountId] = useState<string>("");
    const [date, setDate] = useState<string>(new Date().toISOString().split("T")[0]);
    const [amount, setAmount] = useState<string>("");
    const [description, setDescription] = useState<string>("");
    const [propertyId, setPropertyId] = useState<string>("");
    const [unitId, setUnitId] = useState<string>("");
    const [vatApplicable, setVatApplicable] = useState<boolean>(false);
    const [vatRate, setVatRate] = useState<string>("5");
    const [notes, setNotes] = useState<string>("");

    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<string | null>(null);

    useEffect(() => { onCloseRef.current = onClose; }, [onClose]);
    useEffect(() => { submittingRef.current = submitting; }, [submitting]);

    // Reset form whenever the dialog opens.
    useEffect(() => {
        if (!open) return;
        setVendorId(vendor?.id ?? "");
        setAccountId("");
        setDate(new Date().toISOString().split("T")[0]);
        setAmount("");
        const name = vendorDisplayName(vendor);
        setDescription(name ? t("defaultDescription", { name }) : "");
        setPropertyId("");
        setUnitId("");
        setVatApplicable(false);
        setVatRate("5");
        setNotes("");
        setError(null);
        // vendorDisplayName + t are stable per render — deps focused on the
        // signals that should drive a reset.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [open, vendor, locale]);

    // Fetch reference data once dialog opens.
    useEffect(() => {
        if (!open) return;
        (async () => {
            try {
                const [accRes, propRes] = await Promise.all([
                    fetch("/api/proxy/v1/finance/accounts"),
                    fetch("/api/proxy/v1/properties"),
                ]);
                if (accRes.ok) {
                    const all: Account[] = await accRes.json();
                    setAccounts(all.filter(a => a.accountType === "EXPENSE"));
                }
                if (propRes.ok) {
                    const data = await propRes.json();
                    // /api/v1/properties returns wrapped {property,...} objects in this app.
                    const flat = Array.isArray(data)
                        ? data.map((p: any) => p?.property ?? p).filter(Boolean)
                        : [];
                    setProperties(flat);
                }
            } catch (err) {
                console.error(err);
            }
            // Only fetch vendors when caller did not supply them.
            if (!vendors) {
                try {
                    const res = await fetch("/api/proxy/v1/vendors");
                    if (res.ok) {
                        const data: Vendor[] = await res.json();
                        setVendorList(data.filter(v => v.active !== false));
                    }
                } catch (err) {
                    console.error(err);
                }
            } else {
                setVendorList(vendors);
            }
        })();
    }, [open, vendors]);

    useEffect(() => {
        if (!propertyId) { setUnits([]); setUnitId(""); return; }
        (async () => {
            try {
                const res = await fetch(`/api/proxy/v1/units/property/${propertyId}`);
                if (res.ok) setUnits(await res.json());
            } catch (err) {
                console.error(err);
            }
        })();
    }, [propertyId]);

    // A11y: Escape to close + simple focus trap.
    useEffect(() => {
        if (!open) return;
        const previouslyFocused = (typeof document !== "undefined" ? document.activeElement : null) as HTMLElement | null;
        const onKey = (e: KeyboardEvent) => {
            if (e.key === "Escape" && !submittingRef.current) {
                e.preventDefault();
                onCloseRef.current();
            }
        };
        window.addEventListener("keydown", onKey);
        return () => {
            window.removeEventListener("keydown", onKey);
            previouslyFocused?.focus?.();
        };
    }, [open]);

    if (!open) return null;

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        setError(null);
        if (!vendorId) { setError(t("vendorRequired")); return; }
        if (!accountId) { setError(t("accountRequired")); return; }
        const amt = Number(amount);
        if (!amt || amt <= 0) { setError(t("amountRequired")); return; }

        const trimmedDescription = description.trim();
        if (!trimmedDescription) { setError(t("descriptionRequired")); return; }

        setSubmitting(true);
        try {
            const netAmount = amt;
            const vatRateNum = vatApplicable ? Number(vatRate) || 0 : 0;
            const vatAmount = vatApplicable
                ? Math.round(amt * vatRateNum) / 100
                : 0;
            const grossAmount = vatApplicable ? amt + vatAmount : amt;

            const body: Record<string, unknown> = {
                date,
                description: trimmedDescription,
                account: { id: accountId },
                debit: amt,
                credit: 0,
                vendor: { id: vendorId },
                vatApplicable,
                vatAmount,
                vatRate: vatRateNum,
                netAmount: vatApplicable ? netAmount : 0,
                grossAmount: vatApplicable ? grossAmount : 0,
                notes,
            };
            if (propertyId) body.property = { id: propertyId };
            if (unitId) body.unit = { id: unitId };

            const res = await fetch("/api/proxy/v1/finance/transactions", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            if (!res.ok) {
                const txt = await res.text().catch(() => "");
                throw new Error(txt || `HTTP ${res.status}`);
            }
            onSuccess();
        } catch (err: unknown) {
            setError(err instanceof Error && err.message ? err.message : t("paymentFailed"));
        } finally {
            setSubmitting(false);
        }
    };

    const lockedVendor = !!vendor;

    return (
        <div className="fixed inset-0 z-[100] bg-foreground/40 backdrop-blur-sm flex items-start justify-center pt-20 p-4">
            <div
                ref={dialogRef}
                role="dialog"
                aria-modal="true"
                aria-labelledby={DIALOG_TITLE_ID}
                className="bg-surface rounded-xl p-8 w-full max-w-2xl shadow-2xl border border-border relative max-h-[85vh] overflow-y-auto"
            >
                <button
                    type="button"
                    onClick={onClose}
                    disabled={submitting}
                    aria-label={t("cancel")}
                    className="absolute right-6 top-6 p-2 text-muted hover:text-foreground cursor-pointer transition-all duration-200 rounded-lg disabled:opacity-50"
                >
                    <X size={18} />
                </button>
                <h2 id={DIALOG_TITLE_ID} className="text-lg font-bold text-foreground mb-1 flex items-center gap-2">
                    <Wallet size={18} className="text-primary" />
                    {t("vendorPayment")}
                </h2>
                <p className="text-xs text-muted mb-6">{t("vendorPaymentDesc")}</p>

                <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                    <div className="col-span-2">
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                            {t("vendor")}
                        </label>
                        {lockedVendor ? (
                            <input
                                readOnly
                                value={vendorDisplayName(vendor)}
                                className="w-full border border-border rounded-lg bg-input p-3 text-xs text-foreground"
                            />
                        ) : (
                            <select
                                required
                                value={vendorId}
                                onChange={ev => setVendorId(ev.target.value)}
                                className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                            >
                                <option value="">{t("selectVendor")}</option>
                                {vendorList.map(v => (
                                    <option key={v.id} value={v.id}>{vendorDisplayName(v)}</option>
                                ))}
                            </select>
                        )}
                    </div>

                    <div>
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                            {t("date")}
                        </label>
                        <input
                            type="date"
                            required
                            value={date}
                            onChange={ev => setDate(ev.target.value)}
                            className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                        />
                    </div>

                    <div>
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                            {t("amount")}
                        </label>
                        <input
                            type="number"
                            min="0.01"
                            step="0.01"
                            required
                            value={amount}
                            onChange={ev => setAmount(ev.target.value)}
                            placeholder="0.00"
                            className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                        />
                    </div>

                    <div className="col-span-2">
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                            {t("expenseAccount")}
                        </label>
                        <select
                            required
                            value={accountId}
                            onChange={ev => setAccountId(ev.target.value)}
                            className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                        >
                            <option value="">{t("selectExpenseAccount")}</option>
                            {accounts.map(a => (
                                <option key={a.id} value={a.id}>{a.code} - {a.name}</option>
                            ))}
                        </select>
                    </div>

                    <div>
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                            {t("property")}
                        </label>
                        <select
                            value={propertyId}
                            onChange={ev => { setPropertyId(ev.target.value); setUnitId(""); }}
                            className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                        >
                            <option value="">{t("organisationLevel")}</option>
                            {properties.map(p => (
                                <option key={p.id} value={p.id}>{p.nameEn}</option>
                            ))}
                        </select>
                    </div>

                    <div>
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                            {t("unit")}
                        </label>
                        <select
                            value={unitId}
                            onChange={ev => setUnitId(ev.target.value)}
                            disabled={!propertyId}
                            className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none disabled:opacity-50"
                        >
                            <option value="">{t("propertyLevel")}</option>
                            {units.map(u => (
                                <option key={u.id} value={u.id}>{u.unitNumber}</option>
                            ))}
                        </select>
                    </div>

                    <div className="col-span-2">
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                            {t("descriptionLabel")}
                        </label>
                        <input
                            required
                            value={description}
                            onChange={ev => setDescription(ev.target.value)}
                            placeholder={t("descriptionPlaceholder")}
                            className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none"
                        />
                    </div>

                    <div className="flex items-center gap-3 pt-2">
                        <label className="flex items-center gap-2 cursor-pointer">
                            <input
                                type="checkbox"
                                checked={vatApplicable}
                                onChange={ev => setVatApplicable(ev.target.checked)}
                                className="rounded border-border"
                            />
                            <span className="text-xs font-bold text-muted">{t("vatApplicable")}</span>
                        </label>
                    </div>
                    <div>
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                            {t("vatRate")}
                        </label>
                        <input
                            type="number"
                            step="0.01"
                            min="0"
                            value={vatRate}
                            onChange={ev => setVatRate(ev.target.value)}
                            disabled={!vatApplicable}
                            className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none disabled:opacity-50"
                        />
                    </div>

                    <div className="col-span-2">
                        <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">
                            {t("notes")}
                        </label>
                        <textarea
                            value={notes}
                            onChange={ev => setNotes(ev.target.value)}
                            rows={2}
                            className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none resize-none"
                        />
                    </div>

                    {error && (
                        <div className="col-span-2 bg-error/10 border border-error/20 rounded-lg px-4 py-3 text-xs text-error font-medium" role="alert">
                            {error}
                        </div>
                    )}

                    <div className="col-span-2 flex justify-end gap-3 mt-2">
                        <button
                            type="button"
                            onClick={onClose}
                            disabled={submitting}
                            className="bg-surface text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-input transition-all disabled:opacity-50"
                        >
                            {t("cancel")}
                        </button>
                        <button
                            type="submit"
                            disabled={submitting}
                            className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all disabled:opacity-50 flex items-center gap-2"
                        >
                            {submitting && <Loader2 size={14} className="animate-spin" />}
                            {submitting ? t("saving") : t("save")}
                        </button>
                    </div>
                </form>
            </div>
        </div>
    );
}

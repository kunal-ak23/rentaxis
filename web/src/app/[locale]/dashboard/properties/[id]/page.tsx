"use client";

import { useState, useEffect } from "react";
import { useParams, useRouter } from "next/navigation";
import { useTranslations, useLocale } from "next-intl";
import { Link } from "@/i18n/routing";
import { Building2, Home, FileText, ArrowLeft, Plus, MapPin, Upload, Calendar, DollarSign, Settings, Wrench, Zap, Hammer, Shield, Hospital, Pill, Siren, HelpCircle, Phone, Mail, Pencil, Trash2, Dumbbell, Car, BookOpen } from "lucide-react";
import { AmenitiesTab } from "./_components/AmenitiesTab";
import { ParkingTab } from "./_components/ParkingTab";
import PropertyAccountsTab from "@/components/finance/PropertyAccountsTab";
import { cn } from "@/lib/utils";
import { useSession } from "next-auth/react";
import { hasPermission, canConfigureRentSettings, type UserRole } from "@/lib/rbac";
import { formatCurrency, formatCurrencyCompact } from "@/lib/format";
import { ApiError, throwIfNotOk } from "@/lib/api/facilities";
import { NumberInput, MoneyTextInput, focusFirstInvalidMoney } from "@/components/ui/NumberInput";
import { MONEY_MAX_12_2, moneyValueOrNull } from "@/lib/money";
import { AccessDeniedState, LoadFailedState, NotFoundState } from "@/components/ui/PageStates";
import { BUILDING_FLOORS_MAX, BUILDING_FLOORS_MIN, floorsInRange, refusalOf, sizeIsValid } from "@/lib/units/unitRules";
import { useLatestRequest, type RequestTicket } from "@/hooks/useLatestRequest";
import { isAbortError } from "@/lib/api/abort";

type PropertyContact = {
    id: string;
    propertyId: string;
    category: string;
    customLabel?: string;
    name: string;
    phone: string;
    email?: string;
    address?: string;
    notes?: string;
    sortOrder: number;
};

const CONTACT_CATEGORIES = [
    { value: 'PLUMBER', label: 'Plumber', icon: 'Wrench' },
    { value: 'ELECTRICIAN', label: 'Electrician', icon: 'Zap' },
    { value: 'HANDYMAN', label: 'Handyman', icon: 'Hammer' },
    { value: 'SECURITY', label: 'Security', icon: 'Shield' },
    { value: 'HOSPITAL_CLINIC', label: 'Hospital / Clinic', icon: 'Hospital' },
    { value: 'PHARMACY', label: 'Pharmacy', icon: 'Pill' },
    { value: 'BUILDING_MAINTENANCE', label: 'Building Maintenance', icon: 'Building2' },
    { value: 'CIVIL_DEFENSE', label: 'Civil Defense', icon: 'Siren' },
    { value: 'OTHER', label: 'Other', icon: 'HelpCircle' },
] as const;

function getCategoryIcon(category: string) {
    const map: Record<string, React.ElementType> = {
        PLUMBER: Wrench,
        ELECTRICIAN: Zap,
        HANDYMAN: Hammer,
        SECURITY: Shield,
        HOSPITAL_CLINIC: Hospital,
        PHARMACY: Pill,
        BUILDING_MAINTENANCE: Building2,
        CIVIL_DEFENSE: Siren,
        OTHER: HelpCircle,
    };
    return map[category] || HelpCircle;
}

function getCategoryLabel(category: string, customLabel: string | undefined, tc: (key: string) => string, has: (key: string) => boolean) {
    if (category === 'OTHER' && customLabel) return customLabel;
    // The category's translated name; its English label only for a value the catalog does not know.
    const key = `categories.${category}`;
    return has(key) ? tc(key) : CONTACT_CATEGORIES.find(c => c.value === category)?.label || category;
}

const EMPTY_CONTACT_FORM = { category: 'PLUMBER', customLabel: '', name: '', phone: '', email: '', address: '', notes: '' };

export default function PropertyDetailPage() {
    const params = useParams();
    const router = useRouter();
    const t = useTranslations("MasterData");
    const e = useTranslations("Emirates");
    const tOnlinePayments = useTranslations("OnlinePayments");
    const tFacilities = useTranslations("Facilities");
    const tLedger = useTranslations("Ledger");
    const tNav = useTranslations("Navigation");
    const tState = useTranslations("PageState");
    const tc = useTranslations("PropertyContacts");
    const categoryLabel = (category: string, customLabel?: string) =>
        getCategoryLabel(category, customLabel, (k) => tc(k as never), (k) => tc.has(k as never));
    const locale = useLocale();
    const propertyId = params.id as string;

    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const canCreate = hasPermission(userRole, 'canCreateProperties');
    const canManageRentSettings = userRole ? canConfigureRentSettings(userRole) : false;
    const canManageFacilities = hasPermission(userRole, 'canManageFacilities');
    const canManageAccountSetup = hasPermission(userRole, 'canManageAccountSetup');
    // PropertyController's managers endpoint stays SA/TA/PM-only even though
    // ACCOUNTANT now reads the property itself — see rbac.ts's
    // canViewPropertyManagers. Gate both the fetch and the card on it so an
    // ACCOUNTANT visit doesn't log a 403 on every load.
    const canViewManagers = hasPermission(userRole, 'canViewPropertyManagers');

    const [activeTab, setActiveTab] = useState<"overview" | "buildings" | "units" | "leases" | "amenities" | "parking" | "accounts">("overview");
    const [property, setProperty] = useState<any>(null);
    const [propertyLoad, setPropertyLoad] = useState<"loading" | "ok" | "notFound" | "forbidden" | "failed">("loading");
    const [buildings, setBuildings] = useState<any[]>([]);
    const [units, setUnits] = useState<any[]>([]);
    const [managers, setManagers] = useState<any[]>([]);
    const [contacts, setContacts] = useState<PropertyContact[]>([]);
    const [contactDeleteError, setContactDeleteError] = useState(false);
    const [showContactForm, setShowContactForm] = useState(false);
    const [editingContact, setEditingContact] = useState<PropertyContact | null>(null);
    const [contactFormData, setContactFormData] = useState(EMPTY_CONTACT_FORM);
    const [contactSubmitting, setContactSubmitting] = useState(false);
    const [contactFormError, setContactFormError] = useState<string | null>(null);

    useEffect(() => {
        fetchProperty();
        fetchBuildings();
        fetchUnits();
        if (canViewManagers) fetchManagers();
        fetchContacts();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [propertyId, canViewManagers]);

    // Break round 1, F5: a 404/403/500 (or a network failure) used to leave
    // `property` null, and the page kept its skeleton forever. The load now
    // ends in one of: loaded, not found, access denied, or failed (retry).
    // Break round 3, F1: each load is aborted on unmount and superseded by a
    // newer one, silently; a real failure still lands in its state.
    const beginProperty = useLatestRequest();
    const beginBuildings = useLatestRequest();
    const beginUnits = useLatestRequest();
    const beginManagers = useLatestRequest();
    const beginContacts = useLatestRequest();

    const fetchProperty = async () => {
        const { signal, isCurrent } = beginProperty();
        setPropertyLoad("loading");
        try {
            const res = await fetch(`/api/proxy/v1/properties/${propertyId}`, { signal });
            if (!isCurrent()) return;
            if (res.ok) {
                const body = await res.json();
                if (!isCurrent()) return;
                setProperty(body);
                setPropertyLoad("ok");
            } else if (res.status === 404 || res.status === 400) {
                setPropertyLoad("notFound");
            } else if (res.status === 403) {
                setPropertyLoad("forbidden");
            } else {
                setPropertyLoad("failed");
            }
        } catch (err) {
            if (isAbortError(err) || !isCurrent()) return;
            setPropertyLoad("failed");
        }
    };

    // Secondary reads: a failure leaves the tab empty rather than breaking the page.
    const fetchList = async <T,>(ticket: RequestTicket, url: string, set: (rows: T[]) => void) => {
        const { signal, isCurrent } = ticket;
        try {
            const res = await fetch(url, { signal });
            if (!res.ok) return;
            const rows = await res.json();
            if (isCurrent()) set(rows);
        } catch (err) {
            if (isAbortError(err) || !isCurrent()) return;
            console.error("Failed to fetch", url, err);
        }
    };

    const fetchBuildings = () => fetchList(beginBuildings(), `/api/proxy/v1/buildings/property/${propertyId}`, setBuildings);

    const fetchUnits = () => fetchList(beginUnits(), `/api/proxy/v1/units/property/${propertyId}`, setUnits);

    const fetchManagers = () => fetchList(beginManagers(), `/api/proxy/v1/properties/${propertyId}/managers`, setManagers);

    const fetchContacts = () => fetchList<PropertyContact>(beginContacts(), `/api/proxy/v1/properties/${propertyId}/contacts`, setContacts);

    const openAddContact = () => {
        setEditingContact(null);
        setContactFormData(EMPTY_CONTACT_FORM);
        setContactFormError(null);
        setShowContactForm(true);
    };

    const openEditContact = (contact: PropertyContact) => {
        setEditingContact(contact);
        setContactFormData({
            category: contact.category,
            customLabel: contact.customLabel || '',
            name: contact.name,
            phone: contact.phone,
            email: contact.email || '',
            address: contact.address || '',
            notes: contact.notes || '',
        });
        setContactFormError(null);
        setShowContactForm(true);
    };

    const handleContactSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        setContactSubmitting(true);
        setContactFormError(null);
        try {
            const payload = {
                ...contactFormData,
                propertyId,
                sortOrder: editingContact ? editingContact.sortOrder : contacts.length,
            };
            const url = editingContact
                ? `/api/proxy/v1/properties/${propertyId}/contacts/${editingContact.id}`
                : `/api/proxy/v1/properties/${propertyId}/contacts`;
            const res = await fetch(url, {
                method: editingContact ? 'PUT' : 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload),
            });
            await throwIfNotOk(res);
            setShowContactForm(false);
            setEditingContact(null);
            setContactFormData(EMPTY_CONTACT_FORM);
            fetchContacts();
        } catch (err) {
            setContactFormError(err instanceof ApiError ? err.message : tc("saveFailed"));
        } finally {
            setContactSubmitting(false);
        }
    };

    const handleDeleteContact = async (contactId: string) => {
        if (!confirm(tc("deleteConfirm"))) return;
        // Break-it R2 sweep: a refused delete used to leave the card with no word.
        setContactDeleteError(false);
        try {
            const res = await fetch(`/api/proxy/v1/properties/${propertyId}/contacts/${contactId}`, { method: 'DELETE' });
            if (res.ok) fetchContacts();
            else setContactDeleteError(true);
        } catch {
            setContactDeleteError(true);
        }
    };

    if (propertyLoad === "notFound") {
        return <NotFoundState message={tState("propertyNotFound")} backHref="/dashboard/properties" backLabel={tState("backToProperties")} />;
    }
    if (propertyLoad === "forbidden") return <AccessDeniedState />;
    if (propertyLoad === "failed") return <LoadFailedState onRetry={fetchProperty} />;

    if (!property) return (
        <div className="p-8 max-w-7xl mx-auto space-y-6">
            <div className="h-4 w-32 bg-input rounded animate-pulse" />
            <div className="bg-surface rounded-xl p-8 border border-border">
                <div className="h-6 w-64 bg-input rounded animate-pulse mb-3" />
                <div className="h-4 w-48 bg-background rounded animate-pulse" />
            </div>
            <div className="flex gap-6 border-b border-border pb-4">
                {[1, 2, 3, 4].map(i => (
                    <div key={i} className="h-4 w-20 bg-input rounded animate-pulse" />
                ))}
            </div>
            <div className="grid grid-cols-3 gap-4">
                {[1, 2, 3].map(i => (
                    <div key={i} className="h-24 bg-background rounded-xl animate-pulse" />
                ))}
            </div>
        </div>
    );

    const displayName = locale === "ar" && property.nameAr ? property.nameAr : property.nameEn;

    return (
        <div className="p-8 max-w-7xl mx-auto">
            <button
                onClick={() => router.back()}
                className="flex items-center gap-2 text-xs font-bold text-muted hover:text-foreground mb-6 transition-colors cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded"
            >
                <ArrowLeft size={14} /> Back to Projects
            </button>

            <div className="bg-surface rounded-xl p-8 border border-border mb-8">
                <div className="flex items-start justify-between">
                    <div>
                        <div className="flex items-center gap-3 mb-2">
                            <h1 className="text-2xl font-bold text-foreground tracking-tight">{displayName}</h1>
                            <span className="text-[10px] font-bold uppercase tracking-widest px-3 py-1 bg-primary/10 text-primary rounded-lg border border-primary/20">
                                {property.type && t.has(`propertyType${property.type}`) ? t(`propertyType${property.type}`) : property.type}
                            </span>
                        </div>
                        <p className="text-sm font-medium text-muted flex items-center gap-2">
                            <MapPin size={14} className="text-muted" />
                            {property.address ? `${property.address}, ` : ""}{e(property.emirate)}
                            {property.makaniNumber && ` • Makani: ${property.makaniNumber}`}
                        </p>
                    </div>
                    {canManageRentSettings && (
                        <Link
                            href="/dashboard/settings?section=rent"
                            className="flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold border border-border text-muted hover:bg-background hover:text-foreground transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Settings size={14} />
                            {tOnlinePayments("rentSettings")}
                        </Link>
                    )}
                </div>
            </div>

            {/* Tabs */}
            <div className="flex items-center gap-6 mb-8 border-b border-border">
                {[
                    { id: "overview", label: t("tabOverview"), icon: Home },
                    { id: "buildings", label: t("tabBuildings"), icon: Building2 },
                    { id: "units", label: t("tabUnits"), icon: Home },
                    { id: "leases", label: t("tabContracts"), icon: FileText },
                    // Gated behind the same canManageFacilities flag passed as
                    // `canManage` to AmenitiesTab/ParkingTab below, so the nav
                    // tabs and the in-tab edit controls can't drift out of sync.
                    ...(canManageFacilities ? [
                        { id: "amenities", label: tFacilities("amenitiesTab"), icon: Dumbbell },
                        { id: "parking", label: tFacilities("parkingTab"), icon: Car },
                    ] : []),
                    // Same flag the AccountController/PropertyAccountController
                    // enforce — a role that can't read the mappings shouldn't be
                    // offered a tab that only 403s.
                    ...(canManageAccountSetup ? [
                        { id: "accounts", label: tLedger("propertyAccounts"), icon: BookOpen },
                    ] : []),
                ].map(tab => {
                    const Icon = tab.icon;
                    const isActive = activeTab === tab.id;
                    return (
                        <button
                            key={tab.id}
                            onClick={() => setActiveTab(tab.id as any)}
                            className={cn(
                                "flex items-center gap-2 pb-4 text-sm font-bold transition-all duration-200 relative cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded",
                                isActive ? "text-primary" : "text-muted hover:text-foreground"
                            )}
                        >
                            <Icon size={16} />
                            {tab.label}
                            {isActive && (
                                <div className="absolute bottom-0 left-0 right-0 h-0.5 bg-primary rounded-t-full" />
                            )}
                        </button>
                    );
                })}
            </div>

            {/* Content areas */}
            {activeTab === "overview" && (<>
                {canViewManagers && (
                    <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
                        <div className="bg-background rounded-xl p-6 border border-border col-span-1 md:col-span-2">
                            <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-4 flex items-center gap-2">
                                <Building2 size={12} className="text-primary/40" />
                                {t("propertyManager")}
                            </p>
                            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                                {managers.length > 0 ? managers.map(m => (
                                    <div key={m.id} className="bg-surface p-4 rounded-xl border border-border hover:shadow-md transition-all duration-200 flex flex-col gap-1">
                                        <p className="text-sm font-bold text-foreground">{m.name}</p>
                                        <p className="text-[11px] font-bold text-muted">{m.email}</p>
                                        {m.phoneNumber && (
                                            <p className="text-[11px] font-bold text-primary/70 font-mono mt-1">{m.phoneNumber}</p>
                                        )}
                                    </div>
                                )) : (
                                    <p className="text-xs font-medium text-muted italic">No managers assigned.</p>
                                )}
                            </div>
                        </div>
                    </div>
                )}

                {/* Key Contacts Section */}
                <div className="mt-6">
                    <div className="bg-background rounded-xl p-6 border border-border">
                        <div className="flex items-center justify-between mb-4">
                            <p className="text-xs font-semibold text-muted uppercase tracking-[0.15em] flex items-center gap-2">
                                <Phone size={12} className="text-primary/40" />
                                {tc("title")}
                            </p>
                            {canCreate && (
                                <button
                                    onClick={openAddContact}
                                    className="flex items-center gap-2 bg-primary text-primary-foreground px-3 py-1.5 rounded-full text-[11px] font-bold hover:opacity-90 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                >
                                    <Plus size={12} /> {tc("add")}
                                </button>
                            )}
                        </div>

                        {contactDeleteError && (
                            <p role="alert" className="mb-3 text-xs text-error">{t("contactDeleteFailed")}</p>
                        )}
                        {contacts.length > 0 ? (
                            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                                {contacts.map(contact => {
                                    const CategoryIcon = getCategoryIcon(contact.category);
                                    return (
                                        <div key={contact.id} className="bg-surface p-4 rounded-xl border border-border hover:shadow-md transition-all duration-200 flex flex-col gap-2">
                                            <div className="flex items-center justify-between">
                                                <div className="flex items-center gap-2">
                                                    <CategoryIcon size={14} className="text-primary/60" />
                                                    <span className="text-[10px] font-bold uppercase tracking-widest text-muted">
                                                        {categoryLabel(contact.category, contact.customLabel)}
                                                    </span>
                                                </div>
                                                {canCreate && (
                                                    <div className="flex items-center gap-1">
                                                        <button
                                                            onClick={() => openEditContact(contact)}
                                                            aria-label={tc("edit")}
                                                            className="p-1 rounded hover:bg-background text-muted hover:text-foreground transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                                        >
                                                            <Pencil size={12} />
                                                        </button>
                                                        <button
                                                            onClick={() => handleDeleteContact(contact.id)}
                                                            aria-label={tc("delete")}
                                                            className="p-1 rounded hover:bg-error/10 text-muted hover:text-error transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                                                        >
                                                            <Trash2 size={12} />
                                                        </button>
                                                    </div>
                                                )}
                                            </div>
                                            <p className="text-sm font-bold text-foreground">{contact.name}</p>
                                            <a href={`tel:${contact.phone}`} className="text-[11px] font-bold text-primary/70 font-mono flex items-center gap-1 hover:text-primary transition-colors">
                                                <Phone size={11} /> {contact.phone}
                                            </a>
                                            {contact.email && (
                                                <a href={`mailto:${contact.email}`} className="text-[11px] font-medium text-muted flex items-center gap-1 hover:text-foreground transition-colors">
                                                    <Mail size={11} /> {contact.email}
                                                </a>
                                            )}
                                            {contact.address && (
                                                <p className="text-[10px] text-muted flex items-center gap-1">
                                                    <MapPin size={10} /> {contact.address}
                                                </p>
                                            )}
                                            {contact.notes && (
                                                <p className="text-[10px] text-muted italic mt-1">{contact.notes}</p>
                                            )}
                                        </div>
                                    );
                                })}
                            </div>
                        ) : (
                            <p className="text-xs font-medium text-muted italic">{tc("empty")}</p>
                        )}
                    </div>
                </div>

                {/* Contact Form Modal */}
                {showContactForm && (
                    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={() => setShowContactForm(false)} onKeyDown={(e) => { if (e.key === 'Escape') setShowContactForm(false); }}>
                        <div className="bg-surface rounded-xl border border-border shadow-xl w-full max-w-lg mx-4 p-6" onClick={(e) => e.stopPropagation()}>
                            <h3 className="text-lg font-bold text-foreground mb-4">
                                {editingContact ? tc("edit") : tc("add")}
                            </h3>
                            <form onSubmit={handleContactSubmit} className="space-y-4">
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{tc("category")}</label>
                                    <select
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                        value={contactFormData.category}
                                        onChange={e => setContactFormData({ ...contactFormData, category: e.target.value })}
                                    >
                                        {CONTACT_CATEGORIES.map(c => (
                                            <option key={c.value} value={c.value}>{categoryLabel(c.value)}</option>
                                        ))}
                                    </select>
                                </div>
                                {contactFormData.category === 'OTHER' && (
                                    <div>
                                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{tc("customLabel")}</label>
                                        <input
                                            className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                            placeholder={tc("customLabelPlaceholder")}
                                            value={contactFormData.customLabel}
                                            onChange={e => setContactFormData({ ...contactFormData, customLabel: e.target.value })}
                                        />
                                    </div>
                                )}
                                <div className="grid grid-cols-2 gap-4">
                                    <div>
                                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{tc("name")}</label>
                                        <input
                                            required
                                            className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                            placeholder={tc("namePlaceholder")}
                                            value={contactFormData.name}
                                            onChange={e => setContactFormData({ ...contactFormData, name: e.target.value })}
                                        />
                                    </div>
                                    <div>
                                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{tc("phone")}</label>
                                        <input
                                            required
                                            className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                            placeholder="+971 50 123 4567"
                                            value={contactFormData.phone}
                                            onChange={e => setContactFormData({ ...contactFormData, phone: e.target.value })}
                                        />
                                    </div>
                                </div>
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{tc("email")}</label>
                                    <input
                                        type="email"
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200"
                                        placeholder="email@example.com"
                                        value={contactFormData.email}
                                        onChange={e => setContactFormData({ ...contactFormData, email: e.target.value })}
                                    />
                                </div>
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{tc("address")}</label>
                                    <textarea
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 resize-none"
                                        rows={2}
                                        placeholder={tc("addressPlaceholder")}
                                        value={contactFormData.address}
                                        onChange={e => setContactFormData({ ...contactFormData, address: e.target.value })}
                                    />
                                </div>
                                <div>
                                    <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{tc("notes")}</label>
                                    <textarea
                                        className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 resize-none"
                                        rows={2}
                                        placeholder={tc("notesPlaceholder")}
                                        value={contactFormData.notes}
                                        onChange={e => setContactFormData({ ...contactFormData, notes: e.target.value })}
                                    />
                                </div>
                                {contactFormError && (
                                    <div className="bg-error/10 border border-error/30 rounded-lg px-4 py-3 text-xs text-error">
                                        {contactFormError}
                                    </div>
                                )}
                                <div className="flex justify-end gap-2 pt-2">
                                    <button
                                        type="button"
                                        onClick={() => { setShowContactForm(false); setEditingContact(null); }}
                                        className="px-4 py-2 text-xs font-bold text-muted cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg transition-all duration-200"
                                    >
                                        {tc("cancel")}
                                    </button>
                                    <button
                                        type="submit"
                                        disabled={contactSubmitting}
                                        className="px-4 py-2 bg-primary text-white rounded-lg text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed"
                                    >
                                        {contactSubmitting ? tc("saving") : (editingContact ? tc("update") : tc("save"))}
                                    </button>
                                </div>
                            </form>
                        </div>
                    </div>
                )}
            </>)}

            {activeTab === "buildings" && (
                <BuildingsTab buildings={buildings} propertyId={propertyId} canCreate={canCreate} onUpdate={fetchBuildings} />
            )}

            {activeTab === "units" && (
                <>
                    {/* The occupancy view (reserved / maintenance, incoming tenant) had no inbound link. */}
                    <div className="flex justify-end mb-3">
                        <Link href={`/dashboard/properties/${propertyId}/units`} data-testid="property-units-page-link"
                            className="text-xs font-semibold text-primary hover:underline">
                            {tNav("openUnitsPage")}
                        </Link>
                    </div>
                    <UnitsTab units={units} buildings={buildings} propertyId={propertyId} canCreate={canCreate} onUpdate={fetchUnits} />
                </>
            )}

            {activeTab === "leases" && (
                <LeasesTab propertyId={propertyId} />
            )}

            {activeTab === "amenities" && (
                <AmenitiesTab propertyId={propertyId} buildings={buildings} canManage={canManageFacilities} />
            )}

            {activeTab === "parking" && (
                <ParkingTab propertyId={propertyId} buildings={buildings} canManage={canManageFacilities} />
            )}

            {activeTab === "accounts" && canManageAccountSetup && (
                <PropertyAccountsTab propertyId={propertyId} />
            )}
        </div>
    );
}

// ------ BUILDINGS TAB SUB-COMPONENT ------

function BuildingsTab({ buildings, propertyId, canCreate, onUpdate }: any) {
    const t = useTranslations("MasterData");
    const [showForm, setShowForm] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [formError, setFormError] = useState<string | null>(null);
    const [formData, setFormData] = useState({ nameEn: "", nameAr: "", floors: 1 });

    const handleSubmit = async (e: any) => {
        e.preventDefault();
        // Break-it R3 ops3 F3: -3, 0 and 99 999 floors were saved as typed.
        if (!floorsInRange(formData.floors)) {
            setFormError(t("floorsRange", { min: BUILDING_FLOORS_MIN, max: BUILDING_FLOORS_MAX }));
            return;
        }
        setSubmitting(true);
        setFormError(null);
        try {
            const res = await fetch("/api/proxy/v1/buildings", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ ...formData, property: { id: propertyId } })
            });
            await throwIfNotOk(res);
            setShowForm(false);
            setFormData({ nameEn: "", nameAr: "", floors: 1 });
            onUpdate();
        } catch (err) {
            const refusal = err instanceof ApiError ? refusalOf(err.body) : null;
            setFormError(refusal?.code === "building.floorsOutOfRange"
                ? t("floorsRange", { min: BUILDING_FLOORS_MIN, max: BUILDING_FLOORS_MAX })
                : err instanceof ApiError ? err.message : "Failed to save building. Please try again.");
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div>
            <div className="flex justify-between items-center mb-6">
                <h2 className="text-lg font-bold">Buildings</h2>
                {canCreate && (
                    <button
                        onClick={() => setShowForm(true)}
                        className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-full text-xs font-bold hover:opacity-90 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                    >
                        <Plus size={14} /> Add Building
                    </button>
                )}
            </div>

            {showForm && (
                <form onSubmit={handleSubmit} className="bg-surface p-6 rounded-xl border border-border mb-6 grid grid-cols-3 gap-4">
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Name (EN)</label>
                        <input required className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={formData.nameEn} onChange={e => setFormData({ ...formData, nameEn: e.target.value })} />
                    </div>
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1 text-right">Name (AR)</label>
                        <input className="w-full bg-input border border-border rounded-lg p-2 text-xs text-right focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={formData.nameAr} onChange={e => setFormData({ ...formData, nameAr: e.target.value })} dir="rtl" />
                    </div>
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Floors</label>
                        <NumberInput required min={BUILDING_FLOORS_MIN} max={BUILDING_FLOORS_MAX} step={1} className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={formData.floors} onChange={(v) => setFormData({ ...formData, floors: v })} />
                    </div>
                    {formError && (
                        <div className="col-span-3 bg-error/10 border border-error/30 rounded-lg px-4 py-3 text-xs text-error">
                            {formError}
                        </div>
                    )}
                    <div className="col-span-3 flex justify-end gap-2 mt-2">
                        <button type="button" onClick={() => setShowForm(false)} className="px-4 py-2 text-xs font-bold text-muted cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg transition-all duration-200">Cancel</button>
                        <button type="submit" disabled={submitting} className="px-4 py-2 bg-primary text-white rounded-lg text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed">{submitting ? "Saving..." : "Save"}</button>
                    </div>
                </form>
            )}

            <div className="grid grid-cols-3 gap-4">
                {buildings.map((b: any) => (
                    <div key={b.id} className="bg-surface rounded-xl p-4 border border-border flex justify-between items-center hover:shadow-md transition-all duration-200">
                        <div>
                            <p className="font-bold text-sm text-foreground">{b.nameEn}</p>
                            {b.nameAr && <p className="text-xs text-muted font-medium" dir="rtl">{b.nameAr}</p>}
                        </div>
                        <div className="text-xs font-bold text-muted bg-background px-2 py-1 rounded-md border border-border">
                            {b.floors} Floors
                        </div>
                    </div>
                ))}
                {buildings.length === 0 && !showForm && (
                    <div className="col-span-3 text-center py-12 text-muted font-medium text-xs">No buildings added yet.</div>
                )}
            </div>
        </div>
    );
}

// ------ UNITS TAB SUB-COMPONENT ------

function UnitsTab({ units, buildings, propertyId, canCreate, onUpdate }: any) {
    const t = useTranslations("MasterData");
    const locale = useLocale();
    const [showForm, setShowForm] = useState(false);
    const [showBulkUpload, setShowBulkUpload] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [bulkError, setBulkError] = useState<string | null>(null);
    const [addUnitError, setAddUnitError] = useState<string | null>(null);
    const [file, setFile] = useState<File | null>(null);
    const [uploadBuildingId, setUploadBuildingId] = useState<string>("");
    const [unitForm, setUnitForm] = useState({
        unitNumber: "", type: "STUDIO", sizeSqft: "", expectedRent: "", buildingId: ""
    });

    const unitTypes = ["STUDIO", "BHK1", "BHK2", "BHK3", "PENTHOUSE", "RETAIL", "OFFICE"];

    const handleAddUnit = async (e: any) => {
        e.preventDefault();
        if (focusFirstInvalidMoney(e.currentTarget)) return;
        // Break-it R3 ops3 F2: the rule the server applies to every unit, said here first.
        if (!sizeIsValid(unitForm.sizeSqft)) {
            setAddUnitError(t("unitSizePositive"));
            return;
        }
        setSubmitting(true);
        setAddUnitError(null);
        try {
            const body: any = {
                unitNumber: unitForm.unitNumber,
                type: unitForm.type,
                sizeSqft: unitForm.sizeSqft ? Number(unitForm.sizeSqft) : null,
                expectedRent: moneyValueOrNull(unitForm.expectedRent, { allowZero: true, max: MONEY_MAX_12_2 }),
                status: "VACANT",
                property: { id: propertyId },
            };
            if (unitForm.buildingId) {
                body.building = { id: unitForm.buildingId };
            }
            const res = await fetch("/api/proxy/v1/units", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            await throwIfNotOk(res);
            setShowForm(false);
            setUnitForm({ unitNumber: "", type: "STUDIO", sizeSqft: "", expectedRent: "", buildingId: "" });
            onUpdate();
        } catch (err) {
            // Break-it R3 ops3 F1: "Unit 101 already exists in Tower A", in the user's language.
            const refusal = err instanceof ApiError ? refusalOf(err.body) : null;
            setAddUnitError(refusal?.code === "unit.numberTaken"
                ? t("unitNumberTaken", { unitNumber: String(refusal.args.unitNumber ?? unitForm.unitNumber), place: String((locale === "ar" && refusal.args.placeAr) || refusal.args.place || "") })
                : err instanceof ApiError ? err.message : "Failed to save unit. Please try again.");
        } finally {
            setSubmitting(false);
        }
    };

    const handleBulkUpload = async (e: any) => {
        e.preventDefault();
        if (!file) return;
        setSubmitting(true);
        setBulkError(null);
        try {
            const formData = new FormData();
            formData.append("file", file);
            formData.append("propertyId", propertyId);
            if (uploadBuildingId) formData.append("buildingId", uploadBuildingId);

            const res = await fetch("/api/proxy/v1/units/bulk", {
                method: "POST",
                body: formData,
            });

            if (res.ok) {
                setShowBulkUpload(false);
                setFile(null);
                onUpdate();
            } else {
                // Backend returns { message, errors: ["Row N: ..."] } for invalid CSVs.
                const data = await res.json().catch(() => null);
                const rows = Array.isArray(data?.errors) ? data.errors : [];
                setBulkError(
                    rows.length > 0
                        ? rows.join("\n")
                        : data?.message ?? `Upload failed (status ${res.status})`,
                );
            }
        } catch {
            setBulkError("Upload failed. Please try again.");
        } finally {
            setSubmitting(false);
        }
    };

    return (
        <div>
            <div className="flex justify-between items-center mb-6">
                <h2 className="text-lg font-bold">Units</h2>
                {canCreate && (
                    <div className="flex items-center gap-2">
                        <button
                            onClick={() => { setShowForm(true); setShowBulkUpload(false); setAddUnitError(null); }}
                            className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-full text-xs font-bold hover:opacity-90 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Plus size={14} /> Add Unit
                        </button>
                        <button
                            onClick={() => { setShowBulkUpload(!showBulkUpload); setShowForm(false); setBulkError(null); }}
                            className="flex items-center gap-2 bg-background text-foreground px-4 py-2 rounded-full text-xs font-bold hover:bg-input transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none"
                        >
                            <Upload size={14} /> Bulk Upload CSV
                        </button>
                    </div>
                )}
            </div>

            {showForm && (
                <form onSubmit={handleAddUnit} className="bg-surface p-6 rounded-xl border border-border mb-6 grid grid-cols-2 md:grid-cols-5 gap-4">
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Unit Number</label>
                        <input required className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" placeholder={t("unitNumberPlaceholder")} value={unitForm.unitNumber} onChange={e => setUnitForm({ ...unitForm, unitNumber: e.target.value })} />
                    </div>
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">{t("type")}</label>
                        <select className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={unitForm.type} onChange={e => setUnitForm({ ...unitForm, type: e.target.value })}>
                            {unitTypes.map(ut => <option key={ut} value={ut}>{t.has(`unitType${ut}`) ? t(`unitType${ut}`) : ut}</option>)}
                        </select>
                    </div>
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Size (Sqft)</label>
                        <input type="number" min={0.01} step="any" className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" placeholder="e.g. 850" value={unitForm.sizeSqft} onChange={e => setUnitForm({ ...unitForm, sizeSqft: e.target.value })} />
                    </div>
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Expected Rent (AED/year)</label>
                        <MoneyTextInput options={{ allowZero: true, max: MONEY_MAX_12_2 }} className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" placeholder="e.g. 85000" value={unitForm.expectedRent} onChange={v => setUnitForm({ ...unitForm, expectedRent: v })} />
                    </div>
                    <div>
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Building</label>
                        <select className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={unitForm.buildingId} onChange={e => setUnitForm({ ...unitForm, buildingId: e.target.value })}>
                            <option value="">No Building</option>
                            {buildings.map((b: any) => <option key={b.id} value={b.id}>{b.nameEn}</option>)}
                        </select>
                    </div>
                    {addUnitError && (
                        <div className="col-span-2 md:col-span-5 bg-error/10 border border-error/30 rounded-lg px-4 py-3 text-xs text-error">
                            {addUnitError}
                        </div>
                    )}
                    <div className="col-span-2 md:col-span-5 flex justify-end gap-2 mt-2">
                        <button type="button" onClick={() => setShowForm(false)} className="px-4 py-2 text-xs font-bold text-muted cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none rounded-lg transition-all duration-200">Cancel</button>
                        <button type="submit" disabled={submitting} className="px-4 py-2 bg-primary text-white rounded-lg text-xs font-bold cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed">{submitting ? "Saving..." : "Save Unit"}</button>
                    </div>
                </form>
            )}

            {showBulkUpload && (
                <form onSubmit={handleBulkUpload} className="bg-surface p-6 rounded-xl border border-border mb-6 flex items-end gap-4">
                    <div className="flex-1">
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">CSV File</label>
                        <input type="file" accept=".csv" required onChange={e => setFile(e.target.files?.[0] || null)} className="w-full text-xs" />
                        <p className="text-[10px] text-muted mt-1">Format: unitNumber, type, sizeSqft, expectedRent, status</p>
                    </div>
                    <div className="flex-1">
                        <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1">Assign to Building (Optional)</label>
                        <select className="w-full bg-input border border-border rounded-lg p-2 text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200" value={uploadBuildingId} onChange={e => setUploadBuildingId(e.target.value)}>
                            <option value="">No Building (Direct to Property)</option>
                            {buildings.map((b: any) => <option key={b.id} value={b.id}>{b.nameEn}</option>)}
                        </select>
                    </div>
                    <button type="submit" disabled={submitting} className="px-6 py-2 bg-primary text-white rounded-lg text-xs font-bold mb-[2px] cursor-pointer focus:ring-2 focus:ring-primary/30 focus:outline-none transition-all duration-200 disabled:opacity-50 disabled:cursor-not-allowed">{submitting ? "Uploading..." : "Upload"}</button>
                </form>
            )}

            {showBulkUpload && bulkError && (
                <div className="bg-error/10 border border-error/30 rounded-lg px-4 py-3 mb-6 text-xs text-error whitespace-pre-line">
                    {bulkError}
                </div>
            )}

            <div className="bg-surface border border-border rounded-xl overflow-hidden">
                <table className="w-full text-left text-sm">
                    <thead className="bg-background text-muted text-xs font-semibold uppercase tracking-[0.15em]">
                        <tr>
                            <th className="px-6 py-4">Unit #</th>
                            <th className="px-6 py-4">Building</th>
                            <th className="px-6 py-4">Type</th>
                            <th className="px-6 py-4">Status</th>
                            <th className="px-6 py-4">Size (Sqft)</th>
                            <th className="px-6 py-4 text-right">Rent</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                        {units.map((u: any) => (
                            <tr key={u.id} className="hover:bg-background/50 transition-all duration-200">
                                <td className="px-6 py-4 font-bold text-foreground">{u.unitNumber}</td>
                                <td className="px-6 py-4 text-muted font-medium">
                                    {buildings.find((b: any) => b.id === u.building?.id)?.nameEn || "N/A"}
                                </td>
                                <td className="px-6 py-4">
                                    <span className="px-2 py-1 text-[10px] font-bold uppercase tracking-widest bg-background rounded-md">
                                        {u.type && t.has(`unitType${u.type}`) ? t(`unitType${u.type}`) : u.type}
                                    </span>
                                </td>
                                <td className="px-6 py-4">
                                    <span className={cn(
                                        "px-2 py-1 text-[10px] font-bold uppercase tracking-widest rounded-md",
                                        u.status === "VACANT" ? "bg-success/10 text-success" : "bg-info/10 text-info"
                                    )}>
                                        {u.status}
                                    </span>
                                </td>
                                <td className="px-6 py-4 text-muted">{u.sizeSqft}</td>
                                <td className="px-6 py-4 text-right font-bold">{formatCurrencyCompact(u.expectedRent)}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
                {units.length === 0 && (
                    <div className="text-center py-12 text-muted font-medium text-xs">No units found.</div>
                )}
            </div>
        </div>
    );
}

// ------ LEASES TAB SUB-COMPONENT ------

function LeasesTab({ propertyId }: { propertyId: string }) {
    const t = useTranslations("MasterData");
    const [leases, setLeases] = useState<any[]>([]);
    const begin = useLatestRequest();

    useEffect(() => {
        fetchLeases();
    }, [propertyId]);

    const fetchLeases = async () => {
        const { signal, isCurrent } = begin();
        try {
            const res = await fetch(`/api/proxy/v1/leases/property/${propertyId}`, { signal });
            if (!res.ok) return;
            const rows = await res.json();
            if (isCurrent()) setLeases(rows);
        } catch (err) {
            if (isAbortError(err) || !isCurrent()) return;
            console.error(err);
        }
    };

    const getStatusColor = (status: string) => {
        switch (status) {
            case 'ACTIVE': return 'bg-success/10 text-success';
            case 'DRAFT': return 'bg-background text-muted';
            case 'PENDING_SIGNATURE': return 'bg-warning/10 text-warning';
            case 'TERMINATED': return 'bg-error/10 text-error';
            case 'EXPIRED': return 'bg-warning/10 text-warning';
            default: return 'bg-info/10 text-info';
        }
    };

    return (
        <div>
            <h2 className="text-lg font-bold mb-6">{t("leases")}</h2>
            <div className="bg-surface border border-border rounded-xl overflow-hidden">
                <table className="w-full text-start text-sm">
                    <thead className="bg-background text-muted text-xs font-semibold uppercase tracking-[0.15em]">
                        <tr>
                            <th className="px-6 py-4 text-start">{t("unit")}</th>
                            <th className="px-6 py-4 text-start">{t("renter")}</th>
                            <th className="px-6 py-4 text-start">{t("status")}</th>
                            <th className="px-6 py-4 text-start">{t("contractPeriod")}</th>
                            <th className="px-6 py-4 text-end">{t("rentAed")}</th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-border">
                        {leases.map((l: any) => (
                            <tr key={l.id} className="hover:bg-background/50 transition-all duration-200">
                                <td className="px-6 py-4 font-bold text-foreground flex items-center gap-2">
                                    <FileText size={14} className="text-muted" />
                                    {t("unit")} {l.unitIdentifier}
                                </td>
                                <td className="px-6 py-4 text-muted font-medium">{l.renterName}</td>
                                <td className="px-6 py-4">
                                    <span className={cn("px-2 py-1 text-[10px] font-bold uppercase tracking-widest rounded-md", getStatusColor(l.status))}>
                                        {l.status.replace('_', ' ')}
                                    </span>
                                </td>
                                <td className="px-6 py-4 text-muted text-xs flex items-center gap-1">
                                    <Calendar size={12} className="text-muted" />
                                    {new Date(l.startDate).toLocaleDateString()} - {new Date(l.endDate).toLocaleDateString()}
                                </td>
                                <td className="px-6 py-4 text-end font-bold flex items-center justify-end gap-1">
                                    <DollarSign size={12} className="text-muted" />
                                    {formatCurrencyCompact(l.rentAmount)}
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
                {leases.length === 0 && (
                    <div className="text-center py-12 text-muted font-medium text-xs">{t("noContractsForProperty")}</div>
                )}
            </div>
        </div>
    );
}

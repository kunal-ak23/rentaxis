"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Plus, X, User, Mail, Phone } from "lucide-react";
import { useSession } from "next-auth/react";
import { hasPermission, type UserRole } from "@/lib/rbac";

type Renter = {
    id: string;
    nameEn: string;
    nameAr: string;
    email: string;
    phone: string;
    primaryLanguage: string;
};

export default function RentersPage() {
    const t = useTranslations("MasterData");
    const locale = useLocale();
    const [renters, setRenters] = useState<Renter[]>([]);
    const [showForm, setShowForm] = useState(false);
    const { data: session } = useSession();

    const userRole = session?.user?.role as UserRole | undefined;
    const canManageRenters = hasPermission(userRole, 'canManageRenters');

    const [formData, setFormData] = useState({
        nameEn: "",
        nameAr: "",
        email: "",
        phone: "",
        primaryLanguage: "EN",
        createPortalAccount: false
    });

    useEffect(() => {
        fetchRenters();
    }, []);

    const fetchRenters = async () => {
        try {
            const res = await fetch("/api/proxy/v1/renters");
            if (res.ok) {
                const data = await res.json();
                setRenters(data);
            }
        } catch (err) {
            console.error(err);
        }
    };

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        try {
            const res = await fetch("/api/proxy/v1/renters", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(formData)
            });
            if (res.ok) {
                setShowForm(false);
                fetchRenters();
                setFormData({
                    nameEn: "",
                    nameAr: "",
                    email: "",
                    phone: "",
                    primaryLanguage: "EN",
                    createPortalAccount: false
                });
            }
        } catch (err) {
            console.error(err);
        }
    };

    const getRenterDisplayName = (r: Renter) => {
        if (locale === 'ar' && r.nameAr) return r.nameAr;
        return r.nameEn;
    };

    return (
        <div>
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-black text-foreground tracking-tight mb-1">
                        {t("renters")}
                    </h1>
                    <p className="text-xs text-gray-500 font-medium">
                        {t("manageRenters")}
                    </p>
                </div>
                {canManageRenters && (
                    <button
                        onClick={() => setShowForm(true)}
                        className="flex items-center gap-2 bg-primary text-primary-foreground px-5 py-2.5 rounded-full text-xs font-bold hover:opacity-90 transition-all shadow-lg shadow-primary/10 active:scale-95"
                    >
                        <Plus size={14} />
                        {t("addRenter")}
                    </button>
                )}
            </div>

            {showForm && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-white rounded-3xl p-8 max-w-xl w-full shadow-2xl border border-gray-100 relative">
                        <button onClick={() => setShowForm(false)} className="absolute right-6 top-6 p-2 text-gray-400 hover:text-gray-600"><X size={18} /></button>
                        <h2 className="text-lg font-black mb-1">{t("addRenter")}</h2>
                        <p className="text-xs text-gray-400 mb-8 font-medium">{t("createRenterProfile")}</p>
                        <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("nameEn")}</label>
                                <input required placeholder="John Doe" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.nameEn} onChange={ev => setFormData({ ...formData, nameEn: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("nameAr")}</label>
                                <input placeholder="جون دو" className="w-full bg-input border border-border p-3 rounded-xl text-xs text-right" value={formData.nameAr} onChange={ev => setFormData({ ...formData, nameAr: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("email")}</label>
                                <input type="email" placeholder="john@example.com" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.email} onChange={ev => setFormData({ ...formData, email: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("phone")}</label>
                                <input placeholder="+971 50 123 4567" className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.phone} onChange={ev => setFormData({ ...formData, phone: ev.target.value })} />
                            </div>
                            <div className="col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase mb-1.5 ml-1">{t("preferredLanguage")}</label>
                                <select className="w-full bg-input border border-border p-3 rounded-xl text-xs" value={formData.primaryLanguage} onChange={ev => setFormData({ ...formData, primaryLanguage: ev.target.value })}>
                                    <option value="EN">English</option>
                                    <option value="AR">Arabic</option>
                                </select>
                            </div>
                            <div className="col-span-1 flex items-end">
                                <label className="flex items-center gap-3 cursor-pointer p-3">
                                    <input
                                        type="checkbox"
                                        checked={formData.createPortalAccount}
                                        onChange={ev => setFormData({ ...formData, createPortalAccount: ev.target.checked })}
                                        className="w-4 h-4 rounded border-gray-300 text-primary focus:ring-primary"
                                    />
                                    <span className="text-xs font-bold text-gray-600">{t("createPortalAccount")}</span>
                                </label>
                            </div>
                            <div className="col-span-2 flex justify-end gap-3 mt-4">
                                <button type="button" onClick={() => setShowForm(false)} className="px-6 py-3 text-xs font-bold text-gray-500">{t("cancel")}</button>
                                <button type="submit" className="px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold">{t("create")}</button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                {renters.map(r => (
                    <div key={r.id} className="bg-white rounded-[2rem] p-6 shadow-sm border border-gray-100 hover:shadow-md transition-all group">
                        <div className="flex items-start gap-4 mb-6">
                            <div className="w-12 h-12 bg-primary/10 rounded-2xl flex items-center justify-center text-primary border border-primary/20 shrink-0">
                                <User size={20} />
                            </div>
                            <div>
                                <h3 className="text-sm font-black text-foreground tracking-tight">{getRenterDisplayName(r)}</h3>
                                {r.nameAr && locale !== 'ar' && <p className="text-[10px] text-gray-400 font-bold mb-1">{r.nameAr}</p>}
                                {r.nameEn && locale === 'ar' && <p className="text-[10px] text-gray-400 font-bold mb-1">{r.nameEn}</p>}
                                <span className="inline-flex items-center justify-center px-2 py-0.5 rounded text-[9px] font-bold bg-gray-100 text-gray-500 tracking-wider">
                                    {r.primaryLanguage}
                                </span>
                            </div>
                        </div>

                        <div className="space-y-3">
                            <div className="flex items-center gap-3">
                                <Mail size={14} className="text-gray-400" />
                                <span className="text-xs font-medium text-gray-600 truncate">{r.email || t("noEmailProvided")}</span>
                            </div>
                            <div className="flex items-center gap-3">
                                <Phone size={14} className="text-gray-400" />
                                <span className="text-xs font-medium text-gray-600">{r.phone || t("noPhoneProvided")}</span>
                            </div>
                        </div>
                    </div>
                ))}
            </div>

            {renters.length === 0 && !showForm && (
                <div className="text-center py-24 bg-gray-50 border border-dashed border-gray-200 rounded-[2.5rem] flex flex-col items-center">
                    <div className="w-16 h-16 bg-white rounded-2xl flex items-center justify-center text-gray-200 shadow-sm mb-6">
                        <User size={32} />
                    </div>
                    <p className="text-sm font-bold text-gray-400 mb-6 uppercase tracking-widest">
                        {t("noRentersFound")}
                    </p>
                    {canManageRenters && (
                        <button onClick={() => setShowForm(true)} className="text-xs font-black text-foreground border-b-2 border-primary pb-0.5 hover:text-primary transition-all">
                            {t("addRenter")}
                        </button>
                    )}
                </div>
            )}
        </div>
    );
}

"use client";

import { useState, useEffect } from "react";
import { useTranslations, useLocale } from "next-intl";
import { Plus, MapPin, Building2, Hash, ArrowRight, X } from "lucide-react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";

type Property = {
    id: string;
    nameEn: string;
    nameAr: string;
    emirate: string;
    address: string;
    makaniNumber: string;
    type: string;
};

export default function PropertiesPage() {
    const t = useTranslations("MasterData");
    const e = useTranslations("Emirates");
    const locale = useLocale();
    const [properties, setProperties] = useState<Property[]>([]);
    const [showForm, setShowForm] = useState(false);
    const [formData, setFormData] = useState({
        nameEn: "",
        nameAr: "",
        emirate: "DUBAI",
        address: "",
        makaniNumber: "",
        type: "RESIDENTIAL"
    });

    useEffect(() => {
        fetchProperties();
    }, []);

    const fetchProperties = async () => {
        try {
            const res = await fetch("/api/v1/properties");
            if (res.ok) {
                const data = await res.json();
                setProperties(data);
            }
        } catch (err) {
            console.error(err);
        }
    };

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        try {
            const res = await fetch("/api/v1/properties", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(formData)
            });
            if (res.ok) {
                setShowForm(false);
                fetchProperties();
                setFormData({
                    nameEn: "",
                    nameAr: "",
                    emirate: "DUBAI",
                    address: "",
                    makaniNumber: "",
                    type: "RESIDENTIAL"
                });
            }
        } catch (err) {
            console.error(err);
        }
    };

    return (
        <div className="p-8 max-w-7xl mx-auto">
            <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 mb-10">
                <div>
                    <h1 className="text-xl font-black text-foreground tracking-tight mb-1">
                        {t("properties")}
                    </h1>
                    <p className="text-xs text-gray-500 font-medium">
                        Manage and track your property portfolio across the UAE.
                    </p>
                </div>
                <button
                    onClick={() => setShowForm(true)}
                    className="flex items-center gap-2 bg-primary text-primary-foreground px-5 py-2.5 rounded-full text-xs font-bold hover:opacity-90 transition-all shadow-lg shadow-primary/10 active:scale-95 self-start"
                >
                    <Plus size={14} />
                    {t("addProperty")}
                </button>
            </div>

            {showForm && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-white rounded-3xl p-8 max-w-xl w-full shadow-2xl border border-gray-100 relative">
                        <button
                            onClick={() => setShowForm(false)}
                            className="absolute right-6 top-6 p-2 text-gray-400 hover:text-gray-600 transition-colors"
                        >
                            <X size={18} />
                        </button>

                        <h2 className="text-lg font-black mb-1 text-gray-900 leading-tight">{t("addProperty")}</h2>
                        <p className="text-xs text-gray-400 mb-8 font-medium">Fill in the details to register a new property.</p>

                        <form onSubmit={handleSubmit} className="grid grid-cols-2 gap-5">
                            <div className="col-span-2 sm:col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1.5 ml-1">{t("nameEn")}</label>
                                <input
                                    required
                                    placeholder="Property Name (EN)"
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs placeholder:text-gray-300 focus:outline-none focus:ring-2 focus:ring-primary/10 focus:border-primary/30 transition-all"
                                    value={formData.nameEn}
                                    onChange={ev => setFormData({ ...formData, nameEn: ev.target.value })}
                                />
                            </div>
                            <div className="col-span-2 sm:col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1.5 ml-1">{t("nameAr")}</label>
                                <input
                                    placeholder="اسم العقار (AR)"
                                    className="w-full bg-gray-50 border border-gray-100 p-3 rounded-xl text-xs text-right placeholder:text-gray-300 focus:outline-none focus:ring-2 focus:ring-blue-600/10 focus:border-blue-600/30 transition-all"
                                    value={formData.nameAr}
                                    onChange={ev => setFormData({ ...formData, nameAr: ev.target.value })}
                                />
                            </div>
                            <div className="col-span-2 sm:col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1.5 ml-1">{t("emirate")}</label>
                                <select
                                    className="w-full bg-gray-50 border border-gray-100 p-3 rounded-xl text-xs focus:outline-none focus:ring-2 focus:ring-blue-600/10 focus:border-blue-600/30 transition-all appearance-none"
                                    value={formData.emirate}
                                    onChange={ev => setFormData({ ...formData, emirate: ev.target.value })}
                                >
                                    {["DUBAI", "ABU_DHABI", "SHARJAH", "AJMAN", "UMM_AL_QUWAIN", "RAS_AL_KHAIMAH", "FUJAIRAH"].map(opt => (
                                        <option key={opt} value={opt}>{e(opt)}</option>
                                    ))}
                                </select>
                            </div>
                            <div className="col-span-2 sm:col-span-1">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1.5 ml-1">{t("makaniNumber")}</label>
                                <input
                                    placeholder="e.g. 12345 67890"
                                    className="w-full bg-gray-50 border border-gray-100 p-3 rounded-xl text-xs placeholder:text-gray-300 focus:outline-none focus:ring-2 focus:ring-blue-600/10 focus:border-blue-600/30 transition-all"
                                    value={formData.makaniNumber}
                                    onChange={ev => setFormData({ ...formData, makaniNumber: ev.target.value })}
                                />
                            </div>
                            <div className="col-span-2">
                                <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1.5 ml-1">{t("address")}</label>
                                <textarea
                                    placeholder="Enter full physical address..."
                                    className="w-full bg-gray-50 border border-gray-100 p-3 rounded-xl text-xs h-24 resize-none placeholder:text-gray-300 focus:outline-none focus:ring-2 focus:ring-blue-600/10 focus:border-blue-600/30 transition-all"
                                    value={formData.address}
                                    onChange={ev => setFormData({ ...formData, address: ev.target.value })}
                                />
                            </div>
                            <div className="col-span-2 flex justify-end gap-3 mt-4">
                                <button
                                    type="button"
                                    onClick={() => setShowForm(false)}
                                    className="px-6 py-3 rounded-xl text-xs font-bold text-gray-500 hover:bg-gray-50 transition-colors"
                                >
                                    {t("cancel")}
                                </button>
                                <button
                                    type="submit"
                                    className="px-8 py-3 bg-primary text-primary-foreground rounded-xl text-xs font-bold hover:opacity-90 shadow-lg shadow-primary/10 active:scale-95 transition-all"
                                >
                                    {t("create")}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
                {properties.map(p => (
                    <div key={p.id} className="group bg-white p-5 rounded-2xl border border-gray-100 hover:border-gray-200 hover:shadow-[0_8px_30px_rgb(0,0,0,0.02)] transition-all duration-300 flex flex-col relative overflow-hidden">
                        <div className="flex justify-between items-start mb-5 relative z-10">
                            <div className="flex items-center gap-3">
                                <div className="w-9 h-9 bg-accent rounded-lg flex items-center justify-center text-gray-400 border border-border group-hover:bg-primary/5 group-hover:text-primary group-hover:border-primary/20 transition-colors">
                                    <Building2 size={18} />
                                </div>
                                <h3 className="text-sm font-black text-foreground tracking-tight">
                                    {locale === 'ar' && p.nameAr ? p.nameAr : p.nameEn}
                                </h3>
                            </div>
                            <span className="text-[9px] font-black uppercase tracking-widest px-2 py-1 bg-accent text-gray-400 group-hover:bg-primary/5 group-hover:text-primary rounded-md transition-colors">
                                {p.type}
                            </span>
                        </div>

                        <div className="space-y-3 mb-6 relative z-10">
                            <div className="flex items-start gap-2.5">
                                <MapPin size={12} className="text-gray-300 mt-0.5 shrink-0" />
                                <p className="text-xs text-gray-500 leading-relaxed line-clamp-2">{p.address}</p>
                            </div>
                            <div className="flex items-center gap-2.5">
                                <Hash size={12} className="text-gray-300 shrink-0" />
                                <span className="text-[10px] font-bold text-gray-400 tracking-wider uppercase">{p.makaniNumber || "NO MAKANI"}</span>
                            </div>
                        </div>

                        <div className="mt-auto pt-4 border-t border-gray-50 flex items-center justify-between relative z-10">
                            <span className="text-[10px] font-black text-gray-400 uppercase tracking-widest">{e(p.emirate)}</span>
                            <Link
                                href={`/dashboard/properties/${p.id}/units`}
                                className="flex items-center gap-1.5 text-xs font-bold text-foreground hover:text-primary transition-colors group/link"
                            >
                                {t("units")}
                                <ArrowRight size={12} className="transition-transform group-hover/link:translate-x-0.5" />
                            </Link>
                        </div>

                        {/* Decorative Background Blob */}
                        <div className="absolute top-0 right-0 w-24 h-24 bg-primary/5 blur-3xl rounded-full -mr-12 -mt-12 transition-opacity opacity-0 group-hover:opacity-100" />
                    </div>
                ))}
            </div>

            {properties.length === 0 && !showForm && (
                <div className="text-center py-24 bg-gray-50 border border-dashed border-gray-200 rounded-[2.5rem] flex flex-col items-center">
                    <div className="w-16 h-16 bg-white rounded-2xl flex items-center justify-center text-gray-200 shadow-sm mb-6">
                        <Building2 size={32} />
                    </div>
                    <p className="text-sm font-bold text-gray-400 mb-6 uppercase tracking-widest">No properties in your portfolio</p>
                    <button
                        onClick={() => setShowForm(true)}
                        className="text-xs font-black text-foreground border-b-2 border-primary pb-0.5 hover:text-primary hover:border-primary transition-all"
                    >
                        {t("addProperty")}
                    </button>
                </div>
            )}
        </div>
    );
}

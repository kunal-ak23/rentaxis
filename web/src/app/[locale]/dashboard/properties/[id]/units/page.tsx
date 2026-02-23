"use client";

import { useState, useEffect, use } from "react";
import { useTranslations } from "next-intl";
import { Plus, X, Building, Info, LayoutList, Ruler, Hash } from "lucide-react";
import { cn } from "@/lib/utils";

type Unit = {
    id: string;
    unitNumber: string;
    type: string;
    sizeSqft: number;
    status: string;
};

export default function UnitsPage({ params }: { params: Promise<{ id: string }> }) {
    const { id: propertyId } = use(params);
    const t = useTranslations("MasterData");
    const [units, setUnits] = useState<Unit[]>([]);
    const [showForm, setShowForm] = useState(false);
    const [formData, setFormData] = useState({
        unitNumber: "",
        type: "BHK1",
        sizeSqft: 0,
        status: "VACANT",
        property: { id: propertyId }
    });

    useEffect(() => {
        fetchUnits();
    }, [propertyId]);

    const fetchUnits = async () => {
        try {
            const res = await fetch(`/api/v1/units/property/${propertyId}`);
            if (res.ok) {
                const data = await res.json();
                setUnits(data);
            }
        } catch (err) {
            console.error(err);
        }
    };

    const handleSubmit = async (ev: React.FormEvent) => {
        ev.preventDefault();
        try {
            const res = await fetch("/api/v1/units", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(formData)
            });
            if (res.ok) {
                setShowForm(false);
                fetchUnits();
                setFormData({
                    unitNumber: "",
                    type: "BHK1",
                    sizeSqft: 0,
                    status: "VACANT",
                    property: { id: propertyId }
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
                    <h1 className="text-xl font-black text-foreground tracking-tight mb-1">{t("units")}</h1>
                    <p className="text-[10px] text-gray-400 font-bold uppercase tracking-[0.2em]">Property ID: {propertyId}</p>
                </div>
                <button
                    onClick={() => setShowForm(true)}
                    className="flex items-center gap-2 bg-primary text-primary-foreground px-5 py-2.5 rounded-full text-xs font-bold hover:opacity-90 transition-all shadow-lg shadow-primary/10 active:scale-95 self-start"
                >
                    <Plus size={14} />
                    {t("addUnit")}
                </button>
            </div>

            {showForm && (
                <div className="fixed inset-0 bg-gray-900/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-white rounded-3xl p-8 max-w-md w-full shadow-2xl border border-gray-100 relative">
                        <button
                            onClick={() => setShowForm(false)}
                            className="absolute right-6 top-6 p-2 text-gray-400 hover:text-gray-600 transition-colors"
                        >
                            <X size={18} />
                        </button>

                        <h2 className="text-lg font-black mb-1 text-gray-900 leading-tight">{t("addUnit")}</h2>
                        <p className="text-xs text-gray-400 mb-8 font-medium">Add a new inventory unit to this property.</p>

                        <form onSubmit={handleSubmit} className="space-y-5">
                            <div>
                                <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1.5 ml-1">{t("unitNumber")}</label>
                                <input
                                    required
                                    placeholder="e.g. 101, A-02"
                                    className="w-full bg-input border border-border p-3 rounded-xl text-xs placeholder:text-gray-300 focus:outline-none focus:ring-2 focus:ring-primary/10 focus:border-primary/30 transition-all"
                                    value={formData.unitNumber}
                                    onChange={ev => setFormData({ ...formData, unitNumber: ev.target.value })}
                                />
                            </div>
                            <div className="flex gap-4">
                                <div className="flex-1">
                                    <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1.5 ml-1">{t("unitType")}</label>
                                    <select
                                        className="w-full bg-gray-50 border border-gray-100 p-3 rounded-xl text-xs focus:outline-none focus:ring-2 focus:ring-blue-600/10 focus:border-blue-600/30 transition-all appearance-none"
                                        value={formData.type}
                                        onChange={ev => setFormData({ ...formData, type: ev.target.value })}
                                    >
                                        <option value="STUDIO">Studio</option>
                                        <option value="BHK1">1 BHK</option>
                                        <option value="BHK2">2 BHK</option>
                                        <option value="BHK3">3 BHK</option>
                                        <option value="OFFICE">Office</option>
                                        <option value="RETAIL">Retail</option>
                                    </select>
                                </div>
                                <div className="flex-1">
                                    <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-wider mb-1.5 ml-1">{t("sizeSqft")}</label>
                                    <input
                                        type="number"
                                        placeholder="Sq. Ft."
                                        className="w-full bg-gray-50 border border-gray-100 p-3 rounded-xl text-xs placeholder:text-gray-300 focus:outline-none focus:ring-2 focus:ring-blue-600/10 focus:border-blue-600/30 transition-all"
                                        value={formData.sizeSqft === 0 ? "" : formData.sizeSqft}
                                        onChange={ev => setFormData({ ...formData, sizeSqft: Number(ev.target.value) })}
                                    />
                                </div>
                            </div>
                            <div className="flex justify-end gap-3 mt-4">
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

            <div className="bg-white rounded-[2rem] border border-gray-100 shadow-[0_20px_50px_rgba(0,0,0,0.02)] overflow-hidden">
                <table className="w-full text-left">
                    <thead>
                        <tr className="bg-gray-50/50 border-b border-gray-100">
                            <th className="p-5 text-[10px] font-black uppercase tracking-widest text-gray-400">
                                <div className="flex items-center gap-2">
                                    <Hash size={12} />
                                    {t("unitNumber")}
                                </div>
                            </th>
                            <th className="p-5 text-[10px] font-black uppercase tracking-widest text-gray-400">
                                <div className="flex items-center gap-2">
                                    <LayoutList size={12} />
                                    {t("unitType")}
                                </div>
                            </th>
                            <th className="p-5 text-[10px] font-black uppercase tracking-widest text-gray-400">
                                <div className="flex items-center gap-2">
                                    <Ruler size={12} />
                                    {t("sizeSqft")}
                                </div>
                            </th>
                            <th className="p-5 text-[10px] font-black uppercase tracking-widest text-gray-400">
                                <div className="flex items-center gap-2">
                                    <Info size={12} />
                                    {t("status")}
                                </div>
                            </th>
                        </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-50">
                        {units.map(u => (
                            <tr key={u.id} className="group hover:bg-gray-50/50 transition-colors">
                                <td className="p-5">
                                    <span className="text-sm font-black text-foreground group-hover:text-primary transition-colors">
                                        {u.unitNumber}
                                    </span>
                                </td>
                                <td className="p-5">
                                    <span className="text-xs font-bold text-gray-500 uppercase tracking-wider">{u.type}</span>
                                </td>
                                <td className="p-5">
                                    <span className="text-xs font-bold text-gray-900">{u.sizeSqft} <span className="text-[10px] text-gray-400">sqft</span></span>
                                </td>
                                <td className="p-5 text-right md:text-left">
                                    <span className={cn(
                                        "px-2.5 py-1 rounded-full text-[10px] font-black uppercase tracking-widest",
                                        u.status === 'VACANT' ? 'bg-emerald-50 text-emerald-600' :
                                            u.status === 'OCCUPIED' ? 'bg-primary/5 text-primary' :
                                                'bg-amber-50 text-amber-600'
                                    )}>
                                        {u.status}
                                    </span>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
                {units.length === 0 && (
                    <div className="p-20 text-center flex flex-col items-center">
                        <div className="w-12 h-12 bg-gray-50 rounded-xl flex items-center justify-center text-gray-200 mb-4">
                            <Building size={24} />
                        </div>
                        <p className="text-[10px] font-bold text-gray-400 uppercase tracking-[0.2em]">
                            Inventory is empty
                        </p>
                    </div>
                )}
            </div>
        </div>
    );
}

"use client";

import { useState, useEffect, useCallback } from "react";
import { useTranslations } from "next-intl";
import { Plus, X, Building2, Hash, Settings2, ShieldCheck, Loader2, Search, Pencil, Copy, Check, Zap, Phone, Upload, Stamp } from "lucide-react";
import { cn } from "@/lib/utils";
import { Pagination } from "@/components/ui/Pagination";
import { FileUpload } from "@/components/ui/FileUpload";

type Tenant = { id: string; name: string; status: string; address?: string; trn?: string; logoUrl?: string; ticketOtpRequired?: boolean; phone?: string; stampImageUrl?: string; createdAt: string };

type FeatureToggle = {
  feature: string;
  label: string;
  defaultEnabled: boolean;
  enabled: boolean;
};

export default function SuperAdminTenantsPage() {
    const t = useTranslations("Index");
    const [tenants, setTenants] = useState<Tenant[]>([]);
    const [loading, setLoading] = useState(true);
    const [submitting, setSubmitting] = useState(false);
    const [showForm, setShowForm] = useState(false);
    const [editingTenant, setEditingTenant] = useState<Tenant | null>(null);
    const [formData, setFormData] = useState({ name: "", address: "", trn: "", status: "ACTIVE", logoUrl: "", ticketOtpRequired: true, phone: "", stampImageUrl: "" });
    const [uploadingStamp, setUploadingStamp] = useState(false);
    const [searchQuery, setSearchQuery] = useState("");
    const [currentPage, setCurrentPage] = useState(1);
    const [itemsPerPage, setItemsPerPage] = useState(25);
    const [copiedId, setCopiedId] = useState<string | null>(null);
    const [featuresDrawerTenant, setFeaturesDrawerTenant] = useState<Tenant | null>(null);
    const [features, setFeatures] = useState<FeatureToggle[]>([]);
    const [featuresLoading, setFeaturesLoading] = useState(false);
    const [featuresUpdating, setFeaturesUpdating] = useState<string | null>(null);

    useEffect(() => {
        fetchTenants();
    }, []);

    const fetchTenants = async () => {
        setLoading(true);
        try {
            const res = await fetch("/api/proxy/admin/tenants");
            if (res.ok) {
                const data = await res.json();
                data.sort((a: Tenant, b: Tenant) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime());
                setTenants(data);
            }
        } catch (e) {
            console.error(e);
        } finally {
            setLoading(false);
        }
    };

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!formData.name) return;
        setSubmitting(true);
        try {
            const url = editingTenant
                ? `/api/proxy/admin/tenants/${editingTenant.id}`
                : "/api/proxy/admin/tenants";
            const res = await fetch(url, {
                method: editingTenant ? "PUT" : "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(formData),
            });
            if (res.ok) {
                resetForm();
                fetchTenants();
            }
        } catch (e) {
            console.error(e);
        } finally {
            setSubmitting(false);
        }
    };

    const openEdit = (tenant: Tenant) => {
        setEditingTenant(tenant);
        setFormData({ name: tenant.name, address: tenant.address || "", trn: tenant.trn || "", status: tenant.status || "ACTIVE", logoUrl: tenant.logoUrl || "", ticketOtpRequired: tenant.ticketOtpRequired !== false, phone: tenant.phone || "", stampImageUrl: tenant.stampImageUrl || "" });
        setShowForm(true);
    };

    const resetForm = () => {
        setShowForm(false);
        setEditingTenant(null);
        setFormData({ name: "", address: "", trn: "", status: "ACTIVE", logoUrl: "", ticketOtpRequired: true, phone: "", stampImageUrl: "" });
    };

    const handleStampUpload = async (file: File) => {
        if (!editingTenant) return;
        setUploadingStamp(true);
        try {
            const fd = new FormData();
            fd.append("file", file);
            const res = await fetch(`/api/proxy/admin/tenants/${editingTenant.id}/stamp`, {
                method: "POST",
                body: fd,
            });
            if (res.ok) {
                const data = await res.json();
                setFormData(prev => ({ ...prev, stampImageUrl: data.stampImageUrl || "" }));
                // Refresh tenant list so table reflects new stamp
                fetchTenants();
            }
        } catch (e) {
            console.error(e);
        } finally {
            setUploadingStamp(false);
        }
    };

    const closeFeaturesDrawer = useCallback(() => setFeaturesDrawerTenant(null), []);

    useEffect(() => {
        const handleEscape = (e: KeyboardEvent) => { if (e.key === "Escape") closeFeaturesDrawer(); };
        if (featuresDrawerTenant) {
            document.addEventListener("keydown", handleEscape);
            return () => document.removeEventListener("keydown", handleEscape);
        }
    }, [featuresDrawerTenant, closeFeaturesDrawer]);

    const openFeaturesDrawer = async (tenant: Tenant) => {
        setFeaturesDrawerTenant(tenant);
        setFeaturesLoading(true);
        try {
            const res = await fetch(`/api/proxy/admin/tenants/${tenant.id}/features`);
            if (res.ok) setFeatures(await res.json());
        } finally {
            setFeaturesLoading(false);
        }
    };

    const toggleFeature = async (feature: string, enabled: boolean) => {
        if (!featuresDrawerTenant) return;
        setFeatures(prev => prev.map(f => f.feature === feature ? { ...f, enabled } : f));
        setFeaturesUpdating(feature);
        try {
            const res = await fetch(
                `/api/proxy/admin/tenants/${featuresDrawerTenant.id}/features/${feature}`,
                { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ enabled }) }
            );
            if (!res.ok) {
                setFeatures(prev => prev.map(f => f.feature === feature ? { ...f, enabled: !enabled } : f));
            }
        } catch {
            setFeatures(prev => prev.map(f => f.feature === feature ? { ...f, enabled: !enabled } : f));
        } finally {
            setFeaturesUpdating(null);
        }
    };

    const filteredTenants = searchQuery
        ? tenants.filter((t) =>
            t.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
            t.id.toLowerCase().includes(searchQuery.toLowerCase())
        )
        : tenants;

    const paginatedTenants = filteredTenants.slice((currentPage - 1) * itemsPerPage, currentPage * itemsPerPage);

    return (
        <div className="p-8 max-w-7xl mx-auto">
            <div className="flex items-center justify-between mb-8">
                <div>
                    <h1 className="mb-1">Super Admin: Manage Tenants</h1>
                    <p className="text-sm text-muted">System-wide infrastructure and data isolation control.</p>
                </div>
                <div className="flex items-center gap-3">
                    <div className="relative">
                        <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
                        <input
                            type="text"
                            placeholder="Search..."
                            value={searchQuery}
                            onChange={(e) => { setSearchQuery(e.target.value); setCurrentPage(1); }}
                            className="pl-9 pr-4 py-2 bg-surface border border-border rounded-lg text-sm text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none w-64 transition-all"
                        />
                    </div>
                    <button
                        onClick={() => { resetForm(); setShowForm(true); }}
                        className="flex items-center gap-2 bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer"
                    >
                        <Plus size={14} />
                        Provision New Organization
                    </button>
                </div>
            </div>

            {/* Create / Edit Modal */}
            {showForm && (
                <div className="fixed inset-0 bg-foreground/40 backdrop-blur-sm flex items-center justify-center p-4 z-[100]">
                    <div className="bg-surface rounded-xl p-8 max-w-md w-full shadow-2xl border border-border relative">
                        <button
                            onClick={resetForm}
                            aria-label="Close"
                            className="absolute right-6 top-6 p-2 text-muted hover:text-foreground transition-all duration-200 cursor-pointer rounded-lg"
                        >
                            <X size={18} />
                        </button>

                        <h2 className="text-lg font-bold mb-1 text-foreground">
                            {editingTenant ? "Edit Organization" : "Provision New Organization"}
                        </h2>
                        <p className="text-xs text-muted mb-6 font-medium">
                            {editingTenant ? "Update organization details." : "Register a new client entity onto the platform."}
                        </p>

                        <form onSubmit={handleSubmit} className="space-y-4">
                            {/* Logo Upload */}
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">Organization Logo</label>
                                <FileUpload
                                    value={formData.logoUrl}
                                    onChange={(url) => setFormData({ ...formData, logoUrl: url })}
                                    onRemove={() => setFormData({ ...formData, logoUrl: "" })}
                                    folder="assets"
                                    label="Upload Company Logo"
                                    hint="PNG, JPG or SVG. Max 2MB. Drag & drop or click to browse."
                                />
                            </div>

                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">Organization Name</label>
                                <input
                                    required
                                    placeholder="e.g. Al Futtaim Properties"
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs text-foreground placeholder:text-muted/50 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
                                    value={formData.name}
                                    onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">Address</label>
                                <textarea
                                    rows={2}
                                    placeholder="Office address (shown on receipts)"
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs text-foreground placeholder:text-muted/50 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all resize-none"
                                    value={formData.address}
                                    onChange={(e) => setFormData({ ...formData, address: e.target.value })}
                                />
                            </div>
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">TRN (Tax Registration Number)</label>
                                <input
                                    placeholder="e.g. 100XXXXXXXXX"
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs text-foreground placeholder:text-muted/50 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
                                    value={formData.trn}
                                    onChange={(e) => setFormData({ ...formData, trn: e.target.value })}
                                />
                            </div>
                            {/* Phone */}
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1 flex items-center gap-1"><Phone size={10} /> Phone</label>
                                <input
                                    type="tel"
                                    placeholder="+971 50 123 4567"
                                    maxLength={40}
                                    className="w-full border border-border rounded-lg bg-surface p-3 text-xs text-foreground placeholder:text-muted/50 focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all"
                                    value={formData.phone}
                                    onChange={(e) => setFormData({ ...formData, phone: e.target.value })}
                                />
                            </div>
                            {/* Stamp / Seal */}
                            <div>
                                <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1 flex items-center gap-1"><Stamp size={10} /> Stamp / Seal</label>
                                {formData.stampImageUrl && (
                                    <img src={formData.stampImageUrl} alt="Stamp" className="max-h-24 max-w-24 border border-border rounded-lg object-contain mb-2 bg-white p-1" />
                                )}
                                <label className={cn(
                                    "flex items-center gap-2 px-3 py-2 rounded-lg text-xs font-semibold transition-colors",
                                    uploadingStamp ? "bg-input text-muted cursor-not-allowed" : "bg-primary/10 text-primary hover:bg-primary/20 cursor-pointer"
                                )}>
                                    {uploadingStamp ? <Loader2 size={12} className="animate-spin" /> : <Upload size={12} />}
                                    {formData.stampImageUrl ? "Replace Stamp" : "Upload Stamp"}
                                    <input
                                        type="file"
                                        accept="image/*"
                                        className="hidden"
                                        disabled={uploadingStamp || !editingTenant}
                                        onChange={(e) => {
                                            const f = e.target.files?.[0];
                                            if (f) handleStampUpload(f);
                                            if (e.target) e.target.value = "";
                                        }}
                                    />
                                </label>
                                {!editingTenant && (
                                    <p className="text-[10px] text-muted mt-1">Save the organization first, then upload stamp.</p>
                                )}
                            </div>
                            {editingTenant && (
                                <div>
                                    <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider mb-1.5 ml-1">Status</label>
                                    <select
                                        value={formData.status}
                                        onChange={(e) => setFormData({ ...formData, status: e.target.value })}
                                        className="w-full border border-border rounded-lg bg-surface p-3 text-xs text-foreground focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all cursor-pointer"
                                    >
                                        <option value="ACTIVE">Active</option>
                                        <option value="INACTIVE">Inactive</option>
                                    </select>
                                </div>
                            )}
                            <div className="flex items-center justify-between">
                                <div>
                                    <label className="block text-[10px] font-semibold text-muted uppercase tracking-wider">Ticket Closure OTP</label>
                                    <p className="text-[9px] text-muted mt-0.5">Require renters to share OTP to close tickets</p>
                                </div>
                                <button
                                    type="button"
                                    onClick={() => setFormData({ ...formData, ticketOtpRequired: !formData.ticketOtpRequired })}
                                    className={cn("w-10 h-5 rounded-full transition-colors cursor-pointer", formData.ticketOtpRequired ? "bg-primary" : "bg-muted/40")}
                                >
                                    <div className={cn("w-4 h-4 bg-white rounded-full transition-transform shadow-sm", formData.ticketOtpRequired ? "translate-x-5" : "translate-x-0.5")} />
                                </button>
                            </div>
                            <div className="flex justify-end gap-3 pt-2">
                                <button
                                    type="button"
                                    onClick={resetForm}
                                    className="bg-surface text-foreground border border-border px-4 py-2 rounded-lg text-xs font-semibold hover:bg-input transition-all cursor-pointer"
                                >
                                    Cancel
                                </button>
                                <button
                                    type="submit"
                                    disabled={submitting}
                                    className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer disabled:opacity-50 flex items-center gap-2"
                                >
                                    {submitting && <Loader2 size={14} className="animate-spin" />}
                                    {editingTenant ? "Save Changes" : "Create Organization"}
                                </button>
                            </div>
                        </form>
                    </div>
                </div>
            )}

            {loading ? (
                <div className="bg-surface rounded-xl border border-border overflow-hidden p-6 space-y-4">
                    {[...Array(4)].map((_, i) => (
                        <div key={i} className="flex gap-6 animate-pulse">
                            <div className="h-4 bg-input rounded-lg w-1/4" />
                            <div className="h-4 bg-input rounded-lg w-1/3" />
                            <div className="h-4 bg-input rounded-lg w-1/6" />
                        </div>
                    ))}
                </div>
            ) : (
            <div className="bg-surface rounded-xl border border-border overflow-hidden">
                <table className="w-full text-left">
                    <thead>
                        <tr className="bg-input/50">
                            <th className="px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">Organization Name</th>
                            <th className="px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">Address</th>
                            <th className="px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">TRN</th>
                            <th className="px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider">Status</th>
                            <th className="px-5 py-3.5 text-[11px] font-semibold text-muted uppercase tracking-wider text-right">Actions</th>
                        </tr>
                    </thead>
                    <tbody>
                        {paginatedTenants.map(tenant => (
                            <tr key={tenant.id} className="border-b border-border hover:bg-input/30 transition-colors">
                                <td className="px-5 py-3.5">
                                    <div>
                                        <span className="text-sm font-semibold text-foreground">{tenant.name}</span>
                                        <p className="text-[10px] text-muted font-mono mt-0.5 flex items-center gap-1">
                                            {tenant.id}
                                            <button
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    navigator.clipboard.writeText(tenant.id);
                                                    setCopiedId(tenant.id);
                                                    setTimeout(() => setCopiedId(null), 2000);
                                                }}
                                                className="p-0.5 rounded text-muted hover:text-foreground transition-colors cursor-pointer"
                                                title="Copy ID"
                                            >
                                                {copiedId === tenant.id ? <Check size={10} className="text-success" /> : <Copy size={10} />}
                                            </button>
                                        </p>
                                    </div>
                                </td>
                                <td className="px-5 py-3.5 text-xs text-muted max-w-[200px] truncate">
                                    {tenant.address || "—"}
                                </td>
                                <td className="px-5 py-3.5 text-xs text-foreground font-mono">
                                    {tenant.trn || "—"}
                                </td>
                                <td className="px-5 py-3.5">
                                    <span className={cn(
                                        "px-2.5 py-1 rounded-lg text-[10px] font-semibold",
                                        tenant.status === 'ACTIVE' ? 'bg-success/10 text-success' : 'bg-input text-muted'
                                    )}>
                                        {tenant.status || 'ACTIVE'}
                                    </span>
                                </td>
                                <td className="px-5 py-3.5 text-right">
                                    <div className="inline-flex items-center gap-1">
                                        <button
                                            onClick={() => openFeaturesDrawer(tenant)}
                                            title="Feature Toggles"
                                            className="p-1.5 rounded hover:bg-neutral-100 text-neutral-500 hover:text-blue-600 transition-colors cursor-pointer"
                                        >
                                            <Zap size={15} />
                                        </button>
                                        <button
                                            onClick={() => openEdit(tenant)}
                                            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-semibold text-primary hover:bg-primary/10 rounded-lg transition-colors cursor-pointer"
                                        >
                                            <Pencil size={12} />
                                            Edit
                                        </button>
                                    </div>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
                {filteredTenants.length === 0 && (
                    <div className="p-24 text-center flex flex-col items-center">
                        <div className="w-16 h-16 bg-input rounded-xl flex items-center justify-center text-muted mb-6 border border-dashed border-border">
                            <ShieldCheck size={32} />
                        </div>
                        <p className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                            No organizations provisioned yet.
                        </p>
                    </div>
                )}
                {filteredTenants.length > 0 && (
                    <div className="px-5 pb-4">
                        <Pagination
                            currentPage={currentPage}
                            totalItems={filteredTenants.length}
                            itemsPerPage={itemsPerPage}
                            onPageChange={setCurrentPage}
                            onItemsPerPageChange={(n) => { setItemsPerPage(n); setCurrentPage(1); }}
                        />
                    </div>
                )}
            </div>
            )}

            {/* Features Drawer */}
            {featuresDrawerTenant && (
              <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40" onClick={closeFeaturesDrawer}>
                <div className="bg-white rounded-xl shadow-xl w-full max-w-md mx-4" onClick={e => e.stopPropagation()}>
                  <div className="flex items-center justify-between px-5 py-4 border-b border-neutral-100">
                    <div>
                      <h2 className="font-semibold text-neutral-900 text-sm">{t("featureToggles")}</h2>
                      <p className="text-xs text-neutral-500 mt-0.5">{featuresDrawerTenant.name}</p>
                    </div>
                    <button
                      onClick={closeFeaturesDrawer}
                      className="p-1.5 rounded hover:bg-neutral-100 text-neutral-500 cursor-pointer"
                    >
                      <X size={16} />
                    </button>
                  </div>
                  <div className="p-5">
                    {featuresLoading ? (
                      <div className="flex items-center justify-center py-8 text-neutral-400">
                        <Loader2 size={20} className="animate-spin mr-2" /> {t("loading")}
                      </div>
                    ) : features.length === 0 ? (
                      <p className="text-sm text-neutral-500 text-center py-6">{t("noFeaturesAvailable")}</p>
                    ) : (
                      <div className="space-y-4">
                        {features.map(f => (
                          <div key={f.feature} className="flex items-center justify-between gap-4">
                            <div>
                              <p className="text-sm font-medium text-neutral-800">{f.label}</p>
                              <p className="text-xs text-neutral-400 mt-0.5">
                                {t("default")}: {f.defaultEnabled ? t("on") : t("off")}
                              </p>
                            </div>
                            <button
                              disabled={featuresUpdating === f.feature}
                              onClick={() => toggleFeature(f.feature, !f.enabled)}
                              className={cn(
                                "relative w-11 h-6 rounded-full transition-colors cursor-pointer flex-shrink-0 disabled:opacity-60",
                                f.enabled ? "bg-blue-600" : "bg-neutral-200"
                              )}
                            >
                              <span className={cn(
                                "absolute top-0.5 left-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform",
                                f.enabled ? "translate-x-5" : "translate-x-0"
                              )} />
                            </button>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </div>
              </div>
            )}
        </div>
    );
}

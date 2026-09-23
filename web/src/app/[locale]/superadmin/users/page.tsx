"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { Plus, X, Users, Search, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";
import { ConfirmDialog } from "@/components/ui/confirm-dialog";
import { Pagination } from "@/components/ui/Pagination";
import { assignableRoles, getRoleLabel, getRoleLabelKey, PROVISIONABLE_ROLES, type UserRole } from "@/lib/rbac";
import { ApiError, throwIfNotOk } from "@/lib/api/facilities";

// Derived from PROVISIONABLE_ROLES rather than hand-listed: a hand-written copy
// is how ACCOUNTANT came to be grantable by the API but absent from this form.
const ALL_ROLE_OPTIONS: { value: UserRole; label: string }[] =
    PROVISIONABLE_ROLES.map((value) => ({ value, label: getRoleLabel(value) }));

type User = { id: string; name: string; email: string; role: string; tenantId: string };

export default function SuperAdminUsersPage() {
    const tRoles = useTranslations("Roles");
    // t.has guards a role the catalogue does not know; getRoleLabel is the
    // English fallback rather than letting next-intl throw.
    const roleLabel = (role: string) =>
        tRoles.has(getRoleLabelKey(role)) ? tRoles(getRoleLabelKey(role)) : getRoleLabel(role);
    const t = useTranslations("Index"); // Or custom namespace
    const { data: session } = useSession();
    const currentRole = session?.user?.role as UserRole | undefined;
    const currentTenantId = (session?.user?.tenantId as string | undefined) || "";
    const isSuperAdmin = currentRole === "SUPER_ADMIN";
    const [users, setUsers] = useState<User[]>([]);
    const [loading, setLoading] = useState(true);
    const [showForm, setShowForm] = useState(false);
    const [submitting, setSubmitting] = useState(false);
    const [editingUserId, setEditingUserId] = useState<string | null>(null);
    const [searchQuery, setSearchQuery] = useState("");
    const [currentPage, setCurrentPage] = useState(1);
    const [itemsPerPage, setItemsPerPage] = useState(25);

    // Delete dialog state
    const [deleteDialogOpen, setDeleteDialogOpen] = useState(false);
    const [deleting, setDeleting] = useState(false);
    const [userToDelete, setUserToDelete] = useState<string | null>(null);
    const [deleteError, setDeleteError] = useState<string | null>(null);

    // Form error (create/update failures, e.g. duplicate email or 403)
    const [formError, setFormError] = useState<string | null>(null);

    // Form state
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [name, setName] = useState("");
    const [role, setRole] = useState("TENANT_USER");
    const [tenantId, setTenantId] = useState("");
    const [phoneNumber, setPhoneNumber] = useState("");

    // Roles at or below the signed-in admin's level may be provisioned. The
    // currently-selected role is always included so the <select> never renders
    // a value with no matching <option> — covers both the session-loading
    // window (currentRole undefined) and editing a user whose existing role
    // (e.g. RENTER) isn't normally provisionable from this page.
    const roleOptions = (() => {
        const allowed = ALL_ROLE_OPTIONS.filter((o) => assignableRoles(currentRole).includes(o.value));
        if (role && !allowed.some((o) => o.value === role)) {
            return [...allowed, { value: role as UserRole, label: roleLabel(role) }];
        }
        return allowed;
    })();

    type Tenant = { id: string; name: string };
    const [tenants, setTenants] = useState<Tenant[]>([]);

    // Properties for Property Manager assignment
    const [properties, setProperties] = useState<any[]>([]);
    const [selectedPropertyIds, setSelectedPropertyIds] = useState<string[]>([]);

    useEffect(() => {
        fetchUsers();
        fetchTenants();
        fetchProperties();
    }, []);

    const fetchUsers = async () => {
        setLoading(true);
        try {
            const res = await fetch("/api/proxy/admin/users");
            if (res.ok) {
                const data = await res.json();
                data.sort((a: any, b: any) => (a.id || '').localeCompare(b.id || ''));
                setUsers(data);
            }
        } catch (e) {
            console.error(e);
        } finally {
            setLoading(false);
        }
    };

    const fetchTenants = async () => {
        try {
            const res = await fetch("/api/proxy/admin/tenants");
            if (res.ok) {
                const data = await res.json();
                setTenants(data);
            }
        } catch (e) {
            console.error("Failed to fetch tenants:", e);
        }
    };

    const fetchProperties = async () => {
        try {
            const res = await fetch("/api/proxy/v1/properties");
            if (res.ok) {
                setProperties(await res.json());
            }
        } catch (e) {
            console.error(e);
        }
    };

    const resetForm = () => {
        setEditingUserId(null);
        // Non-super-admins can only provision into their own tenant.
        setEmail(""); setPassword(""); setName(""); setTenantId(isSuperAdmin ? "" : currentTenantId); setRole("TENANT_USER"); setSelectedPropertyIds([]);
        setPhoneNumber("");
        setFormError(null);
    };

    const handleSubmitUser = async (e: React.FormEvent) => {
        e.preventDefault();
        setSubmitting(true);
        setFormError(null);
        try {
            const bodyData: any = { email, name, role, tenantId, phoneNumber };
            if (password) bodyData.password = password;

            if (role === 'PROPERTY_MANAGER') {
                // Always send the list (including []) — the backend skips the
                // property-assignment sync entirely when propertyIds is null,
                // so omitting an empty selection would silently keep
                // assignments the admin believes were revoked.
                bodyData.propertyIds = selectedPropertyIds;
            }

            const url = editingUserId
                ? `/api/proxy/admin/users/${editingUserId}`
                : "/api/proxy/admin/users";
            const method = editingUserId ? "PUT" : "POST";

            const res = await fetch(url, {
                method,
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(bodyData),
            });
            // Surface backend failures (duplicate email, guard-phone conflict,
            // role-hierarchy 403, ...) instead of leaving the form open silently.
            await throwIfNotOk(res);
            setShowForm(false);
            resetForm();
            fetchUsers();
        } catch (e) {
            console.error(e);
            setFormError(e instanceof ApiError ? e.message : "Something went wrong. Please try again.");
        } finally {
            setSubmitting(false);
        }
    };

    const confirmDelete = async () => {
        if (!userToDelete) return;
        setDeleting(true);
        setDeleteError(null);
        try {
            const res = await fetch(`/api/proxy/admin/users/${userToDelete}`, { method: "DELETE" });
            await throwIfNotOk(res);
            fetchUsers();
        } catch (e) {
            console.error(e);
            setDeleteError(e instanceof ApiError ? e.message : "Failed to delete the user. Please try again.");
        } finally {
            setDeleting(false);
            setDeleteDialogOpen(false);
            setUserToDelete(null);
        }
    };

    const handleDeleteClick = (id: string) => {
        setUserToDelete(id);
        setDeleteError(null);
        setDeleteDialogOpen(true);
    };

    const handleEdit = async (user: User) => {
        setFormError(null);
        setEditingUserId(user.id);
        setName(user.name);
        setEmail(user.email);
        setRole(user.role);
        // Non-super-admins can only operate within their own tenant; never let an
        // edited user's stored tenantId override that scope.
        setTenantId(isSuperAdmin ? (user.tenantId || "") : currentTenantId);
        setPhoneNumber((user as any).phoneNumber || "");
        setPassword("");

        // Fetch existing assignments if it's a Property Manager
        if (user.role === 'PROPERTY_MANAGER') {
            try {
                const res = await fetch(`/api/proxy/admin/users/${user.id}/properties`);
                if (res.ok) {
                    const ids = await res.json();
                    setSelectedPropertyIds(ids);
                }
            } catch (e) {
                console.error("Failed to fetch property assignments:", e);
            }
        } else {
            setSelectedPropertyIds([]);
        }

        setShowForm(true);
    };

    const filteredUsers = searchQuery
        ? users.filter((u) =>
            u.name.toLowerCase().includes(searchQuery.toLowerCase()) ||
            u.email.toLowerCase().includes(searchQuery.toLowerCase()) ||
            u.role.toLowerCase().includes(searchQuery.toLowerCase())
        )
        : users;

    const paginatedUsers = filteredUsers.slice((currentPage - 1) * itemsPerPage, currentPage * itemsPerPage);

    return (
        <div className="space-y-6 max-w-5xl mx-auto w-full pb-20">
            {/* Header Area */}
            <div className="flex items-center justify-between mb-8">
                <div>
                    <h1 className="mb-1">Super Admin: Manage Users</h1>
                    <p className="text-sm text-muted">View and provision system-wide users across all tenants.</p>
                </div>
                <div className="flex items-center gap-3">
                    <div className="relative group">
                        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted group-focus-within:text-primary transition-colors" />
                        <input
                            type="text"
                            placeholder="Search users..."
                            value={searchQuery}
                            onChange={(e) => { setSearchQuery(e.target.value); setCurrentPage(1); }}
                            className="w-64 border border-border rounded-lg bg-surface text-foreground p-2.5 pl-9 text-xs placeholder:text-muted focus:outline-none focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all duration-200 font-medium shadow-sm shadow-black/[0.02]"
                        />
                    </div>
                    <button
                        onClick={() => { resetForm(); setShowForm(true); }}
                        className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer flex items-center gap-2"
                    >
                        <Plus size={14} />
                        New User
                    </button>
                </div>
            </div>

            {/* Delete error banner */}
            {deleteError && (
                <div className="bg-error/10 border border-error/20 text-error text-xs font-medium rounded-lg px-4 py-3 flex items-center justify-between gap-3" role="alert">
                    <span>{deleteError}</span>
                    <button
                        onClick={() => setDeleteError(null)}
                        aria-label="Dismiss"
                        className="text-error hover:text-error/70 transition-colors cursor-pointer shrink-0"
                    >
                        <X size={14} />
                    </button>
                </div>
            )}

            {/* Content Area */}
            {loading ? (
                <div className="bg-surface rounded-xl border border-border shadow-sm p-8 space-y-5">
                    {[...Array(5)].map((_, i) => (
                        <div key={i} className="flex gap-6 animate-pulse">
                            <div className="h-4 bg-input rounded-lg w-1/5" />
                            <div className="h-4 bg-input rounded-lg w-1/4" />
                            <div className="h-4 bg-input rounded-lg w-1/6" />
                            <div className="h-4 bg-input rounded-lg w-1/6" />
                            <div className="h-4 bg-input rounded-lg w-1/6" />
                        </div>
                    ))}
                </div>
            ) : (
                <div className="bg-surface rounded-xl border border-border overflow-hidden shadow-sm">
                    <div className="p-6 md:p-8">
                        {users.length === 0 ? (
                            <div className="text-center py-20 px-6">
                                <div className="w-16 h-16 bg-input rounded-xl flex items-center justify-center mx-auto mb-6 border border-border/50">
                                    <Users className="w-8 h-8 text-muted" />
                                </div>
                                <h3 className="text-base font-bold text-foreground mb-2">No users found</h3>
                                <p className="text-xs text-muted max-w-sm mx-auto font-medium">
                                    No users have been registered in the system yet. Click "New User" to add one.
                                </p>
                            </div>
                        ) : (
                            <div className="overflow-x-auto">
                                <table className="w-full text-left border-collapse">
                                    <thead>
                                        <tr className="border-b border-border">
                                            <th className="pb-4 bg-input/50 text-[11px] font-semibold text-muted uppercase tracking-wider px-2">Name</th>
                                            <th className="pb-4 bg-input/50 text-[11px] font-semibold text-muted uppercase tracking-wider px-2">Email</th>
                                            <th className="pb-4 bg-input/50 text-[11px] font-semibold text-muted uppercase tracking-wider px-2">Phone</th>
                                            <th className="pb-4 bg-input/50 text-[11px] font-semibold text-muted uppercase tracking-wider px-2">Role</th>
                                            <th className="pb-4 bg-input/50 text-[11px] font-semibold text-muted uppercase tracking-wider px-2">Tenant ID</th>
                                            <th className="pb-4 bg-input/50 text-[11px] font-semibold text-muted uppercase tracking-wider px-2 text-right">Actions</th>
                                        </tr>
                                    </thead>
                                    <tbody className="text-xs font-medium text-foreground">
                                        {paginatedUsers.map((u) => (
                                            <tr key={u.id} className="border-b border-border hover:bg-input/30 transition-colors group">
                                                <td className="py-4 px-2 font-bold">{u.name}</td>
                                                <td className="py-4 px-2 text-muted">{u.email}</td>
                                                <td className="py-4 px-2 text-muted font-mono text-[10px]">{(u as any).phoneNumber || "-"}</td>
                                                <td className="py-4 px-2">
                                                    <span className={cn(
                                                        "px-2.5 py-1 rounded-lg text-[10px] font-bold tracking-wide",
                                                        u.role === 'SUPER_ADMIN' ? "bg-primary/10 text-primary border border-primary/20" :
                                                            u.role === 'TENANT_ADMIN' ? "bg-accent/20 text-accent-foreground border border-accent/30" :
                                                                "bg-input text-foreground border border-border"
                                                    )}>
                                                        {u.role.replace('_', ' ')}
                                                    </span>
                                                </td>
                                                <td className="py-4 px-2 text-muted font-mono text-[10px] truncate max-w-[120px]">
                                                    {u.tenantId || "N/A"}
                                                </td>
                                                <td className="py-4 px-2 text-right">
                                                    <div className="flex justify-end gap-2">
                                                        <button
                                                            onClick={() => handleEdit(u)}
                                                            className="text-xs px-3 py-1.5 bg-primary/10 text-primary rounded-lg hover:bg-primary/20 transition-all duration-200 font-bold cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                                        >
                                                            Edit
                                                        </button>
                                                        <button
                                                            onClick={() => handleDeleteClick(u.id)}
                                                            className="text-xs px-3 py-1.5 bg-error/10 text-error rounded-lg hover:bg-error/20 transition-all duration-200 font-bold cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                                                        >
                                                            Delete
                                                        </button>
                                                    </div>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                                <Pagination
                                    currentPage={currentPage}
                                    totalItems={filteredUsers.length}
                                    itemsPerPage={itemsPerPage}
                                    onPageChange={setCurrentPage}
                                    onItemsPerPageChange={(n) => { setItemsPerPage(n); setCurrentPage(1); }}
                                />
                            </div>
                        )}
                    </div>
                </div>
            )}

            {/* Slide-over Form Overlay */}
            {showForm && (
                <div className="fixed inset-0 z-[100] flex justify-end">
                    <div className="absolute inset-0 bg-black/20 backdrop-blur-sm transition-opacity" onClick={() => setShowForm(false)} />

                    <div className="relative w-full max-w-md bg-surface h-full shadow-2xl flex flex-col animate-in slide-in-from-right duration-300">
                        <div className="flex items-center justify-between p-6 border-b border-border">
                            <div>
                                <h2 className="text-lg font-bold mb-1 text-foreground leading-tight">
                                    {editingUserId ? "Edit User" : "Provision New User"}
                                </h2>
                                <p className="text-[11px] font-medium text-muted uppercase tracking-wider">System Administration</p>
                            </div>
                            <button
                                onClick={() => setShowForm(false)}
                                aria-label="Close"
                                className="w-8 h-8 bg-input rounded-lg flex items-center justify-center text-muted hover:bg-input/80 transition-all duration-200 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                            >
                                <X size={16} />
                            </button>
                        </div>

                        <div className="p-6 flex-1 overflow-y-auto">
                            <form id="user-form" onSubmit={handleSubmitUser} className="space-y-4">
                                <div>
                                    <label className="block text-[10px] font-bold text-muted uppercase tracking-wider mb-2 ml-1">Full Name</label>
                                    <input
                                        type="text"
                                        required
                                        value={name}
                                        onChange={(e) => setName(e.target.value)}
                                        className="w-full border border-border rounded-lg bg-surface p-3 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none focus:border-primary transition-all duration-200 font-medium"
                                        placeholder="e.g. Acme Corp Admin"
                                    />
                                </div>
                                <div>
                                    <label className="block text-[10px] font-bold text-muted uppercase tracking-wider mb-2 ml-1">Email Address</label>
                                    <input
                                        type="email"
                                        required
                                        value={email}
                                        onChange={(e) => setEmail(e.target.value)}
                                        className="w-full border border-border rounded-lg bg-surface p-3 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none focus:border-primary transition-all duration-200 font-medium"
                                        placeholder="e.g. admin@acmecorp.com"
                                    />
                                </div>
                                <div>
                                    <label className="block text-[10px] font-bold text-muted uppercase tracking-wider mb-2 ml-1">Phone Number</label>
                                    <input
                                        type="tel"
                                        value={phoneNumber}
                                        onChange={(e) => setPhoneNumber(e.target.value)}
                                        className="w-full border border-border rounded-lg bg-surface p-3 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none focus:border-primary transition-all duration-200 font-medium"
                                        placeholder="e.g. +971 50 123 4567"
                                    />
                                </div>
                                <div>
                                    <label className="block text-[10px] font-bold text-muted uppercase tracking-wider mb-2 ml-1">
                                        Password {editingUserId && "(Leave blank to keep unchanged)"}
                                    </label>
                                    <input
                                        type="password"
                                        required={!editingUserId}
                                        value={password}
                                        onChange={(e) => setPassword(e.target.value)}
                                        className="w-full border border-border rounded-lg bg-surface p-3 text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none focus:border-primary transition-all duration-200 font-medium"
                                        placeholder={editingUserId ? "Leave blank to keep current" : "Secure password"}
                                    />
                                </div>
                                <div>
                                    <label className="block text-[10px] font-bold text-muted uppercase tracking-wider mb-2 ml-1">Role</label>
                                    <select
                                        value={role}
                                        onChange={(e) => setRole(e.target.value)}
                                        className="w-full border border-border rounded-lg bg-surface text-foreground p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none focus:border-primary transition-all duration-200 font-medium"
                                    >
                                        {roleOptions.map((o) => (
                                            <option key={o.value} value={o.value}>{o.label}</option>
                                        ))}
                                    </select>
                                </div>
                                {role === 'PROPERTY_MANAGER' && (
                                    <div className="space-y-3">
                                        <label className="block text-[10px] font-bold text-muted uppercase tracking-wider mb-1 ml-1">Assign Properties</label>
                                        <div className="flex flex-wrap gap-2 p-2 bg-input border border-border rounded-lg min-h-[44px]">
                                            {selectedPropertyIds.map(id => {
                                                const p = properties.find(prop => prop.property.id === id);
                                                return (
                                                    <div key={id} className="bg-primary/10 text-primary text-[10px] font-bold px-2 py-1 rounded-lg flex items-center gap-1 border border-primary/20">
                                                        {p?.property.nameEn || "Property"}
                                                        <button
                                                            type="button"
                                                            onClick={() => setSelectedPropertyIds(prev => prev.filter(i => i !== id))}
                                                            className="hover:text-error transition-colors"
                                                        >
                                                            <X size={10} />
                                                        </button>
                                                    </div>
                                                );
                                            })}
                                            <select
                                                value=""
                                                onChange={(e) => {
                                                    const val = e.target.value;
                                                    if (val && !selectedPropertyIds.includes(val)) {
                                                        setSelectedPropertyIds(prev => [...prev, val]);
                                                    }
                                                }}
                                                className="bg-transparent text-[10px] font-bold text-foreground focus:outline-none flex-1 min-w-[100px] cursor-pointer"
                                            >
                                                <option value="" disabled>Add property...</option>
                                                {properties.map((p) => (
                                                    <option
                                                        key={p.property.id}
                                                        value={p.property.id}
                                                        className={selectedPropertyIds.includes(p.property.id) ? "text-muted" : ""}
                                                    >
                                                        {p.property.nameEn || p.property.nameAr || "Unnamed Property"}
                                                    </option>
                                                ))}
                                            </select>
                                        </div>
                                    </div>
                                )}
                                {isSuperAdmin ? (
                                    <div>
                                        <label className="block text-[10px] font-bold text-muted uppercase tracking-wider mb-2 ml-1">Tenant (Organization)</label>
                                        <select
                                            value={tenantId}
                                            onChange={(e) => setTenantId(e.target.value)}
                                            className="w-full border border-border rounded-lg bg-surface text-foreground p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:outline-none focus:border-primary transition-all duration-200 font-medium"
                                        >
                                            <option value="">None (Super Admin Context)</option>
                                            {tenants.map((t) => (
                                                <option key={t.id} value={t.id}>{t.name}</option>
                                            ))}
                                        </select>
                                    </div>
                                ) : (
                                    // Tenant admins can only provision within their own tenant; the
                                    // field is shown read-only and the server enforces the same scope.
                                    <div>
                                        <label className="block text-[10px] font-bold text-muted uppercase tracking-wider mb-2 ml-1">Tenant (Organization)</label>
                                        <div className="w-full border border-border rounded-lg bg-input text-muted p-3 text-xs font-medium">
                                            {tenants.find((t) => t.id === (tenantId || currentTenantId))?.name || "Your organization"}
                                        </div>
                                    </div>
                                )}
                            </form>
                        </div>

                        <div className="p-6 border-t border-border bg-input/50 space-y-3">
                            {formError && (
                                <div className="bg-error/10 border border-error/20 text-error text-xs font-medium rounded-lg px-3 py-2" role="alert">
                                    {formError}
                                </div>
                            )}
                            <button
                                type="submit"
                                form="user-form"
                                disabled={submitting}
                                className="w-full py-3 bg-primary text-primary-foreground rounded-lg text-xs font-semibold hover:bg-primary/90 transition-all cursor-pointer flex items-center justify-center gap-2 disabled:opacity-50 disabled:cursor-not-allowed"
                            >
                                {submitting ? <Loader2 size={14} className="animate-spin" /> : <Users size={14} />}
                                {editingUserId ? "Update User" : "Provision User"}
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* Confirm Delete Dialog */}
            <ConfirmDialog
                isOpen={deleteDialogOpen}
                onClose={() => setDeleteDialogOpen(false)}
                onConfirm={confirmDelete}
                title="Delete User"
                description="Are you sure you want to delete this user? This action cannot be undone and will permanently remove the user from the system."
                confirmText="Delete User"
                isDestructive={true}
                isLoading={deleting}
            />

        </div>
    );
}

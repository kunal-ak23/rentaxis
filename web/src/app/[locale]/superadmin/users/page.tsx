"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import { Plus, X, Users, Search, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

type User = { id: string; name: string; email: string; role: string; tenantId: string };

export default function SuperAdminUsersPage() {
    const t = useTranslations("Index"); // Or custom namespace
    const [users, setUsers] = useState<User[]>([]);
    const [loading, setLoading] = useState(true);
    const [showForm, setShowForm] = useState(false);

    // Form state
    const [email, setEmail] = useState("");
    const [password, setPassword] = useState("");
    const [name, setName] = useState("");
    const [role, setRole] = useState("TENANT_USER");
    const [tenantId, setTenantId] = useState("");

    useEffect(() => {
        fetchUsers();
    }, []);

    const fetchUsers = async () => {
        setLoading(true);
        try {
            const res = await fetch("/api/proxy/admin/users");
            if (res.ok) {
                const data = await res.json();
                setUsers(data);
            }
        } catch (e) {
            console.error(e);
        } finally {
            setLoading(false);
        }
    };

    const createUser = async (e: React.FormEvent) => {
        e.preventDefault();
        try {
            const res = await fetch("/api/proxy/admin/users", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ email, password, name, role, tenantId }),
            });
            if (res.ok) {
                setShowForm(false);
                setEmail(""); setPassword(""); setName(""); setTenantId("");
                fetchUsers();
            }
        } catch (e) {
            console.error(e);
        }
    };

    return (
        <div className="space-y-6 max-w-5xl mx-auto w-full pb-20">
            {/* Header Area */}
            <div className="flex flex-col md:flex-row justify-between items-start md:items-end gap-6 mb-8 relative">
                <div className="absolute -top-12 -left-12 w-32 h-32 bg-primary/5 rounded-full blur-[80px] pointer-events-none" />

                <div className="space-y-2 relative z-10 w-full md:w-auto">
                    <div className="flex items-center gap-3 mb-1">
                        <div className="w-5 h-5 bg-blue-50 text-blue-600 rounded flex items-center justify-center border border-blue-100">
                            <Users size={12} />
                        </div>
                        <h1 className="text-xl font-black text-foreground tracking-tight">Super Admin: Manage Users</h1>
                    </div>
                    <p className="text-xs text-gray-400 font-medium">
                        View and provision system-wide users across all tenants.
                    </p>
                </div>

                <div className="flex items-center gap-3 w-full md:w-auto relative z-10">
                    <div className="relative flex-1 md:w-64 group">
                        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400 group-focus-within:text-primary transition-colors" />
                        <input
                            type="text"
                            placeholder="Search users..."
                            className="w-full bg-white border border-border p-2.5 pl-9 rounded-xl text-xs placeholder:text-gray-400 focus:outline-none focus:ring-2 focus:ring-primary/10 focus:border-primary/30 transition-all font-medium shadow-sm shadow-black/[0.02]"
                        />
                    </div>
                    <button
                        onClick={() => setShowForm(true)}
                        className="bg-primary text-primary-foreground p-2.5 px-4 rounded-xl hover:opacity-90 active:scale-95 transition-all shadow-md shadow-primary/20 flex flex-col items-center justify-center cursor-pointer group shrink-0"
                    >
                        <div className="flex items-center gap-2">
                            <Plus size={14} className="group-hover:rotate-90 transition-transform duration-300" />
                            <span className="text-xs font-black uppercase tracking-wider">New User</span>
                        </div>
                    </button>
                </div>
            </div>

            {/* Content Area */}
            {loading ? (
                <div className="h-64 flex items-center justify-center bg-white rounded-2xl border border-border shadow-sm">
                    <Loader2 className="w-8 h-8 animate-spin text-primary opacity-50" />
                </div>
            ) : (
                <div className="bg-white rounded-[2rem] border border-border overflow-hidden shadow-sm">
                    <div className="p-6 md:p-8">
                        {users.length === 0 ? (
                            <div className="text-center py-20 px-6">
                                <div className="w-16 h-16 bg-gray-50 rounded-2xl flex items-center justify-center mx-auto mb-6 border border-border/50">
                                    <Users className="w-8 h-8 text-gray-400" />
                                </div>
                                <h3 className="text-base font-black text-foreground mb-2">No users found</h3>
                                <p className="text-xs text-gray-500 max-w-sm mx-auto font-medium">
                                    No users have been registered in the system yet. Click "New User" to add one.
                                </p>
                            </div>
                        ) : (
                            <div className="overflow-x-auto">
                                <table className="w-full text-left border-collapse">
                                    <thead>
                                        <tr className="border-b border-border/50">
                                            <th className="pb-4 text-[10px] font-black uppercase tracking-widest text-gray-400">Name</th>
                                            <th className="pb-4 text-[10px] font-black uppercase tracking-widest text-gray-400">Email</th>
                                            <th className="pb-4 text-[10px] font-black uppercase tracking-widest text-gray-400">Role</th>
                                            <th className="pb-4 text-[10px] font-black uppercase tracking-widest text-gray-400">Tenant ID</th>
                                        </tr>
                                    </thead>
                                    <tbody className="text-xs font-medium text-foreground">
                                        {users.map((u) => (
                                            <tr key={u.id} className="border-b border-border/50 hover:bg-gray-50/50 transition-colors group">
                                                <td className="py-4 font-bold">{u.name}</td>
                                                <td className="py-4 text-gray-500">{u.email}</td>
                                                <td className="py-4">
                                                    <span className={cn(
                                                        "px-2.5 py-1 rounded-full text-[10px] font-bold tracking-wide",
                                                        u.role === 'SUPER_ADMIN' ? "bg-purple-50 text-purple-700 border border-purple-200" :
                                                            u.role === 'TENANT_ADMIN' ? "bg-blue-50 text-blue-700 border border-blue-200" :
                                                                "bg-gray-50 text-gray-700 border border-gray-200"
                                                    )}>
                                                        {u.role.replace('_', ' ')}
                                                    </span>
                                                </td>
                                                <td className="py-4 text-gray-400 font-mono text-[10px] truncate max-w-[120px]">
                                                    {u.tenantId || "N/A"}
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        )}
                    </div>
                </div>
            )}

            {/* Slide-over Form Overlay */}
            {showForm && (
                <div className="fixed inset-0 z-50 flex justify-end">
                    <div className="absolute inset-0 bg-black/20 backdrop-blur-sm transition-opacity" onClick={() => setShowForm(false)} />

                    <div className="relative w-full max-w-md bg-white h-full shadow-2xl flex flex-col animate-in slide-in-from-right duration-300">
                        <div className="flex items-center justify-between p-6 border-b border-border">
                            <div>
                                <h2 className="text-lg font-black mb-1 text-foreground leading-tight">Provision New User</h2>
                                <p className="text-[11px] font-medium text-gray-400 uppercase tracking-widest">System Administration</p>
                            </div>
                            <button
                                onClick={() => setShowForm(false)}
                                className="w-8 h-8 bg-gray-50 rounded-full flex items-center justify-center text-gray-500 hover:bg-gray-100 transition-colors"
                            >
                                <X size={16} />
                            </button>
                        </div>

                        <div className="p-6 flex-1 overflow-y-auto">
                            <form id="new-user-form" onSubmit={createUser} className="space-y-4">
                                <div>
                                    <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2 ml-1">Full Name</label>
                                    <input
                                        type="text"
                                        required
                                        value={name}
                                        onChange={(e) => setName(e.target.value)}
                                        className="w-full bg-gray-50 border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all font-medium"
                                        placeholder="e.g. Acme Corp Admin"
                                    />
                                </div>
                                <div>
                                    <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2 ml-1">Email Address</label>
                                    <input
                                        type="email"
                                        required
                                        value={email}
                                        onChange={(e) => setEmail(e.target.value)}
                                        className="w-full bg-gray-50 border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all font-medium"
                                        placeholder="e.g. admin@acmecorp.com"
                                    />
                                </div>
                                <div>
                                    <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2 ml-1">Password</label>
                                    <input
                                        type="password"
                                        required
                                        value={password}
                                        onChange={(e) => setPassword(e.target.value)}
                                        className="w-full bg-gray-50 border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all font-medium"
                                        placeholder="Secure password"
                                    />
                                </div>
                                <div>
                                    <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2 ml-1">Role</label>
                                    <select
                                        value={role}
                                        onChange={(e) => setRole(e.target.value)}
                                        className="w-full bg-gray-50 border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all font-medium"
                                    >
                                        <option value="SUPER_ADMIN">Super Admin</option>
                                        <option value="TENANT_ADMIN">Tenant Admin</option>
                                        <option value="TENANT_USER">Tenant User</option>
                                    </select>
                                </div>
                                <div>
                                    <label className="block text-[10px] font-bold text-gray-400 uppercase tracking-widest mb-2 ml-1">Tenant ID (Optional)</label>
                                    <input
                                        type="text"
                                        value={tenantId}
                                        onChange={(e) => setTenantId(e.target.value)}
                                        className="w-full bg-gray-50 border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary transition-all font-medium"
                                        placeholder="e.g. tenant-uuid"
                                    />
                                </div>
                            </form>
                        </div>

                        <div className="p-6 border-t border-border bg-gray-50/50">
                            <button
                                type="submit"
                                form="new-user-form"
                                className="w-full py-3 bg-primary text-primary-foreground rounded-xl text-xs font-black uppercase tracking-widest hover:opacity-90 active:scale-[0.98] transition-all shadow-lg shadow-primary/20 flex items-center justify-center gap-2 group"
                            >
                                <Users size={14} className="group-hover:scale-110 transition-transform" />
                                Provision User
                            </button>
                        </div>
                    </div>
                </div>
            )}
        </div>
    );
}

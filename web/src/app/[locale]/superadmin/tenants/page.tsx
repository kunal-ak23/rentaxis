"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";

type Tenant = { id: string; name: string; status: string; createdAt: string };

export default function SuperAdminTenantsPage() {
    const t = useTranslations("Index"); // Replace with specific translation scope later
    const [tenants, setTenants] = useState<Tenant[]>([]);
    const [newTenantName, setNewTenantName] = useState("");

    useEffect(() => {
        fetchTenants();
    }, []);

    const fetchTenants = async () => {
        try {
            const res = await fetch("/api/proxy/admin/tenants"); // Proxy to Spring Boot
            if (res.ok) {
                const data = await res.json();
                setTenants(data);
            }
        } catch (e) {
            console.error(e);
        }
    };

    const createTenant = async (e: React.FormEvent) => {
        e.preventDefault();
        if (!newTenantName) return;

        try {
            const res = await fetch("/api/proxy/admin/tenants", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ name: newTenantName }),
            });
            if (res.ok) {
                setNewTenantName("");
                fetchTenants();
            }
        } catch (e) {
            console.error(e);
        }
    };

    return (
        <div className="p-8 max-w-4xl mx-auto">
            <h1 className="text-3xl font-bold mb-6">Super Admin: Manage Tenants</h1>

            <form onSubmit={createTenant} className="mb-10 p-6 bg-white shadow rounded-lg border">
                <h2 className="text-xl font-semibold mb-4">Provision New Organization</h2>
                <div className="flex gap-4">
                    <input
                        type="text"
                        value={newTenantName}
                        onChange={(e) => setNewTenantName(e.target.value)}
                        placeholder="Organization Name"
                        className="flex-1 border p-2 rounded"
                    />
                    <button type="submit" className="bg-blue-600 text-white px-6 py-2 rounded hover:bg-blue-700">
                        Create
                    </button>
                </div>
            </form>

            <div className="bg-white shadow rounded-lg border overflow-hidden">
                <table className="w-full text-left">
                    <thead className="bg-gray-50 border-b">
                        <tr>
                            <th className="p-4">ID</th>
                            <th className="p-4">Name</th>
                            <th className="p-4">Status</th>
                        </tr>
                    </thead>
                    <tbody>
                        {tenants.map(t => (
                            <tr key={t.id} className="border-b last:border-0 hover:bg-gray-50">
                                <td className="p-4 font-mono text-xs">{t.id}</td>
                                <td className="p-4">{t.name}</td>
                                <td className="p-4">
                                    <span className="px-2 py-1 bg-green-100 text-green-800 rounded-full text-xs">
                                        {t.status}
                                    </span>
                                </td>
                            </tr>
                        ))}
                        {tenants.length === 0 && (
                            <tr>
                                <td colSpan={3} className="p-4 text-center text-gray-500">No organizations found.</td>
                            </tr>
                        )}
                    </tbody>
                </table>
            </div>
        </div>
    );
}

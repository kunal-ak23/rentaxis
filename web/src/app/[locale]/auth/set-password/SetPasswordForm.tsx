"use client";

import { useState } from "react";
import { useRouter } from "@/i18n/routing";

interface Props {
    token: string;
    email: string;
    name: string;
    locale: string;
}

export default function SetPasswordForm({ token, email, name, locale }: Props) {
    const router = useRouter();
    const [password, setPassword] = useState("");
    const [confirm, setConfirm] = useState("");
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    async function submit(e: React.FormEvent) {
        e.preventDefault();
        setError(null);
        if (password.length < 8) { setError("Password must be at least 8 characters."); return; }
        if (password !== confirm) { setError("Passwords don't match."); return; }
        setLoading(true);
        try {
            const res = await fetch("/api/auth/set-password", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ token, newPassword: password }),
            });
            if (res.status === 204) {
                router.push("/auth/login?welcome=1");
            } else if (res.status === 410) {
                setError("This invite link has expired.");
            } else if (res.status === 409) {
                setError("This invite has already been used. Please log in.");
            } else if (res.status === 400) {
                setError("Password too short or invalid request.");
            } else {
                setError("Could not set password. Please try again.");
            }
        } catch (e) {
            setError("Network error. Please try again.");
        } finally {
            setLoading(false);
        }
    }

    return (
        <form onSubmit={submit} style={{ maxWidth: 420, margin: "60px auto", fontFamily: "system-ui", padding: 24 }}>
            <h1 style={{ marginBottom: 8 }}>Welcome to RentAxis</h1>
            <p style={{ marginTop: 0, color: "#475569" }}>Hi {name}, set a password to activate your account ({email}).</p>
            <label style={{ display: "block", marginTop: 24 }}>
                <span style={{ display: "block", fontSize: 14, fontWeight: 500, marginBottom: 4 }}>New password</span>
                <input type="password" required minLength={8} value={password} onChange={e => setPassword(e.target.value)}
                       style={{ display: "block", width: "100%", padding: 10, border: "1px solid #CBD5E1", borderRadius: 6 }} />
            </label>
            <label style={{ display: "block", marginTop: 16 }}>
                <span style={{ display: "block", fontSize: 14, fontWeight: 500, marginBottom: 4 }}>Confirm password</span>
                <input type="password" required minLength={8} value={confirm} onChange={e => setConfirm(e.target.value)}
                       style={{ display: "block", width: "100%", padding: 10, border: "1px solid #CBD5E1", borderRadius: 6 }} />
            </label>
            {error && <p style={{ color: "#b91c1c", marginTop: 12 }}>{error}</p>}
            <button type="submit" disabled={loading}
                    style={{ marginTop: 20, padding: "12px 24px", background: "#0F766E", color: "#fff",
                             border: 0, borderRadius: 8, fontWeight: 600, cursor: "pointer", width: "100%" }}>
                {loading ? "Setting…" : "Set password"}
            </button>
        </form>
    );
}

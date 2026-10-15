"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { useRouter } from "@/i18n/routing";

interface Props {
    token: string;
    email: string;
    name: string;
    locale: string;
}

export default function SetPasswordForm({ token, email, name, locale }: Props) {
    const t = useTranslations("Auth");
    const router = useRouter();
    const [password, setPassword] = useState("");
    const [confirm, setConfirm] = useState("");
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);

    async function submit(e: React.FormEvent) {
        e.preventDefault();
        setError(null);
        if (password.length < 8) { setError(t("errTooShort")); return; }
        if (password !== confirm) { setError(t("errMismatch")); return; }
        setLoading(true);
        try {
            const res = await fetch("/api/proxy/auth/set-password", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ token, newPassword: password }),
            });
            if (res.status === 204) {
                router.push("/auth/login?welcome=1");
            } else if (res.status === 410) {
                setError(t("errExpired"));
            } else if (res.status === 409) {
                setError(t("errUsed"));
            } else if (res.status === 400) {
                setError(t("errInvalid"));
            } else {
                setError(t("errGeneric"));
            }
        } catch {
            setError(t("errNetwork"));
        } finally {
            setLoading(false);
        }
    }

    return (
        <form onSubmit={submit} style={{ maxWidth: 420, margin: "60px auto", fontFamily: "system-ui", padding: 24 }}>
            <h1 style={{ marginBottom: 8 }}>{t("setTitle")}</h1>
            <p style={{ marginTop: 0, color: "#475569" }}>{t("setIntro", { name, email })}</p>
            <label style={{ display: "block", marginTop: 24 }}>
                <span style={{ display: "block", fontSize: 14, fontWeight: 500, marginBottom: 4 }}>{t("newPassword")}</span>
                <input type="password" required minLength={8} value={password} onChange={e => setPassword(e.target.value)}
                       style={{ display: "block", width: "100%", padding: 10, border: "1px solid #CBD5E1", borderRadius: 6 }} />
            </label>
            <label style={{ display: "block", marginTop: 16 }}>
                <span style={{ display: "block", fontSize: 14, fontWeight: 500, marginBottom: 4 }}>{t("confirmPassword")}</span>
                <input type="password" required minLength={8} value={confirm} onChange={e => setConfirm(e.target.value)}
                       style={{ display: "block", width: "100%", padding: 10, border: "1px solid #CBD5E1", borderRadius: 6 }} />
            </label>
            {error && <p style={{ color: "#b91c1c", marginTop: 12 }}>{error}</p>}
            <button type="submit" disabled={loading}
                    style={{ marginTop: 20, padding: "12px 24px", background: "#0F766E", color: "#fff",
                             border: 0, borderRadius: 8, fontWeight: 600, cursor: "pointer", width: "100%" }}>
                {loading ? t("setting") : t("setButton")}
            </button>
        </form>
    );
}

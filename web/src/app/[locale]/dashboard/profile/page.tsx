"use client";

import { useState, useEffect } from "react";
import { useLocale, useTranslations } from "next-intl";
import { User, Phone, Mail, Loader2, Check, Lock } from "lucide-react";
import { getRoleLabel, getRoleLabelKey, type UserRole } from "@/lib/rbac";
import { isPlausiblePhone, normalizePhone } from "@/lib/phone";
import { normalizePersonName } from "@/lib/personName";
import { useLatestRequest } from "@/hooks/useLatestRequest";
import { isAbortError } from "@/lib/api/abort";

/**
 * The refusals a user can act on, in their language. Anything else is the
 * generic line; under /en the server's own sentence may follow it, under /ar
 * never (it is English).
 */
function refusalKey(message: string): "nameTooLong" | "nameRequired" | "phoneInvalid" | null {
    if (/phone/i.test(message)) return "phoneInvalid";
    if (/name is required/i.test(message)) return "nameRequired";
    if (/too long|at most \d+ characters/i.test(message)) return "nameTooLong";
    return null;
}

type Profile = {
    id: string;
    email: string;
    name: string;
    role: string;
    phoneNumber: string | null;
};

export default function ProfilePage() {
    const tRoles = useTranslations("Roles");
    const t = useTranslations("Profile");
    // t.has guards a role the catalogue does not know; getRoleLabel is the
    // English fallback rather than letting next-intl throw.
    const roleLabel = (role: string) =>
        tRoles.has(getRoleLabelKey(role)) ? tRoles(getRoleLabelKey(role)) : getRoleLabel(role);
    const locale = useLocale();
    const [profile, setProfile] = useState<Profile | null>(null);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [saved, setSaved] = useState(false);
    const [saveError, setSaveError] = useState("");

    const [name, setName] = useState("");
    const [phoneNumber, setPhoneNumber] = useState("");

    const [changingPassword, setChangingPassword] = useState(false);
    const [currentPassword, setCurrentPassword] = useState("");
    const [newPassword, setNewPassword] = useState("");
    const [confirmPassword, setConfirmPassword] = useState("");
    const [passwordError, setPasswordError] = useState("");
    const [passwordSuccess, setPasswordSuccess] = useState("");
    const [savingPassword, setSavingPassword] = useState(false);

    // Break round 3, F1: aborted on unmount (navigating away mid-load), silently.
    const beginProfile = useLatestRequest();

    useEffect(() => {
        fetchProfile();
        // eslint-disable-next-line react-hooks/exhaustive-deps -- mount/selection loads; the useLatestRequest gate is stable
    }, []);

    const fetchProfile = async () => {
        const { signal, isCurrent } = beginProfile();
        try {
            const res = await fetch("/api/proxy/auth/me", { signal });
            if (res.ok) {
                const data = await res.json();
                if (!isCurrent()) return;
                setProfile(data);
                setName(data.name || "");
                setPhoneNumber(data.phoneNumber || "");
            }
        } catch (err) {
            if (isAbortError(err) || !isCurrent()) return;
            console.error(err);
        } finally {
            if (isCurrent()) setLoading(false);
        }
    };

    const handleSave = async (e: React.FormEvent) => {
        e.preventDefault();
        setSaved(false);
        setSaveError("");
        // Break-it R2 M6: normalize (Unicode spaces, Arabic-Indic digits) before
        // validating and before sending, so the backend's Pattern check (which
        // normalizes the same way) never disagrees with what this screen just
        // accepted, and the stored value matches what was validated here.
        const normalizedPhone = normalizePhone(phoneNumber);
        if (!isPlausiblePhone(normalizedPhone)) {
            setSaveError(t("phoneInvalid"));
            return;
        }
        // Break-it R3 portal3 F6: the name the server will store (invisible
        // characters gone, Unicode spaces trimmed). Nothing left means no name —
        // refused here, never a "Saved" that saved nothing.
        const normalizedName = normalizePersonName(name);
        if (!normalizedName) {
            setSaveError(t("nameRequired"));
            return;
        }
        setSaving(true);
        try {
            const res = await fetch("/api/proxy/auth/me", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ name: normalizedName, phoneNumber: normalizedPhone }),
            });
            if (res.ok) {
                const data = await res.json();
                setProfile(data);
                setName(data.name || normalizedName);
                setSaved(true);
                setTimeout(() => setSaved(false), 3000);
            } else {
                // Break-it round 2 (portal2) F2: a refused save used to stop
                // the spinner and say nothing. Say so in the user's language:
                // a reason they can act on is mapped; otherwise the generic
                // line, followed by the server's (English) sentence only in EN.
                const body = await res.json().catch(() => null) as { message?: unknown } | null;
                const detail = typeof body?.message === "string" ? body.message.trim() : "";
                const known = refusalKey(detail);
                setSaveError(known ? t(known)
                    : detail && locale === "en" ? `${t("saveFailed")} ${detail}` : t("saveFailed"));
            }
        } catch (err) {
            console.error(err);
            setSaveError(t("saveFailed"));
        } finally {
            setSaving(false);
        }
    };

    const handlePasswordChange = async (e: React.FormEvent) => {
        e.preventDefault();
        setPasswordError("");
        setPasswordSuccess("");

        if (newPassword !== confirmPassword) {
            setPasswordError(t("passwordsDoNotMatch"));
            return;
        }
        if (newPassword.length < 8) {
            setPasswordError(t("passwordTooShort"));
            return;
        }

        setSavingPassword(true);
        try {
            const res = await fetch("/api/proxy/auth/me/password", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ currentPassword, newPassword }),
            });
            if (res.ok) {
                setPasswordSuccess(t("passwordUpdated"));
                setCurrentPassword("");
                setNewPassword("");
                setConfirmPassword("");
                setChangingPassword(false);
                setTimeout(() => setPasswordSuccess(""), 3000);
            } else {
                const data = await res.json().catch(() => null) as { error?: unknown } | null;
                const reason = typeof data?.error === "string" ? data.error : "";
                setPasswordError(/current password is incorrect/i.test(reason) ? t("currentPasswordIncorrect")
                    : /at least \d+ characters/i.test(reason) ? t("passwordTooShort")
                    : t("passwordUpdateFailed"));
            }
        } catch {
            setPasswordError(t("passwordUpdateFailed"));
        } finally {
            setSavingPassword(false);
        }
    };

    if (loading) {
        return (
            <div className="flex items-center justify-center py-24">
                <Loader2 className="w-6 h-6 animate-spin text-primary opacity-60" />
            </div>
        );
    }

    if (!profile) {
        return (
            <div className="text-center py-24 bg-surface border border-dashed border-border rounded-xl">
                <p className="text-sm text-muted">{t("loadFailed")}</p>
            </div>
        );
    }

    return (
        <div className="max-w-2xl">
            <div className="mb-8">
                <h1 className="mb-1">{t("title")}</h1>
                <p className="text-sm text-muted">{t("subtitle")}</p>
            </div>

            {/* Profile Card */}
            <div className="bg-surface rounded-xl border border-border mb-6">
                {/* Avatar Header */}
                <div className="px-6 py-5 border-b border-border flex items-center gap-4">
                    <div className="w-14 h-14 rounded-full bg-primary/10 text-primary flex items-center justify-center font-bold text-xl border-2 border-primary/20">
                        {profile.name?.charAt(0) || 'U'}
                    </div>
                    <div>
                        <h2 className="text-base font-bold text-foreground">{profile.name}</h2>
                        <div className="flex items-center gap-2 mt-0.5">
                            <span className="text-xs text-muted">{profile.email}</span>
                            <span className="text-[10px] font-semibold text-primary bg-primary/10 px-2 py-0.5 rounded-md border border-primary/20">
                                {roleLabel(profile.role as UserRole)}
                            </span>
                        </div>
                    </div>
                </div>

                {/* Edit Form */}
                <form onSubmit={handleSave} className="p-6 space-y-5">
                    <div>
                        <label className="block text-[11px] font-semibold text-muted uppercase tracking-wider mb-1.5">
                            {t("fullName")}
                        </label>
                        <div className="relative">
                            <User size={15} className="absolute start-3.5 top-1/2 -translate-y-1/2 text-muted" />
                            <input
                                type="text"
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                maxLength={255}
                                className="w-full border border-border rounded-lg bg-background ps-10 pe-4 py-3 text-sm text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all"
                                required
                            />
                        </div>
                    </div>

                    <div>
                        <label className="block text-[11px] font-semibold text-muted uppercase tracking-wider mb-1.5">
                            {t("phoneNumber")}
                        </label>
                        {/* A phone / email reads left-to-right in Arabic too; the icon follows it. */}
                        <div className="relative" dir="ltr">
                            <Phone size={15} className="absolute start-3.5 top-1/2 -translate-y-1/2 text-muted" />
                            <input
                                type="tel"
                                value={phoneNumber}
                                onChange={(e) => setPhoneNumber(e.target.value)}
                                placeholder="+971 50 123 4567"
                                className="w-full border border-border rounded-lg bg-background ps-10 pe-4 py-3 text-sm text-foreground placeholder:text-muted/40 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all"
                            />
                        </div>
                    </div>

                    <div>
                        <label className="block text-[11px] font-semibold text-muted uppercase tracking-wider mb-1.5">
                            {t("emailAddress")}
                        </label>
                        {/* A phone / email reads left-to-right in Arabic too; the icon follows it. */}
                        <div className="relative" dir="ltr">
                            <Mail size={15} className="absolute start-3.5 top-1/2 -translate-y-1/2 text-muted/50" />
                            <input
                                type="email"
                                value={profile.email}
                                disabled
                                className="w-full border border-border rounded-lg bg-input ps-10 pe-4 py-3 text-sm text-muted cursor-not-allowed"
                            />
                        </div>
                        <p className="text-[10px] text-muted mt-1 ms-1">{t("emailHint")}</p>
                    </div>

                    {saveError && (
                        <div role="alert" className="bg-error/10 text-error text-xs font-semibold px-4 py-2.5 rounded-lg border border-error/20">
                            {saveError}
                        </div>
                    )}

                    <div className="flex items-center gap-3 pt-2">
                        <button
                            type="submit"
                            disabled={saving}
                            className="flex items-center gap-2 bg-primary text-primary-foreground px-6 py-2.5 rounded-lg text-sm font-semibold hover:bg-primary/90 transition-all disabled:opacity-60 cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                        >
                            {saving ? (
                                <Loader2 size={14} className="animate-spin" />
                            ) : saved ? (
                                <Check size={14} />
                            ) : null}
                            {saved ? t("saved") : t("saveChanges")}
                        </button>
                    </div>
                </form>
            </div>

            {/* Password Section */}
            <div className="bg-surface rounded-xl border border-border">
                <div className="px-6 py-4 border-b border-border flex items-center justify-between">
                    <div className="flex items-center gap-3">
                        <div className="w-9 h-9 rounded-lg bg-warning/10 flex items-center justify-center">
                            <Lock size={16} className="text-warning" />
                        </div>
                        <div>
                            <h3 className="text-sm font-semibold text-foreground">{t("password")}</h3>
                            <p className="text-xs text-muted">{t("passwordHint")}</p>
                        </div>
                    </div>
                    {!changingPassword && (
                        <button
                            onClick={() => setChangingPassword(true)}
                            className="text-xs font-semibold text-primary hover:text-primary/80 transition-colors cursor-pointer focus:outline-none"
                        >
                            {t("changePassword")}
                        </button>
                    )}
                </div>

                {passwordSuccess && (
                    <div className="mx-6 mt-4 bg-success/10 text-success text-xs font-semibold px-4 py-2.5 rounded-lg border border-success/20">
                        {passwordSuccess}
                    </div>
                )}

                {changingPassword && (
                    <form onSubmit={handlePasswordChange} className="p-6 space-y-4">
                        <div>
                            <label className="block text-[11px] font-semibold text-muted uppercase tracking-wider mb-1.5">
                                {t("currentPassword")}
                            </label>
                            <input
                                type="password"
                                value={currentPassword}
                                onChange={(e) => setCurrentPassword(e.target.value)}
                                required
                                className="w-full border border-border rounded-lg bg-background px-4 py-3 text-sm text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all"
                            />
                        </div>
                        <div>
                            <label className="block text-[11px] font-semibold text-muted uppercase tracking-wider mb-1.5">
                                {t("newPassword")}
                            </label>
                            <input
                                type="password"
                                value={newPassword}
                                onChange={(e) => setNewPassword(e.target.value)}
                                required
                                minLength={8}
                                className="w-full border border-border rounded-lg bg-background px-4 py-3 text-sm text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all"
                            />
                        </div>
                        <div>
                            <label className="block text-[11px] font-semibold text-muted uppercase tracking-wider mb-1.5">
                                {t("confirmNewPassword")}
                            </label>
                            <input
                                type="password"
                                value={confirmPassword}
                                onChange={(e) => setConfirmPassword(e.target.value)}
                                required
                                className="w-full border border-border rounded-lg bg-background px-4 py-3 text-sm text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all"
                            />
                        </div>

                        {passwordError && (
                            <div className="bg-error/10 text-error text-xs font-semibold px-4 py-2.5 rounded-lg border border-error/20">
                                {passwordError}
                            </div>
                        )}

                        <div className="flex items-center gap-3 pt-1">
                            <button
                                type="submit"
                                disabled={savingPassword}
                                className="flex items-center gap-2 bg-accent text-accent-foreground px-6 py-2.5 rounded-lg text-sm font-semibold hover:brightness-110 transition-all disabled:opacity-60 cursor-pointer focus:ring-2 focus:ring-accent/20 focus:outline-none"
                            >
                                {savingPassword && <Loader2 size={14} className="animate-spin" />}
                                {t("updatePassword")}
                            </button>
                            <button
                                type="button"
                                onClick={() => {
                                    setChangingPassword(false);
                                    setPasswordError("");
                                    setCurrentPassword("");
                                    setNewPassword("");
                                    setConfirmPassword("");
                                }}
                                className="text-sm font-medium text-muted hover:text-foreground transition-colors cursor-pointer"
                            >
                                {t("cancel")}
                            </button>
                        </div>
                    </form>
                )}
            </div>
        </div>
    );
}

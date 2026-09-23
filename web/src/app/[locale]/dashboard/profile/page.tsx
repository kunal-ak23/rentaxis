"use client";

import { useState, useEffect } from "react";
import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { User, Phone, Mail, Shield, Loader2, Check, Lock } from "lucide-react";
import { cn } from "@/lib/utils";
import { getRoleLabel, getRoleLabelKey, type UserRole } from "@/lib/rbac";

type Profile = {
    id: string;
    email: string;
    name: string;
    role: string;
    phoneNumber: string | null;
};

export default function ProfilePage() {
    const tRoles = useTranslations("Roles");
    // t.has guards a role the catalogue does not know; getRoleLabel is the
    // English fallback rather than letting next-intl throw.
    const roleLabel = (role: string) =>
        tRoles.has(getRoleLabelKey(role)) ? tRoles(getRoleLabelKey(role)) : getRoleLabel(role);
    const { data: session } = useSession();
    const [profile, setProfile] = useState<Profile | null>(null);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [saved, setSaved] = useState(false);

    const [name, setName] = useState("");
    const [phoneNumber, setPhoneNumber] = useState("");

    const [changingPassword, setChangingPassword] = useState(false);
    const [currentPassword, setCurrentPassword] = useState("");
    const [newPassword, setNewPassword] = useState("");
    const [confirmPassword, setConfirmPassword] = useState("");
    const [passwordError, setPasswordError] = useState("");
    const [passwordSuccess, setPasswordSuccess] = useState("");
    const [savingPassword, setSavingPassword] = useState(false);

    useEffect(() => {
        fetchProfile();
    }, []);

    const fetchProfile = async () => {
        try {
            const res = await fetch("/api/proxy/auth/me");
            if (res.ok) {
                const data = await res.json();
                setProfile(data);
                setName(data.name || "");
                setPhoneNumber(data.phoneNumber || "");
            }
        } catch (err) {
            console.error(err);
        } finally {
            setLoading(false);
        }
    };

    const handleSave = async (e: React.FormEvent) => {
        e.preventDefault();
        setSaving(true);
        setSaved(false);
        try {
            const res = await fetch("/api/proxy/auth/me", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ name, phoneNumber }),
            });
            if (res.ok) {
                const data = await res.json();
                setProfile(data);
                setSaved(true);
                setTimeout(() => setSaved(false), 3000);
            }
        } catch (err) {
            console.error(err);
        } finally {
            setSaving(false);
        }
    };

    const handlePasswordChange = async (e: React.FormEvent) => {
        e.preventDefault();
        setPasswordError("");
        setPasswordSuccess("");

        if (newPassword !== confirmPassword) {
            setPasswordError("New passwords do not match");
            return;
        }
        if (newPassword.length < 8) {
            setPasswordError("Password must be at least 8 characters");
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
                setPasswordSuccess("Password updated successfully");
                setCurrentPassword("");
                setNewPassword("");
                setConfirmPassword("");
                setChangingPassword(false);
                setTimeout(() => setPasswordSuccess(""), 3000);
            } else {
                const data = await res.json();
                setPasswordError(data.error || "Failed to update password");
            }
        } catch (err) {
            setPasswordError("Something went wrong");
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
                <p className="text-sm text-muted">Unable to load profile</p>
            </div>
        );
    }

    return (
        <div className="max-w-2xl">
            <div className="mb-8">
                <h1 className="mb-1">My Profile</h1>
                <p className="text-sm text-muted">Manage your account details and password.</p>
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
                            Full Name
                        </label>
                        <div className="relative">
                            <User size={15} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" />
                            <input
                                type="text"
                                value={name}
                                onChange={(e) => setName(e.target.value)}
                                className="w-full border border-border rounded-lg bg-background pl-10 pr-4 py-3 text-sm text-foreground focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all"
                                required
                            />
                        </div>
                    </div>

                    <div>
                        <label className="block text-[11px] font-semibold text-muted uppercase tracking-wider mb-1.5">
                            Phone Number
                        </label>
                        <div className="relative">
                            <Phone size={15} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-muted" />
                            <input
                                type="tel"
                                value={phoneNumber}
                                onChange={(e) => setPhoneNumber(e.target.value)}
                                placeholder="+971 50 123 4567"
                                className="w-full border border-border rounded-lg bg-background pl-10 pr-4 py-3 text-sm text-foreground placeholder:text-muted/40 focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all"
                            />
                        </div>
                    </div>

                    <div>
                        <label className="block text-[11px] font-semibold text-muted uppercase tracking-wider mb-1.5">
                            Email Address
                        </label>
                        <div className="relative">
                            <Mail size={15} className="absolute left-3.5 top-1/2 -translate-y-1/2 text-muted/50" />
                            <input
                                type="email"
                                value={profile.email}
                                disabled
                                className="w-full border border-border rounded-lg bg-input pl-10 pr-4 py-3 text-sm text-muted cursor-not-allowed"
                            />
                        </div>
                        <p className="text-[10px] text-muted mt-1 ml-1">Contact your administrator to change email.</p>
                    </div>

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
                            {saved ? "Saved" : "Save Changes"}
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
                            <h3 className="text-sm font-semibold text-foreground">Password</h3>
                            <p className="text-xs text-muted">Change your account password</p>
                        </div>
                    </div>
                    {!changingPassword && (
                        <button
                            onClick={() => setChangingPassword(true)}
                            className="text-xs font-semibold text-primary hover:text-primary/80 transition-colors cursor-pointer focus:outline-none"
                        >
                            Change Password
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
                                Current Password
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
                                New Password
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
                                Confirm New Password
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
                                Update Password
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
                                Cancel
                            </button>
                        </div>
                    </form>
                )}
            </div>
        </div>
    );
}

import { notFound } from "next/navigation";
import SetPasswordForm from "./SetPasswordForm";

interface Props {
    params: Promise<{ locale: string }>;
    searchParams: Promise<{ token?: string }>;
}

interface InviteInfo {
    email: string;
    name: string;
    expiresAt: string;
}

async function fetchInviteInfo(token: string): Promise<{ ok: true; info: InviteInfo } | { ok: false; status: number }> {
    const base = process.env.BACKEND_URL ?? "http://backend:8080";
    const res = await fetch(`${base}/api/auth/set-password/validate?token=${encodeURIComponent(token)}`,
            { cache: "no-store" });
    if (res.ok) return { ok: true, info: await res.json() };
    return { ok: false, status: res.status };
}

export default async function SetPasswordPage({ params, searchParams }: Props) {
    const { locale } = await params;
    const { token } = await searchParams;

    if (!token) return notFound();

    const result = await fetchInviteInfo(token);
    if (!result.ok) {
        const reason = result.status === 410 ? "expired" : "invalid";
        return (
            <div style={{ padding: 32, fontFamily: "system-ui", maxWidth: 480, margin: "60px auto" }}>
                <h2>This invite link is {reason}.</h2>
                <p>Please ask your tenant administrator to send a new invitation.</p>
            </div>
        );
    }

    return <SetPasswordForm token={token} email={result.info.email} name={result.info.name} locale={locale} />;
}

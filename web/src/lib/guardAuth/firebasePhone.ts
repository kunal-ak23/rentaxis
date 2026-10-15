"use client";

/**
 * Firebase phone verification for guard sign-in on the web — the same flow the
 * Security app uses (Firebase sends and checks the SMS code, rate-limits and
 * locks out repeated attempts; the backend then verifies the ID token at
 * POST /api/v1/auth/firebase and maps the phone to one active guard). The
 * invisible reCAPTCHA is Firebase's own abuse check for web phone sign-in.
 *
 * Firebase's session is kept in memory only and signed out once the ID token is
 * read: the web session is NextAuth's, as for every other role.
 */
import type { ConfirmationResult, Auth, RecaptchaVerifier } from "firebase/auth";

export type GuardAuthConfig = {
    apiKey: string;
    authDomain: string;
    projectId: string;
    appId: string | null;
    emulatorHost: string | null;
};

/** The deployment's Firebase web config, or null when guard phone sign-in is not set up. */
export async function loadGuardAuthConfig(): Promise<GuardAuthConfig | null> {
    try {
        const res = await fetch("/api/guard-auth/config", { cache: "no-store" });
        if (!res.ok) return null;
        const body = await res.json();
        return body?.configured ? body as GuardAuthConfig : null;
    } catch {
        return null;
    }
}

let authPromise: Promise<Auth> | null = null;

async function guardAuth(config: GuardAuthConfig, languageCode: string): Promise<Auth> {
    authPromise ??= (async () => {
        const { initializeApp, getApps } = await import("firebase/app");
        const { initializeAuth, inMemoryPersistence, connectAuthEmulator } = await import("firebase/auth");
        const name = "miftah-guard-web";
        const app = getApps().find(a => a.name === name) ?? initializeApp({
            apiKey: config.apiKey,
            authDomain: config.authDomain,
            projectId: config.projectId,
            ...(config.appId ? { appId: config.appId } : {}),
        }, name);
        const auth = initializeAuth(app, { persistence: inMemoryPersistence });
        if (config.emulatorHost) {
            connectAuthEmulator(auth, `http://${config.emulatorHost}`, { disableWarnings: true });
        }
        return auth;
    })();
    const auth = await authPromise;
    auth.languageCode = languageCode;
    return auth;
}

let verifier: RecaptchaVerifier | null = null;

/** Sends the SMS code to `phoneE164`. `containerId` is an element the invisible reCAPTCHA can use. */
export async function sendGuardCode(config: GuardAuthConfig, phoneE164: string, containerId: string,
    languageCode: string): Promise<ConfirmationResult> {
    const auth = await guardAuth(config, languageCode);
    const { RecaptchaVerifier, signInWithPhoneNumber } = await import("firebase/auth");
    verifier?.clear();
    verifier = new RecaptchaVerifier(auth, containerId, { size: "invisible" });
    try {
        return await signInWithPhoneNumber(auth, phoneE164, verifier);
    } catch (err) {
        verifier.clear();
        verifier = null;
        throw err;
    }
}

/** Checks the code; returns the Firebase ID token the backend verifies. */
export async function confirmGuardCode(confirmation: ConfirmationResult, code: string): Promise<string> {
    const credential = await confirmation.confirm(code);
    const token = await credential.user.getIdToken(true);
    // NextAuth holds the web session; Firebase's is not needed past this point.
    if (authPromise) await (await authPromise).signOut().catch(() => undefined);
    return token;
}

/** The Firebase error code, when `err` is a FirebaseError. */
export function firebaseErrorCode(err: unknown): string | null {
    return typeof err === "object" && err !== null && "code" in err && typeof (err as { code: unknown }).code === "string"
        ? (err as { code: string }).code : null;
}

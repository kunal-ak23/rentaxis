import { NextResponse } from "next/server";

/**
 * The Firebase web-app config for guard phone sign-in (tutorial 25), read from
 * the server's environment at request time so a deployment sets it without a
 * rebuild. These values are public by design (Firebase ships them in every web
 * client); the security is the SMS code and the backend's token check, not the
 * config. 404 when guard phone sign-in is not configured, so the page can say so.
 *
 *   FIREBASE_WEB_API_KEY, FIREBASE_WEB_AUTH_DOMAIN, FIREBASE_WEB_APP_ID and
 *   FIREBASE_PROJECT_ID (the project the backend verifies tokens for).
 *   FIREBASE_AUTH_EMULATOR_HOST (local only): use the Firebase Auth emulator.
 */
export const dynamic = "force-dynamic";

export function GET() {
    const apiKey = process.env.FIREBASE_WEB_API_KEY;
    const authDomain = process.env.FIREBASE_WEB_AUTH_DOMAIN;
    const projectId = process.env.FIREBASE_PROJECT_ID;
    const appId = process.env.FIREBASE_WEB_APP_ID;
    const emulatorHost = process.env.FIREBASE_AUTH_EMULATOR_HOST || null;
    if (!apiKey || !projectId || (!emulatorHost && (!authDomain || !appId))) {
        return NextResponse.json({ configured: false }, { status: 404, headers: { "Cache-Control": "no-store" } });
    }
    return NextResponse.json(
        { configured: true, apiKey, authDomain: authDomain ?? `${projectId}.firebaseapp.com`, projectId, appId: appId ?? null, emulatorHost },
        { headers: { "Cache-Control": "no-store" } },
    );
}

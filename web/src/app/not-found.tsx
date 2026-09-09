import Link from "next/link";

/**
 * Root 404 — the boundary an unmatched URL actually reaches.
 *
 * `[locale]/not-found.tsx` only covers `notFound()` raised *inside* that
 * segment. A URL matching no route at all falls through to the root, and with
 * no file here Next served its own bare "404: This page could not be found."
 * page — no navigation back into the app, no branding, no Arabic. That is what
 * production was still returning after the locale-level boundary shipped; it
 * only showed up when the deployed app was checked rather than the tests.
 *
 * Deliberately dependency-light: this renders outside `[locale]/layout.tsx`, so
 * there is no `NextIntlClientProvider` and no locale to translate against. It
 * links into the app and lets the locale layout take over from there, rather
 * than guessing a language.
 */
export default function RootNotFound() {
    return (
        <div
            style={{
                fontFamily: "system-ui, -apple-system, sans-serif",
                minHeight: "60vh",
                display: "flex",
                flexDirection: "column",
                alignItems: "center",
                justifyContent: "center",
                gap: "0.75rem",
                padding: "4rem 1rem",
                textAlign: "center",
            }}
        >
            <h1 style={{ fontSize: "1.25rem", fontWeight: 600, margin: 0 }}>Page not found</h1>
            <p style={{ color: "#4b5563", fontSize: "0.875rem", margin: 0 }}>
                The page you are looking for does not exist or has moved.
            </p>
            <Link
                href="/en/dashboard"
                style={{
                    marginTop: "0.5rem",
                    background: "#111827",
                    color: "#fff",
                    borderRadius: "0.375rem",
                    padding: "0.5rem 1rem",
                    fontSize: "0.875rem",
                    textDecoration: "none",
                }}
            >
                Go to the dashboard
            </Link>
        </div>
    );
}

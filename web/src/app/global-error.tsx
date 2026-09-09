"use client";

/**
 * Last-resort boundary for an error thrown by the root layout itself.
 *
 * This replaces the entire document, so it cannot rely on the locale layout,
 * NextIntlClientProvider, or any app styling — a boundary that depends on the
 * thing that just failed is not a boundary. Copy is deliberately untranslated
 * and styles inline for that reason; every other error path goes through
 * [locale]/error.tsx, which is translated.
 */
export default function GlobalError({
    error,
    reset,
}: {
    error: Error & { digest?: string };
    reset: () => void;
}) {
    return (
        <html lang="en">
            <body style={{ fontFamily: "system-ui, sans-serif", padding: "4rem 1rem", textAlign: "center" }}>
                <h1 style={{ fontSize: "1.25rem", fontWeight: 600 }}>Something went wrong</h1>
                <p style={{ color: "#4b5563", fontSize: "0.875rem" }}>
                    The application failed to start. Please reload the page.
                </p>
                {error.digest && (
                    <p style={{ color: "#9ca3af", fontFamily: "monospace", fontSize: "0.75rem" }}>{error.digest}</p>
                )}
                <button
                    type="button"
                    onClick={reset}
                    style={{
                        marginTop: "1rem", background: "#111827", color: "#fff",
                        border: 0, borderRadius: "0.375rem", padding: "0.5rem 1rem",
                        fontSize: "0.875rem", cursor: "pointer",
                    }}
                >
                    Try again
                </button>
            </body>
        </html>
    );
}

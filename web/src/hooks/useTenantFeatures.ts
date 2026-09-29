import { useState, useEffect } from "react";
import { useSession } from "next-auth/react";

type FeatureMap = Record<string, boolean>;

// Module-level cache so client-side navigations render feature-gated nav
// instantly. It is keyed to the signed-in user and revalidated after a short
// TTL (and on window focus) so a superadmin flipping a toggle becomes visible
// without a hard reload, and a session change never serves the previous
// user's flags.
const CACHE_TTL_MS = 60_000;

let featuresCache: FeatureMap | null = null;
let slugCache: string | null = null;
let cacheUserId: string | null = null;
let cacheFetchedAt = 0;

export function useTenantFeatures() {
    const { data: session, status } = useSession();
    const userId = session?.user?.id ?? null;
    const [features, setFeatures] = useState<FeatureMap>(
        () => (cacheUserId === userId && featuresCache) || {}
    );
    // Whether the flags have been answered at all (from the cache or a finished
    // read, successful or not), so a page gated on a flag can tell "off" from
    // "not known yet" instead of flashing its disabled state on every mount.
    const [loaded, setLoaded] = useState<boolean>(() => cacheUserId === userId && featuresCache !== null);
    const [tenantSlug, setTenantSlug] = useState<string>(
        () => (cacheUserId === userId && slugCache !== null ? slugCache : "")
    );

    useEffect(() => {
        if (status !== "authenticated") return;

        if (cacheUserId !== userId) {
            featuresCache = null;
            slugCache = null;
            cacheFetchedAt = 0;
            cacheUserId = userId;
        }

        if (featuresCache) {
            setFeatures(featuresCache);
            setLoaded(true);
        }
        if (slugCache !== null) setTenantSlug(slugCache);

        const revalidate = () => {
            // Mark up front so concurrently mounting hook instances don't all fetch.
            cacheFetchedAt = Date.now();
            fetch("/api/proxy/v1/tenant/features")
                .then(r => {
                    if (!r.ok) {
                        // Consume/cancel the body before rejecting: an unread response
                        // body on a non-ok fetch never "finishes" in Chromium, which
                        // stalls Playwright's networkidle wait on every page that
                        // renders this hook. The fail-closed behaviour (no features
                        // enabled) is unchanged.
                        r.body?.cancel().catch(() => {});
                        return Promise.reject(new Error(String(r.status)));
                    }
                    return r.json();
                })
                .then((body: FeatureMap | null) => {
                    // A body that is not a map (an empty or null reply) is "nothing on".
                    const data: FeatureMap = body && typeof body === "object" ? body : {};
                    featuresCache = data;
                    setFeatures(data);
                    setLoaded(true);
                })
                // Fail closed, but answered: a page gated on a flag shows its "off" state.
                .catch(() => setLoaded(true));
            fetch("/api/proxy/v1/tenant/info")
                .then(r => {
                    if (!r.ok) {
                        r.body?.cancel().catch(() => {});
                        return Promise.reject(new Error(String(r.status)));
                    }
                    return r.json();
                })
                .then((data: { slug?: string }) => {
                    const slug = data.slug ?? "";
                    slugCache = slug;
                    setTenantSlug(slug);
                })
                .catch(() => {});
        };

        const isStale = () =>
            featuresCache === null ||
            slugCache === null ||
            Date.now() - cacheFetchedAt > CACHE_TTL_MS;

        if (isStale()) revalidate();

        const onFocus = () => {
            if (isStale()) revalidate();
        };
        window.addEventListener("focus", onFocus);
        return () => window.removeEventListener("focus", onFocus);
    }, [status, userId]);

    return {
        isEnabled: (feature: string) => features[feature] ?? false,
        loaded,
        tenantSlug,
    };
}

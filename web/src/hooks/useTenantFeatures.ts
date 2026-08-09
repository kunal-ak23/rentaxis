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

        if (featuresCache) setFeatures(featuresCache);
        if (slugCache !== null) setTenantSlug(slugCache);

        const revalidate = () => {
            // Mark up front so concurrently mounting hook instances don't all fetch.
            cacheFetchedAt = Date.now();
            fetch("/api/proxy/v1/tenant/features")
                .then(r => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
                .then((data: FeatureMap) => {
                    featuresCache = data;
                    setFeatures(data);
                })
                .catch(() => {});
            fetch("/api/proxy/v1/tenant/info")
                .then(r => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
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
        tenantSlug,
    };
}

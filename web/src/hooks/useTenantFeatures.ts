import { useState, useEffect } from "react";
import { useSession } from "next-auth/react";

type FeatureMap = Record<string, boolean>;

let featuresCache: FeatureMap | null = null;
let slugCache: string | null = null;

export function useTenantFeatures() {
    const { status } = useSession();
    const [features, setFeatures] = useState<FeatureMap>(featuresCache ?? {});
    const [tenantSlug, setTenantSlug] = useState<string>(slugCache ?? "");

    useEffect(() => {
        if (status !== "authenticated") return;

        if (featuresCache) {
            setFeatures(featuresCache);
        } else {
            fetch("/api/proxy/v1/tenant/features")
                .then(r => r.ok ? r.json() : {})
                .then((data: FeatureMap) => {
                    featuresCache = data;
                    setFeatures(data);
                })
                .catch(() => {});
        }

        if (slugCache !== null) {
            setTenantSlug(slugCache);
        } else {
            fetch("/api/proxy/v1/tenant/info")
                .then(r => r.ok ? r.json() : { slug: "" })
                .then((data: { slug: string }) => {
                    slugCache = data.slug ?? "";
                    setTenantSlug(slugCache);
                })
                .catch(() => {});
        }
    }, [status]);

    return {
        isEnabled: (feature: string) => features[feature] ?? false,
        tenantSlug,
    };
}

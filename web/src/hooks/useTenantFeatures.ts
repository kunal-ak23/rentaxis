import { useState, useEffect } from "react";
import { useSession } from "next-auth/react";

type FeatureMap = Record<string, boolean>;

let cache: FeatureMap | null = null;

export function useTenantFeatures() {
    const { status } = useSession();
    const [features, setFeatures] = useState<FeatureMap>(cache ?? {});

    useEffect(() => {
        if (status !== "authenticated") return;
        if (cache) {
            setFeatures(cache);
            return;
        }
        fetch("/api/proxy/v1/tenant/features")
            .then(r => r.ok ? r.json() : {})
            .then((data: FeatureMap) => {
                cache = data;
                setFeatures(data);
            })
            .catch(() => {});
    }, [status]);

    return {
        isEnabled: (feature: string) => features[feature] ?? false,
    };
}

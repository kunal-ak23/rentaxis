import { useEffect } from "react";

/**
 * Calls the provided fetch function when the page becomes visible again
 * (e.g., user switches back to this browser tab or navigates back via sidebar).
 */
export function useRefetchOnFocus(fetchFn: () => void) {
    useEffect(() => {
        const onFocus = () => fetchFn();
        window.addEventListener("focus", onFocus);
        return () => window.removeEventListener("focus", onFocus);
    }, [fetchFn]);
}

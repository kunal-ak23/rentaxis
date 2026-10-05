import { renderHook } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

const locale = vi.hoisted(() => ({ current: "en" }));
vi.mock("next-intl", () => ({ useTranslations: () => (k: string) => k, useLocale: () => locale.current }));
vi.mock("next-auth/react", () => ({ useSession: () => ({ data: null, status: "unauthenticated" }) }));

import { useDateTime } from "../shared";

describe("useDateTime — gate times are shown in the gate's zone (Asia/Dubai)", () => {
    it("shows 14:00Z as 18:00, the time picked at the gate, whatever the browser zone", () => {
        const { result } = renderHook(() => useDateTime());
        // 14:00 UTC is 18:00 in Dubai; in the browser's own zone it would read
        // 14:00 (UTC CI), 19:30 (India) and so on.
        expect(result.current.dateTime("2026-10-05T14:00:00Z")).toContain("18:00");
        // 21:30 UTC is already the next day in Dubai.
        expect(result.current.date("2026-10-05T21:30:00Z")).toContain("6 Oct");
    });
});

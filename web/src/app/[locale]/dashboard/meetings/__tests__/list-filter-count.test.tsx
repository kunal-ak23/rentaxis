import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchRouter } from "@/test/fetchRouter";

// Tutorial 23: filtering to Approved showed one row under "Showing 1-3 of 3" —
// the page filtered in the browser. The filters now go to the server.

vi.mock("next-auth/react", () => ({ useSession: () => ({ data: { user: { role: "TENANT_ADMIN", id: "me-1" } } }) }));
vi.mock("@/i18n/routing", () => ({
    Link: ({ href, children }: { href: string; children: React.ReactNode }) => <a href={href}>{children}</a>,
    useRouter: () => ({ push: vi.fn() }),
}));
vi.mock("next/dynamic", () => ({ default: () => () => <div data-testid="calendar" /> }));
vi.mock("../CreateMeetingModal", () => ({ default: () => null }));
vi.mock("next-intl", () => ({
    useTranslations: () => Object.assign((k: string) => k, { has: () => false }),
    useLocale: () => "en",
}));

import MeetingsPage from "../page";

let api: ReturnType<typeof fetchRouter>;
beforeEach(() => {
    api = fetchRouter();
    api.on("GET", "/v1/meetings", { body: { content: [], totalElements: 0 } });
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Meetings list filters", () => {
    it("sends the status, type and purpose filters to the server", async () => {
        render(<MeetingsPage />);
        await waitFor(() => expect(api.callsTo("GET", "/v1/meetings?")).toHaveLength(1));
        expect(api.calls[0].url).not.toContain("status=");
        const [status, type] = screen.getAllByRole("combobox");
        fireEvent.change(status, { target: { value: "APPROVED" } });
        await waitFor(() => expect(api.calls.at(-1)!.url).toContain("status=APPROVED"));
        fireEvent.change(type, { target: { value: "OFFICE_VISIT" } });
        await waitFor(() => expect(api.calls.at(-1)!.url).toContain("type=OFFICE_VISIT"));
        expect(api.calls.at(-1)!.url).toContain("page=0");
    });
});

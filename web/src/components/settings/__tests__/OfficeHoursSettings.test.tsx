import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fetchRouter } from "@/test/fetchRouter";

vi.mock("next-intl", () => ({ useTranslations: () => (k: string) => k }));

import OfficeHoursSettings from "../OfficeHoursSettings";

let api: ReturnType<typeof fetchRouter>;
beforeEach(() => {
    api = fetchRouter();
    api.on("GET", "/settings/org/office-hours", { body: { start: "09:00:00", end: "18:00:00" } });
    api.on("PUT", "/settings/org/office-hours", c => ({ body: c.body }));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("Office hours setting", () => {
    it("shows the organisation's hours and saves new ones", async () => {
        render(<OfficeHoursSettings />);
        const opens = await screen.findByLabelText("officeHoursOpens") as HTMLInputElement;
        await waitFor(() => expect(opens.value).toBe("09:00"));
        fireEvent.change(opens, { target: { value: "08:30" } });
        fireEvent.click(screen.getByTestId("office-hours-save"));
        await waitFor(() => expect(api.callsTo("PUT", "/office-hours")).toHaveLength(1));
        expect(api.callsTo("PUT", "/office-hours")[0].body).toEqual({ start: "08:30", end: "18:00" });
        expect(await screen.findByText("officeHoursSaved")).toBeTruthy();
    });

    it("refuses closing before opening without calling the server", async () => {
        render(<OfficeHoursSettings />);
        const closes = await screen.findByLabelText("officeHoursCloses") as HTMLInputElement;
        await waitFor(() => expect(closes.value).toBe("18:00"));
        fireEvent.change(closes, { target: { value: "08:00" } });
        fireEvent.click(screen.getByTestId("office-hours-save"));
        expect(await screen.findByText("officeHoursInvalid")).toBeTruthy();
        expect(api.callsTo("PUT", "/office-hours")).toHaveLength(0);
    });
});

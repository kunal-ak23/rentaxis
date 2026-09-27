import { act, cleanup, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NextIntlClientProvider } from "next-intl";
import en from "../../../../messages/en.json";
import { TowerSelect } from "../TowerSelect";

// #106 R1-P3-b/c: the Tower select must never leave a `buildingId` filter
// applied with no visible sign of it (a chip to remove) or clear a filter
// that is merely waiting on a slow/failed network read.

afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
});

const withIntl = (node: React.ReactNode) => (
    <NextIntlClientProvider locale="en" messages={en}>{node}</NextIntlClientProvider>
);

describe("TowerSelect", () => {
    it("R1-P3-b: a buildingId not among this property's towers (another property's, or a deleted one) is dropped, not silently kept", async () => {
        global.fetch = vi.fn(async () => ({
            ok: true,
            json: async () => [{ id: "tower-1", nameEn: "Tower 1" }],
        })) as unknown as typeof fetch;
        const onChange = vi.fn();
        render(withIntl(
            <TowerSelect propertyId="prop-1" value="tower-from-elsewhere" onChange={onChange} />,
        ));
        await waitFor(() => expect(onChange).toHaveBeenCalledWith(""));
    });

    it("R1-P3-b: a buildingId that IS one of this property's towers keeps its chip and is never cleared", async () => {
        global.fetch = vi.fn(async () => ({
            ok: true,
            json: async () => [{ id: "tower-1", nameEn: "Tower 1" }],
        })) as unknown as typeof fetch;
        const onChange = vi.fn();
        const { findByTestId } = render(withIntl(
            <TowerSelect propertyId="prop-1" value="tower-1" onChange={onChange} testId="tf" />,
        ));
        await findByTestId("tf-chip");
        expect(onChange).not.toHaveBeenCalled();
    });

    it("R1-P3-c: a transient load failure never reports the filter unavailable (never drops a valid buildingId)", async () => {
        vi.useFakeTimers();
        let call = 0;
        global.fetch = vi.fn(async () => {
            call++;
            if (call === 1) throw new Error("network blip");
            return { ok: true, json: async () => [{ id: "tower-1", nameEn: "Tower 1" }] };
        }) as unknown as typeof fetch;
        const onAvailabilityChange = vi.fn();
        render(withIntl(
            <TowerSelect propertyId="prop-1" value="tower-1" onChange={vi.fn()} onAvailabilityChange={onAvailabilityChange} />,
        ));
        // Let the first (failing) attempt's rejection settle.
        await act(async () => { await Promise.resolve(); await Promise.resolve(); });
        // Never told the caller "unavailable" off the back of the transient failure.
        expect(onAvailabilityChange).not.toHaveBeenCalledWith(false);
        // The retry (800 ms later) runs and succeeds.
        await act(async () => { await vi.advanceTimersByTimeAsync(800); });
        expect(onAvailabilityChange).toHaveBeenCalledWith(true);
        expect(onAvailabilityChange).not.toHaveBeenCalledWith(false);
        vi.useRealTimers();
    });

    it("R1-P3-2: once the tower read gives up, a kept buildingId still shows a chip that clears it", async () => {
        global.fetch = vi.fn(async () => ({ ok: false, status: 503, json: async () => ({}) })) as unknown as typeof fetch;
        const onChange = vi.fn();
        const { findByTestId } = render(withIntl(
            <TowerSelect propertyId="prop-1" value="tower-1" onChange={onChange} testId="tf" />,
        ));
        const chip = await findByTestId("tf-chip", undefined, { timeout: 3000 });
        expect(chip).toHaveTextContent("towers could not be loaded");
        expect(onChange).not.toHaveBeenCalled();
        chip.querySelector("button")?.click();
        expect(onChange).toHaveBeenCalledWith("");
    });
});

import { addDaysIso, addMonthsIso } from "@/lib/businessDate";

/**
 * Demo feedback 2026-09-29: the Post-dated book is read by maturity window —
 * Next 1 week, Next 2 weeks (the default), Next 1 month, or a custom From–To —
 * instead of by calendar month. Every window starts on the Dubai business date
 * and both ends are inclusive, matching `GET /cheques/post-dated?from&to`.
 * The page URL uses the namespaced {@link PDC_PARAMS}.
 */
export type PdcPreset = "1w" | "2w" | "1m" | "custom";
export const PDC_PRESETS: PdcPreset[] = ["1w", "2w", "1m", "custom"];
export const DEFAULT_PDC_PRESET: Exclude<PdcPreset, "custom"> = "2w";
/** The server's cap (ChequeQueryService.MAX_POST_DATED_RANGE_DAYS): `to - from` under 366 days. */
export const MAX_PDC_WINDOW_DAYS = 366;

export type PdcWindow = { preset: PdcPreset; from: string; to: string };

const ISO = /^\d{4}-\d{2}-\d{2}$/;

export function presetWindow(preset: Exclude<PdcPreset, "custom">, today: string): { from: string; to: string } {
    switch (preset) {
        case "1w": return { from: today, to: addDaysIso(today, 7) };
        case "2w": return { from: today, to: addDaysIso(today, 14) };
        case "1m": return { from: today, to: addMonthsIso(today, 1) };
    }
}

/**
 * The URL keys. Namespaced (review of #393): the Collection hub keeps its query
 * when a search jumps to the cheque register, and the register reads plain
 * `from`/`to` as its own date filters — a custom PDC window written there
 * silently filtered the register.
 */
export const PDC_PARAMS = { range: "pdcRange", from: "pdcFrom", to: "pdcTo" } as const;
/** What #393 first wrote; read once for an old bookmark and cleared on the next write. */
const LEGACY = { range: "range", from: "from", to: "to" } as const;

/**
 * The window a URL names (`?pdcRange=1w|2w|1m|custom&pdcFrom=&pdcTo=`, or an old
 * `?range=…&from=&to=` bookmark). An unknown or missing range is the default; a
 * custom range missing a well-formed end falls back to the default preset's end.
 */
export function windowFromParams(params: URLSearchParams, today: string): PdcWindow {
    const keys = params.has(PDC_PARAMS.range) || !params.has(LEGACY.range) ? PDC_PARAMS : LEGACY;
    const raw = params.get(keys.range);
    const preset: PdcPreset = (PDC_PRESETS as string[]).includes(raw ?? "") ? (raw as PdcPreset) : DEFAULT_PDC_PRESET;
    if (preset !== "custom") return { preset, ...presetWindow(preset, today) };
    const fallback = presetWindow(DEFAULT_PDC_PRESET, today);
    const from = params.get(keys.from) ?? "";
    const to = params.get(keys.to) ?? "";
    return { preset, from: ISO.test(from) ? from : fallback.from, to: ISO.test(to) ? to : fallback.to };
}

/**
 * Writes the window into `params` in place under the namespaced keys: a preset
 * carries only `pdcRange`; custom carries its dates. An old `range=` bookmark's
 * keys (and its `from`/`to`, which were ours) are removed; a plain `from`/`to`
 * without it belongs to someone else and is left alone.
 */
export function writeWindowParams(params: URLSearchParams, w: PdcWindow): URLSearchParams {
    if (params.has(LEGACY.range)) {
        params.delete(LEGACY.range);
        params.delete(LEGACY.from);
        params.delete(LEGACY.to);
    }
    params.set(PDC_PARAMS.range, w.preset);
    if (w.preset === "custom") {
        params.set(PDC_PARAMS.from, w.from);
        params.set(PDC_PARAMS.to, w.to);
    } else {
        params.delete(PDC_PARAMS.from);
        params.delete(PDC_PARAMS.to);
    }
    return params;
}

/** Why the server would refuse this window, or null when it is fine. */
export function windowProblem(w: { from: string; to: string }): "invalid" | "tooLong" | null {
    if (!ISO.test(w.from) || !ISO.test(w.to) || w.from > w.to) return "invalid";
    const days = (Date.parse(`${w.to}T00:00:00Z`) - Date.parse(`${w.from}T00:00:00Z`)) / 86_400_000;
    return days >= MAX_PDC_WINDOW_DAYS ? "tooLong" : null;
}

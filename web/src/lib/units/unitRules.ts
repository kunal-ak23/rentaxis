/**
 * Break-it round 3 (ops3) F1/F3: the rules the backend applies to buildings and
 * units, for the forms to say the same thing before the request goes.
 * Backend: BuildingService.FLOORS_MIN/MAX and UnitRules — keep them in step.
 */
export const BUILDING_FLOORS_MIN = 1;
export const BUILDING_FLOORS_MAX = 200;

/** A building's floors: a whole number from 1 to 200. */
export function floorsInRange(floors: number): boolean {
    return Number.isInteger(floors) && floors >= BUILDING_FLOORS_MIN && floors <= BUILDING_FLOORS_MAX;
}

/** A unit's size, when given: above zero. */
export function sizeIsValid(size: string): boolean {
    if (size.trim() === "") return true;
    const n = Number(size);
    return Number.isFinite(n) && n > 0;
}

/**
 * A refusal's code and values from an API error body (`unit.numberTaken` with
 * `{unitNumber, place}`, `building.floorsOutOfRange`), so the form can say it in
 * the user's language; null when the body carries none.
 */
export function refusalOf(body: string | undefined): { code: string; args: Record<string, string | number> } | null {
    if (!body) return null;
    try {
        const parsed = JSON.parse(body);
        if (typeof parsed?.code !== "string") return null;
        return { code: parsed.code, args: parsed.args && typeof parsed.args === "object" ? parsed.args : {} };
    } catch {
        return null;
    }
}

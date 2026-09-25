/**
 * A property's rent-collection settings (GET/POST /v1/rent-settings/{propertyId}),
 * shared by Settings › Rent & fines (the full form) and Settings › Payments
 * (the online-payment switch). The save endpoint takes the whole row, so both
 * send every field back through toRentSettingsBody.
 */
export type RentSettingsData = {
    id?: string;
    propertyId: string;
    dueDayOfMonth: number;
    gracePeriodDays: number;
    penaltyType: "NONE" | "FIXED_PER_DAY" | "PERCENTAGE";
    penaltyAmount: number;
    onlinePaymentEnabled: boolean;
    // nullable fine override fields — null means inherit org default
    fineBounceAmount: number | null;
    fineSignatureMismatchAmount: number | null;
    fineAccountClosedAmount: number | null;
    fineGraceDays: number | null;
    finePerDayRate: number | null;
    /** Spec §4a: a renewal increase above this percentage shows a notice; null = none. */
    renewalIncreaseWarnPercent: number | null;
};

export const DEFAULT_RENT_SETTINGS: Omit<RentSettingsData, "propertyId"> = {
    dueDayOfMonth: 1,
    gracePeriodDays: 5,
    penaltyType: "NONE",
    penaltyAmount: 0,
    onlinePaymentEnabled: false,
    fineBounceAmount: null,
    fineSignatureMismatchAmount: null,
    fineAccountClosedAmount: null,
    fineGraceDays: null,
    finePerDayRate: null,
    renewalIncreaseWarnPercent: null,
};

export function toRentSettingsBody(s: RentSettingsData): Omit<RentSettingsData, "id" | "propertyId"> {
    return {
        dueDayOfMonth: s.dueDayOfMonth, gracePeriodDays: s.gracePeriodDays, penaltyType: s.penaltyType,
        penaltyAmount: s.penaltyAmount, onlinePaymentEnabled: s.onlinePaymentEnabled,
        fineBounceAmount: s.fineBounceAmount, fineSignatureMismatchAmount: s.fineSignatureMismatchAmount,
        fineAccountClosedAmount: s.fineAccountClosedAmount, fineGraceDays: s.fineGraceDays,
        finePerDayRate: s.finePerDayRate, renewalIncreaseWarnPercent: s.renewalIncreaseWarnPercent,
    };
}

/** GET /v1/rent-settings/{id}: 204 = no row yet (defaults; a 204 body must not be parsed), 2xx = merge, else null. */
export async function readRentSettings(res: Response, propertyId: string): Promise<RentSettingsData | null> {
    if (res.status === 204) return { ...DEFAULT_RENT_SETTINGS, propertyId };
    if (!res.ok) return null;
    return { ...DEFAULT_RENT_SETTINGS, ...(await res.json()) };
}

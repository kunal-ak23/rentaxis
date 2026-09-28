import { toCsv } from "@/lib/csv";
import { BUSINESS_TIME_ZONE } from "@/lib/businessDate";
import type { InterestDTO, InterestStatus } from "@/types/listing";

type Translate = (key: string) => string;

const STATUS_KEY: Record<InterestStatus, string> = {
    ACTIVE: "interestStatusActive",
    NOTIFIED: "interestStatusNotified",
    WITHDRAWN: "interestStatusWithdrawn",
    CONVERTED: "interestStatusConverted",
};

/** The enquiry's calendar date in the business zone, `yyyy-MM-dd` (sortable in a spreadsheet). */
function businessDate(iso: string): string {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return new Intl.DateTimeFormat("en-CA", {
        timeZone: BUSINESS_TIME_ZONE, year: "numeric", month: "2-digit", day: "2-digit",
    }).format(d);
}

/**
 * Break-it R3 data3 F1: the listing-interests export. The renter's name and the
 * "I'm interested" note are renter-typed free text, so this goes through the
 * shared `toCsv` (formula-cell neutralisation, CRLF) like every other export —
 * never a hand-rolled join. `t` is the `Listings` translator.
 */
export function interestsCsv(interests: InterestDTO[], t: Translate): string {
    const headers = [
        t("interestName"), t("interestEmail"), t("interestPhone"),
        t("interestNote"), t("interestDate"), t("interestStatus"),
    ];
    const rows = interests.map((i) => [
        i.renterName,
        i.renterEmail,
        i.renterPhone,
        i.note,
        businessDate(i.createdAt),
        STATUS_KEY[i.status] ? t(STATUS_KEY[i.status]) : i.status,
    ]);
    return toCsv(headers, rows);
}

import { fmtIsoDate } from "@/components/leases/leaseMath";
import { isolate } from "@/components/dashboard/activityText";
import { formatNumber } from "@/lib/format";

/** The fields of GET /v1/notifications this module reads. */
export type NotificationLike = {
    type: string;
    /** Server-built English copy; what older rows and unknown keys show. */
    title: string;
    message: string;
    /** Key of the structured sentence (#81); absent on rows written before it. */
    messageKey?: string | null;
    /** Raw values: plain decimals, ISO dates and ISO instants. */
    params?: Record<string, string> | null;
};

type Translator = {
    (key: string, values?: Record<string, string | number>): string;
    has: (key: string) => boolean;
    raw: (key: string) => unknown;
};

/** Values the sentences carry as numbers, for their plurals. */
const NUMERIC = new Set(["days", "seq"]);

/**
 * A notification's title and body in the viewer's language (#81).
 *
 * The server writes every notification in English, and also stores the key of
 * its sentence plus the raw values that go into it. The sentence is rebuilt
 * here from those, the values formatted for the locale (amounts grouped, dates
 * and meeting slots in UAE time) and each one isolated so a Latin name or a
 * number keeps its place inside an Arabic sentence. A row without a key, or
 * with one this build does not know, shows the stored English as before.
 */
export function notificationText(
    n: NotificationLike,
    t: Translator,
    locale: string,
    tMeetings?: Translator,
): { title: string; body: string } {
    const fallback = { title: n.title, body: n.message };
    const key = n.messageKey;
    if (!key || !t.has(`messages.${key}.title`) || !t.has(`messages.${key}.body`)) return fallback;

    const p = n.params ?? {};
    const values = localizedValues(p, t, locale, tMeetings);

    const title = render(t, `messages.${key}.title`, values);
    let body = render(t, `messages.${key}.body`, values);
    // A sentence missing one of its values falls back to its shorter variant
    // (a listing without a title), and failing that to the stored English.
    if (body == null && t.has(`messages.${key}.bodyShort`)) body = render(t, `messages.${key}.bodyShort`, values);
    if (title == null || body == null) return fallback;
    return { title, body };
}

/** Renders one message only when every argument it names is present. */
function render(t: Translator, path: string, values: Record<string, string | number>): string | null {
    const raw = t.raw(path);
    if (typeof raw !== "string") return null;
    const needed = argumentNames(raw);
    if (needed.some((name) => !(name in values))) return null;
    return t(path, values);
}

/** Top-level argument names of an ICU message: "{name}", "{days, plural, ...}". */
function argumentNames(message: string): string[] {
    const names: string[] = [];
    let depth = 0;
    for (let i = 0; i < message.length; i++) {
        const c = message[i];
        if (c === "{") {
            if (depth === 0) {
                const m = /^\{\s*(\w+)\s*[,}]/.exec(message.slice(i));
                if (m) names.push(m[1]);
            }
            depth++;
        } else if (c === "}") {
            depth--;
        }
    }
    return names;
}

function localizedValues(
    p: Record<string, string>,
    t: Translator,
    locale: string,
    tMeetings?: Translator,
): Record<string, string | number> {
    const v: Record<string, string | number> = {};
    const label = (group: string, code: string) => (t.has(`${group}.${code}`) ? t(`${group}.${code}`) : code);

    for (const [name, value] of Object.entries(p)) {
        if (value == null || value === "") continue;
        if (NUMERIC.has(name) && Number.isFinite(Number(value))) v[name] = Number(value);
        else v[name] = isolate(value);
    }
    if (p.amount != null && Number.isFinite(Number(p.amount))) {
        v.amount = isolate(t("amountAed", { amount: formatNumber(Number(p.amount)) }));
    }
    if (p.date) v.date = isolate(fmtIsoDate(p.date, locale));
    if (p.slot) v.slot = isolate(formatSlot(p.slot, locale));
    // The cheque by its number when it has one; its schedule position otherwise.
    if (p.chequeNo) v.ref = t("refCheque", { number: isolate(p.chequeNo) });
    else if (p.seq != null) v.ref = t("refInstalment", { seq: Number(p.seq) });
    if (p.reason) v.reason = label(p.reason in REASON_GROUP ? REASON_GROUP[p.reason] : "failureReason", p.reason);
    if (p.intent) v.intent = label("renewalIntent", p.intent);
    if (p.resourceType === "PARKING" && p.resourceName) v.resource = t("parkingSpot", { spot: isolate(p.resourceName) });
    else if (p.resourceType === "AMENITY" && p.resourceName) v.resource = isolate(p.resourceName);
    // A cancelled meeting is named by its title, else by its purpose.
    if (p.title) v.meeting = isolate(p.title);
    else if (p.purpose) {
        v.meeting = tMeetings && tMeetings.has(`purposeLabel.${p.purpose}`)
            ? tMeetings(`purposeLabel.${p.purpose}`)
            : p.purpose.replace(/_/g, " ");
    }
    return v;
}

const REASON_GROUP: Record<string, string> = {
    CHEQUE_RETURN: "penaltyReason",
    LATE_PAYMENT: "penaltyReason",
    OTHER: "penaltyReason",
    SERVICE_RECHARGE: "penaltyReason",
    ADMIN_FEE: "penaltyReason",
    DAMAGE: "penaltyReason",
    MAINTENANCE_RECHARGE: "penaltyReason",
    BOOKING_FEE: "penaltyReason",
};

/** A meeting slot as the office keeps it: UAE time, in the viewer's language. */
function formatSlot(iso: string, locale: string): string {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return d.toLocaleString(locale === "ar" ? "ar-AE" : "en-GB", {
        timeZone: "Asia/Dubai",
        weekday: "short",
        day: "numeric",
        month: "short",
        hour: "numeric",
        minute: "2-digit",
    });
}

/** "5 min ago" / "منذ 5 دقائق", then the date after a week. */
export function timeAgo(dateStr: string, t: Translator, locale: string, now: Date = new Date()): string {
    const date = new Date(dateStr);
    const diffMs = now.getTime() - date.getTime();
    const mins = Math.floor(diffMs / 60000);
    if (mins < 1) return t("justNow");
    if (mins < 60) return t("minutesAgo", { count: mins });
    const hours = Math.floor(diffMs / 3600000);
    if (hours < 24) return t("hoursAgo", { count: hours });
    const days = Math.floor(diffMs / 86400000);
    if (days < 7) return t("daysAgo", { count: days });
    return fmtIsoDate(dateStr, locale);
}

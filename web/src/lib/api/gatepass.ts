/**
 * Gate passes and walk-in visitors — the web client for GatePassController and
 * GateWalkInController. Types mirror GatePassDtos / GateWalkInDtos field for field.
 *
 * The credential split is the backend's and this file keeps it: only `GatePass`
 * (the creator's own view, `/gatepass/mine`) carries `qrToken` / `numericCode`.
 * Every other audience — approvers, the guard's board, the walk-in desk — gets a
 * shape with no credential in it, and nothing here reconstructs one.
 */
import { ApiError, throwIfNotOk } from "@/lib/api/facilities";
import { normalizePhone } from "@/lib/phone";

export type GatePassStatus = "PENDING_APPROVAL" | "ACTIVE" | "USED" | "EXPIRED" | "CANCELLED";
export type GatePassType = "SINGLE_USE" | "RECURRING";
export type ScanDirection = "ENTRY" | "EXIT";
export type ScanResult = "ALLOWED" | "REJECTED";
export type GateVisitorType =
    | "GUEST" | "DELIVERY" | "MAID" | "MILK_VENDOR" | "LAUNDRY_VENDOR" | "SERVICE_VENDOR" | "OTHER";

export const VISITOR_TYPES: GateVisitorType[] =
    ["GUEST", "DELIVERY", "MAID", "MILK_VENDOR", "LAUNDRY_VENDOR", "SERVICE_VENDOR", "OTHER"];

/** GatePassDtos.GatePassResponse — the creator's view, credentials included. */
export type GatePass = {
    id: string;
    propertyId: string;
    unitId: string;
    guestName: string;
    guestPhone: string;
    purpose: string | null;
    vehicleNumber: string | null;
    passType: GatePassType;
    validFrom: string;
    validTo: string;
    status: GatePassStatus;
    qrToken: string | null;
    numericCode: string | null;
    createdAt: string;
};

/** GatePassDtos.GatePassSummary — approvers and guards: no credentials, no tenant identity. */
export type GatePassSummary = {
    id: string;
    propertyId: string;
    propertyName: string | null;
    unitId: string;
    unitNumber: string | null;
    guestName: string;
    guestPhone: string;
    purpose: string | null;
    vehicleNumber: string | null;
    passType: GatePassType;
    validFrom: string;
    validTo: string;
    status: GatePassStatus;
    createdAt: string;
};

export type GuardProperty = { id: string; name: string | null };

/** GatePassDtos.ScanResponse — the gate's verdict. */
export type ScanResponse = {
    result: ScanResult;
    reason: string | null;
    guestName: string | null;
    guestPhone: string | null;
    vehicleNumber: string | null;
    purpose: string | null;
    unitNumber: string | null;
    passType: GatePassType | null;
    validFrom: string | null;
    validTo: string | null;
};

export type Destination = {
    unitId: string;
    unitNumber: string;
    propertyId: string;
    buildingId: string | null;
    buildingName: string | null;
};

/** GateWalkInDtos.WalkInPass. */
export type WalkInPass = {
    id: string;
    propertyId: string;
    unitId: string;
    unitNumber: string | null;
    guestName: string;
    guestPhone: string;
    visitorType: GateVisitorType | null;
    purpose: string | null;
    vehicleNumber: string | null;
    guestPhotoUrl: string | null;
    status: GatePassStatus;
    validTo: string;
    createdAt: string;
};

export type GatePolicy = {
    requireUnregisteredApproval: boolean;
    requireRegisteredApproval: boolean;
    notifyRegisteredEntry: boolean;
    requireFreshPhoto: boolean;
    approvalTimeoutMinutes: number;
};

export type PolicyResponse = GatePolicy & {
    id: string | null;
    propertyId: string;
    buildingId: string | null;
    /** True when nothing is configured at this exact scope and the value is inherited. */
    inherited: boolean;
};

export type RegisteredVisitor = {
    id: string;
    name: string;
    phone: string;
    visitorType: GateVisitorType | null;
};

const BASE = "/api/proxy/v1/gatepass";
const JSON_HEADERS = { "Content-Type": "application/json" };

async function json<T>(res: Response): Promise<T> {
    await throwIfNotOk(res);
    const text = await res.text().catch(() => "");
    return (text ? JSON.parse(text) : undefined) as T;
}

const post = (url: string, body?: unknown) =>
    fetch(url, { method: "POST", headers: JSON_HEADERS, body: body === undefined ? undefined : JSON.stringify(body) });

// ── Tenant (RENTER) ─────────────────────────────────────────────────────────

export type CreatePassRequest = {
    unitId: string;
    guestName: string;
    guestPhone: string;
    purpose?: string;
    vehicleNumber?: string;
    passType: GatePassType;
    validFrom: string;
    validTo: string;
};

export const fetchMyPasses = async () => json<GatePass[]>(await fetch(`${BASE}/mine`));
export const createPass = async (body: CreatePassRequest) => json<GatePass>(await post(BASE, body));
export const cancelPass = async (id: string) => json<GatePass>(await post(`${BASE}/${encodeURIComponent(id)}/cancel`));
export const fetchResidentApprovals = async () => json<WalkInPass[]>(await fetch(`${BASE}/resident-approvals`));
export const decideResidentApproval = async (id: string, approved: boolean) =>
    json<WalkInPass>(await post(`${BASE}/resident-approvals/${encodeURIComponent(id)}`, { approved }));

// ── Approvers (managers and guards) ─────────────────────────────────────────

export const fetchApprovals = async () => json<GatePassSummary[]>(await fetch(`${BASE}/approvals`));
export const decideApproval = async (id: string, approved: boolean) =>
    json<GatePassSummary>(await post(`${BASE}/${encodeURIComponent(id)}/approval`, { approved }));

// ── Security guard ──────────────────────────────────────────────────────────

export const fetchMyGuardProperties = async () => json<GuardProperty[]>(await fetch(`${BASE}/my-properties`));
export const fetchExpectedToday = async () => json<GatePassSummary[]>(await fetch(`${BASE}/expected-today`));
/** A 48-hex-character QR token, as a keyboard-wedge scanner types it; anything else is a numeric code. */
const QR_TOKEN = /^[0-9a-f]{48}$/i;

/**
 * Verify a pass at the gate. Exactly one of qrToken / numericCode goes up —
 * GatePassController#scan refuses both or neither.
 */
export async function scanCode(code: string, direction: ScanDirection): Promise<ScanResponse> {
    const value = code.trim();
    const body = QR_TOKEN.test(value) ? { qrToken: value, direction } : { numericCode: value, direction };
    return json<ScanResponse>(await post(`${BASE}/scan`, body));
}
export const fetchDestinations = async (propertyId: string) =>
    json<Destination[]>(await fetch(`${BASE}/walk-in/destinations?propertyId=${encodeURIComponent(propertyId)}`));
export const fetchWalkInsToday = async () => json<WalkInPass[]>(await fetch(`${BASE}/walk-in/today`));
export const fetchWalkInStatus = async (id: string) =>
    json<WalkInPass>(await fetch(`${BASE}/walk-in/${encodeURIComponent(id)}/status`));
export const admitWalkIn = async (id: string) => json<WalkInPass>(await post(`${BASE}/walk-in/${encodeURIComponent(id)}/admit`));

export type WalkInRequest = {
    propertyId: string;
    unitId: string;
    name: string;
    phone: string;
    visitorType: GateVisitorType;
    purpose?: string;
    vehicleNumber?: string;
    photo?: File | null;
};

/** Multipart, as GateWalkInController#create takes it; the browser sets the boundary. */
export async function createWalkIn(req: WalkInRequest): Promise<WalkInPass> {
    const fd = new FormData();
    fd.set("propertyId", req.propertyId);
    fd.set("unitId", req.unitId);
    fd.set("name", req.name);
    fd.set("phone", req.phone);
    fd.set("visitorType", req.visitorType);
    if (req.purpose) fd.set("purpose", req.purpose);
    if (req.vehicleNumber) fd.set("vehicleNumber", req.vehicleNumber);
    if (req.photo) fd.set("photo", req.photo);
    return json<WalkInPass>(await fetch(`${BASE}/walk-in`, { method: "POST", body: fd }));
}

// ── Gate policy, regular visitors and guard postings (managers) ─────────────

const scopeQuery = (propertyId: string, buildingId?: string | null) => {
    const q = new URLSearchParams({ propertyId });
    if (buildingId) q.set("buildingId", buildingId);
    return q.toString();
};

export const fetchEffectivePolicy = async (propertyId: string, buildingId?: string | null) =>
    json<PolicyResponse>(await fetch(`${BASE}/policies/effective?${scopeQuery(propertyId, buildingId)}`));
/** Sends exactly GateWalkInDtos.PolicyRequest's fields, whatever else the caller's object carries. */
export async function savePolicy(propertyId: string, buildingId: string | null, policy: GatePolicy): Promise<PolicyResponse> {
    const body: GatePolicy = {
        requireUnregisteredApproval: policy.requireUnregisteredApproval,
        requireRegisteredApproval: policy.requireRegisteredApproval,
        notifyRegisteredEntry: policy.notifyRegisteredEntry,
        requireFreshPhoto: policy.requireFreshPhoto,
        approvalTimeoutMinutes: policy.approvalTimeoutMinutes,
    };
    return json<PolicyResponse>(await fetch(`${BASE}/policies?${scopeQuery(propertyId, buildingId)}`, {
        method: "PUT", headers: JSON_HEADERS, body: JSON.stringify(body),
    }));
}

export type RegisterVisitorRequest = {
    propertyId: string;
    unitId: string;
    name: string;
    phone: string;
    visitorType: GateVisitorType;
    validFrom: string | null;
    validTo: string | null;
    active: boolean;
};

export const registerVisitor = async (body: RegisterVisitorRequest) =>
    json<RegisteredVisitor>(await post(`${BASE}/visitors/registration`, body));

export const fetchGuardProperties = async (userId: string) =>
    json<string[]>(await fetch(`${BASE}/guards/${encodeURIComponent(userId)}/properties`));
export const saveGuardProperties = async (userId: string, propertyIds: string[]) =>
    json<string[]>(await fetch(`${BASE}/guards/${encodeURIComponent(userId)}/properties`, {
        method: "PUT", headers: JSON_HEADERS, body: JSON.stringify(propertyIds),
    }));

// ── Rules shared by the screens ─────────────────────────────────────────────

/** The slice of LeaseDTO (`/leases/my-leases`) the pass form reads. */
export type MyContract = {
    id: string;
    unitId: string;
    unitIdentifier: string | null;
    propertyId: string | null;
    propertyName: string | null;
    status: string;
    startDate: string | null;
    endDate: string | null;
};

const CURRENT_STATUSES = new Set(["ACTIVE", "NOTICE_GIVEN", "RENEWED"]);

/**
 * The tenant's current contracts: the units a pass may be raised for. Mirrors
 * LeaseRepository#findCurrentForRenterUser, which GatePassController#create
 * checks — a live status AND today inside the term. Offering anything wider
 * turns a knowable "no current contract" into the server's opaque 404.
 * `today` is the business date (yyyy-MM-dd), compared as a string.
 */
export function currentContracts(all: MyContract[], today: string): MyContract[] {
    return all.filter(c => CURRENT_STATUSES.has(c.status)
        && (!c.startDate || c.startDate <= today)
        && (!c.endDate || c.endDate >= today));
}

/**
 * The phone in the only form the walk-in desk accepts — `+` and 7-15 digits
 * (GateWalkInService.normalizePhone) — or null when it cannot be made so. A
 * leading `00` is read as `+`. The tenant's pass form uses the same rule, as
 * the mobile app does, so a guest's number is the same everywhere.
 */
export function toE164(raw: string): string | null {
    let v = normalizePhone(raw).replace(/[^0-9+]/g, "");
    if (v.startsWith("00")) v = `+${v.slice(2)}`;
    return /^\+\d{7,15}$/.test(v) ? v : null;
}

/** Translation key (GatePass namespace) for a scan refusal the server sends in English. */
const REASON_KEYS: Record<string, string> = {
    "not found": "reasonNotFound",
    "not authorized for this property": "reasonWrongProperty",
    "no entry recorded": "reasonNoEntry",
    "pending approval": "reasonPending",
    "cancelled": "reasonCancelled",
    "expired": "reasonExpired",
    "already used": "reasonUsed",
    "outside validity window": "reasonOutsideWindow",
    "scan in progress, please retry": "reasonRetry",
};

export function scanReasonKey(reason: string | null | undefined): string | null {
    if (!reason) return null;
    return REASON_KEYS[reason.trim().toLowerCase()] ?? null;
}

/** The instant for a local date + time (`yyyy-MM-dd`, `HH:mm`), as the ISO string the API takes. */
export function localInstant(date: string, time: string): string {
    const [y, m, d] = date.split("-").map(Number);
    const [hh, mm] = time.split(":").map(Number);
    return new Date(y, m - 1, d, hh, mm, 0, 0).toISOString();
}

/** A message for a failed call: the server's own sentence when it sent one, else the fallback. */
export function errorText(err: unknown, fallback: string): string {
    if (err instanceof ApiError && err.message && !/^Request failed \(status \d+\)$/.test(err.message)) return err.message;
    return fallback;
}

export { ApiError };

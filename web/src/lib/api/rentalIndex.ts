import { throwIfNotOk } from "@/lib/api/facilities";
import { apiGet, apiSend, qs } from "@/lib/api/ledger";

/** Rental index ranges (RentalIndexController). Mirrors the DTOs under api/dto/rentalindex. */
export type RentalUnitType = "STUDIO" | "BHK1" | "BHK2" | "BHK3" | "PENTHOUSE";
export type DldPropertyType = "APARTMENT" | "VILLA" | "HOTEL_APARTMENT";
export type RentalIndexSource = "DLD_PDF" | "MANUAL" | "DLD_API";

export const RENTAL_UNIT_TYPES: RentalUnitType[] = ["STUDIO", "BHK1", "BHK2", "BHK3", "PENTHOUSE"];

export type RentalIndexRate = {
  id: string; propertyId: string; buildingId: string | null; buildingName: string | null;
  dldPropertyType: DldPropertyType | null; category: string | null; unitType: string;
  indexMin: number; indexMax: number; average: number; areaName: string | null;
  source: RentalIndexSource; sourceUnitId: string | null;
  /** The PDF is fetched from {@link rentalIndexApi.documentUrl}; the DTO carries no URL. */
  hasDocument: boolean;
  dldPercent: number | null; dldIssuedAt: string | null; checkedOn: string; expiresOn: string;
  expired: boolean; revoked: boolean; createdBy: string | null; createdByName: string | null;
  createdAt: string; revokedBy: string | null; revokedAt: string | null;
};

export type ParsedResult = {
  dldPercent: number; increase: number | null; rentAfter: number | null; premiseNo: string | null;
  contractEnd: string | null; currentRent: number | null; indexMin: number; indexMax: number; average: number;
  category: string | null; building: string | null; area: string | null; dldPropertyType: string | null;
  issuedAt: string | null; checkedOn: string; proposedUnitType: string | null;
  /** true/false, or null when there was nothing to compare. */
  premiseMatches: boolean | null; endMatches: boolean | null; rentMatches: boolean | null;
};

export type ManualRateInput = {
  propertyId: string; buildingId?: string | null; unitType: RentalUnitType; dldPropertyType: DldPropertyType;
  category?: string | null; indexMin: number; indexMax: number; checkedOn: string;
};

export type FromPdfArgs = { propertyId: string; buildingId?: string | null; unitType: RentalUnitType; unitId?: string | null };

const BASE = "/api/proxy/v1/rental-index";

/** The caps endpoint takes at most this many lease ids per call (query-string size). */
export const CAPS_BATCH = 100;

export type CapStatus = "OK" | "NO_INDEX" | "NOT_DUBAI" | "NO_EMIRATE" | "NOT_RESIDENTIAL";
export type LeaseCap = {
  leaseId: string; status: CapStatus; allowedPercent: number | null; capRent: number | null; baseRent: number | null;
};

/** One stored rental index check on a lease (GET /leases/{id}/index-checks); figures are annual. */
export type LeaseIndexCheck = {
  id: string; leaseId: string; successorLeaseId: string | null; decidedAt: string; decidedBy: string | null;
  decidedByName: string | null; baseRent: number; newRent: number; average: number; gapPercent: number;
  allowedPercent: number; capRent: number; aboveCap: boolean; overrideReason: string | null;
  rateSource: RentalIndexSource; rateCheckedOn: string; hasUnitDocument: boolean;
};

/** DLD's category wording to a Miftah unit type: "Studio", "N bed rooms+hall" (N up to 3); anything else has none. */
export function unitTypeForCategory(category: string | null | undefined): RentalUnitType | null {
  const c = (category ?? "").trim().toLowerCase();
  if (c === "studio") return "STUDIO";
  const m = /^(\d+)\s*bed\s*rooms?\s*\+\s*hall$/.exec(c);
  if (!m) return null;
  const n = Number(m[1]);
  return n === 1 ? "BHK1" : n === 2 ? "BHK2" : n === 3 ? "BHK3" : null;
}

/** Multipart POST. No Content-Type header: only the browser can write the boundary. */
async function postFile<T>(path: string, file: File, params: Record<string, string | null | undefined>): Promise<T> {
  const fd = new FormData();
  fd.append("file", file);
  const res = await fetch(`${BASE}${path}${qs(params)}`, { method: "POST", body: fd });
  await throwIfNotOk(res);
  return res.json();
}

export const rentalIndexApi = {
  list: (propertyId?: string) => apiGet<RentalIndexRate[]>(`/rental-index/rates${qs({ propertyId })}`),
  /** Reads a DLD result and cross-checks it against a unit and lease; stores nothing. */
  parse: (file: File, ctx: { unitId?: string | null; leaseId?: string | null } = {}) =>
    postFile<ParsedResult>("/parse", file, { unitId: ctx.unitId, leaseId: ctx.leaseId }),
  fromPdf: (file: File, a: FromPdfArgs) =>
    postFile<RentalIndexRate>("/rates/from-pdf", file, {
      propertyId: a.propertyId, buildingId: a.buildingId, unitType: a.unitType, unitId: a.unitId,
    }),
  manual: (body: ManualRateInput) => apiSend<RentalIndexRate>("POST", "/rental-index/rates/manual", body),
  revoke: (id: string) => apiSend<RentalIndexRate>("POST", `/rental-index/rates/${id}/revoke`),
  /** Max increase per lease, in chunks of {@link CAPS_BATCH}; leases the caller can't read are omitted. */
  caps: async (leaseIds: string[]): Promise<LeaseCap[]> => {
    const parts: string[][] = [];
    for (let i = 0; i < leaseIds.length; i += CAPS_BATCH) parts.push(leaseIds.slice(i, i + CAPS_BATCH));
    const pages = await Promise.all(parts.map(c => apiGet<LeaseCap[]>(`/rental-index/caps${qs({ leaseIds: c })}`)));
    return pages.flat();
  },
  documentUrl: (id: string) => `${BASE}/rates/${id}/document`,
  /** The checks stored on a lease, as renewed or as successor, newest first (staff only; 404 when the flag is off). */
  indexChecks: (leaseId: string) => apiGet<LeaseIndexCheck[]>(`/leases/${leaseId}/index-checks`),
};

export function rateStatus(r: Pick<RentalIndexRate, "revoked" | "expired">): "REVOKED" | "EXPIRED" | "ACTIVE" {
  return r.revoked ? "REVOKED" : r.expired ? "EXPIRED" : "ACTIVE";
}

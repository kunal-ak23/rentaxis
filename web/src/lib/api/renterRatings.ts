import { apiGet, apiSend, qs, type Page } from "@/lib/api/ledger";

/** Premium Tenant Rating (RenterRatingController). Mirrors the DTOs under api/dto/rating. */
export type RatingGrade = "A" | "B" | "C" | "D" | "NEW";

export type RatingEventType =
  | "ON_TIME_CHEQUE" | "RENEWAL" | "EARLY_INTENT" | "BOUNCE" | "LATE_CLEAR" | "LATE_PAYMENT_PENALTY"
  | "MISCONDUCT_PENALTY" | "WRITE_OFF" | "LONG_HOLD" | "NO_RESPONSE" | "MEETING_NO_SHOW" | "ADJUSTMENT";

export type RatingEvent = {
  type: RatingEventType; date: string; basePoints: number; weight: number; appliedPoints: number;
  sourceType: string | null; sourceId: string | null; leaseId: string | null;
  adjustmentId: string | null; note: string | null;
};
export type RatingTrendPoint = { date: string; score: number; grade: RatingGrade };
export type RenterRatingDetail = {
  renterId: string; score: number; grade: RatingGrade; deductionsCount: number; base: number;
  events: RatingEvent[]; trend: RatingTrendPoint[]; hasStartedContract: boolean;
};
export type RenterRatingRow = {
  renterId: string; renterName: string; renterNameAr: string | null; score: number; grade: RatingGrade;
  deductionsCount: number; currentLeaseId: string | null; unitLabel: string | null;
  propertyName: string | null; computedAt: string;
};
export type RatingListParams = {
  grade?: RatingGrade; propertyId?: string; q?: string; renterIds?: string[];
  page?: number; size?: number; sort?: string;
};

export const renterRatingsApi = {
  get: (renterId: string) => apiGet<RenterRatingDetail>(`/renters/${renterId}/rating`),
  list: (params: RatingListParams = {}) => apiGet<Page<RenterRatingRow>>(`/renter-ratings${qs(params)}`),
  /** CSV download link (same proxy base the other helpers use). */
  exportUrl: (params: RatingListParams = {}) => `/api/proxy/v1/renter-ratings/export${qs(params)}`,
  addAdjustment: (renterId: string, body: { points: number; reason: string }) =>
    apiSend<RatingEvent>("POST", `/renters/${renterId}/rating/adjustments`, body),
  removeAdjustment: (renterId: string, adjustmentId: string) =>
    apiSend<void>("DELETE", `/renters/${renterId}/rating/adjustments/${adjustmentId}`),
};

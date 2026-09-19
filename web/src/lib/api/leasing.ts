import { ApiError, throwIfNotOk } from "@/lib/api/facilities";
import type { AccountRole, Page, JournalEntry } from "@/lib/api/ledger";

export { ApiError };

const BASE = "/api/proxy/v1";

function qs(params: Record<string, string | number | boolean | string[] | undefined | null>): string {
  const sp = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === "") continue;
    sp.set(k, Array.isArray(v) ? v.join(",") : String(v));
  }
  const s = sp.toString();
  return s ? `?${s}` : "";
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { method: "GET" });
  await throwIfNotOk(res);
  return res.json();
}
async function send<T>(method: "POST" | "PUT" | "DELETE", path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  await throwIfNotOk(res);
  return res.status === 204 ? (undefined as T) : res.json();
}

// ---- enums (mirror the backend's domain.entity.enums.*) ----

export type ChargeBehaviour = "RENT" | "DEPOSIT" | "FEE";

export type ChequeMode = "PDC" | "CASH" | "TRANSFER" | "ONLINE";

export type ChequeStatus =
  | "DRAFT"
  | "REGISTERED"
  | "DEPOSITED"
  | "CLEARED"
  | "BOUNCED"
  | "REPLACED"
  | "CANCELLED"
  | "RETURNED"
  | "ONLINE_PENDING";

export type ChequeFailureReason = "BOUNCE" | "SIGNATURE_MISMATCH" | "ACCOUNT_CLOSED";

export type PenaltyReason = "CHEQUE_RETURN" | "LATE_PAYMENT" | "OTHER";

export type PenaltyAssessmentStatus = "PROPOSED" | "APPROVED" | "WAIVED" | "REVERSED";

/** Not carried on any DTO the web reads today; kept for the i18n labels and for later tasks. */
export type OnlinePaymentStatus = "CREATED" | "CAPTURED" | "CAPTURED_UNAPPLIED" | "FAILED" | "REFUNDED";

export type LeaseStatus =
  | "DRAFT"
  | "PENDING_SIGNATURE"
  | "ACTIVE"
  | "NOTICE_GIVEN"
  | "TERMINATED"
  | "RENEWED"
  | "EXPIRED"
  | "CLOSED";

export type InstallmentDistribution = "UNIFORM" | "FIRST_LARGER" | "LAST_LARGER" | "FIRST_AND_LAST_LARGER";

/**
 * What a lease is read back as. The column still holds the older, wider set on
 * leases drafted before accounting-v2, so a lease that comes back says
 * BANK_TRANSFER or CASH and the type has to admit it.
 */
export type PaymentMethod = "CHEQUE" | "ONLINE" | "BANK_TRANSFER" | "CASH";

/**
 * What a draft may be SAVED as. `CreateLeaseDTO#paymentMethod` is a free string
 * on the wire, but its own doc admits only these two, and the cheque grid is
 * cut from one of them — a draft saved as BANK_TRANSFER generated a grid the
 * service had no mode for. Narrower than {@link PaymentMethod} on purpose: the
 * wider one is what the server may return, this is what the client may send.
 */
export type DraftPaymentMethod = "CHEQUE" | "ONLINE";

// ---- types (mirror the backend DTOs — see api/dto/lease, api/dto/cheque, api/dto/penalty) ----

/** Wire shape of ChargeTypeDTO; used both for reading the catalogue and for create/update bodies. */
export type ChargeType = {
  id: string;
  code: string;
  nameEn: string;
  nameAr: string | null;
  role: AccountRole;
  behaviour: ChargeBehaviour;
  vatApplicableDefault: boolean;
  active: boolean;
  displayOrder: number;
};

/** LeaseLineDTO — a persisted lease line as read back from the server. */
export type LeaseLine = {
  id: string;
  seqNo: number;
  chargeTypeId: string;
  chargeTypeCode: string;
  chargeTypeName: string;
  behaviour: ChargeBehaviour;
  creditAccountId: string | null;
  creditAccountCode: string | null;
  creditAccountName: string | null;
  grossAmount: number;
  discountAmount: number;
  netAmount: number;
  narration: string | null;
  vatApplicable: boolean;
  periodStart: string | null;
  periodEnd: string | null;
};

/** LeaseLineInput — one line as the caller submits it (create, update, renew, extend, amend). */
export type LeaseLineInput = {
  chargeTypeId?: string | null;
  chargeTypeCode?: string | null;
  grossAmount: number;
  discountAmount?: number | null;
  narration?: string | null;
  vatApplicable?: boolean | null;
  creditAccountId?: string | null;
  periodStart?: string | null;
  periodEnd?: string | null;
};

/** CreateLeaseDTO — create/update body for a draft lease. */
export type DraftLeaseInput = {
  unitId: string;
  renterId: string;
  startDate: string;
  endDate: string;
  contractDate?: string | null;
  gracePeriodDays?: number | null;
  firstDueDate?: string | null;
  ejariNumber?: string | null;
  paymentTerms?: number | null;
  installmentDistribution?: InstallmentDistribution | null;
  paymentMethod?: DraftPaymentMethod | null;
  depositPaymentMethod?: DraftPaymentMethod | null;
  paymentReferenceNumber?: string | null;
  agreementDate?: string | null;
  rentVatApplicable?: boolean | null;
  lines: LeaseLineInput[];
};

/** LeaseDTO — the lease as read back, including its posting/renewal-chain state and lines. */
export type LeaseDetail = {
  id: string;
  unitId: string;
  renterId: string;
  unitIdentifier: string | null;
  renterName: string | null;
  startDate: string;
  endDate: string;
  status: LeaseStatus;
  rentAmount: number | null;
  depositAmount: number | null;
  ejariNumber: string | null;
  paymentTerms: number | null;
  installmentDistribution: InstallmentDistribution | null;
  paymentMethod: PaymentMethod | null;
  depositPaymentMethod: PaymentMethod | null;
  paymentReferenceNumber: string | null;
  propertyId: string | null;
  propertyName: string | null;
  propertyCode: string | null;
  hasContract: boolean;
  contractNumber: number | null;
  displayContractNumber: string | null;
  agreementDate: string | null;
  rentVatApplicable: boolean | null;
  contractDate: string | null;
  totalDays: number | null;
  gracePeriodDays: number | null;
  firstDueDate: string | null;
  renterAcceptedAt: string | null;
  renewedFromLeaseId: string | null;
  chainId: string | null;
  receivableAccountId: string | null;
  incomeAccountId: string | null;
  postingJournalId: string | null;
  postedAt: string | null;
  contractValue: number | null;
  lines: LeaseLine[];
};

/** AmendLeaseLinesRequest. */
export type AmendLeaseLinesInput = {
  lines: LeaseLineInput[];
  reason?: string | null;
};

/** RenewLeaseRequest — `lines` omitted/null means "copy the predecessor's lines". */
export type RenewLeaseInput = {
  contractDate?: string | null;
  startDate: string;
  endDate: string;
  lines?: LeaseLineInput[] | null;
  carryDepositForward: boolean;
};

/** ChequeRowInput — one row of a lease's cheque grid, or an extension's registered cheques. */
export type ChequeRowInput = {
  id?: string | null;
  seqNo?: number | null;
  postingDate?: string | null;
  chequeNumber?: string | null;
  chequeDate?: string | null;
  payeeBank?: string | null;
  payerName?: string | null;
  debitAccountId?: string | null;
  amount: number;
  narration?: string | null;
  mode?: ChequeMode | null;
};

/** ExtendLeaseRequest. */
export type ExtendLeaseInput = {
  newEndDate: string;
  contractDate?: string | null;
  lines: LeaseLineInput[];
  cheques: ChequeRowInput[];
};

/** GenerateChequesRequest — every field optional, the service fills in the lease's own defaults. */
export type GenerateChequesRequest = {
  installments?: number | null;
  firstDueDate?: string | null;
  distribution?: InstallmentDistribution | null;
  payeeBank?: string | null;
  debitAccountId?: string | null;
  foldDepositsAndFeesIntoFirst?: boolean | null;
  mode?: ChequeMode | null;
};

/** ChequeDTO — one row of the cheque register. */
export type Cheque = {
  id: string;
  leaseId: string;
  propertyId: string;
  unitId: string;
  renterId: string;
  propertyName: string | null;
  unitIdentifier: string | null;
  renterName: string | null;
  seqNo: number;
  postingDate: string;
  chequeNumber: string | null;
  chequeDate: string | null;
  payeeBank: string | null;
  payerName: string | null;
  debitAccountId: string | null;
  debitAccountName: string | null;
  amount: number;
  narration: string | null;
  mode: ChequeMode;
  status: ChequeStatus;
  failureReason: ChequeFailureReason | null;
  replacesId: string | null;
  replacedById: string | null;
  imageUrl: string | null;
  depositedAt: string | null;
  clearedAt: string | null;
  bouncedAt: string | null;
  returnedAt: string | null;
  pdrJournalId: string | null;
  crtJournalId: string | null;
  cbrJournalId: string | null;
  penaltyAssessmentId: string | null;
  due: boolean;
  overdue: boolean;
  daysOverdue: number;
};

/** PostLeaseResponse — the lease and its cheques re-read after a post, an amend, or an extend. */
export type PostLeaseResponse = {
  lease: LeaseDetail;
  tcoJournalId: string;
  tcoEntryNumber: string;
  cheques: Cheque[];
};

/** PostLeaseDryRunResponse — every validation a real post would run, nothing written. */
export type PostLeaseDryRunResponse = {
  ok: boolean;
  errors: string[];
  contractValue: number;
  contractValueInclVat: number;
  chequeTotal: number;
  depositCarriedForward: number;
  journals: { tco: number; tcoLines: number; pdr: number };
};

/** ChequeActionRequest — the shared shape for deposit/clear/receive/bounce/cancel. */
export type ChequeActionInput = {
  date?: string | null;
  notes?: string | null;
  failureReason?: ChequeFailureReason | null;
  debitAccountId?: string | null;
};

/** DepositBatchRequest — the day's deposit run. */
export type DepositBatchInput = {
  chequeIds: string[];
  date?: string | null;
  debitAccountId?: string | null;
};

/** ReplaceChequeRequest — what the renter handed over after a bounce. */
export type ReplaceChequeInput = {
  replacements: ChequeRowInput[];
  date?: string | null;
  notes?: string | null;
};

/** ChequeSummaryDTO — the register's summary tiles. */
export type ChequeSummary = {
  registeredCount: number;
  registeredAmount: number;
  depositedCount: number;
  depositedAmount: number;
  clearedThisMonthAmount: number;
  bouncedCount: number;
  bouncedAmount: number;
  dueCount: number;
  dueAmount: number;
  overdueCount: number;
  overdueAmount: number;
};

/** AgingReportDTO. */
export type AgingReport = {
  buckets: {
    label: string;
    fromDays: number;
    toDays: number | null;
    count: number;
    amount: number;
    rows: {
      chequeId: string;
      leaseId: string;
      renterName: string | null;
      propertyName: string | null;
      unitIdentifier: string | null;
      chequeNumber: string | null;
      chequeDate: string;
      amount: number;
      daysOverdue: number;
    }[];
  }[];
  totalOutstanding: number;
  totalCount: number;
};

/** LeaseChequeStatsDTO — one lease's collection position, for a batch of leases. */
export type LeaseChequeStats = {
  leaseId: string;
  total: number;
  cleared: number;
  uncleared: number;
  bounced: number;
  totalAmount: number;
  clearedAmount: number;
  dueAmount: number;
};

/** PenaltyAssessmentDTO. */
export type PenaltyAssessment = {
  id: string;
  leaseId: string;
  chequeId: string | null;
  chequeNumber: string | null;
  renterId: string;
  renterName: string | null;
  propertyId: string;
  propertyName: string | null;
  reason: PenaltyReason;
  amount: number;
  description: string | null;
  status: PenaltyAssessmentStatus;
  proposedBy: string | null;
  proposedAt: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  journalId: string | null;
  collectionChequeId: string | null;
  collectionStatus: ChequeStatus | null;
  resolutionNote: string | null;
};

/** ProposePenaltyRequest. */
export type ProposePenaltyInput = {
  leaseId: string;
  chequeId?: string | null;
  reason: PenaltyReason;
  amount: number;
  description?: string | null;
};

/** RenterChequeDTO — a row of the renter's own "my payments" screen. */
export type RenterCheque = {
  id: string;
  leaseId: string;
  installmentNumber: number;
  dueDate: string;
  amount: number;
  status: ChequeStatus;
  mode: ChequeMode;
  chequeNumber: string | null;
  bankName: string | null;
  narration: string | null;
  propertyName: string | null;
  unitIdentifier: string | null;
  renterName: string | null;
  due: boolean;
  overdue: boolean;
  daysOverdue: number;
  gracePeriodDays: number;
  penaltyOutstanding: number;
  payable: number;
  onlineEnabled: boolean;
  penaltyAssessmentId: string | null;
  failureReason: ChequeFailureReason | null;
  clearedAt: string | null;
  statusChangedAt: string | null;
};

/** CreateOrderResponseDTO. */
export type CreateOrderResponse = {
  orderId: string;
  amount: number;
  currency: string;
  gatewayKey: string;
  gatewayCode: string;
  sdkJsUrl: string;
  renterName: string | null;
  renterEmail: string | null;
};

/** VerifyPaymentRequestDTO. */
export type VerifyPaymentInput = {
  gatewayOrderId: string;
  gatewayPaymentId: string;
  gatewaySignature: string;
};

/** VerifyPaymentResponseDTO. */
export type VerifyPaymentResult = {
  success: boolean;
  message: string | null;
  paymentId: string | null;
};

// ---- query shapes ----

export type ChequeListQuery = {
  propertyId?: string;
  status?: ChequeStatus;
  mode?: ChequeMode;
  from?: string;
  to?: string;
  search?: string;
  page?: number;
  size?: number;
};

export type ChequeDueQuery = {
  propertyId?: string;
  asOf?: string;
  page?: number;
  size?: number;
};

export type PostDatedQuery = {
  propertyId?: string;
  month?: string;
};

export type PenaltyListQuery = {
  leaseId?: string;
  status?: PenaltyAssessmentStatus;
  propertyId?: string;
  page?: number;
  size?: number;
};

// ---- API objects ----

export const chargeTypeApi = {
  list: (activeOnly?: boolean) => get<ChargeType[]>(`/finance/charge-types${qs({ activeOnly })}`),
  create: (body: ChargeType) => send<ChargeType>("POST", "/finance/charge-types", body),
  update: (id: string, body: ChargeType) => send<ChargeType>("PUT", `/finance/charge-types/${id}`, body),
};

export const leaseApi = {
  get: (id: string) => get<LeaseDetail>(`/leases/${id}`),
  /**
   * The leases list. `status` and `propertyId` are filters, not hints: they
   * narrow the page the server hands back (`LeaseController#getAllLeasesPaged`,
   * single-valued `status` — not a CSV). A client-side filter over the page
   * already fetched hid every match outside that one page.
   */
  paged: (q: { search?: string; status?: LeaseStatus; propertyId?: string; page?: number; size?: number } = {}) =>
    get<Page<LeaseDetail>>(
      `/leases/paged${qs({ search: q.search, status: q.status, propertyId: q.propertyId, page: q.page ?? 0, size: q.size ?? 25 })}`,
    ),
  createDraft: (body: DraftLeaseInput) => send<LeaseDetail>("POST", "/leases", body),
  updateDraft: (id: string, body: DraftLeaseInput) => send<LeaseDetail>("PUT", `/leases/${id}`, body),
  post: (id: string) => send<PostLeaseResponse>("POST", `/leases/${id}/post`),
  /** `?dryRun=true` — every validation a post would run, nothing written. */
  dryRunPost: (id: string) => send<PostLeaseDryRunResponse>("POST", `/leases/${id}/post${qs({ dryRun: true })}`),
  amendLines: (id: string, body: AmendLeaseLinesInput) => send<PostLeaseResponse>("POST", `/leases/${id}/amend-lines`, body),
  renew: (id: string, body: RenewLeaseInput) => send<LeaseDetail>("POST", `/leases/${id}/renew`, body),
  extend: (id: string, body: ExtendLeaseInput) => send<PostLeaseResponse>("POST", `/leases/${id}/extend`, body),
  cheques: (id: string) => get<Cheque[]>(`/leases/${id}/cheques`),
  generateCheques: (id: string, req?: GenerateChequesRequest) =>
    send<Cheque[]>("POST", `/leases/${id}/cheques/generate`, req),
  generateChequeNumbers: (id: string, startingNumber: string) =>
    send<Cheque[]>("POST", `/leases/${id}/cheques/numbers`, { startingNumber }),
  saveCheques: (id: string, rows: ChequeRowInput[]) => send<Cheque[]>("PUT", `/leases/${id}/cheques`, rows),
  /** GET /finance/journals?leaseId — the plan-1 journals list, filtered to one lease. */
  journals: (leaseId: string, q: { page?: number; size?: number } = {}) =>
    get<Page<JournalEntry>>(`/finance/journals${qs({ leaseId, page: q.page ?? 0, size: q.size ?? 25 })}`),
};

export const chequeApi = {
  list: (q: ChequeListQuery) => get<Page<Cheque>>(`/cheques${qs(q)}`),
  due: (q: ChequeDueQuery) => get<Page<Cheque>>(`/cheques/due${qs(q)}`),
  toDeposit: (q: ChequeDueQuery) => get<Page<Cheque>>(`/cheques/to-deposit${qs(q)}`),
  postDated: (q: PostDatedQuery) => get<Cheque[]>(`/cheques/post-dated${qs(q)}`),
  /** The controller also takes `asOf`; the brief only names `propertyId`, so it is optional and second. */
  summary: (propertyId?: string, asOf?: string) => get<ChequeSummary>(`/cheques/summary${qs({ propertyId, asOf })}`),
  aging: (propertyId?: string, asOf?: string) => get<AgingReport>(`/cheques/aging${qs({ propertyId, asOf })}`),
  statsByLeases: (leaseIds: string[]) => send<LeaseChequeStats[]>("POST", "/cheques/stats-by-leases", leaseIds),
  get: (id: string) => get<Cheque>(`/cheques/${id}`),
  deposit: (id: string, body?: ChequeActionInput) => send<Cheque>("PUT", `/cheques/${id}/deposit`, body),
  depositBatch: (body: DepositBatchInput) => send<Cheque[]>("POST", "/cheques/deposit-batch", body),
  clear: (id: string, body?: ChequeActionInput) => send<Cheque>("PUT", `/cheques/${id}/clear`, body),
  receive: (id: string, body?: ChequeActionInput) => send<Cheque>("PUT", `/cheques/${id}/receive`, body),
  /** `body.failureReason` is required — the backend 400s a bounce without one. */
  bounce: (id: string, body: ChequeActionInput) => send<Cheque>("PUT", `/cheques/${id}/bounce`, body),
  replace: (id: string, body: ReplaceChequeInput) => send<Cheque[]>("POST", `/cheques/${id}/replace`, body),
  cancel: (id: string, body?: ChequeActionInput) => send<Cheque>("PUT", `/cheques/${id}/cancel`, body),
  updateDetails: (id: string, body: ChequeRowInput) => send<Cheque>("PUT", `/cheques/${id}/details`, body),
  cashReceipt: (leaseId: string, body: ChequeRowInput) => send<Cheque>("POST", `/cheques/lease/${leaseId}/cash-receipt`, body),
  /** Not a fetch — the endpoint streams a PDF; callers open/download this path directly. */
  receiptUrl: (id: string) => `${BASE}/cheques/${id}/receipt`,
};

export const penaltyApi = {
  list: (q: PenaltyListQuery) => get<Page<PenaltyAssessment>>(`/penalties${qs(q)}`),
  propose: (body: ProposePenaltyInput) => send<PenaltyAssessment>("POST", "/penalties", body),
  approve: (id: string, date?: string) => send<PenaltyAssessment>("POST", `/penalties/${id}/approve`, { date }),
  waive: (id: string, note?: string) => send<PenaltyAssessment>("POST", `/penalties/${id}/waive`, { note }),
  reverse: (id: string, body: { date?: string; note?: string }) => send<PenaltyAssessment>("POST", `/penalties/${id}/reverse`, body),
  mine: () => get<PenaltyAssessment[]>("/penalties/mine"),
};

export const onlinePayApi = {
  myPayments: () => get<RenterCheque[]>("/online-payments/my-payments"),
  createOrder: (chequeId: string) => send<CreateOrderResponse>("POST", "/online-payments/create-order", { chequeId }),
  verify: (body: VerifyPaymentInput) => send<VerifyPaymentResult>("POST", "/online-payments/verify", body),
  cancel: (chequeId: string) => send<void>("POST", `/online-payments/cancel/${chequeId}`),
};

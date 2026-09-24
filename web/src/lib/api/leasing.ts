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
async function send<T>(method: "POST" | "PUT" | "PATCH" | "DELETE", path: string, body?: unknown): Promise<T> {
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

/** RecognitionStatus — domain.entity.enums.RecognitionStatus (spec §8.2). */
export type RecognitionStatus = "PLANNED" | "POSTED" | "REVERSED" | "CANCELLED";

/** DeductionCategory — domain.entity.enums.DeductionCategory, in full. */
export type DeductionCategory =
  | "UNPAID_RENT"
  | "PENALTIES"
  | "PROPERTY_DAMAGE"
  | "EARLY_TERMINATION_FEE"
  | "CLEANING"
  | "UTILITY_ARREARS"
  | "KEY_REPLACEMENT"
  | "OTHER";

/** AdditionCategory — domain.entity.enums.AdditionCategory, in full. */
export type AdditionCategory =
  | "PREPAID_RENT"
  | "UTILITY_OVERPAYMENT"
  | "DEPOSIT_INTEREST"
  | "LANDLORD_COMPENSATION"
  | "OTHER";

export type SettlementLineType = "DEDUCTION" | "ADDITION";

export type SettlementStatus = "DRAFT" | "FINALIZED";

/**
 * The deduction categories a settlement line may actually carry.
 *
 * `PENALTIES` and `UNPAID_RENT` are deliberately absent, mirroring
 * `SettlementService.DEDUCTION_ROLES` / `requireAllowed(DeductionCategory)`
 * (backend/src/main/java/com/datagami/rentaxis/core/service/SettlementService.java:131-138,
 * :737-751): both are already inside `receivableBalance`, which the statement
 * subtracts, so a line for either charges the renter's deposit twice and is
 * refused with a 400 on save *and* on finalise. Offering them in the select
 * would be offering an action the server always refuses.
 */
export const SETTLEMENT_DEDUCTION_CATEGORIES: readonly DeductionCategory[] = [
  "PROPERTY_DAMAGE",
  "EARLY_TERMINATION_FEE",
  "CLEANING",
  "UTILITY_ARREARS",
  "KEY_REPLACEMENT",
  "OTHER",
];

/**
 * …and the addition categories, minus `PREPAID_RENT` and `UTILITY_OVERPAYMENT`
 * for the mirror reason — `SettlementService.ADDITION_ROLES` /
 * `requireAllowed(AdditionCategory)` (SettlementService.java:147-151, :753-761).
 */
export const SETTLEMENT_ADDITION_CATEGORIES: readonly AdditionCategory[] = [
  "DEPOSIT_INTEREST",
  "LANDLORD_COMPENSATION",
  "OTHER",
];

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
  /** The addendum that charged this line; null for the contract's own lines and an extension's. */
  addendumId?: string | null;
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
  /** Honoured on an amend only; must name an addendum of the same lease. */
  addendumId?: string | null;
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
  /** False when the grace came from the property's default (gap #65); absent on an older server. */
  gracePeriodOverridden?: boolean | null;
  firstDueDate: string | null;
  renterAcceptedAt: string | null;
  renewedFromLeaseId: string | null;
  chainId: string | null;
  receivableAccountId: string | null;
  incomeAccountId: string | null;
  postingJournalId: string | null;
  postedAt: string | null;
  contractValue: number | null;
  /** Set by a termination; null on every other status (spec §9.1). */
  terminatedOn: string | null;
  /** The `TCR`, or null when nothing was unearned. */
  terminationJournalId: string | null;
  terminationNotes: string | null;
  /** #27: when notice was given, by whom, and the move-out date it names. Absent on older rows. */
  noticeDate?: string | null;
  noticeGivenBy?: NoticeParty | null;
  intendedMoveOutDate?: string | null;
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
  /**
   * The output VAT inside `amount` (spec 2026-09-24 §1). Null/absent asks the
   * server for the pro-rata default: the rows without a figure share whatever of
   * the contract's VAT the others have not claimed.
   */
  vatAmount?: number | null;
};

/** ExtendLeaseRequest. */
export type ExtendLeaseInput = {
  newEndDate: string;
  contractDate?: string | null;
  lines: LeaseLineInput[];
  cheques: ChequeRowInput[];
};

/** AddChargeRequest — a mid-term charge on a posted lease, as an addendum. */
export type AddChargeInput = {
  effectiveFrom: string;
  contractDate?: string | null;
  ejariNumber?: string | null;
  reason?: string | null;
  lines: LeaseLineInput[];
  cheques: ChequeRowInput[];
};

/** LeaseAddendumDTO. `ejariPending` is true until an Ejari number is recorded. */
export type LeaseAddendum = {
  id: string;
  addendumNumber: string;
  effectiveFrom: string;
  contractDate: string;
  ejariNumber: string | null;
  ejariPending: boolean;
  reason: string | null;
  value: number;
  tcoJournalId: string;
  tcoEntryNumber: string;
  /**
   * True once `amendLines` has reversed this addendum's own TCO while
   * rebuilding the lease's ledger from a fresh set of lines. The addendum row
   * is not rewritten by an amend, so this is the only way the page can tell
   * its `tcoEntryNumber` is no longer the live entry.
   */
  superseded: boolean;
  createdAt: string;
};

/** AddendumResponse. */
export type AddendumResponse = { addendum: LeaseAddendum; posting: PostLeaseResponse };

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
  /**
   * The VAT this instalment collects (part of `amount`) and the net it is charged
   * on — spec 2026-09-24 §1. Optional so a row the client added and has not saved
   * yet, whose VAT the server has still to work out, is typed honestly.
   */
  vatAmount?: number | null;
  vatTaxableAmount?: number | null;
};

// ---- VAT per instalment (spec 2026-09-24 §1 — api/dto/vat) ----

export type VatTaxPointKind = "INSTALMENT" | "TERMINATION_ADJUSTMENT";
export type VatTaxPointStatus = "PLANNED" | "POSTED" | "CANCELLED";
export type TaxInvoiceKind = "TAX_INVOICE" | "CREDIT_NOTE";

/** VatTaxPointDTO — one row of a lease's VAT schedule. */
export type VatTaxPoint = {
  id: string;
  leaseId: string;
  chequeId: string | null;
  chequeSeqNo: number | null;
  chequeNumber: string | null;
  propertyId: string | null;
  propertyName: string | null;
  unitNumber: string | null;
  kind: VatTaxPointKind;
  taxPointDate: string;
  /** Signed: a termination adjustment that credits VAT back is negative. */
  taxableAmount: number;
  vatAmount: number;
  status: VatTaxPointStatus;
  journalId: string | null;
  journalNumber: string | null;
  invoiceId: string | null;
  invoiceNumber: string | null;
};

/** VatTaxPointRunResult — what "run tax points to date" did, or would do. */
export type VatTaxPointRunResult = {
  preview: boolean;
  posted: number;
  wouldPost: number;
  vatAmount: number;
  points: VatTaxPoint[];
  skippedLocked: number;
  booksLockedThrough: string | null;
  errors: string[];
};

/** TaxInvoiceDTO — a tax invoice or credit note issued on a tax point. */
export type TaxInvoice = {
  id: string;
  invoiceNumber: string;
  kind: TaxInvoiceKind;
  issueDate: string;
  periodStart: string | null;
  periodEnd: string | null;
  leaseId: string;
  chequeId: string | null;
  propertyName: string | null;
  unitNumber: string | null;
  customerName: string | null;
  taxableAmount: number;
  vatRate: number;
  vatAmount: number;
  totalAmount: number;
};

/**
 * `VatController`. The schedule and the run are staff-only; a renter reaches
 * their own invoices through `mine` and `pdfUrl` (the server checks the invoice
 * is addressed to them).
 */
export const vatApi = {
  schedule: (leaseId: string) => get<VatTaxPoint[]>(`/leases/${leaseId}/vat-schedule`),
  leaseInvoices: (leaseId: string) => get<TaxInvoice[]>(`/leases/${leaseId}/tax-invoices`),
  myInvoices: () => get<TaxInvoice[]>("/tax-invoices/mine"),
  /** `dryRun: true` writes nothing and answers with `posted: 0` / `wouldPost: n`. */
  run: (to: string | undefined, dryRun: boolean) =>
    send<VatTaxPointRunResult>("POST", `/finance/vat/tax-points/run${qs({ to, dryRun })}`),
  /** Not a fetch — the endpoint streams a PDF; open or download this path directly. */
  pdfUrl: (invoiceId: string) => `${BASE}/tax-invoices/${invoiceId}/pdf`,
};

// ---- recognition (spec §8.2, §8.4 — api/dto/recognition) ----

/** RecognitionEntryDTO — one calendar-month slice of a rent segment. */
export type RecognitionEntry = {
  id: string;
  leaseId: string;
  segmentId: string;
  /**
   * The lease's property, through its unit, denormalised onto the row so the
   * month-end page can group by building without two lazy loads per line.
   *
   * Nullable on the wire, and typed that way here although the schema does not
   * allow a lease without a unit: a null is exactly the row the close would
   * still post, so the page buckets it rather than dropping it.
   */
  propertyId: string | null;
  propertyName: string | null;
  unitName: string | null;
  periodStart: string;
  periodEnd: string;
  days: number;
  amount: number;
  status: RecognitionStatus;
  journalId: string | null;
  journalNumber: string | null;
  /** Instant — an ISO timestamp, not a date. */
  postedAt: string | null;
};

/**
 * RecognitionRunResultDTO — the answer to a month-end run, preview or not.
 *
 * `posted` is **0 on a preview** and `wouldPost` carries the count; on a real
 * run the two are equal (RecognitionRunResultDTO's own doc). A screen that
 * reads `posted` on a preview reports a close that never happened.
 */
export type RecognitionRunResult = {
  preview: boolean;
  posted: number;
  wouldPost: number;
  amount: number;
  entries: RecognitionEntry[];
  skippedLocked: number;
  skippedLockedEntries: RecognitionEntry[];
  booksLockedThrough: string | null;
  failed: number;
  errors: string[];
};

// ---- termination (spec §9.1 — api/dto/lease) ----

/** TerminationPreviewDTO — what ending the contract on `date` would do. */
export type TerminationPreview = {
  terminationDate: string;
  earnedRentThroughDate: number;
  recognisedSoFar: number;
  unearnedRent: number;
  /**
   * The VAT charged on that unearned rent, which the same `TCR` credits back as
   * a credit note (`Dr OUTPUT_VAT / Cr RENT_RECEIVABLE`). **Zero on a
   * residential tenancy**, and zero for a deposit line whatever its flag says.
   *
   * Optional here and only here: a backend that has not shipped the field must
   * not make the screen read `NaN` — an absent value is 0, which is what a
   * residential tenancy's is anyway. `receivableAfter` below already has it
   * netted in (`LeaseTerminationService` :155, :414-422), so the client's
   * flip arithmetic never adds it a second time.
   */
  unearnedVat?: number;
  /** Uncleared rows dated after T — the default "hand the paper back". */
  chequesToReturn: Cheque[];
  /** Uncleared rows dated on or before T — the money was already due. */
  chequesToKeep: Cheque[];
  /**
   * Rows that already failed. Neither returned nor kept: `ChequeStatus.isUncleared()`
   * excludes BOUNCED, so sending one of these in either list is refused with
   * "These cheques are not uncleared rows of this lease".
   */
  bouncedOutstanding: Cheque[];
  receivableAfter: number;
  /**
   * On a lease whose VAT is declared per instalment (spec 2026-09-24 §1): the tax
   * points due by T that post first, the pending VAT cancelled, and the settling
   * pair — reversed from "Output VAT – not yet due", declared at T, or credited
   * back. All zeros on a legacy lease; optional for the same reason as `unearnedVat`.
   */
  vatSettlement?: TerminationVatSettlement | null;
};

export type TerminationVatSettlement = {
  dueByTerminationDate: number;
  pendingCancelled: number;
  reversedFromDeferred: number;
  declaredAtTermination: number;
  creditedBack: number;
};

/**
 * TerminateLeaseRequest.
 *
 * The two lists are a decision, not a filter: **every** uncleared row must
 * appear in exactly one of them or the request is refused naming the rows it
 * forgot (`LeaseTerminationService.chosenReturns`, :279-315). Both empty means
 * "use the preview's default split".
 */
export type TerminateLeaseInput = {
  terminationDate: string;
  returnChequeIds?: string[] | null;
  keepChequeIds?: string[] | null;
  notes?: string | null;
};

// ---- settlement (spec §9.2 — api/dto/settlement) ----

/** DeductionAttachmentDTO. */
export type DeductionAttachment = {
  id: string;
  deductionId: string;
  name: string;
  fileUrl: string;
  fileType: string;
  fileSize: number;
  uploadedAt: string;
};

/** DeductionLineDTO — one charge against the deposit, on the live statement. */
export type DeductionLine = {
  id: string;
  category: DeductionCategory;
  description: string | null;
  amount: number;
  /** The line's own leaf, else the one its category resolves to. Null only for a legacy line. */
  accountId: string | null;
  accountName: string | null;
  autoCalculated: boolean;
  attachments: DeductionAttachment[];
};

/** AdditionLineDTO — something the landlord owes the renter on top of the deposit. */
export type AdditionLine = {
  id: string;
  category: AdditionCategory;
  description: string | null;
  amount: number;
  accountId: string | null;
  accountName: string | null;
};

/**
 * OutstandingInstrumentDTO — a register row the landlord is still waiting on.
 *
 * Their money sits in PDC receivable, so `receivableBalance` cannot see them
 * and `netRefund` does not net them off — they are listed so the accountant can
 * decide whether to pay a refund out anyway.
 */
export type OutstandingInstrument = {
  id: string;
  seqNo: number;
  mode: ChequeMode;
  chequeNumber: string | null;
  chequeDate: string | null;
  amount: number;
  status: ChequeStatus;
  /** This row exists to collect an approved penalty. */
  penaltyCollection: boolean;
};

/**
 * SettlementStatementDTO — the move-out statement, recomputed from the ledger
 * on every read.
 *
 * `instrumentsOutstanding` / `outstandingInstruments` are optional here and
 * only here: a backend that has not shipped them yet must not be read as
 * "nothing outstanding, go ahead" by accident — the screen treats an absent
 * value as 0, which is the same thing it shows when the register really is
 * empty, and the server re-checks the acknowledgement either way.
 */
export type SettlementStatement = {
  asOf: string;
  earnedRent: number;
  receivedTotal: number;
  /** Debit-positive: +ve the renter owes, −ve the landlord does. */
  receivableBalance: number;
  depositsHeld: number;
  /** APPROVED assessments whose collection row has not cleared. Shown, never added. */
  penaltiesOutstanding: number;
  instrumentsOutstanding?: number;
  outstandingInstruments?: OutstandingInstrument[];
  deductions: DeductionLine[];
  additions: AdditionLine[];
  totalDeductions: number;
  totalAdditions: number;
  /** >0 the landlord pays out, <0 the renter still owes. */
  netRefund: number;
  /** PLANNED recognition rows. Non-zero → run recognition before settling. */
  unrecognisedEntries: number;
};

/** SettlementResponseDTO.DeductionDTO — one stored line. */
export type SettlementLine = {
  id: string;
  category: DeductionCategory | null;
  description: string | null;
  amount: number;
  autoCalculated: boolean;
  type: SettlementLineType;
  additionCategory: AdditionCategory | null;
  accountId: string | null;
  accountName: string | null;
  attachments: DeductionAttachment[];
};

/** SettlementResponseDTO — the stored row: the draft as saved, or what finalise posted. */
export type SettlementResponse = {
  id: string;
  leaseId: string;
  depositAmount: number;
  totalDeductions: number;
  totalAdditions: number;
  /** max(netRefund, 0). */
  refundAmount: number;
  notes: string | null;
  status: SettlementStatus;
  settledBy: string | null;
  settledByName: string | null;
  settledAt: string | null;
  createdAt: string | null;
  settlementDate: string | null;
  earnedRent: number | null;
  receivedTotal: number | null;
  receivableBalance: number | null;
  depositsHeld: number | null;
  penaltiesOutstanding: number | null;
  /** max(-netRefund, 0). */
  balanceDue: number | null;
  refundBankAccountId: string | null;
  /** The STL, or null on a draft. */
  journalId: string | null;
  journalNumber: string | null;
  /** The CASH row raised to collect a balance the deposit could not cover. */
  collectionChequeId: string | null;
  deductions: SettlementLine[];
};

/** SaveSettlementDTO.DeductionItemDTO — one line as the caller submits it. */
export type SaveSettlementLine = {
  id?: string | null;
  category?: DeductionCategory | null;
  description?: string | null;
  amount: number;
  autoCalculated?: boolean;
  type: SettlementLineType;
  additionCategory?: AdditionCategory | null;
  /** Override the leaf this line posts to; omit to let the category resolve it. */
  accountId?: string | null;
};

/** SaveSettlementDTO — the whole grid on every save, never a diff. */
export type SaveSettlementInput = {
  notes?: string | null;
  deductions: SaveSettlementLine[];
};

/**
 * FinalizeSettlementRequest.
 *
 * `refundBankAccountId` is required exactly when `netRefund > 0`
 * (`SettlementService.finalizeSettlement`:452-454) and `acknowledgeOutstanding`
 * exactly when the settlement refunds *and* the register still holds something.
 */
export type FinalizeSettlementInput = {
  settlementDate: string;
  refundBankAccountId?: string | null;
  acknowledgeOutstanding?: boolean;
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
  /** #10: bank each row on its own cheque date; a row dated after today is refused. */
  useChequeDates?: boolean;
};

/** ClearBatchRequest — one bank credit covering several DEPOSITED cheques (#57). */
export type ClearBatchInput = {
  chequeIds: string[];
  clearingDate?: string | null;
  narration?: string | null;
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
  /** When the charged-for thing happened (#12); null on rows proposed before it was recorded. */
  incidentDate?: string | null;
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
  /** yyyy-MM-dd, Asia/Dubai; defaults to today server-side and may not be in the future. */
  incidentDate?: string | null;
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
  /**
   * Whether the gateway would actually take this row — the server's own
   * predicate, not a guess from `status` and `mode` here.
   * `ChequeService.registerOnlinePending` (:656-662) refuses anything but a
   * PDC or an ONLINE row ("Only a post-dated cheque or an online row can be
   * paid through the gateway; row 3 is a CASH receipt"), and `payable` alone
   * did not say so — a lease drafted with CASH or TRANSFER instalments showed
   * the renter a Pay button that failed with a raw Java string every time.
   *
   * Optional because a backend that has not shipped the field yet must not
   * turn the button on: {@link PayOnlineButton} treats an absent value as
   * false.
   */
  payableOnline?: boolean;
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

/**
 * UnappliedOnlinePaymentDTO — a captured online payment whose cheque row never
 * settled (gateway captured the money, the register row it was meant to clear
 * is not CLEARED), so finance owes the renter a refund.
 *
 * Not on the branch yet as a backend type when this client was written — Task
 * 16 was handed the exact contract ahead of the parallel backend work
 * (`GET /online-payments/unapplied`, `GET /online-payments/unapplied/count`).
 * Both endpoints are SA/TA/ACCOUNTANT, same as {@link chequeApi.cancel}'s
 * `canCancelCheques`.
 */
export type UnappliedOnlinePayment = {
  id: string;
  createdAt: string;
  capturedAt: string | null;
  amount: number;
  currency: string;
  gatewayOrderId: string | null;
  gatewayPaymentId: string | null;
  failureReason: string | null;
  chequeId: string | null;
  chequeNumber: string | null;
  chequeStatus: ChequeStatus | null;
  leaseId: string | null;
  displayContractNumber: string | null;
  renterName: string | null;
  propertyName: string | null;
  unitIdentifier: string | null;
};

export type UnappliedOnlinePaymentCount = { count: number; totalAmount: number };

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
  addCharge: (id: string, body: AddChargeInput) => send<AddendumResponse>("POST", `/leases/${id}/addenda`, body),
  addenda: (id: string) => get<LeaseAddendum[]>(`/leases/${id}/addenda`),
  recordAddendumEjari: (id: string, addendumId: string, ejariNumber: string) =>
    send<LeaseAddendum>("PATCH", `/leases/${id}/addenda/${addendumId}/ejari`, { ejariNumber }),
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

/**
 * Month-end close (spec §8.4) and the lease page's Recognition schedule tab.
 *
 * `RecognitionController` refuses a `to` later than today on **both** `pending`
 * and `run` ("Cannot recognise income for periods that have not ended",
 * RecognitionController.java:145-157), and refuses either of them outright when
 * a SUPER_ADMIN has not picked an organisation ("Select an organisation first",
 * :131-135). Both arrive as a 400 with the message in `ApiError.message`.
 */
export const recognitionApi = {
  /** Everything still waiting to be recognised as of `to` (default: today), oldest period first. */
  pending: (to?: string) => get<RecognitionEntry[]>(`/finance/recognition/pending${qs({ to })}`),
  /** `preview: true` writes nothing and answers with `posted: 0` / `wouldPost: n`. */
  run: (to: string | undefined, preview: boolean) =>
    send<RecognitionRunResult>("POST", `/finance/recognition/run${qs({ to, preview })}`),
  /** One lease's whole schedule, every status, oldest period first. Open to PROPERTY_MANAGER. */
  leaseSchedule: (leaseId: string) => get<RecognitionEntry[]>(`/leases/${leaseId}/recognition`),
};

/** Who gave notice (#27): the renter leaving, or the landlord serving notice. */
export type NoticeParty = "RENTER" | "LANDLORD";

/** GiveNoticeRequest — every field optional; the server defaults the date to today and the party to RENTER. */
export type GiveNoticeInput = {
  notes?: string | null;
  noticeDate?: string | null;
  givenBy?: NoticeParty | null;
  intendedMoveOutDate?: string | null;
};

/** Notice and termination (spec §9.1) — `LeaseController` :250-296. */
export const terminationApi = {
  /** Readable by a PROPERTY_MANAGER on their own buildings; writing is one role narrower. */
  preview: (id: string, date: string) =>
    get<TerminationPreview>(`/leases/${id}/terminate/preview${qs({ date })}`),
  terminate: (id: string, body: TerminateLeaseInput) => send<LeaseDetail>("POST", `/leases/${id}/terminate`, body),
  /** ACTIVE → NOTICE_GIVEN. Writes no journal, which is why it admits a manager. */
  notice: (id: string, body: GiveNoticeInput = {}) => send<LeaseDetail>("POST", `/leases/${id}/notice`, body),
};

/**
 * The move-out statement (spec §9.2) — `LeaseController` :309-369.
 *
 * `statement` is the live computation and `get` is the stored row; they are two
 * different documents and the screen needs both. `get` answers **404** when the
 * lease has no settlement yet, which is not an error — it is the normal state
 * before the first Save draft.
 */
export const settlementApi = {
  statement: (id: string) => get<SettlementStatement>(`/leases/${id}/settlement/preview`),
  get: (id: string) => get<SettlementResponse>(`/leases/${id}/settlement`),
  saveDraft: (id: string, body: SaveSettlementInput) =>
    send<SettlementResponse>("POST", `/leases/${id}/settlement/draft`, body),
  finalize: (id: string, body: FinalizeSettlementInput) =>
    send<SettlementResponse>("POST", `/leases/${id}/settlement/finalize`, body),
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
  /** All or nothing: one row that is not DEPOSITED 400s the call, naming it. */
  clearBatch: (body: ClearBatchInput) => send<Cheque[]>("POST", "/cheques/clear-batch", body),
  receive: (id: string, body?: ChequeActionInput) => send<Cheque>("PUT", `/cheques/${id}/receive`, body),
  /** `body.failureReason` is required — the backend 400s a bounce without one. */
  bounce: (id: string, body: ChequeActionInput) => send<Cheque>("PUT", `/cheques/${id}/bounce`, body),
  replace: (id: string, body: ReplaceChequeInput) => send<Cheque[]>("POST", `/cheques/${id}/replace`, body),
  cancel: (id: string, body?: ChequeActionInput) => send<Cheque>("PUT", `/cheques/${id}/cancel`, body),
  updateDetails: (id: string, body: ChequeRowInput) => send<Cheque>("PUT", `/cheques/${id}/details`, body),
  /**
   * Put an ONLINE_PENDING row back on the register (→ REGISTERED) when the
   * gateway session was abandoned and never called back. Staff-only
   * (`canManageCheques`); the renter's own abandonment is handled by
   * `onlinePayApi.cancel`.
   */
  releaseOnline: (id: string, body?: ChequeActionInput) => send<Cheque>("POST", `/cheques/${id}/release-online`, body),
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
  /** See {@link UnappliedOnlinePayment} — contract-only until the backend endpoint lands. */
  unapplied: (q: { page?: number; size?: number } = {}) =>
    get<Page<UnappliedOnlinePayment>>(`/online-payments/unapplied${qs({ page: q.page ?? 0, size: q.size ?? 25 })}`),
  unappliedCount: () => get<UnappliedOnlinePaymentCount>("/online-payments/unapplied/count"),
};

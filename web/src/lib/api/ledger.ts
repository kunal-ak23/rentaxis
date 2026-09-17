import { throwIfNotOk } from "@/lib/api/facilities";

const BASE = "/api/proxy/v1";

function qs(params: Record<string, string | number | string[] | undefined | null>): string {
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

// ---- types ----

export type AccountRole =
  | "RENT_RECEIVABLE"
  | "ADVANCE_RENT"
  | "RENTAL_INCOME"
  | "PDC_RECEIVABLE"
  | "BANK"
  | "SECURITY_DEPOSIT"
  | "ADMIN_FEE"
  | "PARKING_INCOME"
  | "PARKING_DEPOSIT"
  | "COOLING_CHARGES"
  | "MAINTENANCE_CHARGES"
  | "RENT_PENALTY"
  | "CHEQUE_RETURN_PENALTY"
  | "OTHER_INCOME"
  | "FORFEITED_INCOME"
  | "DISCOUNT_ALLOWED"
  | "ROUNDING_OFF"
  | "CASH"
  | "OUTPUT_VAT"
  | "INPUT_VAT"
  | "OPENING_BALANCE_DIFFERENCE";

export type JournalDocType =
  | "TCO"
  | "TCR"
  | "PDR"
  | "CRT"
  | "CBR"
  | "CIL"
  | "RCP"
  | "STL"
  | "PEN"
  | "PISR"
  | "BPV"
  | "OB"
  | "JV";

export type AccountType = "ASSET" | "LIABILITY" | "INCOME" | "EXPENSE" | "EQUITY";

export type AccountSubType =
  | "FIXED_ASSET"
  | "BANK"
  | "CASH"
  | "RECEIVABLE"
  | "PDC_RECEIVABLE"
  | "OTHER_ASSET"
  | "PAYABLE"
  | "ADVANCE"
  | "DEPOSIT_HELD"
  | "PDC_PAYABLE"
  | "OTHER_LIABILITY"
  | "RENTAL_INCOME"
  | "OTHER_INCOME"
  | "DIRECT_EXPENSE"
  | "INDIRECT_EXPENSE"
  | "SALARY_EXPENSE"
  | "OTHER_EXPENSE"
  | "CAPITAL"
  | "RETAINED_EARNINGS";

export type Account = {
  id: string;
  code: string;
  name: string;
  nameEn: string | null;
  nameAr: string | null;
  alias: string | null;
  accountType: AccountType;
  accountSubType: AccountSubType | null;
  parentId: string | null;
  propertyId: string | null;
  system: boolean;
  group: boolean;
  active: boolean;
  displayOrder: number;
  description: string | null;
};

export type RoleMapping = {
  role: AccountRole;
  accountId: string | null;
  accountCode: string | null;
  accountName: string | null;
  inherited: boolean;
};

export type TemplateRow = {
  role: AccountRole;
  namePattern: string;
  parentAccountId: string;
  parentCode: string;
  enabled: boolean;
};

export type LedgerRow = {
  entryId: string;
  entryNumber: string;
  entryDate: string;
  docType: string;
  particular: string;
  narration: string;
  debit: number;
  credit: number;
  balance: number;
  propertyId: string | null;
  unitId: string | null;
  leaseId: string | null;
  renterId: string | null;
  chequeId: string | null;
};

export type AccountLedger = {
  accountId: string;
  accountCode: string;
  accountName: string;
  accountType: string;
  openingBalance: number;
  rows: LedgerRow[];
  totalDebit: number;
  totalCredit: number;
  closingBalance: number;
  truncated: boolean;
};

export type TrialBalanceRow = {
  accountId: string;
  code: string;
  name: string;
  accountType: string;
  parentId: string | null;
  propertyId: string | null;
  debit: number;
  credit: number;
  balance: number;
};

export type JournalLine = {
  lineNo: number;
  accountId: string;
  accountCode: string;
  accountName: string;
  debit: number;
  credit: number;
  narration: string | null;
  propertyId: string | null;
  unitId: string | null;
  leaseId: string | null;
  renterId: string | null;
  chequeId: string | null;
};

export type JournalEntry = {
  id: string;
  entryNumber: string;
  docType: string;
  entryDate: string;
  narration: string | null;
  status: "POSTED" | "REVERSED";
  propertyId: string | null;
  unitId: string | null;
  leaseId: string | null;
  renterId: string | null;
  sourceType: string | null;
  sourceId: string | null;
  reversalOfId: string | null;
  reversedById: string | null;
  importBatchId: string | null;
  postedBy: string | null;
  postedAt: string | null;
  total: number;
  lines: JournalLine[];
};

export type Page<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
};

export type FiscalSettings = {
  fiscalYearStartMonth: number;
  booksStartDate: string | null;
  booksLockedThrough: string | null;
};

export type LedgerQuery = {
  accountIds?: string[];
  from?: string;
  to?: string;
  propertyId?: string;
  unitId?: string;
  leaseId?: string;
  renterId?: string;
};

// Blank optional fields go over the wire as null rather than "": the backend
// binds accountSubType to an enum, and an empty string is not one of its names.
export type CreateAccountBody = {
  code?: string;
  name: string;
  nameEn: string;
  nameAr: string | null;
  alias: string | null;
  accountType: AccountType;
  accountSubType: AccountSubType | null;
  description: string | null;
  parentId?: string | null;
  propertyId?: string | null;
  group?: boolean;
};

export type UpdateAccountBody = {
  name: string;
  nameEn: string;
  nameAr: string | null;
  alias: string | null;
  description: string | null;
  accountSubType: AccountSubType | null;
  active?: boolean;
  displayOrder?: number;
  /** Always applied — omitting it clears the account's property tag, so callers send the current value. */
  propertyId: string | null;
};

export type ManualJournalBody = {
  entryDate: string;
  narration: string;
  propertyId?: string;
  lines: {
    accountId: string;
    debit: number;
    credit: number;
    narration?: string;
    unitId?: string;
    leaseId?: string;
    renterId?: string;
  }[];
};

export const ledgerApi = {
  accounts: {
    list: () => get<Account[]>("/finance/accounts"),
    tree: () => get<Account[]>("/finance/accounts/tree"),
    children: (id: string) => get<Account[]>(`/finance/accounts/${id}/children`),
    create: (body: CreateAccountBody) => send<Account>("POST", "/finance/accounts", body),
    /** Seeds the default chart plus the property-account template and role defaults. */
    seed: () => send<Account[]>("POST", "/finance/accounts/seed"),
    update: (id: string, body: UpdateAccountBody) => send<Account>("PUT", `/finance/accounts/${id}`, body),
    remove: (id: string) => send<void>("DELETE", `/finance/accounts/${id}`),
    import: async (file: File) => {
      const fd = new FormData();
      fd.append("file", file);
      const res = await fetch(`${BASE}/finance/accounts/import`, { method: "POST", body: fd });
      await throwIfNotOk(res);
      return res.json() as Promise<Account[]>;
    },
  },
  propertyAccounts: {
    get: (propertyId: string) => get<RoleMapping[]>(`/properties/${propertyId}/accounts`),
    generate: (propertyId: string) => send<RoleMapping[]>("POST", `/properties/${propertyId}/accounts/generate`),
    set: (propertyId: string, role: AccountRole, accountId: string) =>
      send<RoleMapping>("PUT", `/properties/${propertyId}/accounts/${role}`, { accountId }),
    clear: (propertyId: string, role: AccountRole) => send<void>("DELETE", `/properties/${propertyId}/accounts/${role}`),
  },
  template: {
    get: () => get<TemplateRow[]>("/finance/account-template"),
    save: (rows: TemplateRow[]) => send<TemplateRow[]>("PUT", "/finance/account-template", rows),
  },
  defaults: {
    get: () => get<RoleMapping[]>("/finance/default-accounts"),
    set: (role: AccountRole, accountId: string) => send<RoleMapping>("PUT", `/finance/default-accounts/${role}`, { accountId }),
  },
  roles: () => get<{ role: AccountRole; propertyScoped: boolean }[]>("/finance/account-roles"),
  ledger: {
    general: (q: LedgerQuery) => get<AccountLedger[]>(`/finance/ledger${qs(q)}`),
    account: (id: string, q: LedgerQuery) => get<AccountLedger>(`/finance/ledger/account/${id}${qs(q)}`),
    renter: (renterId: string, q: { from?: string; to?: string }) => get<AccountLedger[]>(`/finance/ledger/renter/${renterId}${qs(q)}`),
    vendor: (vendorId: string, q: { from?: string; to?: string }) => get<AccountLedger>(`/finance/ledger/vendor/${vendorId}${qs(q)}`),
  },
  trialBalance: (q: { asOf?: string; propertyId?: string }) => get<TrialBalanceRow[]>(`/finance/trial-balance${qs(q)}`),
  journals: {
    list: (q: { docType?: JournalDocType | ""; from?: string; to?: string; propertyId?: string; leaseId?: string; page: number; size: number }) =>
      get<Page<JournalEntry>>(`/finance/journals${qs(q)}`),
    get: (id: string) => get<JournalEntry>(`/finance/journals/${id}`),
    postManual: (body: ManualJournalBody) => send<JournalEntry>("POST", "/finance/journals", body),
    reverse: (id: string, body: { date: string; reason: string }) => send<JournalEntry>("POST", `/finance/journals/${id}/reverse`, body),
    docTypes: () => get<JournalDocType[]>("/finance/journals/doc-types"),
  },
  fiscal: {
    get: () => get<FiscalSettings>("/finance/fiscal-settings"),
    update: (body: { fiscalYearStartMonth?: number; booksStartDate?: string }) => send<FiscalSettings>("PUT", "/finance/fiscal-settings", body),
    lock: (through: string) => send<FiscalSettings>("POST", "/finance/fiscal-settings/lock", { through }),
  },
};

const nf = new Intl.NumberFormat("en-US", { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export function fmtAmount(n: number): string {
  return nf.format(n);
}

export function fmtBalance(n: number): string {
  if (Math.abs(n) < 0.005) return "0.00";
  return n > 0 ? `${nf.format(n)} Dr` : `${nf.format(-n)} Cr`;
}

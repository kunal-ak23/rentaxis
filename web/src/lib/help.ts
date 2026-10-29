import type { UserRole } from './rbac';

export interface HelpArticle {
  slug: string;
  title: string;
  description: string;
  category: string;
  roles: UserRole[];
  order: number;
  relatedTour?: string;
  content: string;
  /** The Arabic version, when the article has one: shown under /ar instead of the English. */
  ar?: HelpArticleText;
}

/** The words of an article in one language. */
export interface HelpArticleText {
  title: string;
  description: string;
  content: string;
}

/** An article as a locale reads it: the Arabic version under /ar when there is one, else the English. */
export interface LocalizedHelpArticle extends HelpArticleText {
  lang: 'en' | 'ar';
  dir: 'ltr' | 'rtl';
  /** True when an Arabic reader gets the English because the article has no Arabic yet. */
  englishFallback: boolean;
}

export function localizeArticle(article: HelpArticle, locale: string): LocalizedHelpArticle {
  if (locale === 'ar' && article.ar) return { ...article.ar, lang: 'ar', dir: 'rtl', englishFallback: false };
  const { title, description, content } = article;
  return { title, description, content, lang: 'en', dir: 'ltr', englishFallback: locale === 'ar' };
}

export interface HelpCategory {
  id: string;
  label: string;
  icon: string;
}

export const HELP_CATEGORIES: HelpCategory[] = [
  { id: 'getting-started', label: 'Getting Started', icon: 'Rocket' },
  { id: 'properties', label: 'Properties', icon: 'Building2' },
  { id: 'leases', label: 'Tenancy Contracts', icon: 'FileText' },
  { id: 'finance', label: 'Finance', icon: 'CreditCard' },
  { id: 'operations', label: 'Operations', icon: 'Wrench' },
  { id: 'renter', label: 'Tenant Portal', icon: 'User' },
  { id: 'admin', label: 'Administration', icon: 'Settings' },
];

/** What the help button offers on a page: its article, its tour, and the video tutorials (catalog ids) for it. */
export interface PageHelp {
  article?: string;
  tour?: string;
  videos?: string[];
}

/**
 * One entry per page: every page a role can open has one or a parent's (help-coverage.test.ts),
 * and a detail page (/dashboard/leases/<id>) falls back to its list page.
 * `byRole` swaps the entry for a role that sees the same URL as a different page
 * (a tenant's /dashboard/tickets is their own tickets, not the staff queue).
 */
type PageHelpEntry = PageHelp & { byRole?: Partial<Record<UserRole, PageHelp>> };

const TICKETS_FOR_TENANT: PageHelp = { article: 'renter--submitting-tickets', videos: ['22'] };
const GATE: PageHelp = { article: 'operations--gate-passes', videos: ['25'] };
const BOOKS_START: PageHelp = { article: 'finance--books-start', videos: ['39'] };
const PAYABLES: PageHelp = { article: 'finance--payables', videos: ['44', '19'] };
const REPORTS: PageHelp = { article: 'finance--ledgers-and-reports', videos: ['20'] };

export const HELP_PAGE_MAP: Record<string, PageHelpEntry> = {
  // The Help center itself: no page help (its tour and videos would be Home's).
  '/dashboard/help': {},
  // Home
  '/dashboard': {
    article: 'getting-started--welcome', tour: 'admin-onboarding', videos: ['02', '01'],
    // A Company User's Home has no Leasing or Accounting to tour.
    byRole: { TENANT_USER: { article: 'getting-started--welcome', tour: 'company-user-onboarding', videos: ['02', '01'] } },
  },
  // Leasing
  '/dashboard/leases': {
    article: 'leases--creating-a-lease', tour: 'lease-workflow', videos: ['10', '34', '12'],
    // A property manager reads and works contracts but neither drafts nor posts them.
    byRole: { PROPERTY_MANAGER: { article: 'leases--lease-lifecycle', tour: 'lease-workflow', videos: ['12', '13'] } },
  },
  '/dashboard/leases/renewals': { article: 'leases--renewal-season', videos: ['13', '43'] },
  // One contract (and its Terminate / Settlement pages): its statuses and actions, for every role.
  '/dashboard/leases/[id]': { article: 'leases--lease-lifecycle', videos: ['12', '13', '42'] },
  '/dashboard/renters': { article: 'leases--tenants-and-portal-access', videos: ['09'] },
  '/dashboard/properties': { article: 'properties--managing-properties', tour: 'property-workflow', videos: ['06', '07', '08'] },
  '/dashboard/listings': { article: 'operations--bookings-meetings-promotions' },
  // Cheque / Cash Collection
  '/dashboard/collections': { article: 'leases--payment-schedules', videos: ['15', '35', '40'] },
  // Accounting
  '/dashboard/finance/accounts': { article: 'finance--chart-of-accounts', tour: 'finance-overview', videos: ['38', '17'] },
  '/dashboard/finance/opening-balances': BOOKS_START,
  '/dashboard/finance/vouchers': PAYABLES,
  '/dashboard/finance/vouchers/credit-note': PAYABLES,
  '/dashboard/finance/payables/payment-runs': PAYABLES,
  '/dashboard/finance/payables/issued-cheques': PAYABLES,
  '/dashboard/finance/payables/aging': { ...PAYABLES, byRole: { PROPERTY_MANAGER: { article: 'finance--ledgers-and-reports' } } },
  '/dashboard/finance/payables/opening-items': BOOKS_START,
  '/dashboard/finance/vendors': { article: 'finance--payables', videos: ['19', '44'] },
  '/dashboard/finance/journals': { article: 'finance--ledgers-and-reports', tour: 'finance-overview', videos: ['18'] },
  '/dashboard/finance/general-ledger': { article: 'finance--ledgers-and-reports', videos: ['18', '37'] },
  '/dashboard/finance/tenant-ledger': { article: 'finance--ledgers-and-reports', videos: ['37'] },
  '/dashboard/finance/trial-balance': { article: 'finance--ledgers-and-reports', videos: ['37'] },
  '/dashboard/finance/recognition': { article: 'finance--month-end', videos: ['36'] },
  '/dashboard/finance/bank-accounts': { article: 'finance--payables', videos: ['19'] },
  '/dashboard/finance/bank-reconciliation': { article: 'finance--month-end', videos: ['45'] },
  '/dashboard/finance/reports/balance-sheet': REPORTS,
  '/dashboard/finance/reports/company-pl': REPORTS,
  '/dashboard/finance/reports/property-pl': REPORTS,
  '/dashboard/finance/reports/property-statement': REPORTS,
  '/dashboard/finance/reports/vat-return': { article: 'finance--month-end', videos: ['41'] },
  '/dashboard/reports': REPORTS,
  '/dashboard/finance/fiscal': { article: 'finance--month-end', videos: ['46', '39'] },
  '/dashboard/finance/account-template': { article: 'finance--chart-of-accounts', videos: ['17'] },
  '/dashboard/finance/charge-types': { article: 'finance--chart-of-accounts', videos: ['17'] },
  '/dashboard/finance/import-batches': BOOKS_START,
  '/dashboard/finance/reconciliation': BOOKS_START,
  // Operations
  '/dashboard/tickets': { article: 'operations--tickets', videos: ['22'], byRole: { RENTER: TICKETS_FOR_TENANT } },
  '/dashboard/bookings': { article: 'operations--bookings-meetings-promotions', videos: ['24'] },
  '/dashboard/staff': { article: 'admin--managing-staff', videos: ['05'] },
  // Settings
  '/dashboard/settings': { article: 'admin--tenant-settings', videos: ['05'] },
  '/superadmin/tenants': { article: 'admin--super-admin-guide', tour: 'super-admin', videos: ['04'] },
  '/superadmin/users': {
    article: 'admin--super-admin-guide', videos: ['03'],
    // A Company Admin manages their own organisation's users here.
    byRole: { TENANT_ADMIN: { article: 'admin--managing-staff', videos: ['05', '03'] } },
  },
  '/superadmin/api-keys': { article: 'admin--super-admin-guide', videos: [] },
  // More
  '/dashboard/meetings': {
    article: 'operations--bookings-meetings-promotions', videos: ['23'],
    byRole: { RENTER: { article: 'renter--renter-portal-overview', videos: ['23'] } },
  },
  '/dashboard/gatepass': GATE,
  '/dashboard/gatepass/approvals': GATE,
  '/dashboard/gatepass/gate': GATE,
  '/dashboard/gatepass/settings': GATE,
  '/dashboard/promotions': { article: 'operations--bookings-meetings-promotions', videos: ['28'] },
  // Tenant portal
  '/dashboard/renter-portal': { article: 'renter--renter-portal-overview', tour: 'renter-portal', videos: ['11'] },
  '/dashboard/renter-portal/payments': { article: 'renter--making-payments', videos: ['21'] },
  '/dashboard/renter-portal/penalties': { article: 'renter--renter-portal-overview', videos: ['14'] },
  '/dashboard/renter-portal/gate-passes': GATE,
  '/dashboard/renter-portal/facilities': { article: 'renter--renter-portal-overview', videos: ['24'] },
  '/dashboard/renter-portal/renewals': { article: 'renter--renter-portal-overview', videos: ['13'] },
};

/** A map key with an `[id]` segment matches any one segment there (a contract's own page). */
const escapeRe = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const PATTERNS = Object.keys(HELP_PAGE_MAP).filter(k => k.includes('[id]'))
  .map(k => ({ key: k, re: new RegExp(`^${k.split('[id]').map(escapeRe).join('[^/]+')}$`) }));

/** The help for a page: its own entry, else the nearest parent page's (Home's, for a page with no closer one). */
export function getContextualHelp(pathname: string, role?: UserRole): PageHelp {
  const stripped = pathname.replace(/^\/(en|ar)(?=\/|$)/, '') || '/';
  const parts = stripped.split('/');
  while (parts.length > 1) {
    const path = parts.join('/');
    const entry = HELP_PAGE_MAP[path] ?? HELP_PAGE_MAP[PATTERNS.find(p => p.re.test(path))?.key ?? ''];
    if (entry) {
      const { byRole, ...own } = entry;
      return (role && byRole?.[role]) || own;
    }
    parts.pop();
  }
  return {};
}

export function filterArticlesByRole(articles: HelpArticle[], role?: UserRole): HelpArticle[] {
  if (!role) return [];
  return articles.filter(a => a.roles.includes(role));
}

export function searchArticles(articles: HelpArticle[], query: string): HelpArticle[] {
  const q = query.toLowerCase().trim();
  if (!q) return articles;
  // Either language finds the article, so a search works whichever words the reader knows.
  return articles.filter(a =>
    [a, a.ar].some(text => text && (
      text.title.toLowerCase().includes(q) ||
      text.description.toLowerCase().includes(q) ||
      text.content.toLowerCase().includes(q)
    ))
  );
}

export function readingTime(content: string): number {
  const words = content.split(/\s+/).length;
  return Math.max(1, Math.ceil(words / 200));
}

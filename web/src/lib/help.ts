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
}

export interface HelpCategory {
  id: string;
  label: string;
  icon: string;
}

export const HELP_CATEGORIES: HelpCategory[] = [
  { id: 'getting-started', label: 'Getting Started', icon: 'Rocket' },
  { id: 'properties', label: 'Properties', icon: 'Building2' },
  { id: 'leases', label: 'Leases', icon: 'FileText' },
  { id: 'finance', label: 'Finance', icon: 'CreditCard' },
  { id: 'renter', label: 'Renter Portal', icon: 'User' },
  { id: 'admin', label: 'Administration', icon: 'Settings' },
];

export const HELP_PAGE_MAP: Record<string, { article?: string; tour?: string }> = {
  '/dashboard': { article: 'getting-started--welcome', tour: 'admin-onboarding' },
  '/dashboard/properties': { article: 'properties--managing-properties', tour: 'property-workflow' },
  '/dashboard/leases': { article: 'leases--creating-a-lease', tour: 'lease-workflow' },
  '/dashboard/renters': { article: 'properties--managing-properties' },
  '/dashboard/tickets': { article: 'renter--submitting-tickets' },
  '/dashboard/finance/accounts': { article: 'finance--chart-of-accounts', tour: 'finance-overview' },
  '/dashboard/finance/cheques': { article: 'leases--payment-schedules' },
  '/dashboard/renter-portal': { article: 'renter--renter-portal-overview', tour: 'renter-portal' },
  '/dashboard/renter-portal/payments': { article: 'renter--making-payments' },
  '/dashboard/staff': { article: 'admin--managing-staff' },
  '/dashboard/settings': { article: 'admin--tenant-settings' },
  '/dashboard/finance/journals': { article: 'finance--chart-of-accounts', tour: 'finance-overview' },
  '/dashboard/finance/account-template': { article: 'finance--chart-of-accounts' },
  '/superadmin/tenants': { article: 'admin--super-admin-guide', tour: 'super-admin' },
  '/superadmin/users': { article: 'admin--super-admin-guide' },
};

export function getContextualHelp(pathname: string): { article?: string; tour?: string } {
  const stripped = pathname.replace(/^\/(en|ar)/, '');
  if (HELP_PAGE_MAP[stripped]) return HELP_PAGE_MAP[stripped];
  const parts = stripped.split('/');
  while (parts.length > 2) {
    parts.pop();
    const parent = parts.join('/');
    if (HELP_PAGE_MAP[parent]) return HELP_PAGE_MAP[parent];
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
  return articles.filter(a =>
    a.title.toLowerCase().includes(q) ||
    a.description.toLowerCase().includes(q) ||
    a.content.toLowerCase().includes(q)
  );
}

export function readingTime(content: string): number {
  const words = content.split(/\s+/).length;
  return Math.max(1, Math.ceil(words / 200));
}

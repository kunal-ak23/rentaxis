'use client';

import { useState, useCallback, useMemo } from 'react';
import {
  BookOpen,
  Rocket,
  Building2,
  FileText,
  CreditCard,
  User,
  Settings,
  Clock,
  ChevronRight,
} from 'lucide-react';
import { Link } from '@/i18n/routing';
import { useSession } from 'next-auth/react';
import { cn } from '@/lib/utils';
import { type UserRole, getRoleLabel } from '@/lib/rbac';
import {
  HELP_CATEGORIES,
  filterArticlesByRole,
  searchArticles,
  readingTime,
  type HelpArticle,
} from '@/lib/help';
import HelpSearch from './HelpSearch';
import RoleFilter from './RoleFilter';
import TourTrigger from '@/components/tour/TourTrigger';
import { useTour } from '@/components/tour/TourProvider';

// Icon map for categories
const CATEGORY_ICONS: Record<string, React.ReactNode> = {
  'getting-started': <Rocket size={18} />,
  properties: <Building2 size={18} />,
  leases: <FileText size={18} />,
  finance: <CreditCard size={18} />,
  renter: <User size={18} />,
  admin: <Settings size={18} />,
};

interface HelpCenterProps {
  articles: HelpArticle[];
}

export default function HelpCenter({ articles }: HelpCenterProps) {
  const { data: session } = useSession();
  const userRole = (session?.user as { role?: UserRole } | undefined)?.role;
  const isAdmin = userRole === 'SUPER_ADMIN' || userRole === 'TENANT_ADMIN';

  const [query, setQuery] = useState('');
  const [activeCategory, setActiveCategory] = useState<string | null>(null);
  const [showAllRoles, setShowAllRoles] = useState(false);

  const { availableTours } = useTour();

  const handleSearch = useCallback((q: string) => {
    setQuery(q);
  }, []);

  // Filter articles by role (unless admin toggled "show all")
  const roleFilteredArticles = useMemo(() => {
    if (showAllRoles) return articles;
    return filterArticlesByRole(articles, userRole);
  }, [articles, userRole, showAllRoles]);

  // Then apply search
  const searchedArticles = useMemo(() => {
    return searchArticles(roleFilteredArticles, query);
  }, [roleFilteredArticles, query]);

  // Then apply category filter
  const displayedArticles = useMemo(() => {
    if (!activeCategory) return searchedArticles;
    return searchedArticles.filter((a) => a.category === activeCategory);
  }, [searchedArticles, activeCategory]);

  // Category counts based on searched (not category-filtered) articles
  const categoryCounts = useMemo(() => {
    const counts: Record<string, number> = {};
    for (const cat of HELP_CATEGORIES) {
      counts[cat.id] = searchedArticles.filter((a) => a.category === cat.id).length;
    }
    return counts;
  }, [searchedArticles]);

  const totalCount = searchedArticles.length;

  return (
    <div>
      {/* Header */}
      <div className="mb-8">
        <div className="flex items-center gap-3 mb-2">
          <div className="p-2 rounded-lg bg-primary/10">
            <BookOpen size={24} className="text-primary" />
          </div>
          <div>
            <h1 className="text-2xl font-bold text-gray-900">Help Center</h1>
            <p className="text-sm text-gray-500">
              Find guides and tutorials for using RentAxis
            </p>
          </div>
        </div>
      </div>

      {/* Two-column layout */}
      <div className="flex flex-col lg:flex-row gap-8">
        {/* Sidebar */}
        <aside className="lg:w-60 shrink-0">
          <nav className="space-y-1">
            {/* All categories */}
            <button
              onClick={() => setActiveCategory(null)}
              className={cn(
                'w-full flex items-center justify-between px-3 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer',
                activeCategory === null
                  ? 'bg-primary/10 text-primary'
                  : 'text-gray-600 hover:bg-gray-100'
              )}
            >
              <span>All Articles</span>
              <span className="text-xs text-gray-400">{totalCount}</span>
            </button>

            {HELP_CATEGORIES.map((cat) => {
              const count = categoryCounts[cat.id] || 0;
              if (count === 0 && !query) return null;
              return (
                <button
                  key={cat.id}
                  onClick={() => setActiveCategory(cat.id)}
                  className={cn(
                    'w-full flex items-center justify-between px-3 py-2 rounded-lg text-sm font-medium transition-colors cursor-pointer',
                    activeCategory === cat.id
                      ? 'bg-primary/10 text-primary'
                      : 'text-gray-600 hover:bg-gray-100'
                  )}
                >
                  <span className="flex items-center gap-2">
                    {CATEGORY_ICONS[cat.id]}
                    {cat.label}
                  </span>
                  <span className="text-xs text-gray-400">{count}</span>
                </button>
              );
            })}
          </nav>

          {/* Show all roles toggle for admins */}
          {isAdmin && (
            <div className="mt-6 pt-4 border-t border-gray-200">
              <label className="flex items-center gap-2 text-sm text-gray-600 cursor-pointer">
                <input
                  type="checkbox"
                  checked={showAllRoles}
                  onChange={(e) => setShowAllRoles(e.target.checked)}
                  className="rounded border-gray-300 text-primary focus:ring-primary"
                />
                Show all roles
              </label>
            </div>
          )}
        </aside>

        {/* Main content area */}
        <div className="flex-1 min-w-0">
          {/* Search */}
          <HelpSearch onSearch={handleSearch} className="mb-6" />

          {/* Article grid */}
          {displayedArticles.length > 0 ? (
            <div className="grid grid-cols-1 md:grid-cols-2 gap-4 mb-10">
              {displayedArticles.map((article) => (
                <Link
                  key={article.slug}
                  href={`/dashboard/help/${article.slug}`}
                  className="group flex flex-col p-4 rounded-lg border border-gray-200 bg-white hover:shadow-md hover:border-primary/30 transition-all"
                >
                  <div className="flex items-start justify-between gap-2 mb-2">
                    <h3 className="text-sm font-semibold text-gray-900 group-hover:text-primary transition-colors">
                      {article.title}
                    </h3>
                    <ChevronRight
                      size={16}
                      className="text-gray-300 group-hover:text-primary shrink-0 mt-0.5 transition-colors"
                    />
                  </div>
                  <p className="text-xs text-gray-500 leading-relaxed mb-3 line-clamp-2">
                    {article.description}
                  </p>
                  <div className="mt-auto flex items-center justify-between">
                    <RoleFilter roles={article.roles} compact />
                    <span className="inline-flex items-center gap-1 text-[10px] text-gray-400">
                      <Clock size={10} />
                      {readingTime(article.content)} min
                    </span>
                  </div>
                </Link>
              ))}
            </div>
          ) : (
            <div className="text-center py-12">
              <p className="text-gray-400 text-sm">
                {query
                  ? 'No articles match your search.'
                  : 'No help articles available for your role.'}
              </p>
            </div>
          )}

          {/* Tours section */}
          {availableTours.length > 0 && (
            <div>
              <h2 className="text-lg font-semibold text-gray-900 mb-4">
                Interactive Tours
              </h2>
              <p className="text-sm text-gray-500 mb-4">
                Take a guided walkthrough of key features.
              </p>
              <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
                {availableTours.map((tour) => (
                  <TourTrigger key={tour.id} tour={tour} variant="card" />
                ))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

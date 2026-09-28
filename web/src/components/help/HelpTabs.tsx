'use client';

import { BookOpen, PlayCircle } from 'lucide-react';
import { useTranslations } from 'next-intl';
import { Link } from '@/i18n/routing';
import { cn } from '@/lib/utils';

/** Articles | Video tutorials — the two sections of the Help center. */
export default function HelpTabs({ active }: { active: 'articles' | 'videos' }) {
  const t = useTranslations('Help');
  const tabs = [
    { id: 'articles' as const, href: '/dashboard/help', icon: <BookOpen size={16} aria-hidden />, label: t('tabs.articles') },
    { id: 'videos' as const, href: '/dashboard/help/videos', icon: <PlayCircle size={16} aria-hidden />, label: t('tabs.videos') },
  ];
  return (
    <nav aria-label={t('tabs.label')} className="mb-6 border-b border-gray-200">
      <ul className="flex gap-1 overflow-x-auto">
        {tabs.map((tab) => {
          const current = tab.id === active;
          return (
            <li key={tab.id}>
              <Link
                href={tab.href}
                aria-current={current ? 'page' : undefined}
                className={cn(
                  'inline-flex items-center gap-2 whitespace-nowrap border-b-2 px-3 py-2 text-sm font-medium transition-colors -mb-px',
                  current
                    ? 'border-primary text-primary'
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:border-gray-300'
                )}
              >
                {tab.icon}
                {tab.label}
              </Link>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}

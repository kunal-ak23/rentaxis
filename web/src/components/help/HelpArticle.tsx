'use client';

import { ArrowLeft, Clock, PlayCircle } from 'lucide-react';
import { Link } from '@/i18n/routing';
import { useLocale, useTranslations } from 'next-intl';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import RoleFilter from './RoleFilter';
import TourTrigger from '@/components/tour/TourTrigger';
import { getTourById } from '@/components/tour/tours';
import { readingTime } from '@/lib/help';
import type { HelpArticle as HelpArticleType } from '@/lib/help';
import { TUTORIALS, tutorialsForArticle, type Tutorial } from '@/lib/tutorials/catalog';

interface HelpArticleProps {
  article: HelpArticleType;
  /** The video catalogue; injectable for tests. */
  tutorials?: Tutorial[];
}

export default function HelpArticle({ article, tutorials = TUTORIALS }: HelpArticleProps) {
  const t = useTranslations('Help');
  const locale = useLocale();
  const minutes = readingTime(article.content);
  const relatedTour = article.relatedTour ? getTourById(article.relatedTour) : undefined;
  // Published (non-null YouTube id) tutorials that list this article.
  const videos = tutorialsForArticle(article.slug, tutorials);
  const lang: 'en' | 'ar' = locale === 'ar' ? 'ar' : 'en';

  return (
    <div className="max-w-3xl mx-auto">
      {/* Back link */}
      <Link
        href="/dashboard/help"
        className="inline-flex items-center gap-1.5 text-sm text-gray-500 hover:text-gray-700 mb-6 transition-colors"
      >
        <ArrowLeft size={16} className="rtl:rotate-180" />
        {t('backToHelp')}
      </Link>

      {/* Header */}
      <div className="mb-8">
        <h1 lang="en" dir="auto" className="text-2xl font-bold text-gray-900 mb-2">{article.title}</h1>
        {article.description && (
          <p lang="en" dir="auto" className="text-gray-500 mb-4">{article.description}</p>
        )}
        <div className="flex flex-wrap items-center gap-4">
          <RoleFilter roles={article.roles} compact />
          <span className="inline-flex items-center gap-1 text-xs text-gray-400">
            <Clock size={12} />
            {t('minRead', { count: minutes })}
          </span>
        </div>
      </div>

      {/* Related tour trigger */}
      {relatedTour && (
        <div className="mb-6 p-4 rounded-lg border border-primary/20 bg-primary/5">
          <p className="text-sm font-medium text-gray-700 mb-2">{t('tourAvailable')}</p>
          <TourTrigger tour={relatedTour} variant="inline" />
        </div>
      )}

      {videos.length > 0 && (
        <ul className="mb-6 space-y-2">
          {videos.map((video) => (
            <li key={video.id}>
              <Link
                href={`/dashboard/help/videos?play=${encodeURIComponent(video.id)}`}
                className="inline-flex items-center gap-2 text-sm font-medium text-primary hover:underline"
              >
                <PlayCircle size={16} aria-hidden />
                {t('videos.watchVideo')}
                <span className="font-normal text-gray-500">— {video.title[lang]}</span>
              </Link>
            </li>
          ))}
        </ul>
      )}

      {locale === 'ar' && <p className="mb-4 text-xs text-gray-500">{t('contentEnglishOnly')}</p>}

      {/* Article content: English markdown, laid out LTR even on an RTL page. */}
      <div lang="en" dir="ltr" className="prose prose-gray max-w-none prose-headings:text-gray-900 prose-a:text-primary prose-strong:text-gray-900 prose-table:text-sm prose-th:bg-gray-50 prose-th:px-3 prose-th:py-2 prose-td:px-3 prose-td:py-2 prose-td:border-t prose-td:border-gray-200">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{article.content}</ReactMarkdown>
      </div>
    </div>
  );
}

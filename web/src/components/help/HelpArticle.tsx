'use client';

import { ArrowLeft, Clock } from 'lucide-react';
import { Link } from '@/i18n/routing';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import RoleFilter from './RoleFilter';
import TourTrigger from '@/components/tour/TourTrigger';
import { getTourById } from '@/components/tour/tours';
import { readingTime } from '@/lib/help';
import type { HelpArticle as HelpArticleType } from '@/lib/help';

interface HelpArticleProps {
  article: HelpArticleType;
}

export default function HelpArticle({ article }: HelpArticleProps) {
  const minutes = readingTime(article.content);
  const relatedTour = article.relatedTour ? getTourById(article.relatedTour) : undefined;

  return (
    <div className="max-w-3xl mx-auto">
      {/* Back link */}
      <Link
        href="/dashboard/help"
        className="inline-flex items-center gap-1.5 text-sm text-gray-500 hover:text-gray-700 mb-6 transition-colors"
      >
        <ArrowLeft size={16} />
        Back to Help Center
      </Link>

      {/* Header */}
      <div className="mb-8">
        <h1 className="text-2xl font-bold text-gray-900 mb-2">{article.title}</h1>
        {article.description && (
          <p className="text-gray-500 mb-4">{article.description}</p>
        )}
        <div className="flex flex-wrap items-center gap-4">
          <RoleFilter roles={article.roles} compact />
          <span className="inline-flex items-center gap-1 text-xs text-gray-400">
            <Clock size={12} />
            {minutes} min read
          </span>
        </div>
      </div>

      {/* Related tour trigger */}
      {relatedTour && (
        <div className="mb-6 p-4 rounded-lg border border-primary/20 bg-primary/5">
          <p className="text-sm font-medium text-gray-700 mb-2">Interactive Tour Available</p>
          <TourTrigger tour={relatedTour} variant="inline" />
        </div>
      )}

      {/* Article content */}
      <div className="prose prose-gray max-w-none prose-headings:text-gray-900 prose-a:text-primary prose-strong:text-gray-900 prose-table:text-sm prose-th:bg-gray-50 prose-th:px-3 prose-th:py-2 prose-td:px-3 prose-td:py-2 prose-td:border-t prose-td:border-gray-200">
        <ReactMarkdown remarkPlugins={[remarkGfm]}>{article.content}</ReactMarkdown>
      </div>
    </div>
  );
}

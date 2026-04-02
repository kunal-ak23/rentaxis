'use client';

import { use } from 'react';
import { FileQuestion } from 'lucide-react';
import { Link } from '@/i18n/routing';
import '@/lib/helpArticles'; // side-effect import to register articles
import { getArticleBySlug } from '@/lib/helpLoader';
import HelpArticleView from '@/components/help/HelpArticle';

export default function HelpArticlePage({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  const { slug } = use(params);
  const article = getArticleBySlug(slug);

  if (!article) {
    return (
      <div className="p-4 md:p-6 lg:p-8">
        <div className="max-w-3xl mx-auto text-center py-20">
          <FileQuestion size={48} className="mx-auto text-gray-300 mb-4" />
          <h1 className="text-xl font-bold text-gray-900 mb-2">Article Not Found</h1>
          <p className="text-gray-500 mb-6">
            The help article you&apos;re looking for doesn&apos;t exist or has been removed.
          </p>
          <Link
            href="/dashboard/help"
            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-primary text-white text-sm font-medium hover:bg-primary/90 transition-colors"
          >
            Back to Help Center
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="p-4 md:p-6 lg:p-8">
      <HelpArticleView article={article} />
    </div>
  );
}

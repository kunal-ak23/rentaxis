'use client';

import '@/lib/helpArticles'; // side-effect import to register articles
import { getAllArticles } from '@/lib/helpLoader';
import HelpCenter from '@/components/help/HelpCenter';

export default function HelpPage() {
  const articles = getAllArticles();

  return (
    <div className="p-4 md:p-6 lg:p-8">
      <HelpCenter articles={articles} />
    </div>
  );
}

'use client';

import { use } from 'react';
import VideoTutorials from '@/components/help/VideoTutorials';

export default function HelpVideosPage({
  searchParams,
}: {
  searchParams: Promise<{ play?: string | string[] }>;
}) {
  const { play } = use(searchParams);
  return (
    <div className="p-4 md:p-6 lg:p-8">
      <VideoTutorials initialPlayId={typeof play === 'string' ? play : undefined} />
    </div>
  );
}

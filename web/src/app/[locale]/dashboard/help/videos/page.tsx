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
      {/* Keyed by the video so a new ?play= (from the help button, on this page) opens its player. */}
      <VideoTutorials key={typeof play === 'string' ? play : ''} initialPlayId={typeof play === 'string' ? play : undefined} />
    </div>
  );
}

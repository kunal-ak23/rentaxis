'use client';

import { useCallback, useMemo, useState } from 'react';
import { BookOpen, Clock, Play, PlayCircle } from 'lucide-react';
import { useLocale, useTranslations } from 'next-intl';
import { useSession } from 'next-auth/react';
import { cn } from '@/lib/utils';
import { type UserRole } from '@/lib/rbac';
import {
  TUTORIALS,
  TUTORIAL_TOPICS,
  filterTutorialsByRole,
  formatDuration,
  publishedTutorials,
  searchTutorials,
  thumbnailUrl,
  type PublishedTutorial,
  type Tutorial,
  type TutorialTopic,
} from '@/lib/tutorials/catalog';
import HelpSearch from './HelpSearch';
import HelpTabs from './HelpTabs';
import VideoPlayerDialog from './VideoPlayerDialog';

interface VideoTutorialsProps {
  /** The catalogue; injectable for tests. */
  tutorials?: Tutorial[];
  /** Open this tutorial's player on arrival (`?play=<id>` from an article's "Watch the video"). */
  initialPlayId?: string;
}

export default function VideoTutorials({ tutorials = TUTORIALS, initialPlayId }: VideoTutorialsProps) {
  const { data: session } = useSession();
  const t = useTranslations('Help');
  const locale = useLocale();
  const lang: 'en' | 'ar' = locale === 'ar' ? 'ar' : 'en';
  const userRole = (session?.user as { role?: UserRole } | undefined)?.role;
  const isAdmin = userRole === 'SUPER_ADMIN' || userRole === 'TENANT_ADMIN';

  const [query, setQuery] = useState('');
  const [activeTopic, setActiveTopic] = useState<TutorialTopic | null>(null);
  const [showAllRoles, setShowAllRoles] = useState(false);

  // Only entries with a YouTube id exist as far as this page is concerned.
  const published = useMemo(() => publishedTutorials(tutorials), [tutorials]);
  const [playing, setPlaying] = useState<PublishedTutorial | null>(
    () => (initialPlayId ? published.find((x) => x.id === initialPlayId) ?? null : null),
  );

  const roleFiltered = useMemo(
    () => (showAllRoles && isAdmin ? published : filterTutorialsByRole(published, userRole)),
    [published, userRole, showAllRoles, isAdmin],
  );
  const searched = useMemo(() => searchTutorials(roleFiltered, query), [roleFiltered, query]);
  const displayed = useMemo(
    () => (activeTopic ? searched.filter((x) => x.topic === activeTopic) : searched),
    [searched, activeTopic],
  );
  const topicCounts = useMemo(() => {
    const counts = {} as Record<TutorialTopic, number>;
    for (const topic of TUTORIAL_TOPICS) counts[topic] = searched.filter((x) => x.topic === topic).length;
    return counts;
  }, [searched]);

  const handleSearch = useCallback((q: string) => setQuery(q), []);
  const closePlayer = useCallback(() => setPlaying(null), []);

  const chip = (active: boolean) =>
    cn(
      'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full border px-3 py-1.5 text-xs font-medium transition-colors cursor-pointer',
      active ? 'border-primary bg-primary/10 text-primary' : 'border-gray-200 bg-white text-gray-600 hover:bg-gray-100',
    );

  return (
    <div>
      <div className="mb-6">
        <div className="flex items-center gap-3 mb-2">
          <div className="p-2 rounded-lg bg-primary/10">
            <BookOpen size={24} className="text-primary" />
          </div>
          <div>
            <h1 className="text-2xl font-bold text-gray-900">{t('title')}</h1>
            <p className="text-sm text-gray-500">{t('subtitle')}</p>
          </div>
        </div>
      </div>

      <HelpTabs active="videos" />

      {published.length === 0 ? (
        <div className="py-16 text-center">
          <PlayCircle size={40} className="mx-auto mb-3 text-gray-300" aria-hidden />
          <p className="text-base font-semibold text-gray-900">{t('videos.comingSoon')}</p>
          <p className="mx-auto mt-1 max-w-md text-sm text-gray-500">{t('videos.comingSoonDesc')}</p>
        </div>
      ) : (
        <>
          <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center">
            <HelpSearch onSearch={handleSearch} placeholder={t('videos.searchPlaceholder')} className="flex-1" />
            {isAdmin && (
              <label className="flex items-center gap-2 text-sm text-gray-600 cursor-pointer">
                <input
                  type="checkbox"
                  checked={showAllRoles}
                  onChange={(e) => setShowAllRoles(e.target.checked)}
                  className="rounded border-gray-300 text-primary focus:ring-primary"
                />
                {t('showAllRoles')}
              </label>
            )}
          </div>

          <div role="group" aria-label={t('videos.topicsLabel')} className="mb-6 flex flex-wrap gap-2">
            <button type="button" aria-pressed={activeTopic === null} onClick={() => setActiveTopic(null)} className={chip(activeTopic === null)}>
              {t('videos.allTopics')}
              <span className="text-gray-400">{searched.length}</span>
            </button>
            {TUTORIAL_TOPICS.filter((topic) => topicCounts[topic] > 0).map((topic) => (
              <button
                key={topic}
                type="button"
                aria-pressed={activeTopic === topic}
                onClick={() => setActiveTopic(topic)}
                className={chip(activeTopic === topic)}
              >
                {t(`videos.topics.${topic}`)}
                <span className="text-gray-400">{topicCounts[topic]}</span>
              </button>
            ))}
          </div>

          {displayed.length === 0 ? (
            <p className="py-12 text-center text-sm text-gray-400">
              {query ? t('videos.noMatch') : t('videos.noneForRole')}
            </p>
          ) : (
            TUTORIAL_TOPICS.map((topic) => {
              const items = displayed.filter((x) => x.topic === topic);
              if (items.length === 0) return null;
              return (
                <section key={topic} className="mb-8">
                  <h2 className="mb-3 text-lg font-semibold text-gray-900">{t(`videos.topics.${topic}`)}</h2>
                  <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
                    {items.map((item) => (
                      <article
                        key={item.id}
                        data-testid="video-card"
                        className="group relative flex flex-col overflow-hidden rounded-lg border border-gray-200 bg-white transition-all hover:border-primary/30 hover:shadow-md"
                      >
                        <div className="relative aspect-video w-full bg-gray-100">
                          {/* Plain img: YouTube thumbnails are external and small; no next/image remote config needed. */}
                          {/* eslint-disable-next-line @next/next/no-img-element */}
                          <img
                            src={thumbnailUrl(item.youtubeId)}
                            alt={t('videos.thumbnailAlt', { title: item.title[lang] })}
                            loading="lazy"
                            className="h-full w-full object-cover"
                          />
                          <span className="absolute inset-0 flex items-center justify-center" aria-hidden>
                            <span className="rounded-full bg-black/60 p-3 text-white transition-transform group-hover:scale-110">
                              <Play size={20} className="rtl:-scale-x-100" />
                            </span>
                          </span>
                        </div>
                        <div className="flex flex-1 flex-col p-4">
                          <h3 className="mb-1 text-sm font-semibold text-gray-900 group-hover:text-primary">{item.title[lang]}</h3>
                          <p className="mb-3 line-clamp-2 text-xs leading-relaxed text-gray-500">{item.description[lang]}</p>
                          <div className="mt-auto flex items-center justify-between gap-2 text-[11px] text-gray-500">
                            <span className="rounded-full bg-gray-100 px-2 py-0.5">{t(`videos.topics.${item.topic}`)}</span>
                            <span className="inline-flex items-center gap-1" dir="ltr">
                              <Clock size={11} aria-hidden />
                              <time dateTime={`PT${item.durationSec}S`}>{formatDuration(item.durationSec)}</time>
                            </span>
                          </div>
                        </div>
                        {/* Whole card is the click target; the button carries the accessible name. */}
                        <button
                          type="button"
                          onClick={() => setPlaying(item)}
                          aria-label={t('videos.play', { title: item.title[lang] })}
                          className="absolute inset-0 rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                        />
                      </article>
                    ))}
                  </div>
                </section>
              );
            })
          )}
        </>
      )}

      {playing && <VideoPlayerDialog youtubeId={playing.youtubeId} title={playing.title[lang]} onClose={closePlayer} />}
    </div>
  );
}

"use client";

import { useState } from "react";
import { X, ChevronLeft, ChevronRight, Play, Maximize2 } from "lucide-react";

interface MediaItem {
  url: string;
  mediaType: string | null;
  caption: string | null;
  isCover: boolean;
}

interface ListingGalleryProps {
  media: MediaItem[];
  title: string;
}

export default function ListingGallery({ media, title }: ListingGalleryProps) {
  const [selectedIndex, setSelectedIndex] = useState(0);
  const [lightboxOpen, setLightboxOpen] = useState(false);

  const photos = media.filter(m => m.mediaType === "PHOTO" || m.mediaType === "FLOOR_PLAN");
  const videos = media.filter(m => m.mediaType === "VIDEO_URL");
  const allItems = [...photos, ...videos];

  if (allItems.length === 0) return null;

  const selected = allItems[selectedIndex];
  const isVideo = selected?.mediaType === "VIDEO_URL";

  return (
    <>
      <div className="max-w-2xl mx-auto">
        {/* Main image */}
        <div
          className="relative w-full aspect-[16/9] bg-neutral-900 cursor-pointer group"
          onClick={() => setLightboxOpen(true)}
        >
          {isVideo ? (
            <div className="w-full h-full flex items-center justify-center bg-neutral-900">
              <Play size={48} className="text-white/80" />
            </div>
          ) : (
            /* eslint-disable-next-line @next/next/no-img-element */
            <img
              src={selected?.url}
              alt={selected?.caption ?? title}
              className="w-full h-full object-cover"
            />
          )}
          <div className="absolute top-3 right-3 opacity-0 group-hover:opacity-100 transition-opacity">
            <div className="bg-black/60 text-white rounded-lg px-2.5 py-1.5 text-xs flex items-center gap-1.5">
              <Maximize2 size={12} />
              View full size
            </div>
          </div>
          {allItems.length > 1 && (
            <div className="absolute bottom-3 right-3 bg-black/60 text-white rounded-lg px-2.5 py-1 text-xs">
              {selectedIndex + 1} / {allItems.length}
            </div>
          )}
        </div>

        {/* Thumbnail strip */}
        {allItems.length > 1 && (
          <div className="flex gap-1.5 px-4 py-3 overflow-x-auto">
            {allItems.map((m, i) => (
              <button
                key={i}
                onClick={() => setSelectedIndex(i)}
                className={`relative shrink-0 w-16 h-16 rounded-lg overflow-hidden border-2 transition-all cursor-pointer ${
                  i === selectedIndex
                    ? "border-indigo-500 ring-1 ring-indigo-300"
                    : "border-transparent opacity-70 hover:opacity-100"
                }`}
              >
                {m.mediaType === "VIDEO_URL" ? (
                  <div className="w-full h-full bg-neutral-800 flex items-center justify-center">
                    <Play size={14} className="text-white" />
                  </div>
                ) : (
                  /* eslint-disable-next-line @next/next/no-img-element */
                  <img
                    src={m.url}
                    alt={m.caption ?? ""}
                    className="w-full h-full object-cover"
                  />
                )}
              </button>
            ))}
          </div>
        )}
      </div>

      {/* Lightbox */}
      {lightboxOpen && (
        <div
          className="fixed inset-0 z-50 bg-black/95 flex items-center justify-center"
          onClick={() => setLightboxOpen(false)}
        >
          {/* Close button */}
          <button
            onClick={() => setLightboxOpen(false)}
            className="absolute top-4 right-4 p-2 text-white/70 hover:text-white transition-colors z-50 cursor-pointer"
          >
            <X size={24} />
          </button>

          {/* Navigation */}
          {allItems.length > 1 && (
            <>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  setSelectedIndex(prev => prev > 0 ? prev - 1 : allItems.length - 1);
                }}
                className="absolute left-4 p-2 text-white/70 hover:text-white transition-colors z-50 cursor-pointer"
              >
                <ChevronLeft size={32} />
              </button>
              <button
                onClick={(e) => {
                  e.stopPropagation();
                  setSelectedIndex(prev => prev < allItems.length - 1 ? prev + 1 : 0);
                }}
                className="absolute right-4 p-2 text-white/70 hover:text-white transition-colors z-50 cursor-pointer"
              >
                <ChevronRight size={32} />
              </button>
            </>
          )}

          {/* Content */}
          <div
            className="max-w-4xl max-h-[85vh] w-full mx-4"
            onClick={(e) => e.stopPropagation()}
          >
            {isVideo ? (
              <div className="w-full aspect-video bg-black flex items-center justify-center rounded-lg">
                <p className="text-white/60 text-sm">Video: {selected.url}</p>
              </div>
            ) : (
              /* eslint-disable-next-line @next/next/no-img-element */
              <img
                src={selected?.url}
                alt={selected?.caption ?? title}
                className="w-full h-full object-contain rounded-lg"
              />
            )}
            {selected?.caption && (
              <p className="text-white/70 text-sm text-center mt-3">{selected.caption}</p>
            )}
            <p className="text-white/40 text-xs text-center mt-1">
              {selectedIndex + 1} of {allItems.length}
            </p>
          </div>
        </div>
      )}
    </>
  );
}

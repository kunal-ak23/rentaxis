"use client";

import { useState } from "react";
import { AnimatePresence, motion } from "framer-motion";
import { X, ChevronLeft, ChevronRight, Download, ZoomIn, ZoomOut } from "lucide-react";

interface MediaItem {
    url: string;
    name?: string;
    type?: string; // MIME type
}

interface LightboxProps {
    images: MediaItem[];
    initialIndex: number;
    onClose: () => void;
    onDownload?: (url: string, name: string) => void;
}

export function ImageLightbox({ images, initialIndex, onClose, onDownload }: LightboxProps) {
    const [currentIndex, setCurrentIndex] = useState(initialIndex);
    const [zoomed, setZoomed] = useState(false);

    const current = images[currentIndex];
    const isVideo = current?.type?.startsWith("video/");
    const hasPrev = currentIndex > 0;
    const hasNext = currentIndex < images.length - 1;

    const goPrev = () => { if (hasPrev) { setCurrentIndex(currentIndex - 1); setZoomed(false); } };
    const goNext = () => { if (hasNext) { setCurrentIndex(currentIndex + 1); setZoomed(false); } };

    if (!current) return null;

    return (
        <div className="fixed inset-0 z-[200] flex items-center justify-center" onKeyDown={(e) => {
            if (e.key === "Escape") onClose();
            if (e.key === "ArrowLeft") goPrev();
            if (e.key === "ArrowRight") goNext();
        }} tabIndex={0} autoFocus>
            {/* Backdrop */}
            <motion.div
                initial={{ opacity: 0 }}
                animate={{ opacity: 1 }}
                className="absolute inset-0 bg-black/90"
                onClick={onClose}
            />

            {/* Top bar */}
            <div className="absolute top-0 left-0 right-0 z-20 flex items-center justify-between px-4 py-3">
                <p className="text-white/70 text-xs">
                    {currentIndex + 1} / {images.length}
                    {current.name && <span className="ml-2 text-white/50">{current.name}</span>}
                </p>
                <div className="flex items-center gap-2">
                    {!isVideo && (
                        <button
                            onClick={() => setZoomed(!zoomed)}
                            className="p-2 text-white/70 hover:text-white transition-colors cursor-pointer rounded-lg hover:bg-white/10"
                        >
                            {zoomed ? <ZoomOut size={18} /> : <ZoomIn size={18} />}
                        </button>
                    )}
                    {onDownload && (
                        <button
                            onClick={() => onDownload(current.url, current.name || "file")}
                            className="p-2 text-white/70 hover:text-white transition-colors cursor-pointer rounded-lg hover:bg-white/10"
                        >
                            <Download size={18} />
                        </button>
                    )}
                    <button
                        onClick={onClose}
                        className="p-2 text-white/70 hover:text-white transition-colors cursor-pointer rounded-lg hover:bg-white/10"
                    >
                        <X size={18} />
                    </button>
                </div>
            </div>

            {/* Media content */}
            <div className="relative z-10">
                {isVideo ? (
                    <motion.video
                        key={currentIndex}
                        initial={{ opacity: 0, scale: 0.95 }}
                        animate={{ opacity: 1, scale: 1 }}
                        transition={{ duration: 0.2 }}
                        src={current.url}
                        controls
                        autoPlay
                        className="max-h-[85vh] max-w-[90vw] rounded-lg shadow-2xl"
                    />
                ) : (
                    <motion.img
                        key={currentIndex}
                        initial={{ opacity: 0, scale: 0.95 }}
                        animate={{ opacity: 1, scale: 1 }}
                        transition={{ duration: 0.2 }}
                        src={current.url}
                        alt={current.name || ""}
                        className={`max-h-[85vh] rounded-lg shadow-2xl transition-transform duration-200 ${
                            zoomed ? "max-w-none cursor-zoom-out scale-150" : "max-w-[90vw] cursor-zoom-in"
                        }`}
                        onClick={() => setZoomed(!zoomed)}
                    />
                )}
            </div>

            {/* Navigation arrows */}
            {hasPrev && (
                <button
                    onClick={goPrev}
                    className="absolute left-4 z-20 p-3 bg-black/50 hover:bg-black/70 text-white rounded-full transition-colors cursor-pointer"
                >
                    <ChevronLeft size={20} />
                </button>
            )}
            {hasNext && (
                <button
                    onClick={goNext}
                    className="absolute right-4 z-20 p-3 bg-black/50 hover:bg-black/70 text-white rounded-full transition-colors cursor-pointer"
                >
                    <ChevronRight size={20} />
                </button>
            )}
        </div>
    );
}

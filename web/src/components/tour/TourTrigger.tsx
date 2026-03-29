'use client';

import { Play, CheckCircle2 } from 'lucide-react';
import { useTour, isTourCompleted } from './TourProvider';
import { cn } from '@/lib/utils';
import type { TourDef } from './tours/types';

interface TourTriggerProps {
  tour: TourDef;
  variant?: 'card' | 'inline';
}

export default function TourTrigger({ tour, variant = 'card' }: TourTriggerProps) {
  const { startTour } = useTour();
  const completed = isTourCompleted(tour.id);

  if (variant === 'inline') {
    return (
      <button
        onClick={() => startTour(tour.id)}
        className="inline-flex items-center gap-1.5 text-sm text-primary hover:text-primary/80 font-medium transition-colors cursor-pointer"
      >
        <Play size={14} />
        {completed ? 'Retake Tour' : 'Start Tour'}
      </button>
    );
  }

  return (
    <button
      onClick={() => startTour(tour.id)}
      className={cn(
        "flex flex-col gap-2 p-4 rounded-lg border transition-all cursor-pointer text-left",
        "hover:shadow-md hover:border-primary/30",
        completed
          ? "bg-primary/5 border-primary/20"
          : "bg-white border-gray-200 hover:bg-gray-50"
      )}
    >
      <div className="flex items-center justify-between">
        <span className="text-sm font-semibold text-gray-900">{tour.name}</span>
        {completed ? (
          <CheckCircle2 size={16} className="text-primary" />
        ) : (
          <Play size={16} className="text-accent" />
        )}
      </div>
      <p className="text-xs text-gray-500 leading-relaxed">{tour.description}</p>
    </button>
  );
}

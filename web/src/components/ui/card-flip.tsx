'use client';

import { cn } from '@/lib/utils';
import { useState } from 'react';

export interface CardFlipProps {
    front: React.ReactNode;
    back: React.ReactNode;
    className?: string;
}

export default function CardFlip({ front, back, className }: CardFlipProps) {
    const [isFlipped, setIsFlipped] = useState(false);

    return (
        <div
            className={cn("group relative h-[320px] w-full [perspective:2000px]", className)}
            onMouseEnter={() => setIsFlipped(true)}
            onMouseLeave={() => setIsFlipped(false)}
        >
            <div className={cn(
                'relative h-full w-full transition-all duration-700 [transform-style:preserve-3d]',
                isFlipped ? '[transform:rotateY(180deg)]' : '[transform:rotateY(0deg)]',
            )}>
                {/* Front of card */}
                <div className={cn(
                    'absolute inset-0 h-full w-full',
                    '[transform:rotateY(0deg)] [backface-visibility:hidden]',
                    'overflow-hidden rounded-xl border border-border bg-surface p-6',
                    'shadow-sm transition-all duration-700',
                    isFlipped ? 'opacity-0' : 'opacity-100',
                )}>
                    {front}
                </div>

                {/* Back of card */}
                <div className={cn(
                    'absolute inset-0 h-full w-full',
                    '[transform:rotateY(180deg)] [backface-visibility:hidden]',
                    'overflow-hidden rounded-xl border border-primary/20 bg-primary/5 p-6',
                    'shadow-sm flex flex-col transition-all duration-700',
                    !isFlipped ? 'opacity-0' : 'opacity-100',
                )}>
                    {back}
                </div>
            </div>
        </div>
    );
}

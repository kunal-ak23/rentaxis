import type { ReactNode } from "react";
import { X } from "lucide-react";

/** A plain dialog shell: title, close, body. Scrolls inside on a phone. */
export function Modal({ title, onClose, children, wide, testId }: {
    title: string; onClose: () => void; children: ReactNode; wide?: boolean; testId?: string;
}) {
    return (
        <div className="fixed inset-0 z-50 flex items-start justify-center bg-black/40 p-4 overflow-y-auto" role="dialog"
             aria-modal="true" aria-label={title} data-testid={testId}>
            <div className={`bg-surface border border-border rounded-xl shadow-xl w-full ${wide ? "max-w-5xl" : "max-w-xl"} my-8`}>
                <div className="flex items-center justify-between px-4 py-3 border-b border-border">
                    <h2 className="text-sm font-bold text-foreground">{title}</h2>
                    <button type="button" onClick={onClose} aria-label="close" className="p-1 text-muted hover:text-foreground cursor-pointer">
                        <X size={16} />
                    </button>
                </div>
                <div className="p-4">{children}</div>
            </div>
        </div>
    );
}

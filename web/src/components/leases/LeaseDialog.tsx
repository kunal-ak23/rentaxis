"use client";

import { X, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

/**
 * The shell the four lease actions share.
 *
 * `ConfirmDialog` would have done for three of them, but not for Post: its
 * confirm button has no disabled state, and Post must stay unavailable until
 * the server's dry run comes back clean. Rather than have Post look different
 * from Amend, Renew and Extend, all four use this and the gate is expressed the
 * same way everywhere.
 */

type Props = {
    open: boolean;
    title: string;
    onClose: () => void;
    onConfirm: () => void;
    confirmText: string;
    cancelText: string;
    confirmDisabled?: boolean;
    busy?: boolean;
    destructive?: boolean;
    /** `data-testid` of the confirm button, for the recorded walkthrough. */
    confirmTestId: string;
    width?: "sm" | "lg" | "xl";
    children: React.ReactNode;
};

export default function LeaseDialog({
    open,
    title,
    onClose,
    onConfirm,
    confirmText,
    cancelText,
    confirmDisabled,
    busy,
    destructive,
    confirmTestId,
    width = "sm",
    children,
}: Props) {
    if (!open) return null;
    return (
        <div className="fixed inset-0 z-[105] flex items-center justify-center bg-black/40 p-4">
            <div
                className={cn(
                    "bg-surface rounded-2xl border border-border shadow-2xl w-full flex flex-col max-h-[90vh]",
                    width === "sm" && "max-w-md",
                    width === "lg" && "max-w-2xl",
                    width === "xl" && "max-w-5xl",
                )}
            >
                <div className="flex items-center justify-between px-5 py-3.5 border-b border-border shrink-0">
                    <h3 className="text-sm font-semibold text-foreground">{title}</h3>
                    <button
                        type="button"
                        onClick={onClose}
                        aria-label={cancelText}
                        className="p-1 text-muted hover:text-foreground cursor-pointer"
                    >
                        <X size={15} />
                    </button>
                </div>
                <div className="px-5 py-4 overflow-y-auto flex-1">{children}</div>
                <div className="px-5 py-3.5 border-t border-border bg-input/50 flex justify-end gap-2 shrink-0">
                    <button
                        type="button"
                        onClick={onClose}
                        disabled={busy}
                        className="px-4 py-2 rounded-xl text-xs font-bold uppercase tracking-wider border border-border text-foreground hover:bg-input cursor-pointer disabled:opacity-50"
                    >
                        {cancelText}
                    </button>
                    <button
                        type="button"
                        data-testid={confirmTestId}
                        onClick={onConfirm}
                        disabled={confirmDisabled || busy}
                        className={cn(
                            "inline-flex items-center gap-2 px-4 py-2 rounded-xl text-xs font-bold uppercase tracking-wider cursor-pointer disabled:opacity-50 disabled:cursor-not-allowed",
                            destructive
                                ? "bg-error text-white hover:bg-error/90"
                                : "bg-primary text-primary-foreground hover:bg-primary/90",
                        )}
                    >
                        {busy && <Loader2 size={13} className="animate-spin" />}
                        {confirmText}
                    </button>
                </div>
            </div>
        </div>
    );
}

"use client";

import { useState } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { cn } from "@/lib/utils";

interface PaginationProps {
    currentPage: number;
    totalItems: number;
    itemsPerPage: number;
    onPageChange: (page: number) => void;
    onItemsPerPageChange?: (count: number) => void;
    itemsPerPageOptions?: number[];
}

export function Pagination({
    currentPage,
    totalItems,
    itemsPerPage,
    onPageChange,
    onItemsPerPageChange,
    itemsPerPageOptions = [10, 25, 50, 100],
}: PaginationProps) {
    const totalPages = Math.ceil(totalItems / itemsPerPage);
    const startItem = (currentPage - 1) * itemsPerPage + 1;
    const endItem = Math.min(currentPage * itemsPerPage, totalItems);

    if (totalItems === 0) return null;

    const pages: (number | "...")[] = [];
    if (totalPages <= 7) {
        for (let i = 1; i <= totalPages; i++) pages.push(i);
    } else {
        pages.push(1);
        if (currentPage > 3) pages.push("...");
        for (let i = Math.max(2, currentPage - 1); i <= Math.min(totalPages - 1, currentPage + 1); i++) {
            pages.push(i);
        }
        if (currentPage < totalPages - 2) pages.push("...");
        pages.push(totalPages);
    }

    return (
        <div className="flex items-center justify-between gap-4 pt-4 border-t border-border mt-4">
            <div className="flex items-center gap-2 text-xs text-muted">
                <span>Showing {startItem}-{endItem} of {totalItems}</span>
                {onItemsPerPageChange && (
                    <>
                        <span className="text-border">|</span>
                        <select
                            value={itemsPerPage}
                            onChange={(e) => onItemsPerPageChange(Number(e.target.value))}
                            className="bg-surface border border-border rounded-md px-2 py-1 text-xs text-foreground cursor-pointer focus:ring-2 focus:ring-primary/20 focus:outline-none"
                        >
                            {itemsPerPageOptions.map((n) => (
                                <option key={n} value={n}>{n} per page</option>
                            ))}
                        </select>
                    </>
                )}
            </div>

            <div className="flex items-center gap-1">
                <button
                    onClick={() => onPageChange(currentPage - 1)}
                    disabled={currentPage === 1}
                    className="p-1.5 rounded-md text-muted hover:bg-input hover:text-foreground disabled:opacity-30 disabled:cursor-not-allowed transition-colors cursor-pointer"
                >
                    <ChevronLeft size={14} />
                </button>

                {pages.map((page, i) =>
                    page === "..." ? (
                        <span key={`dots-${i}`} className="px-1 text-xs text-muted">...</span>
                    ) : (
                        <button
                            key={page}
                            onClick={() => onPageChange(page)}
                            className={cn(
                                "min-w-[28px] h-7 rounded-md text-xs font-medium transition-colors cursor-pointer",
                                page === currentPage
                                    ? "bg-primary text-primary-foreground"
                                    : "text-muted hover:bg-input hover:text-foreground"
                            )}
                        >
                            {page}
                        </button>
                    )
                )}

                <button
                    onClick={() => onPageChange(currentPage + 1)}
                    disabled={currentPage === totalPages}
                    className="p-1.5 rounded-md text-muted hover:bg-input hover:text-foreground disabled:opacity-30 disabled:cursor-not-allowed transition-colors cursor-pointer"
                >
                    <ChevronRight size={14} />
                </button>
            </div>
        </div>
    );
}

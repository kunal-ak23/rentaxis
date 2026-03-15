"use client";

import { useState, useRef, useCallback } from "react";
import { Upload, X, Image, Loader2 } from "lucide-react";
import { cn } from "@/lib/utils";

interface FileUploadProps {
    value?: string;
    onChange: (url: string) => void;
    onRemove: () => void;
    folder?: string;
    accept?: string;
    maxSizeMB?: number;
    label?: string;
    hint?: string;
}

export function FileUpload({
    value,
    onChange,
    onRemove,
    folder = "assets",
    accept = "image/png,image/jpeg,image/svg+xml",
    maxSizeMB = 2,
    label = "Upload Logo",
    hint = "PNG, JPG or SVG. Drag & drop or click to browse.",
}: FileUploadProps) {
    const [uploading, setUploading] = useState(false);
    const [error, setError] = useState("");
    const [isDragging, setIsDragging] = useState(false);
    const inputRef = useRef<HTMLInputElement>(null);

    const handleUpload = useCallback(async (file: File) => {
        setError("");

        if (file.size > maxSizeMB * 1024 * 1024) {
            setError(`File must be under ${maxSizeMB}MB`);
            return;
        }

        if (!file.type.startsWith("image/")) {
            setError("Only image files are allowed");
            return;
        }

        setUploading(true);
        try {
            const formData = new FormData();
            formData.append("file", file);
            formData.append("folder", folder);

            const res = await fetch("/api/upload?path=/api/v1/assets/upload", {
                method: "POST",
                body: formData,
            });

            if (res.ok) {
                const data = await res.json();
                onChange(data.url);
            } else {
                const data = await res.json().catch(() => ({}));
                setError(data.error || "Upload failed");
            }
        } catch {
            setError("Upload failed");
        } finally {
            setUploading(false);
        }
    }, [folder, maxSizeMB, onChange]);

    const handleDrop = useCallback((e: React.DragEvent) => {
        e.preventDefault();
        setIsDragging(false);
        const file = e.dataTransfer.files[0];
        if (file) handleUpload(file);
    }, [handleUpload]);

    const handleDragOver = useCallback((e: React.DragEvent) => {
        e.preventDefault();
        setIsDragging(true);
    }, []);

    const handleDragLeave = useCallback(() => {
        setIsDragging(false);
    }, []);

    if (value) {
        return (
            <div className="flex items-center gap-4">
                <div className="relative group">
                    <img
                        src={value}
                        alt="Uploaded"
                        className="h-16 max-w-[200px] object-contain rounded-lg border border-border bg-surface p-1"
                    />
                    <button
                        type="button"
                        onClick={onRemove}
                        className="absolute -top-2 -right-2 w-5 h-5 bg-error text-white rounded-full flex items-center justify-center opacity-0 group-hover:opacity-100 transition-opacity cursor-pointer"
                    >
                        <X size={10} />
                    </button>
                </div>
                <button
                    type="button"
                    onClick={() => inputRef.current?.click()}
                    className="text-xs font-semibold text-primary hover:text-primary/80 transition-colors cursor-pointer"
                >
                    Replace
                </button>
                <input
                    ref={inputRef}
                    type="file"
                    accept={accept}
                    className="hidden"
                    onChange={(e) => {
                        const file = e.target.files?.[0];
                        if (file) handleUpload(file);
                    }}
                />
            </div>
        );
    }

    return (
        <div>
            <div
                onDrop={handleDrop}
                onDragOver={handleDragOver}
                onDragLeave={handleDragLeave}
                onClick={() => !uploading && inputRef.current?.click()}
                className={cn(
                    "border-2 border-dashed rounded-lg p-6 text-center transition-all cursor-pointer",
                    isDragging
                        ? "border-primary bg-primary/5"
                        : "border-border hover:border-primary/40 hover:bg-input/50",
                    uploading && "opacity-60 cursor-wait"
                )}
            >
                {uploading ? (
                    <div className="flex flex-col items-center gap-2">
                        <Loader2 size={20} className="animate-spin text-primary" />
                        <p className="text-xs text-muted font-medium">Uploading...</p>
                    </div>
                ) : (
                    <div className="flex flex-col items-center gap-2">
                        <div className="w-10 h-10 bg-input rounded-lg flex items-center justify-center text-muted">
                            <Image size={18} />
                        </div>
                        <p className="text-xs font-semibold text-foreground">{label}</p>
                        <p className="text-[10px] text-muted">{hint}</p>
                    </div>
                )}
            </div>

            {error && (
                <p className="text-[10px] text-error font-semibold mt-1.5">{error}</p>
            )}

            <input
                ref={inputRef}
                type="file"
                accept={accept}
                className="hidden"
                onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) handleUpload(file);
                }}
            />
        </div>
    );
}

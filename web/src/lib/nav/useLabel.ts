// src/lib/nav/useLabel.ts
"use client";
import { useTranslations } from "next-intl";
import type { Label } from "./types";

/** Resolves a model label with the root translator (`ns.key`). */
export function useLabel(): (label: Label) => string {
    const t = useTranslations();
    return (label: Label) => t(`${label.ns}.${label.key}`);
}

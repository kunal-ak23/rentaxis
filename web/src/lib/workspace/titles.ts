// src/lib/workspace/titles.ts
import { activeNav, type RailSection } from "../nav/navModel";
import type { Label } from "../nav/types";
import { pathOf } from "./store";

/**
 * The default title of a screen (spec §6): the label of the nav item the URL
 * lights, else its section's. Stored as a message key so EN↔AR re-translates it.
 */
export function screenLabel(href: string, rail: RailSection[]): Label | null {
    const search = href.includes("?") ? href.slice(href.indexOf("?")) : "";
    const { section, item } = activeNav(pathOf(href), rail, search);
    const s = rail.find(r => r.id === section);
    if (!s) return null;
    if (item) {
        const hit = s.groups.flatMap(g => g.items).find(i => i.id === item);
        if (hit) return hit.label;
    }
    return s.label;
}

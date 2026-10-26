import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * Tailwind 4 writes CSS only for a colour it knows: `bg-input` names no
 * `--color-input` in globals.css's `@theme`, so it printed nothing and the
 * report kit's shading, hover and focus tint never showed. Every colour utility
 * in the report screens must name a theme token, a Tailwind palette colour or a
 * keyword.
 */

const src = join(__dirname, "..", "..", "..");
const css = readFileSync(join(src, "app", "globals.css"), "utf8");
const theme = css.match(/@theme inline \{([\s\S]*?)\n\}/)?.[1] ?? "";
const TOKENS = new Set([...theme.matchAll(/--color-([a-z0-9-]+):/g)].map(m => m[1]));
const KEYWORDS = new Set(["white", "black", "transparent", "current", "inherit"]);
const PALETTE = /^(slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-(50|[1-9]00|950)$/;

/** The non-colour utilities that share a colour utility's prefix (sizes, sides, alignment, styles). */
const NOT_A_COLOUR: Record<string, RegExp> = {
    text: /^(xs|sm|base|lg|\d?xl|start|end|center|left|right|justify|wrap|nowrap|balance|pretty|ellipsis|clip)$/,
    border: /^([tbselrxy](-\d+)?|\d+|dashed|dotted|solid|double|none|hidden|collapse|separate)$/,
    ring: /^(\d+|inset|offset-.+)$/,
    outline: /^(\d+|none|hidden|dashed|dotted|double|solid|offset-.+)$/,
    divide: /^([xy](-\d+)?|\d+|dashed|dotted|solid|none)$/,
    bg: /^(fixed|local|scroll|clip-.+|origin-.+|repeat.*|no-repeat|cover|contain|auto|center|top|bottom|left|right|none|gradient-.+|linear-.+|radial.*)$/,
};
const COLOUR_UTILITY = /^-?(bg|text|border|ring|outline|divide|fill|stroke|accent|caret)-([a-z][a-z0-9-]*)(\/\d+)?$/;

const files = [
    ...readdirSync(join(src, "components", "reports")).filter(f => f.endsWith(".tsx")).map(f => join(src, "components", "reports", f)),
    join(src, "app", "[locale]", "dashboard", "reports", "page.tsx"),
    join(src, "app", "[locale]", "dashboard", "finance", "tenant-ledger", "page.tsx"),
    ...readdirSync(join(src, "app", "[locale]", "dashboard", "finance", "tenant-ledger", "_components"))
        .filter(f => f.endsWith(".tsx"))
        .map(f => join(src, "app", "[locale]", "dashboard", "finance", "tenant-ledger", "_components", f)),
];

/** Each class-like word in the file's string literals, with its variants (`hover:`, `[&_a]:`) stripped. */
function utilities(file: string): string[] {
    const code = readFileSync(file, "utf8").replace(/\/\*[\s\S]*?\*\/|(^|[^:"'`])\/\/.*$/gm, "$1");
    const literals = [...code.matchAll(/"([^"\\\n]*)"|`([^`]*)`/g)].map(m => m[1] ?? m[2]);
    return literals.flatMap(l => l.split(/\s+/)).map(w => w.slice(w.lastIndexOf(":") + 1).replace(/^!/, ""));
}

describe("report screens' colour utilities", () => {
    it("reads the theme's colour tokens", () => {
        for (const t of ["surface", "border", "primary", "foreground", "report-band", "report-highlight", "report-highlight-fg"]) {
            expect(TOKENS.has(t), t).toBe(true);
        }
        expect(TOKENS.has("input")).toBe(false);
    });

    it("name only colours Tailwind generates", () => {
        let checked = 0;
        const unknown = files.flatMap(file => utilities(file).flatMap(word => {
            const m = word.match(COLOUR_UTILITY);
            if (!m || NOT_A_COLOUR[m[1]]?.test(m[2])) return [];
            checked++;
            return TOKENS.has(m[2]) || KEYWORDS.has(m[2]) || PALETTE.test(m[2]) ? [] : [`${file.slice(src.length + 1)}: ${word}`];
        }));
        expect(unknown).toEqual([]);
        // Not vacuous: the screens' colour classes were all seen.
        expect(checked).toBeGreaterThan(40);
    });
});

import { describe, expect, it } from "vitest";
import { IntlMessageFormat } from "intl-messageformat";

import ar from "../../../messages/ar.json";
import en from "../../../messages/en.json";

/**
 * Both catalogs carry exactly the same keys, and every message is valid ICU in
 * its own locale. A key missing from ar.json renders as its key path under
 * /ar; a malformed plural throws at render time.
 */

type Tree = { [k: string]: string | Tree };

const flatten = (tree: Tree, prefix = ""): [string, string][] =>
    Object.entries(tree).flatMap(([k, v]) =>
        typeof v === "string" ? [[prefix + k, v] as [string, string]] : flatten(v, `${prefix}${k}.`));

const enKeys = new Map(flatten(en as Tree));
const arKeys = new Map(flatten(ar as Tree));

describe("message catalogs", () => {
    it("have the same keys in English and Arabic", () => {
        expect([...enKeys.keys()].filter((k) => !arKeys.has(k)), "missing from ar.json").toEqual([]);
        expect([...arKeys.keys()].filter((k) => !enKeys.has(k)), "missing from en.json").toEqual([]);
    });

    it("hold valid ICU messages in both locales", () => {
        const bad: string[] = [];
        for (const [locale, keys] of [["en", enKeys], ["ar", arKeys]] as const) {
            for (const [key, message] of keys) {
                try {
                    new IntlMessageFormat(message, locale);
                } catch (e) {
                    bad.push(`${locale}:${key}: ${(e as Error).message}`);
                }
            }
        }
        expect(bad).toEqual([]);
    });

    it("use the same placeholders in English and Arabic", () => {
        // Argument names from the parsed message, so plural branch text such as
        // "{it}" is not mistaken for an argument.
        type Node = { value?: unknown; options?: Record<string, { value: Node[] }>; children?: Node[] };
        const collect = (nodes: Node[], out: Set<string>) => {
            for (const n of nodes) {
                if (typeof n.value === "string" && "type" in n && (n as { type: number }).type !== 0) out.add(n.value);
                for (const opt of Object.values(n.options ?? {})) collect(opt.value, out);
                if (n.children) collect(n.children, out);
            }
            return out;
        };
        const args = (m: string) => [...collect(new IntlMessageFormat(m, "en").getAst() as Node[], new Set())].sort();
        const mismatched = [...enKeys].filter(([k, m]) => arKeys.has(k) && args(m).join() !== args(arKeys.get(k)!).join())
            .map(([k]) => k);
        expect(mismatched).toEqual([]);
    });
});

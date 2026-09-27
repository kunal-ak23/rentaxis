#!/usr/bin/env node
// EN/AR message-catalog parity: flattens the keys of messages/en.json and
// messages/ar.json, prints any key missing from either side, and exits 1 on a
// difference. Usage (from web/): node scripts/check-i18n-parity.mjs
// (src/lib/__tests__/catalog-parity.test.ts asserts the same inside vitest,
// plus ICU validity.)
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const dir = join(dirname(fileURLToPath(import.meta.url)), "..", "messages");
const load = (locale) => JSON.parse(readFileSync(join(dir, `${locale}.json`), "utf8"));

const flatten = (tree, prefix = "") =>
    Object.entries(tree).flatMap(([k, v]) =>
        v !== null && typeof v === "object" ? flatten(v, `${prefix}${k}.`) : [`${prefix}${k}`]);

const en = new Set(flatten(load("en")));
const ar = new Set(flatten(load("ar")));
const missingInAr = [...en].filter((k) => !ar.has(k));
const missingInEn = [...ar].filter((k) => !en.has(k));

for (const k of missingInAr) console.log(`missing from ar.json: ${k}`);
for (const k of missingInEn) console.log(`missing from en.json: ${k}`);

if (missingInAr.length || missingInEn.length) {
    console.log(`i18n parity FAILED: ${missingInAr.length} missing from ar, ${missingInEn.length} missing from en`);
    process.exit(1);
}
console.log(`i18n parity OK: ${en.size} keys in both en.json and ar.json`);

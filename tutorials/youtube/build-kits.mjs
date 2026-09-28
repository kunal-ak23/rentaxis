#!/usr/bin/env node
// tutorials/youtube/build-kits.mjs
//
// Generates YouTube upload kits (metadata JSON + human-readable MD + thumbnail
// PNG) for every Miftah web tutorial that has a rendered MP4 in
// tutorials/output/. Reads (never writes):
//   - web/src/lib/tutorials/catalog.ts  (id, slug, title/description, topic, roles, durationSec)
//   - tutorials/output/<slug>.mp4 + .srt
//   - tutorials/narration/<id>-*.txt    (referenced for context only)
//
// Writes, under tutorials/youtube/ only:
//   - kits/<id>.json, kits/<id>.md
//   - thumbnails/<id>.png
//
// Usage: node tutorials/youtube/build-kits.mjs [--ids 01,02,10] [--no-thumbnails]
//
// Fails loudly (non-zero exit) on any validation failure: title/description/
// tag length limits, chapter count/duration rules, missing catalog entries,
// stale terminology (Renter/Lease in generated copy), etc.

import { readFileSync, writeFileSync, readdirSync, existsSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, "../..");
const CATALOG_PATH = path.join(REPO_ROOT, "web/src/lib/tutorials/catalog.ts");
const OUTPUT_DIR = path.join(REPO_ROOT, "tutorials/output");
const NARRATION_DIR = path.join(REPO_ROOT, "tutorials/narration");
const YOUTUBE_DIR = __dirname;
const KITS_DIR = path.join(YOUTUBE_DIR, "kits");
const THUMBS_DIR = path.join(YOUTUBE_DIR, "thumbnails");

const args = process.argv.slice(2);
const idsArgIdx = args.indexOf("--ids");
const onlyIds = idsArgIdx >= 0 ? args[idsArgIdx + 1].split(",").map((s) => s.trim()) : null;
const skipThumbnails = args.includes("--no-thumbnails");

class ValidationError extends Error {}

function fail(msg) {
    throw new ValidationError(msg);
}

// ---------------------------------------------------------------------------
// Terminology guard (spec: web/src/lib/__tests__/terminology.test.ts) — any
// copy we generate ourselves (not copied verbatim from the catalogue) must
// say Tenant/Tenancy Contract/Organisation, never Renter/Lease/Tenant(-org).
// ---------------------------------------------------------------------------
const STALE_WORD = /\b(Renters?|renters?|Leases?|leases?)\b/;
function assertNoStaleTerminology(label, text) {
    if (STALE_WORD.test(text)) {
        fail(`stale terminology in ${label}: "${text.match(STALE_WORD)[0]}" — use Tenant / Tenancy Contract. Text: ${text}`);
    }
}

// ---------------------------------------------------------------------------
// Role display names (per terminology.test.ts + task brief).
// ---------------------------------------------------------------------------
const ROLE_NAMES = {
    SUPER_ADMIN: "System Admin",
    TENANT_ADMIN: "Company Admin",
    PROPERTY_MANAGER: "Property Manager",
    ACCOUNTANT: "Accountant",
    TENANT_USER: "Company User",
    RENTER: "Tenant",
    SECURITY_GUARD: "Security Guard",
};

const TOPIC_PLAYLISTS = {
    "getting-started": "Miftah · Getting started",
    portfolio: "Miftah · Portfolio",
    leasing: "Miftah · Leasing",
    collections: "Miftah · Collections",
    accounting: "Miftah · Accounting",
    operations: "Miftah · Operations",
    "tenant-portal": "Miftah · Tenant portal",
};

const TOPIC_TAGS = {
    "getting-started": ["onboarding", "sign in", "roles and permissions"],
    portfolio: ["property portfolio", "buildings", "units", "bulk import"],
    leasing: ["tenancy contract", "lease agreement", "tenant management"],
    collections: ["rent collection", "cheques", "online payments", "penalties"],
    accounting: ["chart of accounts", "journal voucher", "financial reports", "accounting software"],
    operations: ["maintenance tickets", "facilities booking", "gate pass", "meetings"],
    "tenant-portal": ["tenant portal", "marketplace", "self service"],
};

const BASE_TAGS = ["miftah", "property management", "property management software", "UAE", "Dubai"];

// ---------------------------------------------------------------------------
// Catalog parsing (regex-based — we don't modify or execute catalog.ts, and
// tsx isn't available locally, so we parse the TypeScript source directly).
// ---------------------------------------------------------------------------
const ROLE_GROUPS = {
    ALL: ["SUPER_ADMIN", "TENANT_ADMIN", "PROPERTY_MANAGER", "SECURITY_GUARD", "TENANT_USER", "RENTER", "ACCOUNTANT"],
    ADMINS: ["SUPER_ADMIN", "TENANT_ADMIN"],
    PORTFOLIO: ["TENANT_ADMIN", "PROPERTY_MANAGER"],
    FINANCE: ["SUPER_ADMIN", "TENANT_ADMIN", "ACCOUNTANT"],
};

function extractBraceBlock(src, fromIdx) {
    // fromIdx must point at an opening '{'. Returns the substring including
    // both braces, respecting nesting (ignores braces inside string literals).
    if (src[fromIdx] !== "{") fail(`extractBraceBlock: expected '{' at ${fromIdx}`);
    let depth = 0;
    let inString = null; // holds the quote char when inside a string
    for (let i = fromIdx; i < src.length; i++) {
        const c = src[i];
        if (inString) {
            if (c === "\\") { i++; continue; }
            if (c === inString) inString = null;
            continue;
        }
        if (c === '"' || c === "'" || c === "`") { inString = c; continue; }
        if (c === "{") depth++;
        else if (c === "}") {
            depth--;
            if (depth === 0) return src.slice(fromIdx, i + 1);
        }
    }
    fail("extractBraceBlock: unterminated block");
}

function parseLocalised(objSrc, fieldName) {
    // objSrc contains e.g. `title: { en: "...", ar: "..." },`
    const fieldRe = new RegExp(`${fieldName}\\s*:\\s*\\{`);
    const m = fieldRe.exec(objSrc);
    if (!m) fail(`missing field ${fieldName} in entry: ${objSrc.slice(0, 80)}...`);
    const block = extractBraceBlock(objSrc, m.index + m[0].length - 1);
    const en = /en\s*:\s*"((?:[^"\\]|\\.)*)"/.exec(block);
    const ar = /ar\s*:\s*"((?:[^"\\]|\\.)*)"/.exec(block);
    if (!en || !ar) fail(`could not parse en/ar for ${fieldName}: ${block}`);
    const unescape = (s) => s.replace(/\\"/g, '"').replace(/\\n/g, "\n");
    return { en: unescape(en[1]), ar: unescape(ar[1]) };
}

function parseRoles(objSrc) {
    const m = /roles\s*:\s*(\[[^\]]*\]|[A-Z_]+)/.exec(objSrc);
    if (!m) fail(`missing roles in entry: ${objSrc.slice(0, 80)}...`);
    const expr = m[1];
    if (!expr.startsWith("[")) {
        if (!ROLE_GROUPS[expr]) fail(`unknown role group constant: ${expr}`);
        return [...ROLE_GROUPS[expr]];
    }
    const inner = expr.slice(1, -1);
    const roles = new Set();
    for (const tokRaw of inner.split(",")) {
        const tok = tokRaw.trim();
        if (!tok) continue;
        if (tok.startsWith("...")) {
            const name = tok.slice(3).trim();
            if (!ROLE_GROUPS[name]) fail(`unknown spread role group: ${name}`);
            ROLE_GROUPS[name].forEach((r) => roles.add(r));
        } else {
            const s = /^"([A-Z_]+)"$/.exec(tok);
            if (!s) fail(`unparseable role token: ${tok}`);
            roles.add(s[1]);
        }
    }
    return [...roles];
}

function parseCatalog() {
    const src = readFileSync(CATALOG_PATH, "utf8");
    const startMarker = "export const TUTORIALS: Tutorial[] = [";
    const startIdx = src.indexOf(startMarker);
    if (startIdx < 0) fail("could not find TUTORIALS array in catalog.ts");
    const arrayOpenBracket = startIdx + startMarker.length - 1; // marker itself ends in "= ["
    // Find the matching closing bracket for the array (depth-tracking on [ ]),
    // ignoring brackets inside strings.
    let depth = 0;
    let inString = null;
    let arrayCloseIdx = -1;
    for (let i = arrayOpenBracket; i < src.length; i++) {
        const c = src[i];
        if (inString) {
            if (c === "\\") { i++; continue; }
            if (c === inString) inString = null;
            continue;
        }
        if (c === '"' || c === "'" || c === "`") { inString = c; continue; }
        if (c === "[") depth++;
        else if (c === "]") { depth--; if (depth === 0) { arrayCloseIdx = i; break; } }
    }
    if (arrayCloseIdx < 0) fail("could not find end of TUTORIALS array in catalog.ts");
    const arrayBody = src.slice(arrayOpenBracket + 1, arrayCloseIdx);

    const entries = [];
    let i = 0;
    while (i < arrayBody.length) {
        const brace = arrayBody.indexOf("{", i);
        if (brace < 0) break;
        const block = extractBraceBlock(arrayBody, brace);
        entries.push(block);
        i = brace + block.length;
    }

    return entries.map((objSrc) => {
        const id = /id\s*:\s*"([^"]+)"/.exec(objSrc)?.[1];
        const slug = /slug\s*:\s*"([^"]+)"/.exec(objSrc)?.[1];
        const topic = /topic\s*:\s*"([^"]+)"/.exec(objSrc)?.[1];
        const durationSec = Number(/durationSec\s*:\s*(\d+)/.exec(objSrc)?.[1]);
        if (!id || !slug || !topic || !durationSec) {
            fail(`incomplete catalog entry: ${objSrc.slice(0, 120)}...`);
        }
        if (!TOPIC_PLAYLISTS[topic]) fail(`unknown topic "${topic}" for tutorial ${id}`);
        const title = parseLocalised(objSrc, "title");
        const description = parseLocalised(objSrc, "description");
        const roles = parseRoles(objSrc);
        return { id, slug, topic, durationSec, title, description, roles };
    });
}

// ---------------------------------------------------------------------------
// SRT parsing + chapter derivation
// ---------------------------------------------------------------------------
function srtTimeToSec(t) {
    const m = /(\d+):(\d+):(\d+)[,.](\d+)/.exec(t);
    if (!m) fail(`bad SRT timestamp: ${t}`);
    const [, h, mm, s, ms] = m;
    return Number(h) * 3600 + Number(mm) * 60 + Number(s) + Number(ms) / 1000;
}

function parseSrt(srtPath) {
    const raw = readFileSync(srtPath, "utf8").replace(/\r\n/g, "\n");
    const blocks = raw.split(/\n\n+/).map((b) => b.trim()).filter(Boolean);
    const cues = [];
    for (const block of blocks) {
        const lines = block.split("\n");
        // lines[0] is the cue index, lines[1] is "start --> end", rest is text.
        if (lines.length < 2) continue;
        const timeLine = lines[1];
        const m = /(\S+)\s*-->\s*(\S+)/.exec(timeLine);
        if (!m) continue;
        const start = srtTimeToSec(m[1]);
        const end = srtTimeToSec(m[2]);
        const text = lines.slice(2).join(" ").replace(/\s+/g, " ").trim();
        cues.push({ start, end, text });
    }
    if (cues.length === 0) fail(`no cues parsed from ${srtPath}`);
    return cues;
}

function fmtChapterTime(sec) {
    const s = Math.floor(sec);
    const m = Math.floor(s / 60);
    const rem = s % 60;
    return `${m}:${String(rem).padStart(2, "0")}`;
}

function titleCase(text) {
    if (!text) return "Continued";
    let t = text.split(/(?<=[.!?])\s/)[0]; // first sentence
    const words = t.split(/\s+/).slice(0, 7);
    t = words.join(" ");
    if (t.length > 55) t = t.slice(0, 52).trimEnd() + "…";
    else if (words.length < t.split(/\s+/).length) t += "…";
    // Capitalise first letter.
    return t.charAt(0).toUpperCase() + t.slice(1).replace(/[.,;:]+$/, "");
}

/**
 * Derive YouTube chapters from SRT cue groups. Since the recorder's per-scene
 * timing log is pruned from disk after a tutorial passes QA (see
 * tutorials/work/infra-report.md "Retention"), we fall back to the SRT: pick
 * chapter boundaries at natural pauses between cues (scene-caption
 * boundaries) nearest to evenly spaced target points, snapping every
 * boundary to a cue start so chapters always begin on a caption. Guarantees
 * >=3 chapters, each >=10s, first at 0:00.
 */
function deriveChapters(cues, durationSec) {
    const MIN_CHAPTER_SEC = 10;
    const numChapters = Math.max(3, Math.min(7, Math.round(durationSec / 35)));

    const gaps = cues.map((cue, idx) => {
        if (idx === 0) return { idx, gap: 0 };
        return { idx, gap: cue.start - cues[idx - 1].end };
    });

    const idealTimes = [];
    for (let k = 1; k < numChapters; k++) idealTimes.push((k * durationSec) / numChapters);

    const chosenIdx = new Set([0]);
    for (const ideal of idealTimes) {
        // Search cues within a window of the ideal time, prefer the one
        // following the largest pause (a real scene boundary).
        const windowSec = Math.max(10, durationSec / numChapters / 2);
        // Prefer a cue that starts a new sentence (previous cue ends in
        // ./!/?) — a real caption/scene boundary, not a mid-sentence wrap.
        let bestSentence = null;
        let best = null;
        for (const g of gaps) {
            if (g.idx === 0) continue;
            const cueStart = cues[g.idx].start;
            if (Math.abs(cueStart - ideal) > windowSec) continue;
            if (!best || g.gap > best.gap) best = g;
            const prevText = cues[g.idx - 1].text.trim();
            if (/[.!?]$/.test(prevText)) {
                if (!bestSentence || Math.abs(cueStart - ideal) < Math.abs(cues[bestSentence.idx].start - ideal)) {
                    bestSentence = g;
                }
            }
        }
        let boundaryIdx;
        if (bestSentence) {
            boundaryIdx = bestSentence.idx;
        } else if (best) {
            boundaryIdx = best.idx;
        } else {
            // No pause nearby: snap to the cue whose start is closest to ideal.
            let closest = 1;
            let closestDist = Infinity;
            for (let idx = 1; idx < cues.length; idx++) {
                const d = Math.abs(cues[idx].start - ideal);
                if (d < closestDist) { closestDist = d; closest = idx; }
            }
            boundaryIdx = closest;
        }
        chosenIdx.add(boundaryIdx);
    }

    let boundaries = [...chosenIdx].sort((a, b) => a - b).map((idx) => cues[idx].start);

    // Enforce minimum chapter length by dropping boundaries that are too close
    // to the previous one.
    const filtered = [boundaries[0]];
    for (let i = 1; i < boundaries.length; i++) {
        if (boundaries[i] - filtered[filtered.length - 1] >= MIN_CHAPTER_SEC) {
            filtered.push(boundaries[i]);
        }
    }
    boundaries = filtered;

    // Also ensure the last chapter (boundary -> durationSec) is long enough;
    // if not, drop the last boundary.
    while (boundaries.length > 1 && durationSec - boundaries[boundaries.length - 1] < MIN_CHAPTER_SEC) {
        boundaries.pop();
    }

    if (boundaries.length < 3) {
        // Fallback: evenly split by cue count into exactly 3 chapters.
        boundaries = [0];
        for (let k = 1; k < 3; k++) {
            const targetIdx = Math.round((k * cues.length) / 3);
            const idx = Math.min(cues.length - 1, Math.max(1, targetIdx));
            boundaries.push(cues[idx].start);
        }
        boundaries = [...new Set(boundaries)].sort((a, b) => a - b);
    }

    const chapters = boundaries.map((startSec, i) => {
        const cueAtStart = cues.find((c) => c.start >= startSec) ?? cues[cues.length - 1];
        const label = i === 0 ? "Introduction" : titleCase(cueAtStart.text);
        return { startSec, label };
    });

    return chapters;
}

function validateChapters(chapters, durationSec, context) {
    if (chapters.length < 3) fail(`${context}: fewer than 3 chapters (${chapters.length})`);
    if (chapters[0].startSec !== 0) fail(`${context}: first chapter must start at 0:00`);
    for (let i = 0; i < chapters.length; i++) {
        const end = i + 1 < chapters.length ? chapters[i + 1].startSec : durationSec;
        const len = end - chapters[i].startSec;
        if (len < 10) fail(`${context}: chapter "${chapters[i].label}" is only ${len.toFixed(1)}s (min 10s)`);
    }
}

// ---------------------------------------------------------------------------
// Kit assembly
// ---------------------------------------------------------------------------
function buildTitle(entry) {
    const title = `Miftah tutorial ${entry.id} · ${entry.title.en}`;
    if (title.length > 100) fail(`title exceeds 100 chars (${title.length}): ${title}`);
    return title;
}

function buildDescription(entry, chapters, srtRelPath) {
    const roleNames = entry.roles.map((r) => ROLE_NAMES[r] ?? r).sort();
    const who = roleNames.join(", ");
    const playlist = TOPIC_PLAYLISTS[entry.topic];

    const summary = entry.description.en;
    assertNoStaleTerminology(`${entry.id} description.en (catalog)`, summary);

    const lines = [];
    lines.push(summary);
    lines.push("");
    lines.push(`Who this is for: ${who}.`);
    lines.push("");
    lines.push("Chapters:");
    chapters.forEach((c) => lines.push(`${fmtChapterTime(c.startSec)} ${c.label}`));
    lines.push("");
    lines.push(`Part of the Miftah web course — playlist: ${playlist}`);
    lines.push("");
    lines.push(entry.description.ar);

    const description = lines.join("\n");
    if (description.length > 5000) fail(`${entry.id}: description exceeds 5000 chars (${description.length})`);
    return description;
}

function buildTags(entry) {
    const tags = [...BASE_TAGS, ...(TOPIC_TAGS[entry.topic] ?? [])];
    // A handful of terms that recur across the product regardless of topic.
    tags.push("landlord software", "real estate management");
    const unique = [...new Set(tags)];
    const joined = unique.join(", ");
    if (joined.length > 500) fail(`${entry.id}: tags exceed 500 chars (${joined.length})`);
    return unique;
}

function buildKit(entry, cues, mp4RelPath, srtRelPath) {
    const durationSec = Math.max(entry.durationSec, Math.ceil(cues[cues.length - 1].end));
    const chapters = deriveChapters(cues, durationSec);
    validateChapters(chapters, durationSec, `tutorial ${entry.id}`);

    const title = buildTitle(entry);
    const description = buildDescription(entry, chapters, srtRelPath);
    const tags = buildTags(entry);
    const playlists = [TOPIC_PLAYLISTS[entry.topic], "Miftah · Complete web course"];

    return {
        id: entry.id,
        slug: entry.slug,
        title,
        description,
        tags,
        category: "Education",
        language: "en",
        visibility: "unlisted",
        madeForKids: false,
        playlists,
        chapters: chapters.map((c) => ({ startSec: c.startSec, start: fmtChapterTime(c.startSec), label: c.label })),
        durationSec,
        roles: entry.roles,
        topic: entry.topic,
        captionsFile: srtRelPath,
        videoFile: mp4RelPath,
        thumbnail: `tutorials/youtube/thumbnails/${entry.id}.png`,
    };
}

function kitToMarkdown(kit) {
    const lines = [];
    lines.push(`# ${kit.title}`);
    lines.push("");
    lines.push(`**Tutorial ID:** ${kit.id}  `);
    lines.push(`**Slug:** ${kit.slug}  `);
    lines.push(`**Topic:** ${kit.topic}  `);
    lines.push(`**Roles:** ${kit.roles.join(", ")}  `);
    lines.push(`**Duration:** ${fmtChapterTime(kit.durationSec)}  `);
    lines.push(`**Visibility:** ${kit.visibility}  `);
    lines.push(`**Category:** ${kit.category}  `);
    lines.push(`**Language:** ${kit.language}  `);
    lines.push(`**Made for kids:** ${kit.madeForKids}  `);
    lines.push(`**Playlists:** ${kit.playlists.join(", ")}  `);
    lines.push(`**Video file:** \`${kit.videoFile}\`  `);
    lines.push(`**Captions file:** \`${kit.captionsFile}\`  `);
    lines.push(`**Thumbnail:** \`${kit.thumbnail}\`  `);
    lines.push("");
    lines.push("## Description (paste into YouTube)");
    lines.push("");
    lines.push("```");
    lines.push(kit.description);
    lines.push("```");
    lines.push("");
    lines.push("## Tags");
    lines.push("");
    lines.push(kit.tags.join(", "));
    lines.push("");
    lines.push("## Chapters");
    lines.push("");
    kit.chapters.forEach((c) => lines.push(`- ${c.start} ${c.label}`));
    lines.push("");
    return lines.join("\n");
}

// ---------------------------------------------------------------------------
// Thumbnail rendering (Playwright Chromium, via web/'s dependency — text +
// simple shapes only, no logos).
// ---------------------------------------------------------------------------
function escapeHtml(s) {
    return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function thumbnailHtml(entry) {
    const number = entry.id;
    const title = escapeHtml(entry.title.en);
    return `<!doctype html>
<html>
<head>
<meta charset="utf-8">
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  html, body { width: 1280px; height: 720px; overflow: hidden; }
  body {
    background: #1B1B1B;
    font-family: -apple-system, "Segoe UI", Helvetica, Arial, sans-serif;
    position: relative;
  }
  .accent-bar {
    position: absolute; left: 0; top: 0; bottom: 0; width: 18px;
    background: #EEC046;
  }
  .accent-corner {
    position: absolute; right: -120px; top: -120px; width: 360px; height: 360px;
    border-radius: 50%;
    background: radial-gradient(circle at 30% 30%, rgba(238,192,70,0.35), rgba(238,192,70,0) 70%);
  }
  .wordmark {
    position: absolute; top: 56px; left: 96px;
    color: #EEC046;
    font-size: 30px;
    font-weight: 700;
    letter-spacing: 2px;
    text-transform: uppercase;
  }
  .number {
    position: absolute; left: 96px; top: 140px;
    font-size: 300px;
    font-weight: 800;
    color: #EEC046;
    line-height: 1;
  }
  .number-outline {
    position: absolute; left: 96px; top: 140px;
    font-size: 300px;
    font-weight: 800;
    -webkit-text-stroke: 3px #1B1B1B;
    color: transparent;
    line-height: 1;
  }
  .title-block {
    position: absolute; left: 96px; right: 96px; bottom: 90px;
    color: #FFFFFF;
    font-size: 54px;
    font-weight: 700;
    line-height: 1.15;
    max-height: 220px;
    overflow: hidden;
  }
  .rule {
    position: absolute; left: 96px; bottom: 70px; width: 140px; height: 6px;
    background: #EEC046;
  }
  .brand-tag {
    position: absolute; right: 96px; bottom: 90px;
    color: #EEC046;
    font-size: 26px;
    font-weight: 600;
    letter-spacing: 1px;
  }
</style>
</head>
<body>
  <div class="accent-corner"></div>
  <div class="accent-bar"></div>
  <div class="wordmark">Miftah tutorial</div>
  <div class="number">${number}</div>
  <div class="rule"></div>
  <div class="title-block">${title}</div>
</body>
</html>`;
}

async function renderThumbnail(chromiumModule, entry, outPath) {
    const browser = await chromiumModule.launch();
    try {
        const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
        await page.setContent(thumbnailHtml(entry), { waitUntil: "networkidle" });
        await page.screenshot({ path: outPath });
    } finally {
        await browser.close();
    }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
async function main() {
    mkdirSync(KITS_DIR, { recursive: true });
    mkdirSync(THUMBS_DIR, { recursive: true });

    const catalog = parseCatalog();
    const catalogById = new Map(catalog.map((e) => [e.id, e]));

    const mp4Files = readdirSync(OUTPUT_DIR).filter((f) => f.endsWith(".mp4"));
    const idsWithMp4 = mp4Files
        .map((f) => /^([0-9]+)-/.exec(f)?.[1])
        .filter(Boolean)
        .sort((a, b) => Number(a) - Number(b));

    const targetIds = onlyIds ? idsWithMp4.filter((id) => onlyIds.includes(id)) : idsWithMp4;
    if (targetIds.length === 0) fail("no tutorial ids to process (no MP4s found in tutorials/output)");

    let chromiumModule = null;
    if (!skipThumbnails) {
        const playwrightPath = path.join(REPO_ROOT, "web/node_modules/playwright/index.mjs");
        if (!existsSync(playwrightPath)) {
            fail(`playwright not found at ${playwrightPath} — run npm install in web/, or pass --no-thumbnails`);
        }
        ({ chromium: chromiumModule } = await import(playwrightPath));
    }

    const results = [];
    for (const id of targetIds) {
        const entry = catalogById.get(id);
        if (!entry) fail(`tutorial id ${id} has an MP4 in tutorials/output but no catalog.ts entry`);

        const mp4File = mp4Files.find((f) => f.startsWith(`${id}-`));
        const srtFile = mp4File.replace(/\.mp4$/, ".srt");
        const mp4Path = path.join(OUTPUT_DIR, mp4File);
        const srtPath = path.join(OUTPUT_DIR, srtFile);
        if (!existsSync(srtPath)) fail(`${id}: missing captions file ${srtPath}`);

        const narrationFiles = readdirSync(NARRATION_DIR).filter((f) => f.startsWith(`${id}-`));
        if (narrationFiles.length === 0) {
            console.warn(`warning: tutorial ${id} has no narration/${id}-*.txt file (proceeding anyway)`);
        }

        const cues = parseSrt(srtPath);
        const mp4RelPath = path.relative(REPO_ROOT, mp4Path);
        const srtRelPath = path.relative(REPO_ROOT, srtPath);

        const kit = buildKit(entry, cues, mp4RelPath, srtRelPath);

        // Guard against stale terminology creeping into anything we generated
        // ourselves (title, chapter labels — catalog copy is checked separately).
        assertNoStaleTerminology(`${id} title`, kit.title);
        kit.chapters.forEach((c) => assertNoStaleTerminology(`${id} chapter "${c.label}"`, c.label));

        writeFileSync(path.join(KITS_DIR, `${id}.json`), JSON.stringify(kit, null, 2) + "\n");
        writeFileSync(path.join(KITS_DIR, `${id}.md`), kitToMarkdown(kit));

        if (!skipThumbnails) {
            const thumbPath = path.join(THUMBS_DIR, `${id}.png`);
            await renderThumbnail(chromiumModule, entry, thumbPath);
        }

        results.push(kit);
        console.log(`OK  ${id}  "${kit.title}"  (${kit.chapters.length} chapters, ${fmtChapterTime(kit.durationSec)})`);
    }

    // Registry of uploads — created once, never overwritten by regeneration
    // (uploads happen out-of-band and update this file).
    const uploadsPath = path.join(YOUTUBE_DIR, "uploads.json");
    if (!existsSync(uploadsPath)) {
        const registry = {};
        for (const id of idsWithMp4) {
            registry[id] = { youtubeVideoId: null, uploadedAt: null, visibility: null };
        }
        writeFileSync(uploadsPath, JSON.stringify(registry, null, 2) + "\n");
        console.log(`created ${path.relative(REPO_ROOT, uploadsPath)}`);
    }

    console.log(`\nBuilt ${results.length} kit(s) in ${path.relative(REPO_ROOT, KITS_DIR)}/`);
}

main().catch((err) => {
    if (err instanceof ValidationError) {
        console.error(`\nVALIDATION FAILED: ${err.message}\n`);
    } else {
        console.error(err);
    }
    process.exit(1);
});

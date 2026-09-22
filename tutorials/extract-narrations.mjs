#!/usr/bin/env node

import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const tutorialDir = dirname(fileURLToPath(import.meta.url));
const source = readFileSync(join(tutorialDir, 'tutorial-storyboards.md'), 'utf8');
const outputDir = join(tutorialDir, 'narration');

const sections = [...source.matchAll(
  /^## (\d{2}) — (.+)\n([\s\S]*?)(?=^## (?:\d{2} —|Recording acceptance checklist))/gm,
)];

if (sections.length !== 37) {
  throw new Error(`Expected 37 numbered storyboards, found ${sections.length}`);
}

mkdirSync(outputDir, { recursive: true });
for (const existing of readdirSync(outputDir)) {
  if (/^\d{2}-.*\.txt$/.test(existing)) {
    rmSync(join(outputDir, existing));
  }
}

for (const [, number, title, body] of sections) {
  const narration = body.match(/^- Narration: “([\s\S]*?)”\n/m)?.[1];
  if (!narration) {
    throw new Error(`Storyboard ${number} has no narration block`);
  }

  const slug = title
    .normalize('NFKD')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
  const speech = narration.replace(/\n\s*/g, ' ').replace(/\s+/g, ' ').trim();
  writeFileSync(join(outputDir, `${number}-${slug}.txt`), `${speech}\n`);
}

const outputs = readdirSync(outputDir).filter((name) => /^\d{2}-.*\.txt$/.test(name));
if (outputs.length !== 37) {
  throw new Error(`Expected 37 narration tracks, generated ${outputs.length}`);
}

console.log(`Generated ${outputs.length} narration tracks in ${outputDir}`);

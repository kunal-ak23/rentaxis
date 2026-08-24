#!/usr/bin/env node

import {
  accessSync,
  constants,
  readFileSync,
  readdirSync,
} from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const tutorialDir = dirname(fileURLToPath(import.meta.url));
const storyboards = readFileSync(join(tutorialDir, 'tutorial-storyboards.md'), 'utf8');
const matrix = readFileSync(join(tutorialDir, 'rentaxis-capability-matrix.md'), 'utf8');
const renderer = readFileSync(join(tutorialDir, 'render-tutorial.sh'), 'utf8');
const narrationDir = join(tutorialDir, 'narration');

const failures = [];
const details = [];
// Measured from the Samantha voice on the supported macOS renderer. The `say`
// rate flag is not a literal words-per-minute guarantee for enhanced voices.
const calibratedSpeechRate = 135;
const expectedNumbers = Array.from({ length: 33 }, (_, index) =>
  String(index + 1).padStart(2, '0'),
);

const sections = [...storyboards.matchAll(
  /^## (\d{2}) — (.+)\n([\s\S]*?)(?=^## (?:\d{2} —|Recording acceptance checklist))/gm,
)];

if (sections.length !== 33) {
  failures.push(`Expected 33 storyboards, found ${sections.length}`);
}

const actualNumbers = sections.map((match) => match[1]);
if (actualNumbers.join(',') !== expectedNumbers.join(',')) {
  failures.push(`Storyboard numbers are not exactly 01–33: ${actualNumbers.join(', ')}`);
}

const expectedNarrationFiles = [];
for (const [, number, title, body] of sections) {
  const audience = body.match(/^- Audience: (.+); (\d+)(?:[–-](\d+))? minutes?\.\n/m);
  const capture = body.match(/^- Capture: ([\s\S]*?)(?=\n- Narration:)/m)?.[1];
  const narration = body.match(/^- Narration: “([\s\S]*?)”\n/m)?.[1];

  if (!audience) failures.push(`${number}: missing audience or duration`);
  if (!capture?.trim()) failures.push(`${number}: missing capture instructions`);
  if (!narration?.trim()) failures.push(`${number}: missing narration`);
  if (!audience || !narration) continue;

  const slug = title
    .normalize('NFKD')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '');
  const filename = `${number}-${slug}.txt`;
  expectedNarrationFiles.push(filename);

  const narrationPath = join(narrationDir, filename);
  let extracted;
  try {
    extracted = readFileSync(narrationPath, 'utf8').trim();
  } catch {
    failures.push(`${number}: missing extracted narration ${filename}`);
    continue;
  }

  const normalized = narration.replace(/\n\s*/g, ' ').replace(/\s+/g, ' ').trim();
  if (extracted !== normalized) {
    failures.push(`${number}: extracted narration is stale; run extract-narrations.mjs`);
  }

  const words = normalized.split(/\s+/).filter(Boolean).length;
  const minimumMinutes = Number(audience[2]);
  const maximumMinutes = Number(audience[3] ?? audience[2]);
  // The renderer defaults to a deliberate 115 words per minute. Requiring 100
  // words per advertised minute leaves room for punctuation and on-screen
  // confirmation pauses while rejecting overview copy that cannot narrate the
  // captured steps.
  const minimumWords = minimumMinutes * 100;
  if (words < minimumWords) {
    failures.push(
      `${number}: ${words} narration words cannot support the advertised ${minimumMinutes}+ minutes (minimum ${minimumWords})`,
    );
  }
  const calibratedMinutes = words / calibratedSpeechRate;
  if (calibratedMinutes < minimumMinutes - 0.4 || calibratedMinutes > maximumMinutes + 0.5) {
    failures.push(
      `${number}: calibrated narration duration ${calibratedMinutes.toFixed(1)} min is outside the advertised ${minimumMinutes}–${maximumMinutes} min range`,
    );
  }
  details.push(
    `${number}: ${words} words (~${calibratedMinutes.toFixed(1)} min calibrated speech)`,
  );
}

const actualNarrationFiles = readdirSync(narrationDir)
  .filter((name) => /^\d{2}-.*\.txt$/.test(name))
  .sort();
if (actualNarrationFiles.join(',') !== expectedNarrationFiles.sort().join(',')) {
  failures.push('Narration directory does not contain exactly the 33 expected tracks');
}

const matrixNumbers = [...matrix.matchAll(/^\| (\d{2})\./gm)].map((match) => match[1]);
if (matrixNumbers.join(',') !== expectedNumbers.join(',')) {
  failures.push(`Capability matrix is not exactly 01–33: ${matrixNumbers.join(', ')}`);
}

for (const token of [
  'loudnorm=I=-16:LRA=11:TP=-1.5',
  '-c:v libx264',
  '-c:a aac',
  '1920x1080',
  'task_audio_duration',
  'task_output_duration',
]) {
  if (!renderer.includes(token)) failures.push(`Renderer is missing required contract: ${token}`);
}

try {
  accessSync(join(tutorialDir, 'render-tutorial.sh'), constants.X_OK);
} catch {
  failures.push('render-tutorial.sh is not executable');
}

console.log(details.join('\n'));
if (failures.length > 0) {
  console.error(`\nTutorial library verification failed (${failures.length}):`);
  for (const failure of failures) console.error(`- ${failure}`);
  process.exitCode = 1;
} else {
  console.log('\nTutorial library verification passed: 33 complete narrated tutorials.');
}

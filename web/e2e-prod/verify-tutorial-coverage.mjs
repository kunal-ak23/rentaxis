#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.dirname(fileURLToPath(import.meta.url));
const testsDir = path.join(root, 'tests');
const readmePath = path.join(root, 'README.md');
const coveragePath = path.join(root, 'tutorial-coverage.json');

const failures = [];
const coverage = JSON.parse(fs.readFileSync(coveragePath, 'utf8'));
const readme = fs.readFileSync(readmePath, 'utf8');
const specNames = fs
  .readdirSync(testsDir)
  .filter((name) => name.endsWith('.spec.ts'))
  .map((name) => name.replace(/\.spec\.ts$/, ''))
  .sort();
const functionalSpecs = specNames.filter((name) => name !== '99-cleanup');

if (!Array.isArray(coverage)) {
  failures.push('tutorial-coverage.json must contain an array');
}

const expectedTutorials = Array.from({ length: 28 }, (_, index) => index + 1);
const tutorialNumbers = coverage.map((entry) => entry.tutorial);
if (JSON.stringify(tutorialNumbers) !== JSON.stringify(expectedTutorials)) {
  failures.push('tutorial entries must be ordered exactly from 1 through 28');
}

const referencedSpecs = new Set();
for (const entry of coverage) {
  const label = `tutorial ${String(entry.tutorial).padStart(2, '0')}`;
  if (typeof entry.title !== 'string' || entry.title.trim() === '') {
    failures.push(`${label} must have a title`);
  }
  if (entry.status !== 'prepared' && entry.status !== 'production-passed') {
    failures.push(`${label} has unsupported status ${JSON.stringify(entry.status)}`);
  }
  if (!Array.isArray(entry.specs) || entry.specs.length === 0) {
    failures.push(`${label} must reference at least one production spec`);
  }
  if (!Array.isArray(entry.artifactGaps) || !Array.isArray(entry.manualChecks)) {
    failures.push(`${label} must declare artifactGaps and manualChecks arrays`);
  }
  for (const spec of entry.specs ?? []) {
    referencedSpecs.add(spec);
    if (!functionalSpecs.includes(spec)) {
      failures.push(`${label} references missing or non-functional spec ${spec}`);
    }
  }
}

for (const spec of functionalSpecs) {
  if (!referencedSpecs.has(spec)) {
    failures.push(`functional spec ${spec}.spec.ts is not mapped to a tutorial`);
  }
}

const readmeSpecs = [...readme.matchAll(/^\| `([^`]+)` \|/gm)].map((match) => match[1]).sort();
if (JSON.stringify(readmeSpecs) !== JSON.stringify(specNames)) {
  const missing = specNames.filter((name) => !readmeSpecs.includes(name));
  const stale = readmeSpecs.filter((name) => !specNames.includes(name));
  if (missing.length) failures.push(`README is missing specs: ${missing.join(', ')}`);
  if (stale.length) failures.push(`README lists stale specs: ${stale.join(', ')}`);
}

const artifactGated = coverage.filter((entry) => entry.artifactGaps.length > 0);
const manual = coverage.filter((entry) => entry.manualChecks.length > 0);
const passed = coverage.filter((entry) => entry.status === 'production-passed');

if (failures.length > 0) {
  console.error('Production tutorial coverage verification failed:');
  for (const failure of failures) console.error(`- ${failure}`);
  process.exit(1);
}

console.log(
  `Production tutorial coverage structure passed: ${coverage.length}/28 web tutorials, ` +
    `${functionalSpecs.length}/${functionalSpecs.length} functional specs, and ` +
    `${specNames.length}/${specNames.length} README spec entries mapped.`,
);
console.log(
  `Evidence status: ${passed.length}/28 production-passed; ` +
    `${artifactGated.length} tutorials retain artifact gates; ` +
    `${manual.length} retain controlled manual checks.`,
);

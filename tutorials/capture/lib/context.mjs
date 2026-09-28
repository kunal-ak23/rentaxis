// Run context for one recorder process: command line, environment, paths and
// the seed manifest. Every other module reads its configuration from here, so
// a scenario module never parses argv or env itself. One process records one
// tutorial, which is why this can be a plain module-level singleton.

import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';

const DEFAULT_BASE_URL = 'https://rentaxis.uaenorth.cloudapp.azure.com';

function usage() {
  console.error(
    'Usage: node tutorials/capture/record-tutorial.mjs <tutorial-id> <narration.txt> <output.webm> [speech-rate]',
  );
  process.exit(2);
}

const [, , tutorialIdArg, narrationPathArg, outputPathArg, speechRateArg = '125'] = process.argv;
if (!tutorialIdArg || !narrationPathArg || !outputPathArg) usage();

export const tutorialId = tutorialIdArg;
export const repoRoot = path.resolve(import.meta.dirname, '..', '..', '..');
const requireFromWeb = createRequire(path.join(repoRoot, 'web', 'package.json'));
export const { chromium } = requireFromWeb('@playwright/test');
export const narrationPath = path.resolve(narrationPathArg);
export const outputPath = path.resolve(outputPathArg);
// Super-admin storage state. Production runs keep the historical default; a
// local recording stack passes its own (tutorials/.auth/, gitignored).
export const authStatePath = process.env.TUTORIAL_AUTH_STATE
  ? path.resolve(process.env.TUTORIAL_AUTH_STATE)
  : path.join(repoRoot, 'web', 'e2e-prod', '.auth', 'superadmin.json');
export const seedManifestPath = process.env.TUTORIAL_SEED_MANIFEST
  ? path.resolve(process.env.TUTORIAL_SEED_MANIFEST)
  : path.join(repoRoot, 'scripts', 'seed_tutorial_tenant.out.json');
export const baseURL = process.env.PROD_BASE_URL || DEFAULT_BASE_URL;
export const speechRate = Number(speechRateArg);
export const validateOnly = process.env.TUTORIAL_CAPTURE_VALIDATE_ONLY === '1';
export const qaDir = process.env.TUTORIAL_QA_DIR ? path.resolve(process.env.TUTORIAL_QA_DIR) : null;
const artifactRoot = process.env.TUTORIAL_ARTIFACTS_DIR
  ? path.resolve(process.env.TUTORIAL_ARTIFACTS_DIR)
  : path.join(repoRoot, 'tutorials');
export const rawRoot = process.env.TUTORIAL_RAW_DIR
  ? path.resolve(process.env.TUTORIAL_RAW_DIR)
  : path.join(artifactRoot, 'raw');
export const workRoot = process.env.TUTORIAL_WORK_DIR
  ? path.resolve(process.env.TUTORIAL_WORK_DIR)
  : path.join(artifactRoot, 'work');

if (!fs.existsSync(narrationPath)) throw new Error(`Narration not found: ${narrationPath}`);
if (!fs.existsSync(authStatePath)) {
  throw new Error(`Super-admin auth state is missing (${authStatePath}). Run the auth setup for this environment before recording.`);
}
export const seed = fs.existsSync(seedManifestPath)
  ? JSON.parse(fs.readFileSync(seedManifestPath, 'utf8'))
  : {};
export const tenantId = process.env.TUTORIAL_TENANT_ID || seed.tenant?.id;
export const tenantName = process.env.TUTORIAL_TENANT_NAME || seed.tenant?.name;
if (!tenantId || !tenantName) {
  throw new Error(`The tutorial tenant ID and name are missing from ${seedManifestPath}.`);
}
if (!Number.isInteger(speechRate) || speechRate < 80 || speechRate > 220) {
  throw new Error('Speech rate must be a whole number from 80 to 220 words per minute.');
}
export const tenantSlug = seed.tenant?.slug;
if (!tenantSlug) throw new Error(`The tutorial tenant slug is missing from ${seedManifestPath}.`);

/**
 * How long a navigation and the app-shell wait may take. The default is what
 * this recorder has always used; it is raised from the environment when the
 * machine is shared — a capture that dies on a 30-second navigation because
 * another suite is saturating the box wastes the whole take, and the seeded
 * state some of these tutorials consume with it.
 */
export const navTimeoutMs = Number(process.env.TUTORIAL_NAV_TIMEOUT_MS || 30_000);

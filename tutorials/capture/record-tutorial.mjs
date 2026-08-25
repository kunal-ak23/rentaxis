#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import { createRequire } from 'node:module';
import os from 'node:os';
import path from 'node:path';

const TUTORIAL_TENANT_ID = 'a5c3ad23-9abe-4435-a0b5-929559d516e7';
const TUTORIAL_TENANT_NAME = 'RentAxis Tutorial Demo';
const DEFAULT_BASE_URL = 'https://rentaxis.uaenorth.cloudapp.azure.com';

function usage() {
  console.error(
    'Usage: node tutorials/capture/record-tutorial.mjs <tutorial-id> <narration.txt> <output.webm> [speech-rate]',
  );
  process.exit(2);
}

const [, , tutorialId, narrationPathArg, outputPathArg, speechRateArg = '115'] = process.argv;
if (!tutorialId || !narrationPathArg || !outputPathArg) usage();

const repoRoot = path.resolve(import.meta.dirname, '..', '..');
const requireFromWeb = createRequire(path.join(repoRoot, 'web', 'package.json'));
const { chromium } = requireFromWeb('@playwright/test');
const narrationPath = path.resolve(narrationPathArg);
const outputPath = path.resolve(outputPathArg);
const authStatePath = path.join(repoRoot, 'web', 'e2e-prod', '.auth', 'superadmin.json');
const baseURL = process.env.PROD_BASE_URL || DEFAULT_BASE_URL;
const tenantId = process.env.TUTORIAL_TENANT_ID || TUTORIAL_TENANT_ID;
const speechRate = Number(speechRateArg);

if (!fs.existsSync(narrationPath)) throw new Error(`Narration not found: ${narrationPath}`);
if (!fs.existsSync(authStatePath)) {
  throw new Error('Production auth state is missing. Run the production auth setup before recording.');
}
if (!Number.isInteger(speechRate) || speechRate < 80 || speechRate > 220) {
  throw new Error('Speech rate must be a whole number from 80 to 220 words per minute.');
}

function narrationDurationSeconds() {
  const taskDir = fs.mkdtempSync(path.join(os.tmpdir(), 'rentaxis-tutorial-audio-'));
  const audioPath = path.join(taskDir, 'narration.aiff');
  try {
    execFileSync('say', [
      '-v',
      process.env.TUTORIAL_VOICE || 'Samantha',
      '-r',
      String(speechRate),
      '-f',
      narrationPath,
      '-o',
      audioPath,
    ]);
    const rawDuration = execFileSync(
      'ffprobe',
      [
        '-v',
        'error',
        '-show_entries',
        'format=duration',
        '-of',
        'default=noprint_wrappers=1:nokey=1',
        audioPath,
      ],
      { encoding: 'utf8' },
    ).trim();
    const duration = Number(rawDuration);
    if (!Number.isFinite(duration) || duration <= 0) {
      throw new Error('Speech synthesis produced an invalid narration duration.');
    }
    return duration;
  } finally {
    fs.rmSync(taskDir, { recursive: true, force: true });
  }
}

async function waitForApp(page) {
  await page.waitForLoadState('domcontentloaded');
  await page.locator('main').waitFor({ state: 'visible', timeout: 30_000 });
  await page.waitForTimeout(800);
}

async function addCallout(page, title, body) {
  await page.evaluate(
    ({ title, body }) => {
      document.querySelector('[data-rentaxis-tutorial-callout]')?.remove();
      const root = document.createElement('section');
      root.dataset.rentaxisTutorialCallout = 'true';
      root.setAttribute('aria-hidden', 'true');
      Object.assign(root.style, {
        position: 'fixed',
        left: '50%',
        bottom: '36px',
        transform: 'translateX(-50%)',
        zIndex: '2147483647',
        width: 'min(860px, calc(100vw - 72px))',
        padding: '18px 22px',
        borderRadius: '16px',
        border: '1px solid rgba(255,255,255,.28)',
        background: 'rgba(15, 23, 42, .94)',
        boxShadow: '0 18px 48px rgba(15,23,42,.35)',
        color: '#fff',
        fontFamily: 'Inter, ui-sans-serif, system-ui, sans-serif',
        pointerEvents: 'none',
      });
      const heading = document.createElement('div');
      heading.textContent = title;
      Object.assign(heading.style, { fontSize: '23px', fontWeight: '700', marginBottom: '5px' });
      const copy = document.createElement('div');
      copy.textContent = body;
      Object.assign(copy.style, { fontSize: '17px', lineHeight: '1.45', color: '#dbeafe' });
      root.append(heading, copy);
      document.body.append(root);
    },
    { title, body },
  );
}

async function clearCallout(page) {
  await page.evaluate(() => document.querySelector('[data-rentaxis-tutorial-callout]')?.remove());
}

async function goto(page, pathname) {
  await page.goto(`${baseURL}${pathname}`);
  await waitForApp(page);
}

const scenarios = {
  '20': [
    {
      title: 'Start with the operational dashboard',
      body: 'Use tenant-scoped KPIs as signals, then open the underlying report for detail.',
      run: (page) => goto(page, '/en/dashboard'),
    },
    {
      title: 'Open Financial Reports',
      body: 'Reports can be generated at organisation, property, or unit level.',
      run: (page) => goto(page, '/en/dashboard/finance/reports'),
    },
    {
      title: 'Profit & Loss',
      body: 'Review income and expenses for the selected period and reporting scope.',
      run: (page) => page.getByRole('button', { name: 'Profit & Loss', exact: true }).click(),
    },
    {
      title: 'Balance Sheet',
      body: 'Compare assets, liabilities, and equity at the chosen reporting date.',
      run: (page) => page.getByRole('button', { name: 'Balance Sheet', exact: true }).click(),
    },
    {
      title: 'Trial Balance',
      body: 'Use the trial balance to review debit and credit totals before deeper analysis.',
      run: (page) => page.getByRole('button', { name: 'Trial Balance', exact: true }).click(),
    },
    {
      title: 'Aging Report',
      body: 'Group overdue receivables into aging buckets so collection priorities are visible.',
      run: (page) => page.getByRole('button', { name: 'Aging Report', exact: true }).click(),
    },
    {
      title: 'VAT Return',
      body: 'Review VAT output and input figures for the configured period.',
      run: (page) => page.getByRole('button', { name: 'VAT Return', exact: true }).click(),
    },
    {
      title: 'Ticket Reporting',
      body: 'Use ticket reporting to understand workload, status, and operational trends.',
      run: (page) => page.getByRole('button', { name: 'Tickets', exact: true }).click(),
    },
    {
      title: 'Choose the correct scope',
      body: 'Confirm organisation or property scope and the date range before generating a report.',
      run: async (page) => {
        await page.getByRole('button', { name: 'Profit & Loss', exact: true }).click();
        await page.getByRole('button', { name: 'Org', exact: true }).click();
        await page.getByRole('button', { name: 'Generate Report', exact: true }).click();
        await page.waitForTimeout(1000);
      },
    },
  ],
};

const scenes = scenarios[tutorialId];
if (!scenes) throw new Error(`Tutorial ${tutorialId} does not have an automated capture scenario yet.`);

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
const videoDir = fs.mkdtempSync(path.join(os.tmpdir(), `rentaxis-tutorial-${tutorialId}-`));
const targetDuration = narrationDurationSeconds() + 2;
const holdPerScene = Math.max(2, targetDuration / scenes.length);
const browser = await chromium.launch({ headless: true });
let recordedVideoPath;

try {
  const context = await browser.newContext({
    baseURL,
    storageState: authStatePath,
    viewport: { width: 1920, height: 1080 },
    recordVideo: { dir: videoDir, size: { width: 1920, height: 1080 } },
    colorScheme: 'light',
    locale: 'en-AE',
  });
  const host = new URL(baseURL).hostname;
  await context.addCookies([
    {
      name: 'active_tenant_id',
      value: tenantId,
      domain: host,
      path: '/',
      httpOnly: false,
      secure: baseURL.startsWith('https:'),
      sameSite: 'Lax',
    },
  ]);
  const page = await context.newPage();
  const video = page.video();

  for (const scene of scenes) {
    const startedAt = Date.now();
    await scene.run(page);
    const activeTenantVisible = await page.getByText(TUTORIAL_TENANT_NAME, { exact: true }).first().isVisible();
    if (!activeTenantVisible) {
      throw new Error(`The active organisation is not ${TUTORIAL_TENANT_NAME}; recording stopped.`);
    }
    await addCallout(page, scene.title, scene.body);
    const elapsedSeconds = (Date.now() - startedAt) / 1000;
    await page.waitForTimeout(Math.max(500, (holdPerScene - elapsedSeconds) * 1000));
    await clearCallout(page);
  }

  await page.waitForTimeout(1200);
  await page.close();
  recordedVideoPath = await video.path();
  await context.close();
} finally {
  await browser.close();
}

if (!recordedVideoPath || !fs.existsSync(recordedVideoPath)) {
  throw new Error('Playwright did not produce a tutorial video.');
}
fs.copyFileSync(recordedVideoPath, outputPath);
fs.rmSync(videoDir, { recursive: true, force: true });
console.log(`tutorial=${tutorialId}`);
console.log(`tenant=${TUTORIAL_TENANT_NAME}`);
console.log(`target_duration=${targetDuration.toFixed(2)}`);
console.log(`silent_video=${outputPath}`);

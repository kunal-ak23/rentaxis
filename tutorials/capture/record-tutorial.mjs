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

const [, , tutorialId, narrationPathArg, outputPathArg, speechRateArg = '125'] = process.argv;
if (!tutorialId || !narrationPathArg || !outputPathArg) usage();

const repoRoot = path.resolve(import.meta.dirname, '..', '..');
const requireFromWeb = createRequire(path.join(repoRoot, 'web', 'package.json'));
const { chromium } = requireFromWeb('@playwright/test');
const narrationPath = path.resolve(narrationPathArg);
const outputPath = path.resolve(outputPathArg);
const authStatePath = path.join(repoRoot, 'web', 'e2e-prod', '.auth', 'superadmin.json');
const seedManifestPath = path.join(repoRoot, 'scripts', 'seed_tutorial_tenant.out.json');
const baseURL = process.env.PROD_BASE_URL || DEFAULT_BASE_URL;
const tenantId = process.env.TUTORIAL_TENANT_ID || TUTORIAL_TENANT_ID;
const speechRate = Number(speechRateArg);

if (!fs.existsSync(narrationPath)) throw new Error(`Narration not found: ${narrationPath}`);
if (!fs.existsSync(authStatePath)) {
  throw new Error('Production auth state is missing. Run the production auth setup before recording.');
}
const seed = fs.existsSync(seedManifestPath)
  ? JSON.parse(fs.readFileSync(seedManifestPath, 'utf8'))
  : {};
if (!Number.isInteger(speechRate) || speechRate < 80 || speechRate > 220) {
  throw new Error('Speech rate must be a whole number from 80 to 220 words per minute.');
}

function narrationDurationSeconds() {
  if ((process.env.TUTORIAL_TTS_PROVIDER || 'openai') !== 'mac') {
    const disclosure =
      process.env.TUTORIAL_AI_VOICE_DISCLOSURE || 'This tutorial uses an AI-generated voice.';
    const narration = fs.readFileSync(narrationPath, 'utf8').trim();
    const wordCount = `${disclosure} ${narration}`.trim().split(/\s+/).length;
    return (wordCount / speechRate) * 60 * 1.08 + 4;
  }
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

function routeScene(pathname, title, body, afterNavigation) {
  return {
    title,
    body,
    run: async (page) => {
      await goto(page, pathname);
      if (afterNavigation) await afterNavigation(page);
    },
  };
}

const towerId = seed.properties?.tower;
const ahmedLeaseId = seed.leases?.ahmed;
const saraLeaseId = seed.leases?.sara;
const ticketId = seed.ticketId;
const meetingId = seed.meetings?.['Replacement cheque — Fatima Al Zaabi (A-102)'];
const listingId = seed.listings?.['Bright 2BR in Al Barsha'];

const scenarios = {
  '04': [
    routeScene('/en/superadmin/tenants', 'Organisation administration', 'Search, provision, and review isolated customer organisations from one controlled list.'),
    routeScene('/en/superadmin/tenants', 'Tutorial Demo tenant', 'The recording tenant uses synthetic legal and contact data and remains separate from customer records.', async (page) => {
      await page.getByPlaceholder('Search...').fill(TUTORIAL_TENANT_NAME);
    }),
    routeScene('/en/superadmin/tenants', 'Feature access', 'Listings, Meetings, Email Notifications, Lease Renewals, and Gate Pass are enabled per organisation.', async (page) => {
      const row = page.getByRole('row').filter({ hasText: TUTORIAL_TENANT_NAME });
      await row.getByRole('button', { name: 'Feature Toggles' }).click();
    }),
    routeScene('/en/dashboard', 'Verify the tenant context', 'After switching, confirm the organisation name before creating or editing records.'),
  ],
  '06': [
    routeScene('/en/dashboard/properties', 'Property portfolio', 'Projects and properties provide the foundation for units, leases, operations, and reporting.'),
    routeScene(`/en/dashboard/properties/${towerId}`, 'Prepared residential tower', 'Review bilingual identity, address, emirate, portfolio type, and operational summary.'),
    routeScene('/en/dashboard/properties', 'Card and table views', 'Use cards for visual scanning and tables for compact portfolio comparison.'),
  ],
  '07': [
    routeScene(`/en/dashboard/properties/${towerId}`, 'Property operations', 'A property can contain buildings, units, contacts, amenities, and parking inventory.'),
    routeScene(`/en/dashboard/properties/${towerId}`, 'Buildings', 'Buildings organise floors and provide an optional scope for resident facilities.', async (page) => page.getByRole('button', { name: /^buildings$/i }).click()),
    routeScene(`/en/dashboard/properties/${towerId}`, 'Units', 'Unit status and rent expectations connect portfolio data to leasing.', async (page) => page.getByRole('button', { name: /^units$/i }).click()),
    routeScene(`/en/dashboard/properties/${towerId}`, 'Amenities', 'Bookable amenities can be property-wide or restricted to selected buildings.', async (page) => page.getByRole('button', { name: /^amenities$/i }).click()),
    routeScene(`/en/dashboard/properties/${towerId}`, 'Parking', 'Parking spots retain level, coverage, availability, and booking state.', async (page) => page.getByRole('button', { name: /^parking$/i }).click()),
  ],
  '09': [
    routeScene('/en/dashboard/renters', 'Renter directory', 'Search renter profiles and confirm portal-account status before leasing.'),
    routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Linked tenancy', 'Renter, unit, payment schedule, and active lease status remain connected.'),
    routeScene('/en/dashboard/renters', 'Portal access', 'Create or reset access through authorised administration without exposing credentials in recordings.'),
  ],
  '10': [
    routeScene('/en/dashboard/leases', 'Lease workspace', 'Start from a vacant unit and a verified renter, then draft the commercial terms.'),
    routeScene(`/en/dashboard/leases/${saraLeaseId}`, 'Pending-signature lease', 'Review rent, deposit, dates, payment method, and installment distribution before activation.'),
    routeScene(`/en/dashboard/leases/${saraLeaseId}`, 'Payment-plan preview', 'Confirm every schedule row before generating or signing the tenancy contract.'),
  ],
  '15': [
    routeScene('/en/dashboard/finance/payments', 'Rent cheque operations', 'Use the payment workspace to follow pending, collected, deposited, cleared, and bounced cheques.'),
    routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Lease payment schedule', 'Each installment retains its amount, due date, method, cheque metadata, and state.'),
    routeScene('/en/dashboard/finance/payments', 'Operational follow-up', 'Filter by the current cheque state before collecting, depositing, clearing, or marking a failure.'),
  ],
  '18': [
    routeScene('/en/dashboard/finance/transactions', 'Financial transactions', 'Review journal activity generated by rent, deposits, charges, expenses, and corrections.'),
    routeScene('/en/dashboard/finance/transactions', 'Transaction scope', 'Use dates, account filters, property scope, and description search to find an entry.'),
    routeScene('/en/dashboard/finance/reports', 'Reconcile through reports', 'Move from individual postings to trial balance and other period reports.'),
  ],
  '19': [
    routeScene('/en/dashboard/finance/vendors', 'Vendor directory', 'Keep supplier identity and contact records separate from financial transactions.'),
    routeScene('/en/dashboard/finance/transactions', 'Vendor-linked expenses', 'Split a single supplier invoice across properties or units when allocation is required.'),
    routeScene('/en/dashboard/finance/bank-accounts', 'Bank accounts', 'Maintain approved property bank accounts and identify the default account clearly.'),
  ],
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
  '22': [
    routeScene('/en/dashboard/tickets', 'Maintenance tickets', 'Prioritise work using status, category, property, unit, and requester context.'),
    routeScene(`/en/dashboard/tickets/${ticketId}`, 'Ticket collaboration', 'The renter and property team share replies, assignment, ETA, and status history on one record.'),
    routeScene('/en/dashboard/tickets/reports', 'Ticket reporting', 'Review workload, closure counts, response performance, and satisfaction trends.'),
  ],
  '23': [
    routeScene('/en/dashboard/meetings', 'Meetings and visits', 'Schedule office visits, property viewings, and cheque-replacement appointments.'),
    routeScene(`/en/dashboard/meetings/${meetingId}`, 'Meeting detail', 'Confirm purpose, host, requester, property, unit, time slot, and current decision.'),
    routeScene('/en/dashboard/meetings', 'Calendar follow-up', 'Use status and date filters to find meetings that need approval or completion.'),
  ],
  '24': [
    routeScene('/en/dashboard/bookings', 'Booking requests', 'Property teams review resident amenity and parking requests from one queue.'),
    routeScene(`/en/dashboard/properties/${towerId}`, 'Bookable resources', 'Amenities and parking spots are configured at the property before residents can request them.', async (page) => page.getByRole('button', { name: /^amenities$/i }).click()),
    routeScene('/en/dashboard/bookings', 'Approve or reject', 'Check resource availability, preferred date, unit, and resident note before deciding.'),
  ],
  '25': [
    routeScene('/en/dashboard/gatepass', 'Gate-pass operations', 'Review resident passes, approvals, guard assignments, visitor policy, and walk-in registrations.'),
    routeScene('/en/dashboard/gatepass', 'Approval boundary', 'Tenant operators approve passes while guards see only their assigned properties and scanning tools.'),
    routeScene('/en/dashboard/gatepass', 'Entry and exit audit', 'Every successful or rejected scan contributes to the controlled security trail.'),
  ],
  '26': [
    routeScene('/en/dashboard/listings', 'Listing management', 'Draft, review, publish, unpublish, and archive marketplace listings from tenant administration.'),
    routeScene(`/en/dashboard/listings/${listingId}`, 'Listing detail', 'Review media, description, rent, availability, amenities, coordinates, and SEO before publishing.'),
    routeScene('/en/dashboard/listings', 'Publication state', 'Use clear state labels to separate private drafts from public marketplace inventory.'),
  ],
  '28': [
    routeScene('/en/dashboard/promotions', 'Promotions and offers', 'Manage participating businesses and targeted resident ads from one workspace.'),
    routeScene('/en/dashboard/promotions', 'Coupon configuration', 'Set bilingual copy, placement, dates, property targeting, CTA, code, and terms.'),
    routeScene('/en/dashboard/promotions', 'Engagement analytics', 'Impressions, clicks, and tap-through rate show whether a live promotion is being used.'),
  ],
};

const roleByTutorial = {
  '04': 'superadmin',
  '06': 'tenantAdmin',
  '07': 'tenantAdmin',
  '09': 'tenantAdmin',
  '10': 'tenantAdmin',
  '15': 'tenantAdmin',
  '18': 'tenantAdmin',
  '19': 'tenantAdmin',
  '20': 'superadmin',
  '22': 'tenantAdmin',
  '23': 'tenantAdmin',
  '24': 'tenantAdmin',
  '25': 'tenantAdmin',
  '26': 'tenantAdmin',
  '28': 'tenantAdmin',
};

const scenes = scenarios[tutorialId];
if (!scenes) throw new Error(`Tutorial ${tutorialId} does not have an automated capture scenario yet.`);

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
const videoDir = fs.mkdtempSync(path.join(os.tmpdir(), `rentaxis-tutorial-${tutorialId}-`));
const targetDuration = narrationDurationSeconds() + 2;
const holdPerScene = Math.max(2, targetDuration / scenes.length);
const browser = await chromium.launch({ headless: true });
let recordedVideoPath;

async function authenticatedStorageState(role) {
  if (role === 'superadmin') return authStatePath;
  const credentials = role === 'tenantAdmin' ? seed.adminLogin : null;
  if (!credentials?.email || !credentials?.password) {
    throw new Error(`The seed manifest does not include credentials for ${role}.`);
  }
  const loginContext = await browser.newContext({ baseURL });
  const loginPage = await loginContext.newPage();
  await loginPage.goto('/en/auth/login');
  await loginPage.locator('#login-email').fill(credentials.email);
  await loginPage.locator('#login-password').fill(credentials.password);
  await loginPage.getByRole('button', { name: /sign in|log in/i }).click();
  await loginPage.waitForURL(/\/en\/dashboard/, { timeout: 30_000 });
  const state = await loginContext.storageState();
  await loginContext.close();
  return state;
}

try {
  const role = roleByTutorial[tutorialId] || 'superadmin';
  const storageState = await authenticatedStorageState(role);
  const context = await browser.newContext({
    baseURL,
    storageState,
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

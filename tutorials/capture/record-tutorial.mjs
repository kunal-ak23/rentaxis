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
const validateOnly = process.env.TUTORIAL_CAPTURE_VALIDATE_ONLY === '1';

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
    const suppliedAudio = process.env.TUTORIAL_AUDIO_FILE;
    if (suppliedAudio && fs.existsSync(suppliedAudio)) {
      const rawDuration = execFileSync(
        'ffprobe',
        [
          '-v',
          'error',
          '-show_entries',
          'format=duration',
          '-of',
          'default=noprint_wrappers=1:nokey=1',
          suppliedAudio,
        ],
        { encoding: 'utf8' },
      ).trim();
      const duration = Number(rawDuration);
      if (!Number.isFinite(duration) || duration <= 0) {
        throw new Error(`The supplied narration audio has no usable duration: ${suppliedAudio}`);
      }
      return duration;
    }
    const narration = fs.readFileSync(narrationPath, 'utf8').trim();
    const spokenIntro = process.env.TUTORIAL_SPOKEN_INTRO?.trim();
    const wordCount = [spokenIntro, narration].filter(Boolean).join(' ').trim().split(/\s+/).length;
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

function routeScene(pathname, title, body, afterNavigation, verifyTenantContext = true) {
  return {
    title,
    body,
    verifyTenantContext,
    run: async (page) => {
      await goto(page, pathname);
      if (afterNavigation) await afterNavigation(page);
    },
  };
}

function publicRouteScene(pathname, title, body, afterNavigation) {
  return routeScene(pathname, title, body, afterNavigation, false);
}

function roleRouteScene(role, pathname, title, body, options = {}) {
  return {
    ...routeScene(
      pathname,
      title,
      body,
      options.afterNavigation,
      options.verifyTenantContext ?? role !== 'anonymous',
    ),
    role,
    weight: options.weight,
  };
}

const towerId = seed.properties?.tower;
const ahmedLeaseId = seed.leases?.ahmed;
const saraLeaseId = seed.leases?.sara;
const ticketId = seed.ticketId;
const meetingId = seed.meetings?.['Replacement cheque — Fatima Al Zaabi (A-102)'];
const listingId = seed.listings?.['Bright 2BR in Al Barsha'];
const tenantSlug = seed.tenant?.slug || 'rentaxis-tutorial-demo';

const scenarios = {
  '02': [
    routeScene('/en/dashboard', 'Operational dashboard', 'Read tenant-scoped KPIs as signals, then open the underlying workflow for detail.'),
    routeScene('/en/dashboard/notifications', 'Notifications', 'Review unread events, follow their related records, and acknowledge them without losing history.'),
    routeScene('/en/dashboard/help', 'Help Center', 'Search role-aware guidance whenever a workflow or safety rule needs clarification.'),
    routeScene('/en/dashboard/help/getting-started--roles-and-permissions', 'Role guidance', 'Help articles explain the approved responsibilities and boundaries for each RentAxis role.'),
  ],
  '03': [
    routeScene('/en/superadmin/users', 'Users and roles', 'Super administrators can review cross-organisation users and confirm each assigned role.'),
    routeScene('/en/superadmin/tenants', 'Organisation boundary', 'Switch organisations deliberately and confirm the selected customer before administering records.'),
    routeScene('/en/dashboard', 'Tenant-scoped navigation', 'The sidebar and actions change with the signed-in role and active organisation.'),
    routeScene('/en/dashboard/help/getting-started--roles-and-permissions', 'Permission reference', 'Use the role guide to compare Super Admin, Tenant Admin, Property Manager, Tenant User, Renter, and Security Guard access.'),
  ],
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
  '05': [
    routeScene('/en/superadmin/users', 'User administration', 'Create and edit organisation users with the minimum role needed for their work.'),
    routeScene('/en/dashboard/staff', 'Staff directory', 'Staff records hold employment context separately from application login access.'),
    routeScene(`/en/dashboard/properties/${towerId}`, 'Property assignment', 'Property managers should be assigned only to the properties they are responsible for.'),
    routeScene('/en/superadmin/users', 'Access review', 'Review role and assignment changes after saving, and remove obsolete access promptly.'),
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
  '08': [
    routeScene('/en/dashboard/properties', 'Portfolio import', 'Use the current workbook template for controlled onboarding and migration.', async (page) => {
      await page.getByRole('button', { name: 'Import Portfolio', exact: true }).click();
    }),
    routeScene('/en/dashboard/properties', 'Validate before import', 'Review required sheets, reference values, and row-level validation errors before creating records.', async (page) => {
      await page.getByRole('button', { name: 'Import Portfolio', exact: true }).click();
    }),
    routeScene('/en/dashboard/properties', 'Verify imported records', 'After a successful job, reconcile counts and inspect representative properties, units, renters, leases, and installments.'),
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
  '11': [
    routeScene(`/en/dashboard/leases/${saraLeaseId}`, 'Contract-ready lease', 'Confirm the renter, unit, dates, rent, deposit, and payment plan before generating a contract.'),
    routeScene(`/en/dashboard/leases/${saraLeaseId}`, 'Contract workspace', 'Preview and regenerate the current PDF from the reviewed lease terms.', async (page) => {
      await page.getByRole('button', { name: 'Contract', exact: true }).click();
    }),
    routeScene(`/en/dashboard/leases/${saraLeaseId}`, 'Signature state', 'A rejection returns the lease for correction; only the current regenerated contract should be accepted.', async (page) => {
      await page.getByRole('button', { name: 'Contract', exact: true }).click();
    }),
  ],
  '12': [
    routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Active lease overview', 'Review status, parties, unit, dates, rent, and deposit before administering an active tenancy.'),
    routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Payment schedule', 'Use the schedule as the authoritative installment and cheque timeline.', async (page) => {
      await page.getByRole('button', { name: 'Payment schedule', exact: true }).click();
    }),
    routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Documents', 'Keep approved tenancy attachments with the lease audit trail.', async (page) => {
      await page.getByRole('button', { name: 'Documents', exact: true }).click();
    }),
    routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Interactions', 'Record relevant renter communication without storing passwords or unrelated personal notes.', async (page) => {
      await page.getByRole('button', { name: 'Interactions', exact: true }).click();
    }),
  ],
  '13': [
    routeScene('/en/dashboard/leases', 'Lease lifecycle queue', 'Use status and date filters to identify renewal, extension, termination, and settlement work.'),
    routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Renew or extend', 'Review current terms and renter intent before changing the lease end date or renewal state.'),
    routeScene(`/en/dashboard/leases/${seed.leases?.rajesh}`, 'Terminate and settle', 'Preview outstanding balances, deductions, deposit application, and the final settlement before closing.'),
    routeScene('/en/dashboard/renter-portal/renewals', 'Renter renewal response', 'The resident renewal view records intent while the property team retains approval and closure control.', undefined, false),
  ],
  '14': [
    routeScene('/en/dashboard/settings/fines', 'Fine configuration', 'Configure supported cheque-failure reasons and penalty amounts before processing failures.'),
    routeScene('/en/dashboard/finance/payments', 'Cheque failure workflow', 'Choose the exact failure reason so the correct auditable penalty is generated.'),
    routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Lease penalties', 'Review assessed penalties, payment state, receipts, and related installment history.', async (page) => {
      await page.getByRole('button', { name: 'Penalties', exact: true }).click();
    }),
  ],
  '15': [
    routeScene('/en/dashboard/finance/payments', 'Rent cheque operations', 'Use the payment workspace to follow pending, collected, deposited, cleared, and bounced cheques.'),
    routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Lease payment schedule', 'Each installment retains its amount, due date, method, cheque metadata, and state.'),
    routeScene('/en/dashboard/finance/payments', 'Operational follow-up', 'Filter by the current cheque state before collecting, depositing, clearing, or marking a failure.'),
  ],
  '16': [
    routeScene('/en/dashboard/settings/gateway', 'Payment gateway configuration', 'Select the approved provider and test mode before entering credentials.'),
    routeScene('/en/dashboard/settings/gateway', 'Credential safety', 'Saved secrets remain masked; use the connection test without revealing credentials in recordings.'),
    routeScene('/en/dashboard/settings/rent-settings', 'Online rent settings', 'Property-level settings control readiness, payment instructions, and permitted collection behavior.'),
  ],
  '17': [
    routeScene('/en/dashboard/finance/accounts', 'Chart of Accounts', 'Review the seeded hierarchy before adding or editing a ledger account.'),
    routeScene('/en/dashboard/settings/account-mappings', 'Account mappings', 'Map rent, deposits, penalties, expenses, and other events to the intended ledger codes.'),
    routeScene('/en/dashboard/finance/transactions', 'Downstream ledger effect', 'Verify that operational events post through the configured accounts with traceable descriptions.'),
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
  '21': [
    publicRouteScene('/en/dashboard/renter-portal/payments', 'My payments', 'Residents review installment due dates, methods, status, and payment history from one schedule.'),
    publicRouteScene('/en/dashboard/renter-portal/payments', 'Receipts and history', 'Open completed items for receipts and retain failed or pending states for follow-up.'),
    publicRouteScene('/en/dashboard/renter-portal', 'Renter dashboard', 'Online payment appears only when the organisation and property have an active supported gateway.'),
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
    roleRouteScene('tenantAdmin', `/en/dashboard/properties/${towerId}`, 'Bookable inventory', 'Confirm that amenity and parking inventory is active, available, and correctly scoped before residents request it.', {
      weight: 57,
      afterNavigation: async (page) => page.getByRole('button', { name: /^amenities$/i }).click(),
    }),
    roleRouteScene('renter', '/en/dashboard/renter-portal/facilities', 'Submit facility requests', 'Residents review availability and instructions, then create separate amenity and parking requests.', {
      weight: 56,
      verifyTenantContext: false,
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/bookings', 'Review and approve', 'Managers check the renter, unit, requested period, capacity, conflicts, and notes before deciding.', {
      weight: 54,
    }),
    roleRouteScene('renter', '/en/dashboard/renter-portal/facilities', 'Track, cancel, or release', 'Residents can confirm approval, cancel a future booking, or release an allocation without deleting history.', {
      weight: 55,
      verifyTenantContext: false,
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/properties/${towerId}`, 'Restored availability', 'After cancellation or release, return to inventory and confirm that the resource is available again.', {
      weight: 45,
      afterNavigation: async (page) => page.getByRole('button', { name: /^parking$/i }).click(),
    }),
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
  '27': [
    publicRouteScene(`/en/marketplace/${tenantSlug}`, 'Public marketplace', 'Browse published inventory without exposing private drafts or tenant administration.'),
    publicRouteScene(`/en/marketplace/${tenantSlug}`, 'Search and filters', 'Narrow listings by location, property type, rent, bedrooms, and availability.'),
    publicRouteScene('/en/marketplace/wishlist', 'Wishlist', 'Signed-in visitors can retain selected listings and remove them when no longer relevant.'),
    publicRouteScene('/en/auth/register', 'Visitor registration', 'Create an account before expressing interest or synchronising a wishlist across devices.'),
    publicRouteScene('/en/privacy', 'Privacy and legal information', 'Review privacy, terms, and data-deletion guidance before submitting personal information.'),
  ],
  '28': [
    routeScene('/en/dashboard/promotions', 'Promotions and offers', 'Manage participating businesses and targeted resident ads from one workspace.'),
    routeScene('/en/dashboard/promotions', 'Coupon configuration', 'Set bilingual copy, placement, dates, property targeting, CTA, code, and terms.'),
    routeScene('/en/dashboard/promotions', 'Engagement analytics', 'Impressions, clicks, and tap-through rate show whether a live promotion is being used.'),
  ],
};

const roleByTutorial = {
  '02': 'superadmin',
  '03': 'superadmin',
  '04': 'superadmin',
  '05': 'superadmin',
  '06': 'tenantAdmin',
  '07': 'tenantAdmin',
  '08': 'tenantAdmin',
  '09': 'tenantAdmin',
  '10': 'tenantAdmin',
  '11': 'tenantAdmin',
  '12': 'tenantAdmin',
  '13': 'tenantAdmin',
  '14': 'tenantAdmin',
  '15': 'tenantAdmin',
  '16': 'tenantAdmin',
  '17': 'tenantAdmin',
  '18': 'tenantAdmin',
  '19': 'tenantAdmin',
  '20': 'superadmin',
  '21': 'renter',
  '22': 'tenantAdmin',
  '23': 'tenantAdmin',
  '24': 'tenantAdmin',
  '25': 'tenantAdmin',
  '26': 'tenantAdmin',
  '27': 'anonymous',
  '28': 'tenantAdmin',
};

const scenes = scenarios[tutorialId];
if (!scenes) throw new Error(`Tutorial ${tutorialId} does not have an automated capture scenario yet.`);

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
const videoDir = fs.mkdtempSync(path.join(os.tmpdir(), `rentaxis-tutorial-${tutorialId}-`));
const targetDuration = narrationDurationSeconds() + 2;
const sceneWeights = scenes.map((scene) => {
  const weight = Number(scene.weight ?? 1);
  return Number.isFinite(weight) && weight > 0 ? weight : 1;
});
const totalSceneWeight = sceneWeights.reduce((total, weight) => total + weight, 0);
const browser = await chromium.launch({ headless: true });
const recordedVideoPaths = [];
const storageStateByRole = new Map();

async function authenticatedStorageState(role) {
  if (storageStateByRole.has(role)) return storageStateByRole.get(role);
  if (role === 'superadmin') {
    storageStateByRole.set(role, authStatePath);
    return authStatePath;
  }
  if (role === 'anonymous') {
    storageStateByRole.set(role, undefined);
    return undefined;
  }
  const credentials = role === 'tenantAdmin'
    ? seed.adminLogin
    : role === 'renter'
      ? seed.renterLogins?.find((login) => login.name === 'Ahmed Hassan') || seed.renterLogins?.[0]
      : null;
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
  storageStateByRole.set(role, state);
  return state;
}

try {
  const defaultRole = roleByTutorial[tutorialId] || 'superadmin';
  const host = new URL(baseURL).hostname;

  for (const [sceneIndex, scene] of scenes.entries()) {
    const role = scene.role || defaultRole;
    const storageState = await authenticatedStorageState(role);
    const contextOptions = {
      baseURL,
      storageState,
      viewport: { width: 1920, height: 1080 },
      colorScheme: 'light',
      locale: 'en-AE',
    };
    if (!validateOnly) {
      contextOptions.recordVideo = { dir: videoDir, size: { width: 1920, height: 1080 } };
    }
    const context = await browser.newContext(contextOptions);
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
    const video = validateOnly ? null : page.video();

    try {
      const startedAt = Date.now();
      await scene.run(page);
      if (scene.verifyTenantContext !== false) {
        const activeTenantVisible = await page.getByText(TUTORIAL_TENANT_NAME, { exact: true }).first().isVisible();
        if (!activeTenantVisible) {
          throw new Error(`The active organisation is not ${TUTORIAL_TENANT_NAME}; recording stopped.`);
        }
      }
      await addCallout(page, scene.title, scene.body);
      const sceneDuration = validateOnly
        ? 0.5
        : targetDuration * (sceneWeights[sceneIndex] / totalSceneWeight);
      const elapsedSeconds = (Date.now() - startedAt) / 1000;
      await page.waitForTimeout(Math.max(500, (sceneDuration - elapsedSeconds) * 1000));
      await clearCallout(page);
      await page.waitForTimeout(validateOnly ? 100 : 400);
    } finally {
      await page.close();
      if (video) recordedVideoPaths.push(await video.path());
      await context.close();
    }
  }
} finally {
  await browser.close();
}

if (validateOnly) {
  fs.rmSync(videoDir, { recursive: true, force: true });
  console.log(`tutorial=${tutorialId}`);
  console.log(`tenant=${TUTORIAL_TENANT_NAME}`);
  console.log('scenario_validation=passed');
} else {
  if (recordedVideoPaths.length !== scenes.length || recordedVideoPaths.some((videoPath) => !fs.existsSync(videoPath))) {
    throw new Error(`Playwright produced ${recordedVideoPaths.length} clips for ${scenes.length} tutorial scenes.`);
  }
  if (recordedVideoPaths.length === 1) {
    fs.copyFileSync(recordedVideoPaths[0], outputPath);
  } else {
    const concatPath = path.join(videoDir, 'concat.txt');
    fs.writeFileSync(
      concatPath,
      recordedVideoPaths.map((videoPath) => `file '${videoPath.replaceAll("'", "'\\''")}'`).join('\n'),
    );
    execFileSync('ffmpeg', [
      '-hide_banner',
      '-loglevel',
      'error',
      '-y',
      '-f',
      'concat',
      '-safe',
      '0',
      '-i',
      concatPath,
      '-c',
      'copy',
      outputPath,
    ]);
  }
  fs.rmSync(videoDir, { recursive: true, force: true });
  console.log(`tutorial=${tutorialId}`);
  console.log(`tenant=${TUTORIAL_TENANT_NAME}`);
  console.log(`target_duration=${targetDuration.toFixed(2)}`);
  console.log(`silent_video=${outputPath}`);
}

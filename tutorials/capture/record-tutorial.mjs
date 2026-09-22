#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
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

const [, , tutorialId, narrationPathArg, outputPathArg, speechRateArg = '125'] = process.argv;
if (!tutorialId || !narrationPathArg || !outputPathArg) usage();

const repoRoot = path.resolve(import.meta.dirname, '..', '..');
const requireFromWeb = createRequire(path.join(repoRoot, 'web', 'package.json'));
const { chromium } = requireFromWeb('@playwright/test');
const narrationPath = path.resolve(narrationPathArg);
const outputPath = path.resolve(outputPathArg);
const authStatePath = path.join(repoRoot, 'web', 'e2e-prod', '.auth', 'superadmin.json');
const seedManifestPath = process.env.TUTORIAL_SEED_MANIFEST
  ? path.resolve(process.env.TUTORIAL_SEED_MANIFEST)
  : path.join(repoRoot, 'scripts', 'seed_tutorial_tenant.out.json');
const baseURL = process.env.PROD_BASE_URL || DEFAULT_BASE_URL;
const speechRate = Number(speechRateArg);
const validateOnly = process.env.TUTORIAL_CAPTURE_VALIDATE_ONLY === '1';
const qaDir = process.env.TUTORIAL_QA_DIR ? path.resolve(process.env.TUTORIAL_QA_DIR) : null;
const artifactRoot = process.env.TUTORIAL_ARTIFACTS_DIR
  ? path.resolve(process.env.TUTORIAL_ARTIFACTS_DIR)
  : path.join(repoRoot, 'tutorials');
const rawRoot = process.env.TUTORIAL_RAW_DIR
  ? path.resolve(process.env.TUTORIAL_RAW_DIR)
  : path.join(artifactRoot, 'raw');
const workRoot = process.env.TUTORIAL_WORK_DIR
  ? path.resolve(process.env.TUTORIAL_WORK_DIR)
  : path.join(artifactRoot, 'work');

if (!fs.existsSync(narrationPath)) throw new Error(`Narration not found: ${narrationPath}`);
if (!fs.existsSync(authStatePath)) {
  throw new Error('Production auth state is missing. Run the production auth setup before recording.');
}
const seed = fs.existsSync(seedManifestPath)
  ? JSON.parse(fs.readFileSync(seedManifestPath, 'utf8'))
  : {};
const tenantId = process.env.TUTORIAL_TENANT_ID || seed.tenant?.id;
const tenantName = process.env.TUTORIAL_TENANT_NAME || seed.tenant?.name;
if (!tenantId || !tenantName) {
  throw new Error(`The tutorial tenant ID and name are missing from ${seedManifestPath}.`);
}
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
  // Keep capture artifacts under tutorials/ so interrupted work is reviewable
  // and recoverable. System temporary folders are routinely purged.
  fs.mkdirSync(workRoot, { recursive: true });
  const taskDir = fs.mkdtempSync(path.join(workRoot, `timing-${tutorialId}-`));
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
    // Retain timing audio alongside capture artifacts for reproducible review.
  }
}

async function waitForApp(page) {
  await page.waitForLoadState('domcontentloaded', { timeout: 30_000 }).catch(() => {});
  await Promise.race([
    page.locator('main').waitFor({ state: 'visible', timeout: 30_000 }),
    page.locator('#login-email').waitFor({ state: 'visible', timeout: 30_000 }),
    page.getByText('Create Account', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 }),
    page.getByText('Browse Properties', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 }),
    page.getByText('My Wishlist', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 }),
  ]);
  // Dashboard data can arrive a moment after the shell. Never freeze a
  // transient error state into a tutorial frame; wait for the retrying page to
  // recover, and fail the preflight if it remains genuinely unavailable.
  const dashboardError = page.getByText('Unable to load dashboard data', { exact: true });
  if (await dashboardError.isVisible().catch(() => false)) {
    await dashboardError.waitFor({ state: 'hidden', timeout: 30_000 });
  }
  const dashboardLoading = page.getByText('Loading dashboard...', { exact: true });
  if (await dashboardLoading.isVisible().catch(() => false)) {
    await dashboardLoading.waitFor({ state: 'hidden', timeout: 30_000 });
  }
  await page.waitForTimeout(800);
}

async function clearRecordingIntro(page) {
  await page.evaluate(() => document.querySelector('[data-rentaxis-recording-intro]')?.remove());
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
  // Production keeps a few long-lived resources open, so waiting for the
  // browser's full `load` event can time out after the usable app shell is
  // already ready. DOM readiness is sufficient; waitForApp handles the
  // role-specific content that follows.
  // Wait only for the document commit. The production shell keeps long-lived
  // resources open, so DOMContentLoaded can lag even after the app is usable.
  await page.goto(`${baseURL}${pathname}`, { waitUntil: 'commit', timeout: 30_000 });
  await waitForApp(page);
  await clearRecordingIntro(page);
  await applyCaptureStyles(page);
}

/**
 * Rules that hold for a whole scene rather than only for its closing hold.
 * Applying them from a scene body was not enough: a frame sampled while the
 * scene was still clicking showed what the hold had not yet hidden.
 *
 * - The Next.js development indicator (the "Compiling…" pill a local
 *   `next dev` pins to the bottom-left) is toolchain, not product. The element
 *   does not exist on a production build, so the rule is inert there.
 * - Capture policy (`tutorials/rentaxis-adapter.md`): a renter's phone number
 *   never appears in a frame. The lease overview prints the renter's email and
 *   phone in a card beside the charge lines, on a page short enough to fit a
 *   1080-tall frame whole — there is nowhere to scroll them to. Those two rows
 *   are hidden for the accounting tutorials, which are the ones that hold on
 *   that page. The renter's NAME stays: it is what names the contract.
 */
const PRIVACY_SENSITIVE_TUTORIALS = new Set(['34', '35', '36', '37']);

const CAPTURE_STYLE_RULES = [
  'nextjs-portal, [data-nextjs-toast], #__next-build-watcher { display: none !important; }',
  ...(PRIVACY_SENSITIVE_TUTORIALS.has(tutorialId)
    ? ['div.justify-between:has(> span > svg.lucide-mail),'
       + ' div.justify-between:has(> span > svg.lucide-phone) { visibility: hidden !important; }']
    : []),
].join('\n');

/**
 * Applied once the app shell is up, not from an init script: a style appended
 * before hydration does not survive it. Next replaces the head it streamed
 * when the client takes over, and the element is simply gone by the time the
 * page has rendered (measured against the lease overview). `addStyleTag` after
 * `waitForApp` lands after that swap and stays for the whole scene — the
 * window it does not cover is the loading shell, which has no data on it.
 */
async function applyCaptureStyles(page) {
  await page.addStyleTag({ content: CAPTURE_STYLE_RULES }).catch(() => {});
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
    allowTour: options.allowTour === true,
  };
}

const towerId = seed.properties?.tower;
const towerName = seed.properties?.towerName
  || process.env.TUTORIAL_TOWER_NAME
  || 'RentAxis Tutorial Residence Tower';
const ahmedLeaseId = seed.leases?.ahmed;
const saraLeaseId = seed.leases?.sara;
const ticketId = seed.ticketId;
const meetingId = seed.meetings?.['Replacement cheque — Fatima Al Zaabi (A-102)'];
const listingId = seed.listings?.['Bright 2BR in Al Barsha'];
const tenantSlug = seed.tenant?.slug;
const parkingSpotNumber = 'TUTORIAL-B2-18';
const bookingPreferredDate = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
if (!tenantSlug) throw new Error(`The tutorial tenant slug is missing from ${seedManifestPath}.`);

// ── accounting v2 (tutorials 34–37) ──────────────────────────────────────────
// The seed manifest is the contract between `scripts/seed_demo_tenant.py` and
// these scenarios: the lease ids above, the renter ids, and the register rows
// with the status the seed left each one in. Everything is read optionally —
// this module is evaluated for every tutorial, including the ones recorded
// against a manifest that predates these keys.
const fatimaRenterId = seed.renterIds?.fatima;
const advanceRentAccountId = seed.accounts?.tower?.ADVANCE_RENT;

/** A seeded register row by contract key and sequence number, or undefined. */
function chequeIdAt(leaseKey, seqNo) {
  return seed.cheques?.[leaseKey]?.find((row) => row.seqNo === seqNo)?.id;
}

/** Filled by tutorial 35's collection scene and read by the clearing scene. */
let bankedChequeIds = [];

const isoDate = (date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
/**
 * The furthest date recognition will accept. `RecognitionController#notInTheFuture`
 * rejects a `to` past today, and a period that has not ended has nothing to
 * close — so month-end close always means the end of LAST month, which is also
 * what the storyboard narration says out loud.
 */
const lastMonthEnd = () => {
  const now = new Date();
  return isoDate(new Date(now.getFullYear(), now.getMonth(), 0));
};
/** These contracts are dated 1 January; the ledger filters open on this month. */
const contractYearStart = () => `${new Date().getFullYear()}-01-01`;
const todayIso = () => isoDate(new Date());

async function suppressAutomaticOnboarding(context) {
  await context.addInitScript(() => {
    const key = 'rentaxis_tours_completed';
    let completed = [];
    try {
      completed = JSON.parse(localStorage.getItem(key) || '[]');
    } catch {
      completed = [];
    }
    if (!completed.includes('admin-onboarding')) completed.push('admin-onboarding');
    localStorage.setItem(key, JSON.stringify(completed));
  });
}

async function auditVisibleDialogContrast(page) {
  const failures = await page.evaluate(() => {
    const parseRgb = (value) => {
      const match = value.match(/rgba?\((\d+(?:\.\d+)?)[, ]+(\d+(?:\.\d+)?)[, ]+(\d+(?:\.\d+)?)(?:[, /]+(\d+(?:\.\d+)?))?\)/);
      if (!match) return null;
      return [Number(match[1]), Number(match[2]), Number(match[3]), match[4] == null ? 1 : Number(match[4])];
    };
    const luminance = ([red, green, blue]) => {
      const channels = [red, green, blue].map((channel) => {
        const value = channel / 255;
        return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
      });
      return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2];
    };
    const contrast = (foreground, background) => {
      const light = Math.max(luminance(foreground), luminance(background));
      const dark = Math.min(luminance(foreground), luminance(background));
      return (light + 0.05) / (dark + 0.05);
    };
    const visible = (element) => {
      const style = getComputedStyle(element);
      const rect = element.getBoundingClientRect();
      return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
    };
    const effectiveBackground = (element) => {
      for (let current = element; current; current = current.parentElement) {
        const color = parseRgb(getComputedStyle(current).backgroundColor);
        if (color && color[3] >= 0.95) return color;
      }
      return [255, 255, 255, 1];
    };

    const issues = [];
    for (const dialog of document.querySelectorAll('dialog[open], [role="dialog"]')) {
      if (!visible(dialog)) continue;
      const heading = dialog.querySelector('h1, h2, h3, [class*="title" i]');
      if (!heading || !visible(heading)) continue;
      const foreground = parseRgb(getComputedStyle(heading).color);
      const background = effectiveBackground(heading);
      if (!foreground || !background) continue;
      const ratio = contrast(foreground, background);
      if (ratio < 4.5) {
        issues.push(`${heading.textContent?.trim() || 'Untitled dialog'} (${ratio.toFixed(2)}:1)`);
      }
    }
    return issues;
  });
  if (failures.length > 0) {
    throw new Error(`Dialog contrast preflight failed: ${failures.join(', ')}`);
  }
}

async function signInWithCredentials(page, credentials) {
  await page.locator('#login-email').fill(credentials.email);
  await page.locator('#login-password').fill(credentials.password);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await page.waitForURL(/\/(en|ar)\/dashboard/, { timeout: 30_000 });
  await waitForApp(page);
}

async function openGlobalSearch(page, query) {
  await page.keyboard.press(process.platform === 'darwin' ? 'Meta+K' : 'Control+K');
  const dialog = page.getByRole('dialog', { name: /search/i });
  await dialog.waitFor({ state: 'visible' });
  await dialog.getByRole('textbox').fill(query);
  await dialog.getByRole('button').filter({ hasText: query }).first().waitFor({ state: 'visible', timeout: 30_000 });
}

async function openPreparedParkingRequest(page) {
  const card = page.getByText(parkingSpotNumber, { exact: true }).locator('../..');
  await card.getByRole('button', { name: 'Request', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: `Request ${parkingSpotNumber}` });
  await dialog.waitFor({ state: 'visible' });
  await dialog.locator('input[type="date"]').fill(bookingPreferredDate);
  await dialog.locator('textarea').fill('Tutorial parking request for a second family vehicle.');
  return dialog;
}

async function openPreparedBookingApproval(page) {
  const row = page.getByRole('row').filter({ hasText: parkingSpotNumber }).filter({ hasText: 'Ahmed Hassan' });
  await row.waitFor({ state: 'visible', timeout: 30_000 });
  await row.click();
  const drawer = page.getByRole('dialog', { name: 'Booking Request' });
  await drawer.waitFor({ state: 'visible' });
  await drawer.getByRole('textbox').fill('Approved for the tutorial resident after checking unit and availability.');
  return drawer;
}

const scenarios = {
  '01': [
    roleRouteScene('anonymous', '/en/auth/login', 'Sign in securely', 'Use only the account supplied by your administrator. On shared devices, do not save the password.', {
      weight: 48,
      verifyTenantContext: false,
    }),
    roleRouteScene('anonymous', '/en/auth/login', 'Authenticate', 'Enter the prepared account email and password, then select Sign In to open the role-appropriate landing page.', {
      weight: 42,
      verifyTenantContext: false,
      afterNavigation: async (page) => signInWithCredentials(page, seed.adminLogin),
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard', 'Role-aware navigation', 'Confirm the current organisation and review the menu RentAxis has made available for this account.', {
      weight: 57,
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard', 'Switch to Arabic', 'Use AR in the header. Labels change and the interface moves to a right-to-left layout.', {
      weight: 48,
      afterNavigation: async (page) => {
        await page.getByRole('link', { name: 'AR', exact: true }).click();
        await page.waitForURL(/\/ar\/dashboard/);
        await waitForApp(page);
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard', 'Open the account menu', 'Switch back to English, then use the profile menu for account settings and secure sign-out.', {
      weight: 38,
      afterNavigation: async (page) => {
        await page.locator('header button').filter({ hasText: 'Tenant Admin' }).last().click();
        await page.getByText('Update Profile', { exact: true }).waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/profile', 'Review your profile', 'Check your name, email, role, and phone. Save only intentional changes and never expose passwords in recordings.', {
      weight: 62,
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard', 'Log out safely', 'On a shared device, finish every session by opening the account menu and selecting Logout.', {
      weight: 41,
      verifyTenantContext: false,
      afterNavigation: async (page) => {
        await page.locator('header button').filter({ hasText: 'Tenant Admin' }).last().click();
        await page.getByRole('button', { name: 'Logout', exact: true }).click();
        await page.waitForURL(/\/auth\/login/, { timeout: 30_000 });
        await waitForApp(page);
      },
    }),
    roleRouteScene('anonymous', '/en/auth/login', 'Verify access', 'Sign in again when needed. The same secure navigation pattern applies throughout RentAxis.', {
      weight: 37,
      verifyTenantContext: false,
      afterNavigation: async (page) => signInWithCredentials(page, seed.adminLogin),
    }),
  ],
  '02': [
    roleRouteScene('superadmin', '/en/dashboard', 'Operational dashboard', 'Read tenant-scoped KPIs as signals, then open the underlying workflow or report for detail.', {
      weight: 75,
    }),
    roleRouteScene('superadmin', '/en/dashboard', 'Global search', 'Press Command K on macOS or Control K on Windows, then search for a known renter, lease, cheque, payment, or organisation.', {
      weight: 82,
      afterNavigation: async (page) => openGlobalSearch(page, 'Ahmed Hassan'),
    }),
    roleRouteScene('superadmin', '/en/dashboard', 'Open the prepared lease', 'Choose the tenant-scoped result and confirm RentAxis opens the correct lease rather than a generic results page.', {
      weight: 44,
      afterNavigation: async (page) => {
        await openGlobalSearch(page, 'Ahmed Hassan');
        await page.getByRole('dialog', { name: /search/i }).getByRole('button').filter({ hasText: 'Ahmed Hassan' }).first().click();
        await page.waitForURL(new RegExp(`/en/dashboard/leases/${ahmedLeaseId}`), { timeout: 30_000 });
        await waitForApp(page);
      },
    }),
    roleRouteScene('superadmin', '/en/dashboard', 'Notification preview', 'Use the unread indicator to review recent event details and follow a related record when one is available.', {
      weight: 72,
      afterNavigation: async (page) => {
        await page.locator('header button').filter({ has: page.locator('svg.lucide-bell') }).click();
        await page.getByText('Notifications', { exact: true }).waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('superadmin', '/en/dashboard/notifications', 'Notification history', 'Acknowledging an item removes it from the unread view while preserving it in full history.', {
      weight: 52,
    }),
    roleRouteScene('superadmin', '/en/dashboard/help', 'Search the Help Center', 'Find role-aware workflow and safety guidance without leaving the application.', {
      weight: 67,
      afterNavigation: async (page) => {
        await page.getByPlaceholder('Search help articles...').fill('Security Guard');
        await page.getByText('Roles & Permissions', { exact: true }).waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('superadmin', '/en/dashboard/help/getting-started--roles-and-permissions', 'Role guidance', 'Compare each role’s responsibilities with its available navigation and enabled features.', {
      weight: 58,
    }),
    roleRouteScene('superadmin', '/en/dashboard', 'Use the four-tool pattern', 'Dashboard totals identify work, search reaches records, notifications surface events, and Help explains the approved process.', {
      weight: 38,
      afterNavigation: async (page) => openGlobalSearch(page, tenantName),
    }),
  ],
  '03': [
    roleRouteScene('superadmin', '/en/dashboard', 'Super Admin organisation context', 'Open the organisation switcher and verify the customer context before administering any tenant record.', {
      weight: 72,
      afterNavigation: async (page) => {
        await page.getByRole('button', { name: 'Switch organization' }).click();
        await page.getByPlaceholder('Search organizations...').fill(tenantName);
        await page.getByRole('button', { name: tenantName }).waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard', 'Tenant Admin', 'Tenant administrators manage the enabled portfolio, leasing, finance, users, and settings for their own organisation.', {
      weight: 53,
    }),
    roleRouteScene('propertyManager', '/en/dashboard/properties', 'Property Manager', 'Property managers work only with their assigned properties; direct navigation must not bypass that boundary.', {
      weight: 58,
      verifyTenantContext: false,
      afterNavigation: async (page) => {
        await page.getByText(towerName, { exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/help/getting-started--roles-and-permissions', 'Tenant User', 'Use this more limited staff role for a defined operational surface without tenant-wide administration.', {
      weight: 34,
    }),
    roleRouteScene('renter', '/en/dashboard/renter-portal', 'Renter', 'Residents see their tenancy, payments, maintenance, meetings, services, and marketplace features—not internal administration.', {
      weight: 48,
      verifyTenantContext: false,
    }),
    // Security Guards authenticate with phone OTP through the guard/mobile
    // surface, not the web email/password form. Show the documented role
    // boundary here instead of attempting an invalid web login.
    roleRouteScene('tenantAdmin', '/en/dashboard/help/getting-started--roles-and-permissions', 'Security Guard', 'Guards use assigned-property visitor queues, scans, admissions, and exits without lease, finance, or tenant settings access.', {
      weight: 52,
    }),
    roleRouteScene('superadmin', '/en/dashboard', 'Return to the controlled context', 'Finish by checking the tenant, role, property assignment, and feature access before every sensitive action.', {
      weight: 49,
      afterNavigation: async (page) => {
        await page.getByRole('button', { name: 'Switch organization' }).click();
        await page.getByPlaceholder('Search organizations...').fill(tenantName);
        await page.getByRole('button', { name: tenantName }).waitFor({ state: 'visible' });
      },
    }),
  ],
  '04': [
    routeScene('/en/superadmin/tenants', 'Organisation administration', 'Search, provision, and review isolated customer organisations from one controlled list.', async (page) => {
      await page.getByPlaceholder('Search...').fill(tenantName);
    }),
    routeScene('/en/superadmin/tenants', 'Tutorial Demo tenant', 'The recording tenant uses synthetic legal and contact data and remains separate from customer records.', async (page) => {
      await page.getByPlaceholder('Search...').fill(tenantName);
    }),
    routeScene('/en/superadmin/tenants', 'Feature access', 'Listings, Meetings, Email Notifications, Lease Renewals, and Gate Pass are enabled per organisation.', async (page) => {
      await page.getByPlaceholder('Search...').fill(tenantName);
      const row = page.getByRole('row').filter({ hasText: tenantName });
      await row.getByRole('button', { name: 'Feature Toggles' }).click();
      await page.getByText('Listings (Marketplace)', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
    }),
    routeScene('/en/dashboard', 'Verify the tenant context', 'After switching, confirm the organisation name before creating or editing records.'),
  ],
  '05': [
    routeScene('/en/superadmin/users', 'User administration', 'Create and edit organisation users with the minimum role needed for their work.', async (page) => {
      await page.getByRole('button', { name: 'New User', exact: true }).click();
      await page.getByText('Provision New User', { exact: true }).last().waitFor({ state: 'visible' });
    }),
    routeScene('/en/dashboard/staff', 'Staff directory', 'Staff records hold employment context separately from application login access.', async (page) => {
      await page.getByRole('button', { name: 'Add Staff', exact: true }).click();
      await page.getByText('Add Staff', { exact: true }).last().waitFor({ state: 'visible' });
    }),
    routeScene(`/en/dashboard/properties/${towerId}`, 'Property assignment', 'Property managers should be assigned only to the properties they are responsible for.'),
    routeScene('/en/superadmin/users', 'Access review', 'Review role and assignment changes after saving, and remove obsolete access promptly.', async (page) => {
      await page.getByPlaceholder('Search users...').fill('manager@tutorial-studio.example.com');
    }),
  ],
  '06': [
    routeScene('/en/dashboard/properties', 'Property portfolio', 'Projects and properties provide the foundation for units, leases, operations, and reporting.', async (page) => {
      await page.getByRole('button', { name: 'Add Project', exact: true }).click();
      await page.getByText('Create a new Project (Portfolio Group).', { exact: true }).waitFor({ state: 'visible' });
    }),
    routeScene(`/en/dashboard/properties/${towerId}`, 'Prepared residential tower', 'Review bilingual identity, address, emirate, portfolio type, and operational summary.'),
    routeScene('/en/dashboard/properties', 'Card view', 'Use cards for visual scanning of property names, occupancy, and summary information.', async (page) => {
      await page.getByRole('button', { name: 'Cards', exact: true }).click();
    }),
    routeScene('/en/dashboard/properties', 'Table view', 'Use the table for compact comparison across a larger portfolio.', async (page) => {
      await page.getByRole('button', { name: 'Table', exact: true }).click();
    }),
  ],
  '07': [
    routeScene(`/en/dashboard/properties/${towerId}`, 'Property operations', 'A property can contain buildings, units, contacts, amenities, and parking inventory.'),
    routeScene(`/en/dashboard/properties/${towerId}`, 'Buildings', 'Buildings organise floors and provide an optional scope for resident facilities.', async (page) => {
      await page.getByRole('button', { name: /^buildings$/i }).click();
      await page.getByRole('button', { name: 'Add Building', exact: true }).click();
      await page.getByText('Add Building', { exact: true }).last().waitFor({ state: 'visible' });
    }),
    routeScene(`/en/dashboard/properties/${towerId}`, 'Units', 'Unit status and rent expectations connect portfolio data to leasing.', async (page) => {
      await page.getByRole('button', { name: /^units$/i }).click();
      await page.getByRole('button', { name: 'Add Unit', exact: true }).click();
      await page.getByText('Add Unit', { exact: true }).last().waitFor({ state: 'visible' });
    }),
    routeScene(`/en/dashboard/properties/${towerId}`, 'Contacts', 'Operations contacts keep emergency and maintenance details discoverable without mixing them with login accounts.', async (page) => {
      await page.getByRole('button', { name: 'Add Contact', exact: true }).click();
      await page.getByText('Add Contact', { exact: true }).last().waitFor({ state: 'visible' });
    }),
    routeScene(`/en/dashboard/properties/${towerId}`, 'Amenities', 'Bookable amenities can be property-wide or restricted to selected buildings.', async (page) => {
      await page.getByRole('button', { name: /^amenities$/i }).click();
      await page.getByRole('button', { name: 'Add Amenity', exact: true }).click();
      await page.getByText('Add Amenity', { exact: true }).last().waitFor({ state: 'visible' });
    }),
    routeScene(`/en/dashboard/properties/${towerId}`, 'Parking', 'Parking spots retain level, coverage, availability, and booking state.', async (page) => {
      await page.getByRole('button', { name: /^parking$/i }).click();
      await page.getByRole('button', { name: 'Add Spot', exact: true }).click();
      await page.getByText('Add Spot', { exact: true }).last().waitFor({ state: 'visible' });
    }),
  ],
  '08': [
    routeScene('/en/dashboard/properties', 'Portfolio import', 'Use the current workbook template for controlled onboarding and migration.', async (page) => {
      await page.getByRole('button', { name: 'Import Portfolio', exact: true }).click();
      await page.getByText('Import Portfolio', { exact: true }).last().waitFor({ state: 'visible' });
      await page.getByText('Download Template', { exact: true }).click();
    }),
    routeScene('/en/dashboard/properties', 'Validate before import', 'Review required sheets, reference values, and row-level validation errors before creating records.', async (page) => {
      await page.getByRole('button', { name: 'Import Portfolio', exact: true }).click();
      await page.getByText('Import Portfolio', { exact: true }).last().waitFor({ state: 'visible' });
    }),
    routeScene('/en/dashboard/properties', 'Verify imported records', 'After a successful job, reconcile counts and inspect representative properties, units, renters, leases, and installments.'),
  ],
  '09': [
    routeScene('/en/dashboard/renters', 'Renter directory', 'Search prepared renter profiles, then preview the create-renter form and portal-account option without saving a new record.', async (page) => {
      await page.getByRole('button', { name: 'Add Renter', exact: true }).click();
    }),
    routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Linked tenancy', 'Renter, unit, payment schedule, and active lease status remain connected.'),
    routeScene('/en/dashboard/renters', 'Portal access', 'Use the renter directory as the starting point for authorised portal access, while keeping credentials out of recordings.'),
  ],
  '10': [
    routeScene('/en/dashboard/leases', 'Lease workspace', 'Start from a vacant unit and a verified renter, then preview the draft-lease wizard without saving a new record.', async (page) => {
      await page.getByRole('button', { name: 'Draft Lease', exact: true }).click();
    }),
    routeScene(`/en/dashboard/leases/${saraLeaseId}`, 'Pending-signature lease', 'Review rent, deposit, dates, payment method, and installment distribution before activation.', async (page) => {
      await page.evaluate(() => { document.body.style.zoom = '85%'; });
    }),
    routeScene(`/en/dashboard/leases/${saraLeaseId}`, 'Payment-plan preview', 'Confirm every schedule row before generating or signing the tenancy contract.', async (page) => {
      await page.evaluate(() => { document.body.style.zoom = '85%'; });
      await page.waitForTimeout(250);
    }),
  ],
  '11': [
    routeScene(`/en/dashboard/leases/${saraLeaseId}`, 'Contract-ready lease', 'Confirm the renter, unit, dates, rent, deposit, and payment plan before reviewing the contract.'),
    routeScene(`/en/dashboard/leases/${saraLeaseId}`, 'Contract workspace', 'Use the authenticated Preview Contract control or its new-tab fallback to inspect the current agreement without changing the lease state.', async (page) => {
      await page.getByRole('button', { name: 'Contract', exact: true }).click();
    }),
    routeScene(`/en/dashboard/leases/${saraLeaseId}`, 'Signature state', 'Pending Signature keeps the agreement awaiting renter action; review the current document before any acceptance or rejection.', async (page) => {
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
      await page.getByText(/Loading/).waitFor({ state: 'detached', timeout: 10000 });
      await page.getByRole('button', { name: /Log interaction/i }).click();
    }),
  ],
  '13': [
    routeScene('/en/dashboard/leases', 'Lease lifecycle queue', 'Use status and date filters to identify renewal, extension, termination, and settlement work.'),
    routeScene(`/en/dashboard/leases/${ahmedLeaseId}`, 'Renew or extend', 'Review current terms and renter intent before changing the lease end date or renewal state.'),
    routeScene(`/en/dashboard/leases/${seed.leases?.rajesh}`, 'Terminate and settle', 'Open the settlement preview to review outstanding balances, deductions, deposit application, and the final settlement before closing.', async (page) => {
      await page.getByRole('link', { name: 'Terminate', exact: true }).click();
      await page.waitForURL(/\/settlement/, { waitUntil: 'domcontentloaded', timeout: 15000 });
      await page.getByRole('heading', { name: 'Settlement', exact: true }).waitFor({ state: 'visible', timeout: 15000 });
      await page.evaluate(() => { document.body.style.zoom = '85%'; });
    }),
    roleRouteScene('renter', '/en/dashboard/renter-portal/renewals', 'Renter renewal response', 'The resident renewal view records intent while the property team retains approval and closure control.', { verifyTenantContext: false }),
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
    routeScene('/en/dashboard/settings/rent-settings', 'Online rent settings', 'Property-level settings control readiness, payment instructions, and permitted collection behavior.', async (page) => {
      const propertySelect = page.locator('select').first();
      await propertySelect.locator('option').nth(1).waitFor({ state: 'attached', timeout: 15000 });
      await propertySelect.selectOption({ index: 1 });
      await page.getByText('Loading settings...', { exact: true }).waitFor({ state: 'detached', timeout: 15000 });
      await page.getByText(/Online payment|Rent due|Grace period/i).first().waitFor({ state: 'visible', timeout: 15000 });
    }),
  ],
  '17': [
    routeScene('/en/dashboard/finance/accounts', 'Chart of Accounts', 'Review the seeded hierarchy before adding or editing a ledger account.'),
    routeScene('/en/dashboard/settings/account-mappings', 'Account mappings', 'Map rent, deposits, penalties, expenses, and other events to the intended ledger codes.'),
    routeScene('/en/dashboard/finance/transactions', 'Downstream ledger effect', 'Verify that operational events post through the configured accounts with traceable descriptions.'),
  ],
  '18': [
    routeScene('/en/dashboard/finance/transactions', 'Financial transactions', 'Review journal activity generated by rent, deposits, charges, expenses, and corrections.'),
    routeScene('/en/dashboard/finance/transactions', 'Transaction scope', 'Use dates, account filters, property scope, and description search to find an entry.', async (page) => {
      await page.getByRole('button', { name: 'Filter', exact: true }).click();
      await page.locator('select').first().waitFor({ state: 'visible', timeout: 10000 });
    }),
    routeScene('/en/dashboard/finance/reports', 'Reconcile through reports', 'Move from individual postings to a generated profit-and-loss report.', async (page) => {
      await page.getByRole('button', { name: 'Generate Report', exact: true }).click();
      await page.getByText('Total Income', { exact: true }).first().waitFor({ state: 'visible', timeout: 15000 });
    }),
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
      run: async (page) => {
        await goto(page, '/en/dashboard/finance/reports');
        await page.getByRole('button', { name: 'Profit & Loss', exact: true }).click();
        await page.waitForTimeout(250);
      },
    },
    {
      title: 'Balance Sheet',
      body: 'Compare assets, liabilities, and equity at the chosen reporting date.',
      run: async (page) => {
        await goto(page, '/en/dashboard/finance/reports');
        await page.getByRole('button', { name: 'Balance Sheet', exact: true }).click();
        await page.waitForTimeout(250);
      },
    },
    {
      title: 'Trial Balance',
      body: 'Use the trial balance to review debit and credit totals before deeper analysis.',
      run: async (page) => {
        await goto(page, '/en/dashboard/finance/reports');
        await page.getByRole('button', { name: 'Trial Balance', exact: true }).click();
        await page.waitForTimeout(250);
      },
    },
    {
      title: 'Aging Report',
      body: 'Group overdue receivables into aging buckets so collection priorities are visible.',
      run: async (page) => {
        await goto(page, '/en/dashboard/finance/reports');
        await page.getByRole('button', { name: 'Aging Report', exact: true }).click();
        await page.waitForTimeout(250);
      },
    },
    {
      title: 'VAT Return',
      body: 'Review VAT output and input figures for the configured period.',
      run: async (page) => {
        await goto(page, '/en/dashboard/finance/reports');
        await page.getByRole('button', { name: 'VAT Return', exact: true }).click();
        await page.waitForTimeout(250);
      },
    },
    {
      title: 'Ticket Reporting',
      body: 'Use ticket reporting to understand workload, status, and operational trends.',
      run: async (page) => {
        await goto(page, '/en/dashboard/finance/reports');
        await page.getByRole('button', { name: 'Tickets', exact: true }).click();
        await page.waitForTimeout(250);
      },
    },
    {
      title: 'Choose the correct scope',
      body: 'Confirm organisation or property scope and the date range before generating a report.',
      run: async (page) => {
        await goto(page, '/en/dashboard/finance/reports');
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
    roleRouteScene('tenantAdmin', `/en/dashboard/properties/${towerId}`, 'Confirm bookable inventory', 'Before a resident requests anything, verify that the facility is active, bookable, available, and scoped to the correct property.', {
      weight: 43,
      afterNavigation: async (page) => page.getByRole('button', { name: /^parking$/i }).click(),
    }),
    roleRouteScene('renter', '/en/dashboard/renter-portal/facilities', 'Prepare a parking request', 'The resident checks the property, availability, unit, preferred date, and note before submitting the request.', {
      weight: 55,
      verifyTenantContext: false,
      afterNavigation: openPreparedParkingRequest,
    }),
    roleRouteScene('renter', '/en/dashboard/renter-portal/facilities', 'Submit once', 'After submission, the new request appears as Pending in My Requests and the parking spot remains unallocated until approval.', {
      weight: 42,
      verifyTenantContext: false,
      afterNavigation: async (page) => {
        const dialog = await openPreparedParkingRequest(page);
        await dialog.getByRole('button', { name: 'Submit Request', exact: true }).click();
        await dialog.waitFor({ state: 'hidden', timeout: 30_000 });
        await page.getByRole('row').filter({ hasText: parkingSpotNumber }).filter({ hasText: 'Pending' }).waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/bookings', 'Review the request', 'The manager opens the Pending request and verifies the renter, unit, resource, preferred date, conflicts, and note.', {
      weight: 52,
      afterNavigation: openPreparedBookingApproval,
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/bookings', 'Approve with an auditable note', 'Approve only after the checks are complete. The request changes to Approved and records the manager’s decision note.', {
      weight: 48,
      afterNavigation: async (page) => {
        const drawer = await openPreparedBookingApproval(page);
        await drawer.getByRole('button', { name: 'Approve', exact: true }).click();
        await drawer.getByText('Approved', { exact: true }).waitFor({ state: 'visible', timeout: 30_000 });
      },
    }),
    roleRouteScene('renter', '/en/dashboard/renter-portal/facilities', 'Confirm the allocation', 'Back in the renter account, the same request is Approved and the parking card is held for this resident.', {
      weight: 38,
      verifyTenantContext: false,
      afterNavigation: async (page) => {
        await page.getByRole('row').filter({ hasText: parkingSpotNumber }).filter({ has: page.getByText('Approved', { exact: true }) }).waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('renter', '/en/dashboard/renter-portal/facilities', 'Release the parking spot', 'When the allocation is no longer needed, release it deliberately. History remains visible while availability is restored.', {
      weight: 47,
      verifyTenantContext: false,
      afterNavigation: async (page) => {
        const row = page.getByRole('row').filter({ hasText: parkingSpotNumber }).filter({ has: page.getByText('Approved', { exact: true }) });
        await row.getByRole('button', { name: 'Release Spot', exact: true }).click();
        await page.getByText('Give up this parking spot? It becomes available to others.', { exact: true }).waitFor({ state: 'visible' });
        await page.getByRole('button', { name: 'Release Spot', exact: true }).last().click();
        await page.getByRole('row').filter({ hasText: parkingSpotNumber }).filter({ has: page.getByText('Released', { exact: true }) }).last().waitFor({ state: 'visible', timeout: 30_000 });
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/properties/${towerId}`, 'Verify restored availability', 'Return to the property inventory and confirm that the spot is Available, active, and ready for another request.', {
      weight: 39,
      afterNavigation: async (page) => page.getByRole('button', { name: /^parking$/i }).click(),
    }),
  ],
  '25': [
    routeScene('/en/dashboard/gatepass', 'Gate-pass operations', 'Review the Gate Pass Report filters, scan count, empty state, and audit columns before investigating an entry or exit.'),
    routeScene('/en/dashboard/gatepass', 'Scope by property', 'Use the property selector to narrow the gate audit to one building and verify the resulting scan count.', async (page) => {
      await page.locator('#gatepass-property').selectOption({ label: 'RentAxis Academy Residence Tower' });
      await page.waitForTimeout(450);
    }),
    routeScene('/en/dashboard/gatepass', 'Entry and exit audit', 'Adjust the date range when investigating a period, then export the filtered report when records are available.', async (page) => {
      await page.locator('#gatepass-from').fill('2026-01-01');
      await page.locator('#gatepass-to').fill('2026-12-31');
      await page.waitForTimeout(450);
    }),
  ],
  '26': [
    routeScene('/en/dashboard/listings', 'Listing management', 'Draft, review, publish, unpublish, and archive marketplace listings from tenant administration.'),
    routeScene(`/en/dashboard/listings/${listingId}`, 'Listing detail', 'Review media, description, rent, availability, amenities, coordinates, and SEO before publishing.'),
    routeScene('/en/dashboard/listings', 'Publication state', 'Use clear state labels to separate private drafts from public marketplace inventory.'),
  ],
  '27': [
    roleRouteScene('renter', `/en/marketplace/${tenantSlug}`, 'Renter marketplace', 'Browse published inventory without exposing tenant administration.', { verifyTenantContext: false }),
    roleRouteScene('renter', `/en/marketplace/${tenantSlug}`, 'Search and filters', 'Narrow listings by location, property type, rent, bedrooms, and availability.', { verifyTenantContext: false }),
    roleRouteScene('renter', '/en/marketplace/wishlist', 'Wishlist', 'Retain selected listings privately and remove them when they are no longer relevant.', { verifyTenantContext: false }),
    publicRouteScene('/en/auth/register', 'Visitor registration', 'Create an account before expressing interest or synchronising a wishlist across devices.'),
    publicRouteScene('/en/privacy', 'Privacy and legal information', 'Review privacy, terms, and data-deletion guidance before submitting personal information.'),
  ],
  '28': [
    routeScene('/en/dashboard/promotions', 'Promotions and offers', 'Manage participating businesses and targeted resident ads from one workspace.', async (page) => {
      await page.getByRole('tab', { name: 'Businesses', exact: true }).click();
      await page.waitForTimeout(350);
    }),
    routeScene('/en/dashboard/promotions', 'Coupon configuration', 'Set bilingual copy, placement, dates, property targeting, CTA, code, and terms.', async (page) => {
      await page.getByRole('tab', { name: 'Ads', exact: true }).click();
      await page.getByRole('button', { name: 'Edit', exact: true }).click();
    }),
    routeScene('/en/dashboard/promotions', 'Engagement analytics', 'Impressions, clicks, and tap-through rate show whether a live promotion is being used.', async (page) => {
      await page.locator('select[aria-label="Business"]').selectOption({ label: 'Tutorial Community Cafe' });
      await page.waitForTimeout(350);
    }),
  ],

  // ── Accounting v2 ──────────────────────────────────────────────────────────
  // 34 posts the draft the seed deliberately leaves unposted, so it records
  // first; 36 closes the periods that post opens up, and 37 reads the ledger
  // all three of them wrote. Selectors are the ones
  // `web/e2e/finance/accounting-v2.spec.ts` already proves against this build.
  '34': [
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${saraLeaseId}`, 'Open the draft contract',
      'A tenancy contract starts as a draft. Nothing it holds has reached the ledger yet, so it can still be edited freely.', {
      weight: 70,
      afterNavigation: async (page) => {
        await page.getByTestId('lease-status').waitFor({ state: 'visible', timeout: 20_000 });
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${saraLeaseId}`, 'Read the charge lines',
      'The security deposit and the rent are separate lines because each is credited to its own account and each behaves differently.', {
      weight: 70,
      afterNavigation: async (page) => {
        await page.getByTestId('lease-lines-grid').waitFor({ state: 'visible', timeout: 20_000 });
        await page.getByTestId('lease-lines-totals').waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${saraLeaseId}`, 'Read the cheque grid',
      'The grid records every cheque the renter handed over. It has to add up to the contract value or the contract will not post.', {
      weight: 60,
      afterNavigation: async (page) => {
        await page.getByTestId('cheque-grid').waitFor({ state: 'visible', timeout: 20_000 });
        await page.getByTestId('cheque-grid-match').waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${saraLeaseId}`, 'Post the contract',
      'Posting writes the accounting entries and makes the contract active.', {
      weight: 60,
      afterNavigation: async (page) => {
        await page.getByTestId('lease-post').click();
        // The dry run is the dialog's own answer to "would this post?" — the
        // confirm button is only meaningful once it is on screen.
        await page.getByTestId('post-dry-run-ok').waitFor({ state: 'visible', timeout: 20_000 });
        await page.getByTestId('post-lease-confirm').click();
        await page.getByTestId('lease-posting-journal').waitFor({ state: 'visible', timeout: 30_000 });
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${saraLeaseId}`, 'Read the journals it wrote',
      'One tenancy contract journal, and one post-dated cheque journal for every row of the grid.', {
      weight: 40,
      afterNavigation: async (page) => {
        await page.getByTestId('lease-tab-journals').click();
        await page.getByTestId('lease-journals-tab').locator('table').first()
          .locator('tbody tr').filter({ hasText: 'TCO' }).first()
          .waitFor({ state: 'visible', timeout: 20_000 });
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${saraLeaseId}`, 'Read the planned recognition',
      'The rent is already divided across the term by its real day count. None of it is posted yet.', {
      weight: 30,
      afterNavigation: async (page) => {
        await page.getByTestId('lease-tab-recognition').click();
        await page.getByTestId('recognition-schedule').waitFor({ state: 'visible', timeout: 20_000 });
      },
    }),
  ],

  '35': [
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques', 'Open the cheque register',
      'Every cheque from every posted contract, with its status, its maturity date, the property and the renter.', {
      weight: 65,
      afterNavigation: async (page) => {
        await page.getByTestId('cheque-summary-tiles').waitFor({ state: 'visible', timeout: 20_000 });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques', 'Filter to what matures now',
      'Registered means the cheque is recorded and its journal is written, but the paper has not left the office.', {
      weight: 50,
      afterNavigation: async (page) => {
        await page.getByTestId('cheque-status-filter').selectOption('REGISTERED');
        await page.getByTestId('cheque-filter-apply').click();
        await page.locator('[data-testid^="cheque-row-"]').first().waitFor({ state: 'visible', timeout: 20_000 });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques/collection', 'Bank a batch',
      'Selecting rows and entering a deposit date moves them to deposited. Nothing is posted, because nothing has changed about what you are owed.', {
      weight: 60,
      afterNavigation: async (page) => {
        // `GET /cheques/to-deposit` is REGISTERED **and matured**
        // (`ChequeRepository#findToDeposit`: `chequeDate <= today`), so which of
        // the seeded rows are bankable depends on the day the take is recorded.
        // The first two rows the page offers are taken instead of named ids.
        const boxes = page.locator('[data-testid^="collection-select-"]:not([data-testid="collection-select-all"])');
        await boxes.first().waitFor({ state: 'visible', timeout: 20_000 });
        bankedChequeIds = (await boxes.evaluateAll((els) => els.slice(0, 2)
          .map((el) => el.getAttribute('data-testid').replace('collection-select-', ''))));
        for (const id of bankedChequeIds) await page.getByTestId(`collection-select-${id}`).check();
        await page.getByTestId('collection-deposit-selected').click();
        await page.getByTestId('deposit-batch-total').waitFor({ state: 'visible', timeout: 15_000 });
        await page.getByTestId('deposit-batch-confirm').click();
        await page.getByTestId(`collection-select-${bankedChequeIds[0]}`).waitFor({ state: 'detached', timeout: 20_000 });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques', 'Clear a cheque',
      'When the bank confirms, clearing debits your bank account and credits post-dated cheques receivable. That is when the money becomes yours.', {
      weight: 55,
      afterNavigation: async (page) => {
        const id = bankedChequeIds[0];
        await page.getByTestId(`cheque-row-action-clear-${id}`).click();
        await page.getByTestId('cheque-clear-confirm').click();
        // A cleared PDC may still be returned late, so its bounce action
        // appearing in place of clear is the proof the clearing landed.
        await page.getByTestId(`cheque-row-action-bounce-${id}`).waitFor({ state: 'visible', timeout: 20_000 });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques', 'Return a cheque the bank sent back',
      'Bouncing a cleared cheque reverses the bank side: rent receivable is debited again and the bank credited. The amount is owed once more.', {
      weight: 55,
      afterNavigation: async (page) => {
        const id = chequeIdAt('fatima', 2);
        await page.getByTestId(`cheque-row-action-bounce-${id}`).click();
        await page.getByTestId('bounce-failure-reason').waitFor({ state: 'visible', timeout: 15_000 });
        await page.getByTestId('cheque-bounce-confirm').click();
        await page.getByTestId(`cheque-row-action-replace-${id}`).waitFor({ state: 'visible', timeout: 20_000 });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/cheques/return-replace', 'Replace it with new paper',
      'A returned cheque is usually settled with more than one replacement. Each one registers its own journal, and any shortfall stays on the renter.', {
      weight: 55,
      afterNavigation: async (page) => {
        const id = chequeIdAt('fatima', 2);
        await page.getByTestId(`cheque-row-action-replace-${id}`).click();
        await page.getByTestId('replace-row-0').waitFor({ state: 'visible', timeout: 15_000 });
        // Row 0 opens seeded with the whole returned amount, so splitting it
        // across two instruments means halving row 0 before adding row 1.
        const full = Number(await page.getByTestId('replace-row-0-amount').inputValue());
        const first = Math.round(full * 0.6);
        await page.getByTestId('replace-row-0-amount').fill(String(first));
        await page.getByTestId('replace-row-0-number').fill(`RPL-${Date.now().toString().slice(-6)}-A`);
        await page.getByTestId('replace-add-row').click();
        await page.getByTestId('replace-row-1').waitFor({ state: 'visible' });
        await page.getByTestId('replace-row-1-amount').fill(String(full - first));
        await page.getByTestId('replace-row-1-number').fill(`RPL-${Date.now().toString().slice(-6)}-B`);
        await page.getByTestId('replace-residual').waitFor({ state: 'visible' });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/penalties', 'Penalties are never automatic',
      'A returned cheque proposes a penalty. Finance approves, waives or reverses it from this queue.', {
      weight: 30,
    }),
  ],

  '36': [
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${ahmedLeaseId}`, 'Read the recognition schedule',
      'The term is divided by its real day count to get a daily rate, then multiplied by the real number of days in each month.', {
      weight: 75,
      afterNavigation: async (page) => {
        await page.getByTestId('lease-tab-recognition').click();
        await page.getByTestId('recognition-schedule').waitFor({ state: 'visible', timeout: 20_000 });
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/leases/${ahmedLeaseId}`, 'A short first period and a rounding row',
      'A contract that starts mid month opens short and closes short, and the last row absorbs the rounding so the schedule adds up exactly.', {
      weight: 50,
      afterNavigation: async (page) => {
        await page.getByTestId('lease-tab-recognition').click();
        await page.getByTestId('recognition-schedule').waitFor({ state: 'visible', timeout: 20_000 });
        await page.getByTestId('recognition-schedule').locator('tbody tr').last().scrollIntoViewIfNeeded();
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/recognition', 'Preview the close',
      'Entering a cut-off date lists every planned period that ends on or before it, with a total. Read the total before posting it.', {
      weight: 70,
      afterNavigation: async (page) => {
        await page.getByTestId('recognition-to-date').fill(lastMonthEnd());
        await page.getByTestId('recognition-future-warning').waitFor({ state: 'detached' }).catch(() => {});
        await page.getByTestId('recognition-preview').click();
        await page.getByTestId('recognition-result-title').waitFor({ state: 'visible', timeout: 40_000 });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/recognition', 'Run it',
      'Each period becomes one journal dated the last day of that period, debiting advance rent and crediting rental income.', {
      weight: 55,
      afterNavigation: async (page) => {
        await page.getByTestId('recognition-to-date').fill(lastMonthEnd());
        await page.getByTestId('recognition-preview').click();
        await page.getByTestId('recognition-result-title').waitFor({ state: 'visible', timeout: 40_000 });
        await page.getByTestId('recognition-run').click();
        await page.getByTestId('recognition-run-confirm').click();
        await page.getByTestId('recognition-result-title').waitFor({ state: 'visible', timeout: 60_000 });
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/finance/general-ledger?accountId=${advanceRentAccountId}`, 'Watch advance rent fall',
      'The advance rent balance drops by exactly what was recognised, and rental income rises by the same amount.', {
      weight: 45,
      afterNavigation: async (page) => {
        // These contracts are dated 1 January and the filter opens on the
        // current month, so the report is empty until the From box moves back.
        await page.locator('#ledger-from').fill(contractYearStart());
        await page.getByRole('button', { name: 'Apply' }).click();
        await page.getByRole('row').filter({ hasText: /CIL-/ }).first()
          .waitFor({ state: 'visible', timeout: 20_000 });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/journals', 'It also runs nightly',
      'A backdated contract catches up on its own. Running it by hand is for closing a period on purpose.', {
      weight: 25,
      afterNavigation: async (page) => {
        await page.locator('#jv-doc-type').selectOption('CIL');
        await page.locator('#jv-from').fill(contractYearStart());
        await page.locator('#jv-to').fill(lastMonthEnd());
        await page.getByRole('button', { name: 'Apply' }).click();
        await page.getByRole('row').filter({ hasText: /CIL-/ }).first()
          .waitFor({ state: 'visible', timeout: 20_000 });
      },
    }),
  ],

  '37': [
    roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${fatimaRenterId}`,
      'Open a renter ledger',
      'One block per account the renter has touched, and inside each one row per entry with its document, its counter account and a running balance.', {
      weight: 75,
      afterNavigation: async (page) => {
        await page.locator('#ledger-from').fill(contractYearStart());
        await page.getByRole('button', { name: 'Apply' }).click();
        await page.getByRole('link', { name: /^TCO-/ }).first().waitFor({ state: 'visible', timeout: 20_000 });
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${fatimaRenterId}`,
      'Rent receivable and what is left on it',
      'The contract debited the whole value and the cheque journals credited it back, so what the renter owes lives in the register, not here.', {
      weight: 60,
      afterNavigation: async (page) => {
        await page.locator('#ledger-from').fill(contractYearStart());
        await page.getByRole('button', { name: 'Apply' }).click();
        await page.getByText(/rent receivable/i).first().waitFor({ state: 'visible', timeout: 20_000 });
        await page.getByText(/rent receivable/i).first().evaluate((el) => el.scrollIntoView({ block: 'start' }));
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${fatimaRenterId}`,
      'Until a cheque comes back',
      'A returned cheque debits rent receivable again. That balance is the only thing standing between this renter and a clean account.', {
      weight: 50,
      afterNavigation: async (page) => {
        await page.locator('#ledger-from').fill(contractYearStart());
        await page.getByRole('button', { name: 'Apply' }).click();
        const returned = page.getByRole('row').filter({ has: page.getByRole('link', { name: /^CBR-/ }) }).first();
        await returned.waitFor({ state: 'visible', timeout: 20_000 });
        await returned.evaluate((el) => el.scrollIntoView({ block: 'center' }));
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${fatimaRenterId}`,
      'Post-dated cheques and advance rent',
      'One block holds the paper you still have; the other winds down to zero as the rent is earned month by month.', {
      weight: 50,
      afterNavigation: async (page) => {
        await page.locator('#ledger-from').fill(contractYearStart());
        await page.getByRole('button', { name: 'Apply' }).click();
        await page.getByText(/advance rent/i).first().waitFor({ state: 'visible', timeout: 20_000 });
        await page.getByText(/advance rent/i).first().evaluate((el) => el.scrollIntoView({ block: 'start' }));
      },
    }),
    roleRouteScene('tenantAdmin', `/en/dashboard/finance/tenant-ledger?renterId=${fatimaRenterId}`,
      'Open the journal behind a row',
      'A journal always balances, and it is never edited or deleted, only reversed, which writes a mirror entry and links the two.', {
      weight: 45,
      afterNavigation: async (page) => {
        await page.locator('#ledger-from').fill(contractYearStart());
        await page.getByRole('button', { name: 'Apply' }).click();
        await page.getByRole('link', { name: /^TCO-/ }).first().click();
        await page.waitForURL(/\/dashboard\/finance\/journals\/[0-9a-f-]{36}/, { timeout: 20_000 });
        // Not `reverse-journal`: that button is MANUAL-only by design
        // (`journals/__tests__/reverse-manual-only.test.tsx`), and a tenancy
        // contract journal is LEASE-sourced. The lines themselves are the point.
        await page.locator('table tbody tr').first().waitFor({ state: 'visible', timeout: 20_000 });
      },
    }),
    roleRouteScene('tenantAdmin', '/en/dashboard/finance/trial-balance', 'Check the whole ledger',
      'Total debits equal total credits. That single check is what tells you the ledger behind every screen is sound.', {
      weight: 30,
      afterNavigation: async (page) => {
        await page.locator('#tb-as-of').fill(todayIso());
        await page.getByRole('button', { name: 'Apply' }).click();
        const grand = page.locator('tr').filter({ hasText: 'Grand Total' }).first();
        await grand.waitFor({ state: 'visible', timeout: 20_000 });
        await grand.evaluate((el) => el.scrollIntoView({ block: 'center' }));
      },
    }),
  ],
};

const roleByTutorial = {
  '01': 'anonymous',
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
  '34': 'tenantAdmin',
  '35': 'tenantAdmin',
  '36': 'tenantAdmin',
  '37': 'tenantAdmin',
};

const scenes = scenarios[tutorialId];
if (!scenes) throw new Error(`Tutorial ${tutorialId} does not have an automated capture scenario yet.`);

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.mkdirSync(rawRoot, { recursive: true });
const videoDir = validateOnly
  ? null
  : fs.mkdtempSync(path.join(rawRoot, `tutorial-${tutorialId}-`));
const targetDuration = narrationDurationSeconds() + 2;
// Playwright starts each clip when the page is created, before scene timing
// begins, and keeps a short trailer while the page closes. Reserve that
// measured per-clip overhead so early scenes cannot push the closing workflow
// beyond the narration and get trimmed from the final MP4.
const clipOverheadSeconds = Number(process.env.TUTORIAL_CLIP_OVERHEAD_SECONDS || 1);
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
      : role === 'propertyManager'
        ? seed.operatorLogins?.find((login) => login.role === 'PROPERTY_MANAGER')
        : role === 'securityGuard'
          ? seed.operatorLogins?.find((login) => login.role === 'SECURITY_GUARD')
      : null;
  if (!credentials?.email || !credentials?.password) {
    throw new Error(`The seed manifest does not include credentials for ${role}.`);
  }
  const loginContext = await browser.newContext({ baseURL });
  await suppressAutomaticOnboarding(loginContext);
  const loginPage = await loginContext.newPage();
  await loginPage.goto('/en/auth/login');
  await loginPage.locator('#login-email').fill(credentials.email);
  await loginPage.locator('#login-password').fill(credentials.password);
  await loginPage.getByRole('button', { name: /sign in|log in/i }).click();
  // The production dashboard keeps a few long-lived resources open, so the
  // default `load` wait can time out after navigation has already succeeded.
  // DOM readiness is enough to establish the authenticated storage state.
  await loginPage.waitForURL(/\/en\/dashboard/, { timeout: 30_000, waitUntil: 'domcontentloaded' });
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
    await suppressAutomaticOnboarding(context);
    if (!validateOnly && sceneIndex === 0) {
      const introTitle = String(scene.title || 'RentAxis tutorial').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
      await context.addInitScript({
        content: `(() => {
          try {
            if (sessionStorage.getItem('rentaxisRecordingIntroShown')) return;
            sessionStorage.setItem('rentaxisRecordingIntroShown', '1');
          } catch {}
          const mount = () => {
            if (document.querySelector('[data-rentaxis-recording-intro]')) return;
            const root = document.createElement('div');
            root.dataset.rentaxisRecordingIntro = 'true';
            root.innerHTML = '<div style="font-size:18px;letter-spacing:.18em;text-transform:uppercase;color:#f59e0b;margin-bottom:18px">RentAxis tutorial</div><div style="font-size:42px;line-height:1.1;font-weight:700;max-width:900px">${introTitle}</div>';
            Object.assign(root.style, {
              position: 'fixed', inset: '0', zIndex: '2147483647', display: 'flex',
              flexDirection: 'column', justifyContent: 'center', alignItems: 'center',
              textAlign: 'center', padding: '48px', color: '#f8fafc',
              background: 'linear-gradient(135deg, #0f172a 0%, #1e3a5f 100%)',
              fontFamily: 'Inter, ui-sans-serif, system-ui, sans-serif',
            });
            const mountTarget = document.body;
            if (mountTarget) mountTarget.append(root);
          };
          if (document.body) mount();
          else document.addEventListener('DOMContentLoaded', mount, { once: true });
        })();`,
      });
    }
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
      const activeTourCount = await page.locator('.shepherd-element:visible').count();
      if (activeTourCount > 0 && !scene.allowTour) {
        throw new Error(`An onboarding tour opened unexpectedly in scene ${sceneIndex + 1}; recording stopped.`);
      }
      await auditVisibleDialogContrast(page);
      if (scene.verifyTenantContext !== false) {
        // Role-specific sessions fetch their tenant membership after the
        // application shell is visible. Wait for that asynchronous label
        // before deciding the context is wrong, otherwise a healthy session
        // can fail the preflight during a slow production response.
        await page.getByText(tenantName, { exact: true }).first().waitFor({ state: 'visible', timeout: 10_000 });
      }
      if (qaDir) {
        fs.mkdirSync(qaDir, { recursive: true });
        const qaName = `${tutorialId}-${String(sceneIndex + 1).padStart(2, '0')}-${scene.title.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/(^-|-$)/g, '')}.png`;
        await page.screenshot({ path: path.join(qaDir, qaName), fullPage: false });
      }
      await addCallout(page, scene.title, scene.body);
      const sceneDuration = validateOnly
        ? 0.5
        : Math.max(1, targetDuration * (sceneWeights[sceneIndex] / totalSceneWeight) - clipOverheadSeconds);
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
  console.log(`tutorial=${tutorialId}`);
  console.log(`tenant=${tenantName}`);
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
  console.log(`tutorial=${tutorialId}`);
  console.log(`tenant=${tenantName}`);
  console.log(`target_duration=${targetDuration.toFixed(2)}`);
  console.log(`silent_video=${outputPath}`);
  console.log(`raw_video_dir=${videoDir}`);
}

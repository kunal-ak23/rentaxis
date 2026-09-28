// Page-level waits, navigation, capture styles and preflight audits shared by every scene.
import { baseURL, navTimeoutMs, tutorialId, validateOnly } from './context.mjs';
import { pointerAt } from './cursor.mjs';

export async function waitForApp(page) {
  await page.waitForLoadState('domcontentloaded', { timeout: navTimeoutMs }).catch(() => {});
  await Promise.race([
    page.locator('main').waitFor({ state: 'visible', timeout: navTimeoutMs }),
    page.locator('#login-email').waitFor({ state: 'visible', timeout: navTimeoutMs }),
    page.getByText('Create Account', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs }),
    page.getByText('Browse Properties', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs }),
    page.getByText('My Wishlist', { exact: true }).waitFor({ state: 'visible', timeout: navTimeoutMs }),
  ]);
  // Dashboard data can arrive a moment after the shell. Never freeze a
  // transient error state into a tutorial frame; wait for the retrying page to
  // recover, and fail the preflight if it remains genuinely unavailable.
  const dashboardError = page.getByText('Unable to load dashboard data', { exact: true });
  if (await dashboardError.isVisible().catch(() => false)) {
    await dashboardError.waitFor({ state: 'hidden', timeout: navTimeoutMs });
  }
  const dashboardLoading = page.getByText('Loading dashboard...', { exact: true });
  if (await dashboardLoading.isVisible().catch(() => false)) {
    await dashboardLoading.waitFor({ state: 'hidden', timeout: navTimeoutMs });
  }
  await page.waitForTimeout(800);
}

export async function clearRecordingIntro(page) {
  await page.evaluate(() => document.querySelector('[data-rentaxis-recording-intro]')?.remove());
}

export async function addCallout(page, title, body) {
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

export async function clearCallout(page) {
  await page.evaluate(() => document.querySelector('[data-rentaxis-tutorial-callout]')?.remove());
}

export async function goto(page, pathname) {
  // Production keeps a few long-lived resources open, so waiting for the
  // browser's full `load` event can time out after the usable app shell is
  // already ready. DOM readiness is sufficient; waitForApp handles the
  // role-specific content that follows.
  // Wait only for the document commit. The production shell keeps long-lived
  // resources open, so DOMContentLoaded can lag even after the app is usable.
  await page.goto(`${baseURL}${pathname}`, { waitUntil: 'commit', timeout: navTimeoutMs });
  await waitForApp(page);
  await clearRecordingIntro(page);
  await applyCaptureStyles(page);
  await revealCursor(page);
}

/** A fresh document draws no cursor until the mouse moves; nudge it in place. */
export async function revealCursor(page) {
  if (validateOnly) return;
  const at = pointerAt.get(page);
  if (!at) return;
  await page.mouse.move(at.x + 1, at.y);
  await page.mouse.move(at.x, at.y);
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
 *   are hidden for the accounting tutorials and for tutorial 10 (which ends on
 *   the draft it creates), the ones that hold on that page. The renter's NAME
 *   stays: it is what names the contract. Tutorial 02 opens the same page
 *   from global search.
 */
export const PRIVACY_SENSITIVE_TUTORIALS = new Set(['02', '10', '34', '35', '36', '37']);

export const CAPTURE_STYLE_RULES = [
  'nextjs-portal, [data-nextjs-toast], #__next-build-watcher { display: none !important; }',
  ...(PRIVACY_SENSITIVE_TUTORIALS.has(tutorialId)
    ? ['div.justify-between:has(> span > svg.lucide-mail),'
       + ' div.justify-between:has(> span > svg.lucide-phone) { visibility: hidden !important; }']
    : []),
  // Settings › Users & staff lists every user with a PHONE column, tenants
  // included; tutorial 05 holds on that table. Only that column's cells are
  // hidden (its <td>s are the only third-column monospace cells there).
  ...(tutorialId === '05' ? ['table td.font-mono:nth-child(3) { visibility: hidden !important; }'] : []),
].join('\n');

/**
 * Applied once the app shell is up, not from an init script: a style appended
 * before hydration does not survive it. Next replaces the head it streamed
 * when the client takes over, and the element is simply gone by the time the
 * page has rendered (measured against the lease overview). `addStyleTag` after
 * `waitForApp` lands after that swap and stays for the whole scene — the
 * window it does not cover is the loading shell, which has no data on it.
 */
export async function applyCaptureStyles(page) {
  await page.addStyleTag({ content: CAPTURE_STYLE_RULES }).catch(() => {});
}

export async function suppressAutomaticOnboarding(context) {
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

export async function auditVisibleDialogContrast(page) {
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

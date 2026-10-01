import { navTimeoutMs, validateOnly } from './context.mjs';

// ── visible cursor and click feedback (capture only) ─────────────────────────
// Headless Chromium records no pointer, so a viewer cannot see what was
// clicked. In capture mode every context gets an overlay — an arrow that
// follows the real mouse, a ripple on mousedown, a ring around the field being
// typed into — and every Locator action first glides the real mouse onto its
// target. Validate-only runs get neither: they prove, they do not perform.

/** Runs in the page (init script): draws the cursor, ripple and focus ring. */
export function installCursorOverlay() {
  if (window.__rentaxisCursor) return;
  // Top document only: a frame (the PDF viewer's, say) would draw a second, stale arrow.
  if (window !== window.top) return;
  window.__rentaxisCursor = true;
  const Z = '2147483647';
  const KEY = 'rentaxisCursorAt';
  const mount = () => {
    const host = document.documentElement;
    if (!host || document.getElementById('rentaxis-cursor')) return;
    const style = document.createElement('style');
    style.textContent = `
      #rentaxis-cursor { position: fixed; left: 0; top: 0; width: 26px; height: 32px; z-index: ${Z};
        pointer-events: none; transform-origin: 2px 2px; transition: transform 120ms ease-out; display: none;
        filter: drop-shadow(0 2px 3px rgba(15,23,42,.45)); }
      #rentaxis-cursor.down { transform: scale(.86); }
      .rentaxis-ripple { position: fixed; z-index: ${Z}; pointer-events: none; width: 16px; height: 16px;
        margin: -8px 0 0 -8px; border-radius: 50%; border: 3px solid rgba(238,192,70,.95);
        background: rgba(238,192,70,.28); animation: rentaxis-ripple 520ms ease-out forwards; }
      @keyframes rentaxis-ripple { to { transform: scale(3.4); opacity: 0; } }
      #rentaxis-focus-ring { position: fixed; z-index: 2147483646; pointer-events: none; display: none;
        border: 3px solid rgba(238,192,70,.95); border-radius: 10px;
        box-shadow: 0 0 0 5px rgba(238,192,70,.25); transition: opacity 200ms ease-out; }`;
    const cursor = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    cursor.id = 'rentaxis-cursor';
    cursor.setAttribute('viewBox', '0 0 26 32');
    cursor.innerHTML = '<path d="M2 2 L2 25 L8 19.5 L12.2 29 L16.4 27.2 L12.3 17.8 L20.5 17.8 Z" fill="#111827" stroke="#ffffff" stroke-width="2" stroke-linejoin="round"/>';
    const ring = document.createElement('div');
    ring.id = 'rentaxis-focus-ring';
    host.append(style, ring, cursor);

    const place = (x, y) => {
      cursor.style.display = 'block';
      cursor.style.left = `${x - 2}px`;
      cursor.style.top = `${y - 2}px`;
      try { sessionStorage.setItem(KEY, JSON.stringify([x, y])); } catch {}
    };
    try {
      const saved = JSON.parse(sessionStorage.getItem(KEY) || 'null');
      if (Array.isArray(saved)) place(saved[0], saved[1]);
    } catch {}
    window.addEventListener('mousemove', (e) => place(e.clientX, e.clientY), true);
    window.addEventListener('mousedown', (e) => {
      cursor.classList.add('down');
      const ripple = document.createElement('div');
      ripple.className = 'rentaxis-ripple';
      ripple.style.left = `${e.clientX}px`;
      ripple.style.top = `${e.clientY}px`;
      host.append(ripple);
      setTimeout(() => ripple.remove(), 600);
    }, true);
    window.addEventListener('mouseup', () => cursor.classList.remove('down'), true);

    let fadeTimer = null;
    const typable = (el) => el && el.matches?.('input:not([type=checkbox]):not([type=radio]):not([type=button]):not([type=submit]), textarea, select, [contenteditable="true"]');
    const showRing = (el) => {
      const r = el.getBoundingClientRect();
      Object.assign(ring.style, {
        display: 'block', opacity: '1',
        left: `${r.left - 4}px`, top: `${r.top - 4}px`, width: `${r.width + 8}px`, height: `${r.height + 8}px`,
      });
      clearTimeout(fadeTimer);
      fadeTimer = setTimeout(() => { ring.style.opacity = '0'; }, 1400);
    };
    document.addEventListener('focusin', (e) => { if (typable(e.target)) showRing(e.target); }, true);
    document.addEventListener('input', (e) => { if (typable(e.target)) showRing(e.target); }, true);
    document.addEventListener('focusout', () => { ring.style.opacity = '0'; }, true);
  };
  if (document.documentElement) mount();
  else document.addEventListener('DOMContentLoaded', mount, { once: true });
}

export const pointerAt = new WeakMap();
export const easeInOut = (t) => (t < 0.5 ? 2 * t * t : 1 - ((-2 * t + 2) ** 2) / 2);

/** Glide the real mouse to the centre of `locator` with an eased path, then settle. */
export async function glideTo(locator) {
  const page = locator.page();
  await locator.scrollIntoViewIfNeeded({ timeout: navTimeoutMs }).catch(() => {});
  const box = await locator.boundingBox({ timeout: navTimeoutMs }).catch(() => null);
  if (!box) return;
  const to = { x: box.x + box.width / 2, y: box.y + box.height / 2 };
  const from = pointerAt.get(page) || { x: 1500, y: 640 };
  const distance = Math.hypot(to.x - from.x, to.y - from.y);
  if (distance > 2) {
    const steps = 24;
    const duration = Math.min(600, 380 + distance / 8);
    for (let i = 1; i <= steps; i += 1) {
      const t = easeInOut(i / steps);
      await page.mouse.move(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t);
      await page.waitForTimeout(duration / steps);
    }
  }
  pointerAt.set(page, to);
  await page.waitForTimeout(150);
}

/**
 * Move the pointer off the control it just pressed, onto a neutral spot, so a
 * held frame never looks as if it is about to press something else. Capture
 * only: validate-only runs draw no pointer.
 */
export async function restPointer(page, x, y) {
  if (validateOnly) return;
  const from = pointerAt.get(page) || { x: 1500, y: 640 };
  const distance = Math.hypot(x - from.x, y - from.y);
  if (distance > 2) {
    const steps = 20;
    const duration = Math.min(600, 380 + distance / 8);
    for (let i = 1; i <= steps; i += 1) {
      const t = easeInOut(i / steps);
      await page.mouse.move(from.x + (x - from.x) * t, from.y + (y - from.y) * t);
      await page.waitForTimeout(duration / steps);
    }
  }
  pointerAt.set(page, { x, y });
}

/** Rest the pointer on (not click) a locator the narration is pointing at. */
export async function pointAt(locator) {
  if (validateOnly) {
    // The proof draws no pointer, but what the narration points at must exist:
    // in a capture a missing target costs the whole navigation timeout and
    // throws every later scene off its cue.
    const found = await locator.first().waitFor({ state: 'visible', timeout: 5_000 }).then(() => true, () => false);
    if (!found) throw new Error(`pointAt: nothing visible matches ${locator}`);
    return;
  }
  await glideTo(locator);
}

/** Patch Locator actions once, capture mode only, so every scene's clicks are seen. */
export let locatorPatched = false;
export function patchLocatorForCapture(page) {
  if (locatorPatched || validateOnly) return;
  locatorPatched = true;
  const proto = Object.getPrototypeOf(page.locator('body'));
  for (const name of ['click', 'dblclick', 'fill', 'selectOption', 'check', 'uncheck']) {
    const original = proto[name];
    if (typeof original !== 'function') continue;
    proto[name] = async function glidingAction(...args) {
      await this.waitFor({ state: 'visible', timeout: navTimeoutMs }).catch(() => {});
      await glideTo(this);
      return original.apply(this, args);
    };
  }
}

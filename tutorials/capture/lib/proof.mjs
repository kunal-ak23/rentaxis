// Proof helpers: assertions a scene must pass, and viewer pacing that validate-only skips.
import { navTimeoutMs, validateOnly } from './context.mjs';

/** Fail the proof unless the locator matches exactly `expected` elements. */
export async function expectCount(locator, expected, what) {
  const actual = await locator.count();
  if (actual !== expected) throw new Error(`Expected ${expected} ${what}, found ${actual}.`);
}

/** Fail the proof unless the element's text contains `text` (whitespace-normalised). */
export async function expectText(locator, text, what) {
  await locator.waitFor({ state: 'visible', timeout: navTimeoutMs });
  const actual = (await locator.innerText()).replace(/\s+/g, ' ').trim();
  if (!actual.includes(text)) throw new Error(`${what}: expected "${text}", found "${actual}".`);
}

export const escapeRegExp = (value) => value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/**
 * A beat for the viewer: lets the narration reach an action before the page
 * performs it. Skipped when only validating — the proof does not need it.
 */
export const pace = (page, ms) => (validateOnly ? Promise.resolve() : page.waitForTimeout(ms));

/** Wait until an input inside `rowSelector` holds `value` (grid cells are inputs). */
export async function waitForInputValue(page, rowSelector, value) {
  await page.waitForFunction(
    ({ rowSelector, value }) => [...document.querySelectorAll(`${rowSelector} input`)].some((input) => input.value === value),
    { rowSelector, value },
    { timeout: navTimeoutMs },
  );
}

#!/usr/bin/env node
// Render the SVG sources to the PNGs the app stores (it accepts PNG/JPEG/GIF only):
//   node tutorials/brand/palm-ridge/render.mjs
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';

const here = import.meta.dirname;
const { chromium } = createRequire(path.join(here, '..', '..', '..', 'web', 'package.json'))('@playwright/test');
const jobs = [
  ['logo-mark.svg', 'logo-mark.png', 512, 512],
  ['stamp.svg', 'stamp.png', 520, 520],
];
const browser = await chromium.launch();
try {
  const page = await browser.newPage({ deviceScaleFactor: 1 });
  for (const [src, out, width, height] of jobs) {
    await page.setViewportSize({ width, height });
    const svg = fs.readFileSync(path.join(here, src), 'utf8');
    await page.setContent(`<html><body style="margin:0;background:transparent">${svg}</body></html>`);
    await page.locator('svg').screenshot({ path: path.join(here, out), omitBackground: true });
    console.log(`${out} ${width}x${height}`);
  }
} finally {
  await browser.close();
}

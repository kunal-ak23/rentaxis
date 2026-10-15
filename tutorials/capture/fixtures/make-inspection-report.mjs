// Renders the fictional move-in inspection report tutorial 12 attaches to a
// contract. Every name in it is the demo organisation's; nothing is real.
//   cd web && node ../tutorials/capture/fixtures/make-inspection-report.mjs
import { createRequire } from 'node:module';
import path from 'node:path';

const repo = path.resolve(import.meta.dirname, '..', '..', '..');
const { chromium } = createRequire(path.join(repo, 'web', 'package.json'))('@playwright/test');
const out = path.join(import.meta.dirname, 'm-1501-move-in-inspection.pdf');
const rows = [
  ['Living room', 'Walls and flooring clean, no marks', 'Good'],
  ['Kitchen', 'Appliances working, cabinets intact', 'Good'],
  ['Bedroom 1', 'Wardrobe doors aligned, AC serviced', 'Good'],
  ['Bedroom 2', 'Minor scuff near the window sill', 'Fair'],
  ['Bathrooms', 'Fittings and drains working', 'Good'],
  ['Balcony', 'Glass balustrade clean', 'Good'],
];
const html = `<!doctype html><html><body style="font-family:Helvetica,Arial,sans-serif;color:#1b1b1b;margin:48px">
<h1 style="font-size:22px;margin:0">Oasis Crest Properties</h1>
<h2 style="font-size:16px;font-weight:600;margin:6px 0 24px">Move-in inspection report — Unit M-1501, Oasis Crest Marina Heights</h2>
<p style="font-size:12px">Tenant: Sara Mansour &nbsp;·&nbsp; Inspected: 1 January 2026 &nbsp;·&nbsp; Keys issued: 2 sets, 1 access card</p>
<table style="border-collapse:collapse;width:100%;font-size:12px;margin-top:16px">
<tr>${['Area', 'Observation', 'Condition'].map((h) => `<th style="text-align:left;border-bottom:2px solid #1b1b1b;padding:6px">${h}</th>`).join('')}</tr>
${rows.map((r) => `<tr>${r.map((c) => `<td style="border-bottom:1px solid #ccc;padding:6px">${c}</td>`).join('')}</tr>`).join('')}
</table>
<p style="font-size:11px;color:#666;margin-top:32px">Fictional document for the Miftah tutorials.</p>
</body></html>`;
const browser = await chromium.launch();
const page = await browser.newPage();
await page.setContent(html);
await page.pdf({ path: out, format: 'A4' });
await browser.close();
console.log(out);

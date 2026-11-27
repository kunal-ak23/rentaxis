// Tutorial 53 — Download several receipts at once (PR #466).
// Oasis Crest: Rajesh Kumar's monthly contract on A-103 (seed: 11 cleared cheques, Jan–Oct 2026 plus
// the deposit; November and December still registered, so they have no tick box).
//
// Staff (Company Admin): the contract's Cheques tab, tick January–March rent, Combined receipt (3), the
// downloaded PDF is checked for 3 pages and shown; Select year 2026 ticks all 11, the PDF has 11 pages.
// Renter (Rajesh): My Payments, tick two, Download receipts (2); Select year, 11 pages again.
//
// Read-only: a combined receipt writes nothing, so no snapshot is needed.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { navTimeoutMs, seed, validateOnly } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { narrationDurationSeconds } from '../lib/narration.mjs';
import { expectText, pace, sceneClock } from '../lib/proof.mjs';
import { goto } from '../lib/page.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

const leaseId = seed.leases?.rajesh;
if (!leaseId) throw new Error('The seed manifest has no Rajesh lease: run tutorials/recording-stack.sh seed.');
const YEAR = '2026';
const CLEARED_IN_YEAR = 11;

// ── narration-paced cues: `at(n)` waits until n words into the scene's narration ─
const WORDS = [30, 41, 42, 35, 32, 38];
// Hold weights: scene 4 gives 13 words of hold to the renter clip, which starts late (new browser
// context and sign-in), so the switch lands on "Tenants can do this themselves".
const WEIGHTS = [30, 41, 42, 22, 45, 38];
const secondsPerWord = (narrationDurationSeconds() + 2) / WORDS.reduce((a, b) => a + b, 0);
const wordClock = (page) => {
  const at = sceneClock(page);
  return (words) => at(words * secondsPerWord);
};

const workDir = process.env.TUTORIAL_WORK_DIR || path.join(import.meta.dirname, '..', '..', 'work', '53');

/** Clicks `button`, saves the download, and proves it is a PDF of `pages` pages. */
async function downloadPdf(page, button, pages, name) {
  const [download] = await Promise.all([page.waitForEvent('download', { timeout: navTimeoutMs }), button.click()]);
  fs.mkdirSync(workDir, { recursive: true });
  const file = path.join(workDir, `${name}.pdf`);
  await download.saveAs(file);
  const info = execFileSync('pdfinfo', [file]).toString();
  const n = Number(/Pages:\s+(\d+)/.exec(info)?.[1]);
  if (n !== pages) throw new Error(`${name}: expected a ${pages}-page PDF, got ${n}`);
  console.log(`receipt_pdf ${name} pages=${n} file=${download.suggestedFilename()}`);
  return file;
}

/**
 * Lays the downloaded PDF's pages over the app (the headless screencast cannot show the browser's PDF
 * viewer). The tenant's email and phone values are blurred, as in tutorial 11.
 */
async function showPdf(page, file, label, { thumb = 540 } = {}) {
  if (validateOnly) return;
  const dir = fs.mkdtempSync(path.join(workDir, 'pages-'));
  execFileSync('pdftoppm', ['-png', '-r', thumb > 250 ? '130' : '50', file, path.join(dir, 'p')]);
  const images = fs.readdirSync(dir).filter((f) => f.endsWith('.png')).sort()
    .map((f) => fs.readFileSync(path.join(dir, f)).toString('base64'));
  fs.rmSync(dir, { recursive: true, force: true });
  await page.evaluate(({ images, label, thumb }) => {
    document.querySelector('[data-t53-pdf]')?.remove();
    const root = document.createElement('div');
    root.dataset.t53Pdf = 'true';
    Object.assign(root.style, { position: 'fixed', inset: '0', zIndex: '2147483600', background: '#3b3f44', opacity: '0',
      transition: 'opacity 240ms ease-out', fontFamily: 'Inter, ui-sans-serif, system-ui, sans-serif', overflow: 'hidden' });
    const bar = document.createElement('div');
    Object.assign(bar.style, { height: '52px', background: '#2b2e32', color: '#e5e7eb', display: 'flex', alignItems: 'center',
      gap: '14px', padding: '0 28px', fontSize: '16px' });
    bar.innerHTML = '<span style="font-weight:600">receipts.pdf</span><span style="color:#9ca3af"></span>';
    bar.lastChild.textContent = label;
    const grid = document.createElement('div');
    Object.assign(grid.style, { display: 'flex', flexWrap: 'wrap', justifyContent: 'center', alignContent: 'flex-start',
      gap: '22px', padding: '22px 40px' });
    for (const data of images) {
      const sheet = document.createElement('div');
      Object.assign(sheet.style, { position: 'relative', width: `${thumb}px`, boxShadow: '0 3px 10px rgba(0,0,0,.45)', background: '#fff' });
      const img = document.createElement('img');
      img.src = `data:image/png;base64,${data}`;
      Object.assign(img.style, { width: '100%', display: 'block' });
      // Tenant details: the Email and Phone values (right column, measured on the A4 render).
      const blur = document.createElement('div');
      Object.assign(blur.style, { position: 'absolute', left: '72%', top: '29.3%', width: '25%', height: '4.6%',
        backdropFilter: 'blur(5px)', background: 'rgba(255,255,255,.45)' });
      sheet.append(img, blur);
      grid.append(sheet);
    }
    root.append(bar, grid);
    document.body.append(root);
    requestAnimationFrame(() => { root.style.opacity = '1'; });
  }, { images, label, thumb });
  await pace(page, 400);
}

const closePdf = (page) => page.evaluate(() => document.querySelector('[data-t53-pdf]')?.remove());
const centre = (locator) => locator.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));

const scenes = [
  roleRouteScene('tenantAdmin', `/en/dashboard/leases/${leaseId}`, 'Receipts from the contract',
    'Every cleared cheque in the grid has a tick box.', {
    weight: WEIGHTS[0],
    afterNavigation: async (page) => {
      const at = wordClock(page);
      await restPointer(page, 900, 300);
      await at(11);
      await page.getByTestId('lease-tab-payments').click();
      const bar = page.getByTestId('combined-receipt-bar');
      await bar.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('combined-receipt-download'), 'Combined receipt (0)', 'Nothing ticked yet');
      await centre(bar);
      await at(20);
      await pointAt(page.getByTestId('cheque-receipt-select-1'));
      await at(26);
      await pointAt(page.getByTestId('cheque-row-1').getByText('Cleared'));
    },
  }),
  stepScene('Tick, then download',
    'January, February and March: one PDF, one receipt per page.',
    async (page) => {
      const at = wordClock(page);
      const button = page.getByTestId('combined-receipt-download');
      for (const i of [1, 2, 3]) {
        await page.getByTestId(`cheque-receipt-select-${i}`).check();
        await pace(page, 500);
      }
      await expectText(button, 'Combined receipt (3)', 'Three ticked');
      await at(9);
      await button.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
      await pointAt(button);
      await at(14);
      const file = await downloadPdf(page, button, 3, 'staff-three');
      await showPdf(page, file, '3 pages · Rajesh Kumar · A-103');
      await restPointer(page, 720, 520);
    }, { weight: WEIGHTS[1] }),
  stepScene('A whole year',
    `Select year ${YEAR}: every payment that cleared that year, in one file.`,
    async (page) => {
      const at = wordClock(page);
      await closePdf(page);
      const year = page.getByTestId('combined-receipt-year');
      const button = page.getByTestId('combined-receipt-download');
      await year.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
      await pointAt(year);
      await at(7);
      await year.selectOption(YEAR);
      await expectText(button, `Combined receipt (${CLEARED_IN_YEAR})`, 'The year ticked');
      await at(14);
      await pointAt(button);
      await at(24);
      await pointAt(page.getByTestId('cheque-receipt-select-0'));
      await at(28);
      const file = await downloadPdf(page, button, CLEARED_IN_YEAR, 'staff-year');
      await showPdf(page, file, `${CLEARED_IN_YEAR} pages · ${YEAR}`, { thumb: 196 });
      await restPointer(page, 720, 560);
    }, { weight: WEIGHTS[2] }),
  stepScene('Cleared cheques only',
    'Not yet banked, no receipt. One contract per download, up to 100 receipts.',
    async (page) => {
      const at = wordClock(page);
      await closePdf(page);
      const registered = page.getByTestId('cheque-row-11');
      await expectText(registered, 'Registered', 'November cheque not banked');
      if (await page.getByTestId('cheque-receipt-select-11').count()) throw new Error('A registered cheque offers a receipt tick box');
      await registered.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
      await pace(page, 900);
      await at(4);
      await pointAt(registered.getByText('Registered'));
      await at(9);
      await pointAt(registered.locator('td').first());
      await at(16);
      await pointAt(page.getByTestId('cheque-row-10').locator('td').first());
    }, { weight: WEIGHTS[3] }),
  {
    role: 'renter:Rajesh Kumar',
    title: 'In the tenant portal',
    body: 'My Payments: tick the receipts, then Download receipts.',
    weight: WEIGHTS[4],
    verifyTenantContext: false,
    run: async (page) => {
      await goto(page, '/en/dashboard/renter-portal/payments');
      const at = wordClock(page);
      const bar = page.getByTestId('combined-receipt-bar');
      await bar.waitFor({ state: 'visible', timeout: navTimeoutMs });
      const ticks = page.locator('[data-testid^="receipt-select-"]');
      if (await ticks.count() !== CLEARED_IN_YEAR) throw new Error(`Expected ${CLEARED_IN_YEAR} receiptable rows, got ${await ticks.count()}`);
      await restPointer(page, 900, 400);
      await at(13);
      await centre(bar);
      await pace(page, 700);
      await pointAt(bar);
      await at(21);
      await ticks.nth(2).check();
      await pace(page, 500);
      await ticks.nth(3).check();
      const button = page.getByTestId('combined-receipt-download');
      await expectText(button, 'Download receipts (2)', 'Two ticked');
      await at(24);
      const file = await downloadPdf(page, button, 2, 'renter-two');
      await showPdf(page, file, '2 pages');
      await restPointer(page, 720, 520);
    },
  },
  stepScene('Or a whole year',
    'Several contracts? Choose the contract first.',
    async (page) => {
      const at = wordClock(page);
      await closePdf(page);
      const year = page.getByTestId('combined-receipt-year');
      const button = page.getByTestId('combined-receipt-download');
      await pointAt(year);
      await at(4);
      await year.selectOption(YEAR);
      await expectText(button, `Download receipts (${CLEARED_IN_YEAR})`, 'The year ticked');
      await pointAt(button);
      await at(9);
      const file = await downloadPdf(page, button, CLEARED_IN_YEAR, 'renter-year');
      await showPdf(page, file, `${CLEARED_IN_YEAR} pages · ${YEAR}`, { thumb: 196 });
      await restPointer(page, 720, 560);
    }, { weight: WEIGHTS[5], role: 'renter:Rajesh Kumar', verifyTenantContext: false }),
];

export default { role: 'tenantAdmin', anchored: true, scenes };

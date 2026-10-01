// Tutorial 11 — Generate, review and sign a tenancy contract. Off camera the
// scenario drafts its own contract (A-201 for Rajesh Kumar, the same terms
// tutorial 10 drafts on camera) after deleting any draft or pending contract
// an earlier run left on A-201. On camera the Company Admin previews the PDF
// (full Chromium build: the headless shell cannot show a PDF), confirms and
// saves it (Pending Signature, contract listed under Contract documents), the
// tenant accepts it in the portal, and the admin sees Accepted by tenant.
// The run prints draft_contract_id so record-local.sh can account for it.
//
// Unblocked by main's fix (ContractDocuments refreshKey): the list shows the new
// contract right after Confirm & Save.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { navTimeoutMs, seed, validateOnly } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { goto } from '../lib/page.mjs';
import { expectText, pace } from '../lib/proof.mjs';
import { stepScene } from '../lib/scenes.mjs';

const unitId = seed.units?.a201;
const renterId = seed.renterIds?.rajesh;
let contractId = null;

async function api(page, method, url, data) {
  const res = await page.request.fetch(`/api/proxy/v1${url}`, { method, data });
  if (!res.ok()) throw new Error(`${method} ${url} failed: HTTP ${res.status()} ${(await res.text()).slice(0, 300)}`);
  return res.status() === 204 ? null : res.json().catch(() => null);
}

/** A backend call as Rajesh (local stack only), for rejecting a contract an earlier run left pending. */
async function asTenant(method, url) {
  const backend = new URL(process.env.TUTORIAL_BACKEND_URL || 'http://localhost:8084');
  if (!['localhost', '127.0.0.1'].includes(backend.hostname)) throw new Error('Tenant clean-up runs against a local backend only.');
  const who = seed.renterLogins?.find((login) => login.name === 'Rajesh Kumar');
  const login = await fetch(`${backend.origin}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email: who.email, password: who.password }),
  });
  if (!login.ok) throw new Error(`Tenant sign-in failed: HTTP ${login.status}`);
  const user = await login.json();
  const headers = { 'X-User-Id': String(user.id), 'X-User-Role': user.role, Authorization: `Bearer ${user.token}` };
  if (user.tenantId) Object.assign(headers, { 'X-Tenant-Id': String(user.tenantId), 'X-User-Tenant-Id': String(user.tenantId) });
  const res = await fetch(`${backend.origin}/api/v1${url}`, { method, headers });
  if (!res.ok) throw new Error(`${method} ${url} as the tenant failed: HTTP ${res.status}`);
}

/** Off camera: remove what an earlier run left on A-201, then draft the contract afresh. */
async function prepareDraft(page) {
  const listed = await api(page, 'GET', '/leases/paged?size=100&search=A-201');
  for (const lease of listed.content || []) {
    if (lease.unitId !== unitId || !['DRAFT', 'PENDING_SIGNATURE'].includes(lease.status)) continue;
    if (lease.status === 'PENDING_SIGNATURE') {
      // Only a draft can be deleted. Regenerating clears an acceptance, and the
      // tenant's rejection returns the contract to Draft.
      if (lease.renterAcceptedAt) await api(page, 'POST', `/leases/${lease.id}/generate-contract`);
      await asTenant('PUT', `/leases/${lease.id}/reject`);
    }
    await api(page, 'DELETE', `/leases/${lease.id}`);
  }
  const line = (code, gross) => ({ chargeTypeCode: code, grossAmount: gross, discountAmount: 0, narration: '', vatApplicable: false });
  const draft = await api(page, 'POST', '/leases', {
    unitId, renterId, startDate: '2026-10-01', endDate: '2027-09-30', contractDate: '2026-09-29',
    gracePeriodDays: 5, paymentTerms: 4, paymentMethod: 'CHEQUE', depositPaymentMethod: 'CHEQUE',
    rentVatApplicable: false, lines: [line('RENT', 96000), line('SECURITY_DEPOSIT', 5000)],
  });
  const cheque = (seqNo, chequeNumber, chequeDate, amount, narration) => ({
    seqNo, postingDate: '2026-09-29', chequeNumber, chequeDate, payeeBank: 'Emirates NBD', amount, narration, mode: 'PDC',
  });
  await api(page, 'PUT', `/leases/${draft.id}/cheques`, [
    cheque(1, '500101', '2026-10-01', 29000, 'Security Deposit and Rent - 1st Installment'),
    cheque(2, '500102', '2027-01-01', 24000, 'Rent - 2nd Installment'),
    cheque(3, '500103', '2027-04-01', 24000, 'Rent - 3rd Installment'),
    cheque(4, '500104', '2027-07-01', 24000, 'Rent - 4th Installment'),
  ]);
  contractId = draft.id;
  console.log(`draft_contract_id=${contractId}`);
}

/**
 * The browser's PDF viewer is an out-of-process plugin, and the headless
 * screencast that becomes the video never repaints it (take 1 showed a stale
 * frame there). For the capture, the preview's own PDF is fetched from the
 * page, page 1 is rendered with pdftoppm and laid over the viewer, so the
 * video shows the very document the user sees. The tenant's email and phone
 * rows are blurred as before.
 */
async function paintPdfForCapture(page) {
  if (validateOnly) return;
  const t0 = Date.now();
  // A plain viewer-grey cover first, so the stale plugin frame is never seen.
  await page.evaluate(() => {
    const o = document.querySelector('object[type="application/pdf"]');
    const box = o.getBoundingClientRect();
    const cover = document.createElement('div');
    cover.dataset.tutorialPdfCover = 'true';
    Object.assign(cover.style, { position: 'fixed', left: `${box.left}px`, top: `${box.top}px`, width: `${box.width}px`,
      height: `${box.height}px`, background: '#525659', overflow: 'hidden', zIndex: '55', display: 'flex', justifyContent: 'center',
      // pointer-events on: the mouse over the cover stays in this document, so the arrow follows it.
      paddingTop: '24px', pointerEvents: 'auto' });
    document.body.append(cover);
  });
  const b64 = await page.evaluate(async () => {
    const o = document.querySelector('object[type="application/pdf"]');
    const buf = new Uint8Array(await (await fetch(o.data)).arrayBuffer());
    let s = '';
    for (let i = 0; i < buf.length; i += 0x8000) s += String.fromCharCode(...buf.subarray(i, i + 0x8000));
    return btoa(s);
  });
  const dir = fs.mkdtempSync(path.join(process.env.TUTORIAL_WORK_DIR || os.tmpdir(), 'pdf-'));
  fs.writeFileSync(path.join(dir, 'c.pdf'), Buffer.from(b64, 'base64'));
  execFileSync('pdftoppm', ['-png', '-r', '150', '-f', '1', '-l', '1', '-singlefile', path.join(dir, 'c.pdf'), path.join(dir, 'p1')]);
  const png = fs.readFileSync(path.join(dir, 'p1.png')).toString('base64');
  console.log(`pdf_paint_rendered_ms=${Date.now() - t0}`);
  fs.rmSync(dir, { recursive: true, force: true });
  await page.evaluate((data) => {
    const cover = document.querySelector('[data-tutorial-pdf-cover]');
    const page1 = document.createElement('div');
    Object.assign(page1.style, { position: 'relative', width: '900px', alignSelf: 'flex-start', boxShadow: '0 2px 8px rgba(0,0,0,.4)' });
    const img = document.createElement('img');
    img.src = `data:image/png;base64,${data}`;
    Object.assign(img.style, { width: '900px', height: 'auto', display: 'block' });
    // The tenant's Email and Contact No. values, measured on page 1 at 900 px wide.
    const blur = document.createElement('div');
    Object.assign(blur.style, { position: 'absolute', left: '332px', top: '336px', width: '516px', height: '52px',
      backdropFilter: 'blur(7px)', background: 'rgba(255,255,255,.35)' });
    page1.append(img, blur);
    cover.append(page1);
  }, png);
  console.log(`pdf_paint_total_ms=${Date.now() - t0}`);
}

const scenes = [
  {
    role: 'tenantAdmin',
    title: 'A draft contract',
    body: 'Unit A-201 for Rajesh Kumar, still a draft.',
    weight: 9,
    verifyTenantContext: true,
    run: async (page) => {
      await prepareDraft(page);
      await goto(page, `/en/dashboard/leases/${contractId}`);
      await expectText(page.getByTestId('lease-status'), 'Draft', 'Contract status');
      await pace(page, 5000);
      await pointAt(page.getByTestId('lease-status'));
    },
  },
  stepScene('Preview Contract',
    'The contract as it will be issued. Nothing is saved yet.',
    async (page) => {
      await page.getByTestId('lease-tab-documents').click();
      await page.getByRole('button', { name: 'Preview Contract', exact: true }).click();
      await page.locator('object[type="application/pdf"]').waitFor({ state: 'attached', timeout: navTimeoutMs });
      await paintPdfForCapture(page);
      await page.locator('object[type="application/pdf"]').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1100, 560);
      await pace(page, 12000);
      await pointAt(page.getByRole('button', { name: 'Confirm & Save', exact: true }));
    }, { weight: 20 }),
  stepScene('Pending Signature',
    'Generated and numbered; the contract waits for the tenant.',
    async (page) => {
      await page.getByRole('button', { name: 'Confirm & Save', exact: true }).click();
      await page.locator('object[type="application/pdf"]').waitFor({ state: 'detached', timeout: navTimeoutMs });
      await page.evaluate(() => document.querySelector('[data-tutorial-pdf-cover]')?.remove());
      await page.getByTestId('lease-status').filter({ hasText: 'Pending Signature' }).waitFor({ state: 'visible', timeout: navTimeoutMs });
      await page.getByTestId('lease-contract-number').waitFor({ state: 'visible' });
      // The narration says the contract is listed under Contract documents.
      // Product bug: the list only appears after a reload (see the header).
      await page.getByTestId('contract-documents').waitFor({ state: 'visible', timeout: 10_000 });
      await pointAt(page.getByTestId('contract-doc-CONTRACT'));
    }, { weight: 9 }),
  {
    role: 'renter:Rajesh Kumar',
    title: 'Tenant portal',
    body: 'Rajesh reviews the contract, then accepts it.',
    weight: 25,
    verifyTenantContext: false,
    run: async (page) => {
      await goto(page, '/en/dashboard/renter-portal');
      const card = page.locator('main div').filter({ hasText: 'Unit A-201' }).filter({ has: page.getByRole('button', { name: 'Accept Tenancy Contract' }) }).last();
      await card.waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(card, 'PENDING SIGNATURE', 'Portal status');
      await pace(page, 4000);
      await pointAt(card.getByText('Download Contract'));
      await pace(page, 3500);
      await card.getByRole('button', { name: 'Accept Tenancy Contract' }).click();
      const confirm = page.getByRole('dialog');
      await confirm.waitFor({ state: 'visible' });
      await pace(page, 1200);
      await confirm.getByRole('button', { name: /accept tenancy contract/i }).click();
      await page.getByTestId('lease-accepted').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await restPointer(page, 1100, 700);
      await pointAt(page.getByTestId('lease-accepted'));
    },
  },
  {
    role: 'tenantAdmin',
    title: 'Accepted by tenant',
    body: 'Still pending until it is posted.',
    weight: 13,
    verifyTenantContext: true,
    run: async (page) => {
      await goto(page, `/en/dashboard/leases/${contractId}`);
      await page.getByTestId('lease-renter-accepted').waitFor({ state: 'visible', timeout: navTimeoutMs });
      await expectText(page.getByTestId('lease-status'), 'Pending Signature', 'Contract status');
      await pointAt(page.getByTestId('lease-renter-accepted'));
      await pace(page, 4000);
      await pointAt(page.getByTestId('lease-post'));
    },
  },
];

export default { role: 'tenantAdmin', browserChannel: 'chromium', scenes };

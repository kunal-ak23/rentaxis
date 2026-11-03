// Tutorial 52 — Connect your systems: promoter API keys, the units feed and coupon deep links.
// As Rami Khalil, the outside promoter of Glide Car Wash on Oasis Crest (tutorials/seed/seed_promoter_52.py:
// two properties shared, eight units, an approved coupon ad whose link passes only the code).
//
// The promoter creates a key in the Developer tab and copies it; the proof then calls the partner API
// with that key for real (ping, units/count, units) and the terminal panels show exactly what the local
// backend answered, under the developer guide's placeholder base URL ($MIFTAH_API), never the local one.
// The coupon ad gets unitId/propertyId redirect parameters; off camera the Company Admin approves it and
// Fatima Al Zaabi (A-102) fetches her offers, so the deep-link and validation panels show the link the
// app really builds for her, and her unit is looked up in the real units response. Rotate (the old key
// still answers), revoke (it stops), then the super admin finds the new key by its pasted secret and
// revokes it; the promoter sees "Revoked by Miftah admin". Every key this take creates ends revoked.
//
// Keys and the ad edit cannot be undone in the app: the proof and the capture each start from snapshot
// `pre52` (taken right after the fixture script).
import { baseURL, seed, tenantId, validateOnly } from '../lib/context.mjs';
import { pointAt, restPointer } from '../lib/cursor.mjs';
import { narrationDurationSeconds } from '../lib/narration.mjs';
import { expectCount, expectText, pace, sceneClock } from '../lib/proof.mjs';
import { roleRouteScene, stepScene } from '../lib/scenes.mjs';

// ── where the proof calls the API (local stack only) ────────────────────────
const backendURL = process.env.TUTORIAL_BACKEND_URL
  || `http://localhost:${process.env.TUTORIAL_BACKEND_PORT || 8084}`;
if (!['localhost', '127.0.0.1'].includes(new URL(backendURL).hostname)
    || !['localhost', '127.0.0.1'].includes(new URL(baseURL).hostname)) {
  throw new Error(`Tutorial 52 calls the partner API for real; local stack only (${backendURL}, ${baseURL}).`);
}
const promoter = seed.promoter;
if (!promoter?.userId || !seed.promoterLogin) {
  throw new Error('The seed manifest has no promoter: run tutorials/seed/seed_promoter_52.py, then snapshot pre52.');
}
const [towerId, marinaId] = promoter.propertyIds;
const SHOWN_BASE = 'https://<your Miftah host>/api/v1/partner/promoter';
const KEY_NAME = 'Production sync';
const OWN_REVOKE_REASON = 'Moved to the new key';
const ADMIN_REVOKE_REASON = 'Found in a public code repository';
const UNKNOWN_UNIT = '0d1e2f30-4a5b-4c6d-8e7f-90a1b2c3d4e5';

/** What the take learned, shared by later scenes (one recorder process records one take). */
const state = { firstKey: null, secondKey: null, count: null, units: null, link: null, fatimaUnit: null };

async function partner(path, key) {
  const res = await fetch(`${backendURL}/api/v1/partner/promoter${path}`, { headers: { 'X-Api-Key': key } });
  const text = await res.text();
  let body = null;
  try { body = JSON.parse(text); } catch { body = text; }
  return { status: res.status, body };
}

async function expectPartnerStatus(path, key, status, what) {
  const res = await partner(path, key);
  if (res.status !== status) throw new Error(`${what}: expected HTTP ${status}, got ${res.status} ${JSON.stringify(res.body)}`);
  return res.body;
}

/** A signed-in API session (the app's own login), for off-camera steps. */
async function signedIn(login) {
  const res = await fetch(`${backendURL}/api/auth/login`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: login.email, password: login.password }),
  });
  if (res.status !== 200) throw new Error(`Off-camera login failed (${res.status}).`);
  const u = await res.json();
  const headers = { 'X-User-Id': String(u.id), 'X-User-Role': u.role, 'Content-Type': 'application/json' };
  if (u.tenantId) { headers['X-Tenant-Id'] = String(u.tenantId); headers['X-User-Tenant-Id'] = String(u.tenantId); }
  if (u.token) headers.Authorization = `Bearer ${u.token}`;
  return async (method, path, body) => {
    const r = await fetch(`${backendURL}${path}`, { method, headers, body: body ? JSON.stringify(body) : undefined });
    const text = await r.text();
    if (r.status >= 300) throw new Error(`${method} ${path} -> ${r.status}: ${text.slice(0, 300)}`);
    return text ? JSON.parse(text) : null;
  };
}

// ── narration-paced cues: `at(n)` waits until n words into the scene's narration ─
const WORDS = [32, 45, 29, 17, 36, 40, 58, 56, 64, 35, 21, 42, 23];
// Hold weights: words, except the super admin clip gives five words of hold to the closing clip,
// which otherwise starts late (new browser context) and is cut by the end of the narration.
const WEIGHTS = [...WORDS.slice(0, 11), 37, 28];
const secondsPerWord = (narrationDurationSeconds() + 2) / WORDS.reduce((a, b) => a + b, 0);
const wordClock = (page) => {
  const at = sceneClock(page);
  return (words) => at(words * secondsPerWord);
};

// ── the terminal / explainer panel (an overlay over the app, like the recorder's own cards) ─
async function panelOpen(page, title, { light = false } = {}) {
  await page.evaluate(({ title, light }) => {
    document.querySelector('[data-t52-panel]')?.remove();
    const root = document.createElement('div');
    root.dataset.t52Panel = 'true';
    root.innerHTML = `
      <style>
        [data-t52-panel] { position: fixed; inset: 0; z-index: 2147483600; background: ${light ? '#eef2f7' : '#0f172a'};
          font-family: Inter, ui-sans-serif, system-ui, sans-serif; opacity: 0; transition: opacity 260ms ease-out; }
        [data-t52-panel] .win { position: absolute; left: 96px; right: 96px; top: 56px; bottom: 214px; border-radius: 14px;
          background: ${light ? '#ffffff' : '#0b1020'}; box-shadow: 0 24px 60px rgba(2,6,23,.45);
          border: 1px solid ${light ? '#d8dee8' : '#1e293b'}; overflow: hidden; display: flex; flex-direction: column; }
        [data-t52-panel] .bar { height: 46px; flex: none; display: flex; align-items: center; gap: 9px; padding: 0 18px;
          background: ${light ? '#f8fafc' : '#111a2e'}; border-bottom: 1px solid ${light ? '#e2e8f0' : '#1e293b'};
          color: ${light ? '#334155' : '#94a3b8'}; font-size: 16px; }
        [data-t52-panel] .dot { width: 13px; height: 13px; border-radius: 50%; }
        [data-t52-panel] .body { flex: 1; overflow: hidden; padding: 26px 32px; }
        [data-t52-panel] pre { margin: 0; font: 18px/1.55 'SF Mono', Menlo, Consolas, monospace; color: #e2e8f0;
          white-space: pre-wrap; word-break: break-all; }
        [data-t52-panel] .ln { display: block; border-radius: 6px; transition: background 300ms; }
        [data-t52-panel] .p { color: #4ade80; } [data-t52-panel] .c { color: #64748b; }
        [data-t52-panel] .k { color: #7dd3fc; } [data-t52-panel] .s { color: #fcd34d; } [data-t52-panel] .n { color: #c4b5fd; }
        [data-t52-panel] .hl { background: rgba(250, 204, 21, .16); box-shadow: inset 4px 0 0 #facc15; }
        [data-t52-panel] .ok { background: rgba(74, 222, 128, .14); box-shadow: inset 4px 0 0 #4ade80; }
        [data-t52-panel] .no { background: rgba(248, 113, 113, .14); box-shadow: inset 4px 0 0 #f87171; }
        [data-t52-panel] .tag { margin-left: 14px; font-family: Inter, sans-serif; font-size: 15px; font-weight: 600; }
      </style>
      <div class="win"><div class="bar"><span class="dot" style="background:#f87171"></span><span class="dot" style="background:#fbbf24"></span><span class="dot" style="background:#4ade80"></span><span style="margin-left:12px"></span></div><div class="body"></div></div>`;
    root.querySelector('.bar span:last-child').textContent = title;
    document.body.append(root);
    requestAnimationFrame(() => { root.style.opacity = '1'; });
  }, { title, light });
  await pace(page, 300);
}

async function panelClose(page) {
  await page.evaluate(() => document.querySelector('[data-t52-panel]')?.remove());
}

/** Clears the terminal body (keeps the window). */
async function termClear(page, fontPx = 18) {
  await page.evaluate((fontPx) => {
    const b = document.querySelector('[data-t52-panel] .body');
    b.innerHTML = '<pre></pre>';
    b.firstChild.style.fontSize = `${fontPx}px`;
  }, fontPx);
}

/** Appends lines; kind: 'cmd' ($ prompt, typed in), 'comment', 'out' (JSON-coloured). */
async function termLines(page, lines, kind = 'out') {
  if (kind === 'cmd' && !validateOnly) {
    for (const line of lines) {
      await page.evaluate(() => {
        const pre = document.querySelector('[data-t52-panel] pre');
        const ln = document.createElement('span'); ln.className = 'ln';
        ln.innerHTML = '<span class="p">$ </span><span class="t"></span>'; pre.append(ln);
      });
      for (let i = 0; i < line.length; i += 3) {
        await page.evaluate((text) => {
          const all = document.querySelectorAll('[data-t52-panel] pre .ln .t'); all[all.length - 1].textContent = text;
        }, line.slice(0, i + 3));
        await page.waitForTimeout(18);
      }
    }
    return;
  }
  await page.evaluate(({ lines, kind }) => {
    const pre = document.querySelector('[data-t52-panel] pre');
    const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
    const json = (s) => esc(s)
      .replace(/("[^"]*")(\s*:)/g, '<span class="k">$1</span>$2')
      .replace(/(:\s*)("[^"]*")/g, '$1<span class="s">$2</span>')
      .replace(/(:\s*)(-?\d+(?:\.\d+)?)/g, '$1<span class="n">$2</span>');
    for (const line of lines) {
      const ln = document.createElement('span');
      ln.className = 'ln';
      ln.dataset.text = line;
      ln.innerHTML = kind === 'cmd' ? `<span class="p">$ </span>${esc(line)}`
        : kind === 'comment' ? `<span class="c">${esc(line)}</span>` : json(line);
      pre.append(ln);
    }
  }, { lines, kind });
}

/** Marks the first line containing `text` (hl / ok / no) with an optional tag after it. */
async function termMark(page, text, cls = 'hl', tag = '') {
  const found = await page.evaluate(({ text, cls, tag }) => {
    const ln = [...document.querySelectorAll('[data-t52-panel] pre .ln')].find((el) => el.textContent.includes(text));
    if (!ln) return false;
    ln.classList.add(cls);
    if (tag) {
      const t = document.createElement('span'); t.className = 'tag';
      t.style.color = cls === 'no' ? '#fca5a5' : cls === 'ok' ? '#86efac' : '#fde047';
      t.textContent = tag; ln.append(t);
    }
    return true;
  }, { text, cls, tag });
  if (!found) throw new Error(`Terminal panel: no line contains "${text}".`);
}

const pretty = (obj) => JSON.stringify(obj, null, 2).split('\n');
/** The units page as JSON with one unit per line (the same document, whitespace only). */
function unitsLines(body) {
  const { content, ...rest } = body;
  return ['{', '  "content": [',
    ...content.map((u, i) => `    ${JSON.stringify(u).replace(/","/g, '", "').replace(/":"/g, '": "')}${i < content.length - 1 ? ',' : ''}`),
    '  ],',
    ...Object.entries(rest).map(([k, v], i, all) => `  "${k}": ${JSON.stringify(v)}${i < all.length - 1 ? ',' : ''}`),
    '}'];
}
const curl = (path) => `curl -s -H "X-Api-Key: $MIFTAH_KEY" "$MIFTAH_API${path}"`;
const keyPrefix = (secret) => `${secret.slice(0, 12)}…`;

// ── app locators ────────────────────────────────────────────────────────────
const tab = (page, name) => page.getByRole('tab', { name, exact: true });
const keyRows = (page) => page.locator('main table tbody tr');
const keyRow = (page, status) => keyRows(page).filter({ hasText: status }).first();
const revealDialog = (page) => page.getByRole('dialog').filter({ hasText: 'Your new API key' });

async function readRevealedSecret(page) {
  const secret = (await revealDialog(page).getByTestId('api-key-secret').innerText()).trim();
  if (!/^mfk_live_[A-Za-z0-9]{30,}$/.test(secret)) throw new Error(`Revealed key has an unexpected shape (${secret.slice(0, 12)}…).`);
  return secret;
}

async function copyAndClose(page, at, copyAt, closeAt) {
  const dialog = revealDialog(page);
  await at(copyAt);
  await dialog.getByRole('button', { name: 'Copy', exact: true }).click();
  await expectText(dialog.getByRole('button', { name: 'Copied', exact: true }), 'Copied', 'Copy button');
  const copied = await page.evaluate(() => navigator.clipboard.readText());
  await at(closeAt);
  await dialog.getByRole('button', { name: "I've saved it", exact: true }).click();
  await dialog.waitFor({ state: 'hidden' });
  return copied;
}

const scenes = [
  roleRouteScene('promoter', '/en/dashboard/promoter', 'Create an API key',
    'Promoter portal, Developer tab: Create key, name it, Create.', {
    weight: WORDS[0],
    verifyTenantContext: false,
    afterNavigation: async (page) => {
      const at = wordClock(page);
      await page.context().grantPermissions(['clipboard-read', 'clipboard-write'], { origin: baseURL });
      await expectText(page.locator('main'), `Your ads for ${seed.tenant.name}`, 'Promoter portal subtitle');
      await restPointer(page, 1200, 420);
      await at(13);
      await tab(page, 'Developer').click();
      await page.getByText('No API keys yet', { exact: false }).waitFor({ state: 'visible' });
      await at(16);
      await pointAt(page.getByText('API keys let your own systems', { exact: false }));
      await at(19);
      await page.getByRole('button', { name: 'Create key', exact: true }).click();
      const name = page.getByPlaceholder('e.g. Production sync');
      await name.click();
      await name.pressSequentially(KEY_NAME, { delay: validateOnly ? 0 : 70 });
      await at(29);
      await page.getByRole('button', { name: 'Create', exact: true }).click();
      await revealDialog(page).waitFor({ state: 'visible' });
    },
  }),
  stepScene('Shown once',
    'The full key appears only now. Copy it into your secret manager.',
    async (page) => {
      const at = wordClock(page);
      const dialog = revealDialog(page);
      await expectText(dialog, 'This is the only time you will see this key', 'One-time warning');
      state.firstKey = await readRevealedSecret(page);
      await at(1);
      await pointAt(dialog.getByText('This is the only time', { exact: false }));
      await at(8);
      await pointAt(dialog.getByTestId('api-key-secret'));
      const copied = await copyAndClose(page, at, 20, 27);
      if (copied !== state.firstKey) throw new Error('Clipboard does not hold the revealed key.');
      await expectCount(keyRows(page), 1, 'key rows');
      const row = keyRows(page).first();
      await expectText(row, KEY_NAME, 'Key name');
      await expectText(row, 'Active', 'Key status');
      await expectText(row, keyPrefix(state.firstKey), 'Key prefix only');
      if ((await row.innerText()).includes(state.firstKey)) throw new Error('The table shows the full secret.');
      await at(37);
      await pointAt(row.locator('td').nth(4));
      await at(41);
      await pointAt(row.locator('td').nth(1));
    }, { weight: WORDS[1], verifyTenantContext: false }),
  stepScene('Ping',
    'Send the key in the X-Api-Key header. Ping says who the key acts for.',
    async (page) => {
      const at = wordClock(page);
      const ping = await expectPartnerStatus('/ping', state.firstKey, 200, 'Ping');
      if (ping.promoterUserId !== promoter.userId || ping.tenantId !== tenantId) {
        throw new Error(`Ping answered for ${JSON.stringify(ping)}, not this promoter.`);
      }
      await panelOpen(page, 'glide-sync — your server');
      await termClear(page);
      await termLines(page, ['# The key lives in your secret manager; here it is an environment variable.'], 'comment');
      await termLines(page, [`export MIFTAH_API="${SHOWN_BASE}"`, `export MIFTAH_KEY="${keyPrefix(state.firstKey)}"`], 'cmd');
      await at(6);
      await termLines(page, [curl('/ping')], 'cmd');
      await termLines(page, pretty(ping));
      await at(17);
      await termMark(page, '"promoterUserId"', 'hl');
      await termMark(page, '"tenantId"', 'hl');
    }, { weight: WORDS[2], verifyTenantContext: false }),
  stepScene('Units count',
    'Properties, units and a fingerprint of the whole list.',
    async (page) => {
      const at = wordClock(page);
      const count = await expectPartnerStatus('/units/count', state.firstKey, 200, 'Units count');
      if (count.properties !== 2 || count.units !== 8 || !/^sha256:[0-9a-f]{64}$/.test(count.fingerprint)) {
        throw new Error(`Units count: expected 2 properties, 8 units and a sha256 fingerprint, got ${JSON.stringify(count)}.`);
      }
      state.count = count;
      await termClear(page);
      await termLines(page, [curl('/units/count')], 'cmd');
      await termLines(page, pretty(count));
      await at(6);
      await termMark(page, '"properties"', 'hl');
      await termMark(page, '"units"', 'hl');
      await at(11);
      await termMark(page, '"fingerprint"', 'hl');
    }, { weight: WORDS[3], verifyTenantContext: false }),
  stepScene('The units',
    'Each unit: unitId, unitNumber, propertyId, propertyName. No tenant data.',
    async (page) => {
      const at = wordClock(page);
      const units = await expectPartnerStatus('/units?page=0&size=500', state.firstKey, 200, 'Units');
      const rows = units.content ?? [];
      if (units.totalElements !== state.count.units || rows.length !== state.count.units || units.totalPages !== 1) {
        throw new Error(`Units: expected ${state.count.units} rows on one page, got ${rows.length} of ${units.totalElements}.`);
      }
      if (units.fingerprint !== state.count.fingerprint) throw new Error('Units page fingerprint differs from units/count.');
      for (const row of rows) {
        const keys = Object.keys(row).sort().join(',');
        if (keys !== 'propertyId,propertyName,unitId,unitNumber') throw new Error(`Unit row carries ${keys}.`);
        if (![towerId, marinaId].includes(row.propertyId)) throw new Error(`Unit ${row.unitNumber} is outside the shared properties.`);
      }
      state.units = units;
      await termClear(page, 16);
      await termLines(page, [curl('/units?page=0&size=500') + ' > units.json && cat units.json'], 'cmd');
      await termLines(page, unitsLines(units));
      const first = rows[0];
      await at(13);
      await termMark(page, first.unitId, 'hl');
      await at(32);
      await termMark(page, '"fingerprint"', 'hl');
    }, { weight: WORDS[4], verifyTenantContext: false }),
  stepScene('Sync only when it changes',
    'Poll units/count. Same fingerprint: nothing to do. Different: fetch every page and replace your list.',
    async (page) => {
      const at = wordClock(page);
      const again = await expectPartnerStatus('/units/count', state.firstKey, 200, 'Units count again');
      if (again.fingerprint !== state.count.fingerprint) throw new Error('The fingerprint changed with no change to the units.');
      await termClear(page);
      await termLines(page, [`# stored after the last sync: ${state.count.fingerprint}`, '# every 15 minutes:'], 'comment');
      await at(9);
      await termLines(page, [curl('/units/count')], 'cmd');
      await termLines(page, pretty(again));
      await at(17);
      await termMark(page, '"fingerprint"', 'ok', '= stored: nothing changed, stop');
      await at(27);
      await termLines(page, [
        '# differs? GET /units?page=0..totalPages-1&size=500,',
        '#   restart if any page\'s fingerprint differs from page 0\'s,',
        '#   then replace your list in one step and store the new fingerprint.',
      ], 'comment');
    }, { weight: WORDS[5], verifyTenantContext: false }),
  stepScene('Unit ID on the coupon',
    'Edit the coupon: add unitId from Unit ID and propertyId from Property ID. Saving sends it for approval.',
    async (page) => {
      const at = wordClock(page);
      await panelClose(page);
      await at(8);
      await tab(page, 'Ads').click();
      const adRow = page.locator('main table tbody tr').filter({ hasText: promoter.adTitle }).first();
      await expectText(adRow, 'Live', 'Coupon ad is live');
      await adRow.getByRole('button', { name: 'Edit', exact: true }).click();
      const firstName = page.getByLabel('Parameter name 1', { exact: true });
      await firstName.waitFor({ state: 'visible' });
      if ((await firstName.inputValue()) !== 'code') throw new Error('The coupon link should start with one parameter, code.');
      await firstName.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
      await at(12);
      await pointAt(page.getByLabel('Value from 1', { exact: true }));
      await at(17);
      for (const [n, name, source] of [[2, 'unitId', 'UNIT_ID'], [3, 'propertyId', 'PROPERTY_ID']]) {
        if (n === 3) await at(26);
        await page.getByRole('button', { name: 'Add parameter', exact: true }).click();
        const input = page.getByLabel(`Parameter name ${n}`, { exact: true });
        await input.click();
        await input.pressSequentially(name, { delay: validateOnly ? 0 : 80 });
        await page.getByLabel(`Value from ${n}`, { exact: true }).selectOption(source);
      }
      const example = page.getByTestId('redirect-example');
      await example.evaluate((el) => el.scrollIntoView({ behavior: 'smooth', block: 'center' }));
      await expectText(page.getByTestId('redirect-shares-note'), 'asked to confirm', 'Consent note');
      await expectText(example,
        `${promoter.redirectUrl}?code=${promoter.couponCode}&unitId=3f2b8c1e-0000-4000-8000-000000000002&propertyId=3f2b8c1e-0000-4000-8000-000000000001`,
        'Example link');
      await at(34);
      await pointAt(page.getByTestId('redirect-shares-note'));
      await at(41);
      await pointAt(example);
      await at(48);
      await page.getByRole('button', { name: 'Save', exact: true }).click();
      await expectText(page.getByRole('status').filter({ hasText: 'waiting' }), "waiting for an administrator's approval", 'Saved notice');
      const me = await signedIn(seed.promoterLogin);
      const ads = await me('GET', '/api/v1/promoter/ads?size=100');
      const ad = (ads.content ?? ads).find((a) => a.id === promoter.adId);
      const sources = (ad?.redirectParams ?? []).map((p) => `${p.name}=${p.source}`).join('&');
      if (sources !== 'code=COUPON_CODE&unitId=UNIT_ID&propertyId=PROPERTY_ID' || ad.approvalStatus !== 'PENDING') {
        throw new Error(`Saved ad: params ${sources}, ${ad?.approvalStatus}.`);
      }
      await pointAt(page.getByRole('status').filter({ hasText: 'waiting' }));
    }, { weight: WORDS[6], verifyTenantContext: false }),
  stepScene('On the tenant\'s phone',
    'Copy, code copied, agree to share, then the phone opens the https link: your app or your website.',
    async (page) => {
      const at = wordClock(page);
      // Off camera: the Company Admin approves the edit, and Fatima Al Zaabi (A-102) loads her offers.
      const admin = await signedIn(seed.adminLogin);
      const pending = await (await signedIn(seed.promoterLogin))('GET', '/api/v1/promoter/ads?size=100');
      const ad = (pending.content ?? pending).find((a) => a.id === promoter.adId);
      await admin('POST', `/api/v1/promotions/ads/${promoter.adId}/approve`, { expectedSubmittedAt: ad.submittedAt });
      const fatima = seed.renterLogins.find((login) => login.name === 'Fatima Al Zaabi');
      const offers = await (await signedIn(fatima))('GET', '/api/v1/promotions/offers');
      const card = offers.find((o) => o.id === promoter.adId);
      if (!card) throw new Error('The approved coupon is not in Fatima\'s offers.');
      const unit = state.units.content.find((u) => u.unitNumber === 'A-102');
      if (!unit || unit.propertyId !== towerId) throw new Error('A-102 is not in the units feed under the tower.');
      const expected = `${promoter.redirectUrl}?code=${promoter.couponCode}&unitId=${unit.unitId}&propertyId=${unit.propertyId}`;
      if (card.redirectUrl !== expected) throw new Error(`Fatima's link: expected ${expected}, got ${card.redirectUrl}.`);
      if (!(card.sharedFields ?? []).length) throw new Error('The link shares unit details but asks for no consent.');
      state.link = card.redirectUrl;
      state.fatimaUnit = unit;

      await panelOpen(page, 'On the tenant\'s phone', { light: true });
      await page.evaluate(({ link }) => {
        const body = document.querySelector('[data-t52-panel] .body');
        const step = (n, title, text) => `<div class="st" style="flex:1;min-width:0;border-radius:14px;padding:20px 20px 22px;background:#f8fafc;border:2px solid #e2e8f0;opacity:.18;transition:opacity 400ms, border-color 400ms">
            <div style="width:34px;height:34px;border-radius:50%;background:#0f172a;color:#fff;display:flex;align-items:center;justify-content:center;font-weight:700;font-size:17px;margin-bottom:12px">${n}</div>
            <div style="font-size:22px;font-weight:700;color:#0f172a;margin-bottom:8px">${title}</div>
            <div style="font-size:17px;line-height:1.45;color:#475569">${text}</div></div>`;
        body.innerHTML = `<div style="display:flex;flex-direction:column;gap:26px;height:100%">
          <div style="display:flex;gap:18px">
            ${step(1, 'Tap Copy', 'On the coupon in the Miftah app.')}
            ${step(2, 'Code copied', 'The coupon code is on the clipboard.')}
            ${step(3, 'Agree to share', 'Asked first when the link carries tenant details, such as the unit. Declined: nothing opens.')}
            ${step(4, 'The phone opens the link', 'Handed to the phone itself, not an in-app browser.')}
          </div>
          <div class="st" style="opacity:.18;transition:opacity 400ms;border-radius:12px;background:#0b1020;padding:16px 22px;font:18px/1.5 'SF Mono',Menlo,monospace;color:#fcd34d;word-break:break-all"></div>
          <div style="display:flex;gap:18px">
            <div class="st" style="flex:1;opacity:.18;transition:opacity 400ms;border-radius:14px;padding:20px;background:#ecfdf5;border:2px solid #86efac">
              <div style="font-size:22px;font-weight:700;color:#065f46;margin-bottom:6px">Your app is installed</div>
              <div style="font-size:17px;color:#065f46;line-height:1.45">It has verified Universal Links (iPhone) or App Links (Android) for offers.glidewash.example: the phone opens your app with the full link.</div></div>
            <div class="st" style="flex:1;opacity:.18;transition:opacity 400ms;border-radius:14px;padding:20px;background:#eff6ff;border:2px solid #93c5fd">
              <div style="font-size:22px;font-weight:700;color:#1e3a8a;margin-bottom:6px">Otherwise</div>
              <div style="font-size:17px;color:#1e3a8a;line-height:1.45">The same link opens your website in the browser.</div></div>
            <div class="st" style="flex:1;opacity:.18;transition:opacity 400ms;border-radius:14px;padding:20px;background:#fef2f2;border:2px solid #fca5a5">
              <div style="font-size:22px;font-weight:700;color:#991b1b;margin-bottom:6px">Refused</div>
              <div style="font-size:17px;color:#991b1b;line-height:1.45">Custom schemes such as <span style="font-family:Menlo,monospace">glidewash://redeem</span>: any app could claim them. Links must be https on your registered domains.</div></div>
          </div></div>`;
        body.querySelectorAll('.st')[4].textContent = link;
      }, { link: state.link });
      const reveal = (i) => page.evaluate((i) => {
        const el = document.querySelectorAll('[data-t52-panel] .st')[i];
        el.style.opacity = '1';
        if (el.style.borderColor === 'rgb(226, 232, 240)') el.style.borderColor = '#0f172a';
      }, i);
      await reveal(0);
      await at(7);
      await reveal(1);
      await at(11);
      await reveal(2);
      await at(21);
      await reveal(3);
      await reveal(4);
      await at(33);
      await reveal(5);
      await at(45);
      await reveal(6);
      await at(51);
      await reveal(7);
    }, { weight: WORDS[7], verifyTenantContext: false }),
  stepScene('Validate the redemption',
    'Look the link\'s unitId up in your synced units. Not there: not one of your units.',
    async (page) => {
      const at = wordClock(page);
      const unit = state.fatimaUnit;
      if (state.units.content.some((u) => u.unitId === UNKNOWN_UNIT)) throw new Error('The stand-in unknown unit id is real.');
      await panelOpen(page, 'glide-sync — your server');
      await termClear(page);
      await termLines(page, ['# the link a tenant in A-102 opened:'], 'comment');
      await termLines(page, [state.link]);
      await termMark(page, `unitId=${unit.unitId}`, 'hl');
      await at(13);
      await termLines(page, [`jq '.content[] | select(.unitId == "${unit.unitId}")' units.json`], 'cmd');
      await termLines(page, pretty(unit));
      await at(22);
      await termMark(page, '"unitNumber"', 'ok', 'one of your units');
      await termMark(page, '"propertyName"', 'ok');
      await at(32);
      await termLines(page, [`jq '.content[] | select(.unitId == "${UNKNOWN_UNIT}")' units.json`], 'cmd');
      await termLines(page, ['# (nothing): not one of your units'], 'comment');
      await termMark(page, '(nothing)', 'no');
      await at(44);
      await termLines(page, ['# already redeemed, or suspicious? your own rule: Miftah does not track redemptions'], 'comment');
    }, { weight: WORDS[8], verifyTenantContext: false }),
  stepScene('Rotate a key',
    'Rotate: a new key, shown once. The old one is Expiring and keeps working for 24 hours.',
    async (page) => {
      const at = wordClock(page);
      await panelClose(page);
      await tab(page, 'Developer').click();
      await keyRows(page).first().waitFor({ state: 'visible' });
      const row = keyRow(page, 'Active');
      await expectText(row, KEY_NAME, 'Key before rotation');
      await at(7);
      await row.getByRole('button', { name: 'Rotate', exact: true }).click();
      await revealDialog(page).waitFor({ state: 'visible' });
      await expectText(revealDialog(page), 'The previous key keeps working for 24 hours', 'Rotate note');
      state.secondKey = await readRevealedSecret(page);
      if (state.secondKey === state.firstKey) throw new Error('Rotate returned the same key.');
      await at(12);
      await pointAt(revealDialog(page).getByText('keeps working for 24 hours', { exact: false }));
      const copied = await copyAndClose(page, at, 15, 18);
      if (copied !== state.secondKey) throw new Error('Clipboard does not hold the rotated key.');
      await expectCount(keyRows(page), 2, 'key rows after rotation');
      const expiring = keyRow(page, 'Expiring');
      await expectText(expiring, keyPrefix(state.firstKey), 'Old key is expiring');
      await expectText(expiring, 'Stops working', 'Expiry date');
      await expectText(keyRow(page, 'Active'), keyPrefix(state.secondKey), 'New key is active');
      await expectPartnerStatus('/ping', state.firstKey, 200, 'Old key during the 24 hours');
      await expectPartnerStatus('/ping', state.secondKey, 200, 'New key');
      await at(21);
      await pointAt(expiring.locator('td').nth(4));
      await at(30);
      await pointAt(keyRow(page, 'Active').locator('td').nth(4));
    }, { weight: WORDS[9], verifyTenantContext: false }),
  stepScene('Revoke the old key',
    'Revoke, with an optional reason. It stops working at once.',
    async (page) => {
      const at = wordClock(page);
      await at(5);
      await keyRow(page, 'Expiring').getByRole('button', { name: 'Revoke', exact: true }).click();
      const dialog = page.getByRole('dialog').filter({ hasText: 'Revoke' });
      const reason = dialog.getByRole('textbox');
      await reason.click();
      await reason.pressSequentially(OWN_REVOKE_REASON, { delay: validateOnly ? 0 : 55 });
      await at(14);
      await dialog.getByRole('button', { name: 'Revoke key', exact: true }).click();
      await dialog.waitFor({ state: 'hidden' });
      const revoked = keyRow(page, `Revoked: ${OWN_REVOKE_REASON}`);
      await expectText(revoked, keyPrefix(state.firstKey), 'Old key revoked');
      await expectPartnerStatus('/ping', state.firstKey, 401, 'Revoked old key');
      await expectPartnerStatus('/ping', state.secondKey, 200, 'New key after revoking the old one');
      await pointAt(revoked.locator('td').nth(4));
    }, { weight: WORDS[10], verifyTenantContext: false }),
  roleRouteScene('superadmin', '/en/superadmin/api-keys', 'A leaked key',
    'Super admin, API keys: paste the key to find it, revoke it with a required reason.', {
    weight: WEIGHTS[11],
    verifyTenantContext: false,
    afterNavigation: async (page) => {
      const at = wordClock(page);
      const search = page.getByTestId('api-keys-search');
      await search.waitFor({ state: 'visible' });
      await restPointer(page, 1100, 380);
      await at(9);
      await search.fill(state.secondKey);
      const rows = page.locator('main table tbody tr');
      await page.waitForFunction(() => document.querySelectorAll('main table tbody tr').length === 1, null, { timeout: 15_000 });
      const row = rows.first();
      await expectText(row, seed.tenant.name, 'Organisation');
      await expectText(row, promoter.name, 'Promoter');
      await expectText(row, keyPrefix(state.secondKey).slice(0, -1), 'Prefix');
      await expectText(row, 'Active', 'Status before');
      await at(16);
      await pointAt(row);
      await at(23);
      await row.getByTestId('api-key-revoke').click();
      const reason = page.getByTestId('api-key-revoke-reason');
      await reason.click();
      await reason.pressSequentially(ADMIN_REVOKE_REASON, { delay: validateOnly ? 0 : 50 });
      await at(31);
      await page.getByTestId('api-key-revoke-confirm').click();
      await row.getByTestId('api-key-status').filter({ hasText: 'Revoked' }).waitFor({ state: 'visible', timeout: 15_000 });
      await page.getByTestId('api-key-revoke-dialog').waitFor({ state: 'hidden' });
      await expectPartnerStatus('/ping', state.secondKey, 401, 'Key revoked by the super admin');
      await pointAt(row.getByTestId('api-key-status'));
    },
  }),
  roleRouteScene('promoter', '/en/dashboard/promoter', 'Revoked by Miftah admin',
    'The promoter sees who revoked the key, and why.', {
    weight: WEIGHTS[12],
    verifyTenantContext: false,
    afterNavigation: async (page) => {
      const at = wordClock(page);
      await restPointer(page, 1200, 420);
      await tab(page, 'Developer').click();
      await keyRows(page).nth(1).waitFor({ state: 'visible' });
      await expectCount(keyRows(page), 2, 'key rows at the end');
      const byAdmin = keyRow(page, `Revoked by Miftah admin: ${ADMIN_REVOKE_REASON}`);
      await expectText(byAdmin, keyPrefix(state.secondKey), 'Admin-revoked key');
      await expectText(keyRow(page, `Revoked: ${OWN_REVOKE_REASON}`), keyPrefix(state.firstKey), 'Promoter-revoked key');
      for (const key of [state.firstKey, state.secondKey]) await expectPartnerStatus('/units/count', key, 401, 'Every key of this take');
      await at(3);
      await pointAt(byAdmin.locator('td').nth(4));
      await at(17);
      await pointAt(page.getByRole('button', { name: 'Create key', exact: true }));
    },
  }),
];

export default {
  role: 'promoter',
  anchored: true,
  warmup: [{ role: 'promoter', path: '/en/dashboard/promoter' }, { role: 'superadmin', path: '/en/superadmin/api-keys' }],
  scenes,
};

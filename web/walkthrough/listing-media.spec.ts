import { test, expect, type Page } from '@playwright/test';
import zlib from 'node:zlib';

/**
 * Bug 26/27 sweep: listing photos stored in the organisation's private container
 * actually render — for the admin (listings table + editor Media tab), for an
 * anonymous visitor on the public listing page, and for a renter on the
 * marketplace — and a draft's photo is not public. Also ux6 item 2 (promotion
 * image upload) and the ad date shift (bug 28) through the real UI.
 *
 * Runs against a local stack only (WT_BASE_URL web, WT_BACKEND_URL backend, a
 * scratch database and Azurite). Seeding uses the signed bearer token the
 * backend issues at login — never hand-made identity headers.
 */

const BACKEND = process.env.WT_BACKEND_URL || 'http://localhost:8090';
const SUFFIX = Date.now().toString(36);
const PASSWORD = 'Sweep@12345';

/** A real 64×48 PNG (solid colour), so the browser decodes it and naturalWidth > 0. */
function png(width = 64, height = 48, rgb = [20, 140, 200]): Buffer {
    const crcTable = Array.from({ length: 256 }, (_, n) => {
        let c = n;
        for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
        return c >>> 0;
    });
    const crc = (buf: Buffer) => {
        let c = 0xffffffff;
        for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
        return (c ^ 0xffffffff) >>> 0;
    };
    const chunk = (type: string, data: Buffer) => {
        const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
        const td = Buffer.concat([Buffer.from(type), data]);
        const c = Buffer.alloc(4); c.writeUInt32BE(crc(td));
        return Buffer.concat([len, td, c]);
    };
    const ihdr = Buffer.alloc(13);
    ihdr.writeUInt32BE(width, 0); ihdr.writeUInt32BE(height, 4);
    ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
    const row = Buffer.concat([Buffer.from([0]), Buffer.from(Array.from({ length: width }, () => rgb).flat())]);
    const raw = Buffer.concat(Array.from({ length: height }, () => row));
    return Buffer.concat([
        Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
        chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
    ]);
}

type Session = { id: string; role: string; tenantId: string | null; token: string };

async function login(email: string, password: string): Promise<Session> {
    const res = await fetch(`${BACKEND}/api/auth/login`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ email, password }),
    });
    if (!res.ok) throw new Error(`login ${email} → ${res.status}`);
    const body = await res.json();
    if (!body.token) throw new Error('backend issued no bearer token (APP_AUTH_TOKEN_SECRET unset?)');
    return { id: body.id, role: body.role, tenantId: body.tenantId, token: body.token };
}

async function api<T>(s: Session, method: string, path: string, body?: unknown): Promise<T> {
    const headers: Record<string, string> = { Authorization: `Bearer ${s.token}` };
    if (s.tenantId) headers['X-Tenant-Id'] = s.tenantId;
    let payload: BodyInit | undefined;
    if (body instanceof FormData) payload = body;
    else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
    const res = await fetch(`${BACKEND}${path}`, { method, headers, body: payload });
    const text = await res.text();
    if (res.status >= 400) throw new Error(`${method} ${path} → ${res.status}: ${text.slice(0, 300)}`);
    return (text ? JSON.parse(text) : {}) as T;
}

type Fx = {
    adminEmail: string; renterEmail: string; tenantSlug: string;
    published: { id: string; slug: string; title: string }; draft: { id: string; title: string };
    publishedMediaId: string; draftMediaId: string;
};
let fx: Fx;

async function uiLogin(page: Page, email: string, locale = 'en') {
    await page.goto(`/${locale}/auth/login`);
    await page.locator('input[type="email"]').fill(email);
    await page.locator('input[type="password"]').fill(PASSWORD);
    await page.locator('input[type="password"]').press('Enter');
    await page.waitForURL(/\/dashboard/, { timeout: 30_000 });
}

/** Every <img> matching `selector` has loaded real pixels from a 200 response. */
async function expectImagesLoaded(page: Page, selector: string, min = 1) {
    const imgs = page.locator(selector);
    await expect.poll(async () => imgs.count(), { timeout: 20_000 }).toBeGreaterThanOrEqual(min);
    await expect.poll(async () => imgs.evaluateAll(els =>
        els.every(e => (e as HTMLImageElement).complete && (e as HTMLImageElement).naturalWidth > 0)),
    { timeout: 20_000 }).toBe(true);
    const srcs = await imgs.evaluateAll(els => els.map(e => (e as HTMLImageElement).currentSrc));
    for (const src of srcs) {
        const res = await page.request.get(src);
        expect(res.status(), src).toBe(200);
        expect(res.headers()['content-type'], src).toBe('image/png');
        expect(res.headers()['x-content-type-options'], src).toBe('nosniff');
        expect(src, 'never a raw blob URL').not.toContain('devstoreaccount1');
    }
    return srcs;
}

test.describe.configure({ mode: 'serial' });

test('00 seed an organisation with a published and a draft listing, each with a photo', async () => {
    const sa = await login('admin@rentaxis.com', 'admin123');
    const adminEmail = `ux6-admin-${SUFFIX}@sweep.test`;
    const reg = await fetch(`${BACKEND}/api/auth/register`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fullName: 'Sweep Admin', companyName: `Sweep Homes ${SUFFIX}`, email: adminEmail, password: PASSWORD }),
    }).then(r => r.json());
    const tenantId: string = reg.tenantId;
    await api(sa, 'PUT', `/api/admin/tenants/${tenantId}/features/LISTINGS`, { enabled: true });
    const admin = await login(adminEmail, PASSWORD);

    const property = await api<{ id: string }>(admin, 'POST', '/api/v1/properties',
        { nameEn: `Palm Court ${SUFFIX}`, nameAr: `Palm Court ${SUFFIX}`, emirate: 'DUBAI', address: 'Al Barsha 1, Dubai', type: 'RESIDENTIAL' });
    const unit = async (n: string) => api<{ id: string }>(admin, 'POST', '/api/v1/units',
        { property: { id: property.id }, unitNumber: n, type: 'BHK2', sizeSqft: 1100, expectedRent: 95000 });
    const u1 = await unit(`P-${SUFFIX}`);
    const u2 = await unit(`D-${SUFFIX}`);

    const listing = async (unitId: string, title: string) => api<{ id: string; slug: string }>(admin, 'POST', '/api/listings',
        { unitId, titleEn: title, bedrooms: 2, bathrooms: 2, annualRent: 95000, lat: 25.11, lng: 55.2 });
    const pub = await listing(u1.id, `Sunny 2BR ${SUFFIX}`);
    const draft = await listing(u2.id, `Draft 2BR ${SUFFIX}`);

    const upload = async (id: string, rgb: number[]) => {
        const form = new FormData();
        form.append('file', new Blob([new Uint8Array(png(64, 48, rgb))], { type: 'image/png' }), 'photo.png');
        form.append('isCover', 'true');
        return api<{ id: string; url: string }>(admin, 'POST', `/api/listings/${id}/media`, form);
    };
    const pm = await upload(pub.id, [20, 140, 200]);
    const dm = await upload(draft.id, [200, 80, 20]);
    expect(pm.url).toBe(`/api/listings/${pub.id}/media/${pm.id}/file`);
    await api(admin, 'POST', `/api/listings/${pub.id}/publish`);
    const detail = await api<{ tenantSlug: string; slug: string; media: { url: string }[] }>(admin, 'GET', `/api/listings/${pub.id}`);
    expect(detail.media[0].url).not.toContain('devstoreaccount1');

    // A renter with a portal login, for the marketplace.
    const renterEmail = `ux6-renter-${SUFFIX}@sweep.test`;
    const renter = await api<{ userId: string }>(admin, 'POST', '/api/v1/renters',
        { nameEn: 'Sweep Renter', nameAr: 'Sweep Renter', email: renterEmail, phone: '+971501112233',
          primaryLanguage: 'EN', createPortalAccount: true });
    await api(admin, 'PUT', `/api/admin/users/${renter.userId}`,
        { email: renterEmail, password: PASSWORD, name: 'Sweep Renter', role: 'RENTER', tenantId });

    fx = {
        adminEmail, renterEmail, tenantSlug: detail.tenantSlug,
        published: { id: pub.id, slug: detail.slug, title: `Sunny 2BR ${SUFFIX}` },
        draft: { id: draft.id, title: `Draft 2BR ${SUFFIX}` },
        publishedMediaId: pm.id, draftMediaId: dm.id,
    };
});

for (const locale of ['en', 'ar'] as const) {
    for (const viewport of [{ width: 1366, height: 800 }, { width: 390, height: 844 }]) {
        test(`admin: listings table and Media tab show the photos (${locale}, ${viewport.width}px)`, async ({ page }) => {
            await page.setViewportSize(viewport);
            await uiLogin(page, fx.adminEmail, locale);
            await page.goto(`/${locale}/dashboard/listings`);
            const srcs = await expectImagesLoaded(page, 'img[src*="/media/"]', 2);
            expect(srcs.every(s => s.includes('/api/proxy/listings/'))).toBe(true);

            await page.goto(`/${locale}/dashboard/listings/${fx.draft.id}`);
            await page.getByRole('tab').nth(1).click();
            await expectImagesLoaded(page, '[role="tabpanel"] img', 1);
            // No horizontal scroll at phone width.
            const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
            expect(overflow).toBeLessThanOrEqual(1);
        });
    }
}

test('anonymous: the public listing page shows the photo; a draft photo is not public', async ({ page }) => {
    await page.goto(`/l/${fx.tenantSlug}/${fx.published.slug}`);
    const srcs = await expectImagesLoaded(page, `img[src*="/api/v1/public/listing-media/"]`, 1);
    expect(srcs[0]).toContain(fx.publishedMediaId);

    const draftPublic = await page.request.get(`/api/v1/public/listing-media/${fx.draftMediaId}`);
    expect(draftPublic.status()).toBe(404);
    const staffAnon = await page.request.get(`/api/proxy/listings/${fx.draft.id}/media/${fx.draftMediaId}/file`);
    expect(staffAnon.status()).not.toBe(200);
});

test('renter: the marketplace card and detail page show the photo', async ({ page }) => {
    await uiLogin(page, fx.renterEmail);
    await page.goto(`/en/marketplace/${fx.tenantSlug}`);
    await expectImagesLoaded(page, `img[src*="/api/v1/public/listing-media/"]`, 1);
    await page.goto(`/en/marketplace/${fx.tenantSlug}/${fx.published.slug}`);
    await expectImagesLoaded(page, `img[src*="/api/v1/public/listing-media/"]`, 1);
});

test('admin: a promotion logo and artwork are uploaded and previewed; an ad keeps its dates on edit', async ({ page }) => {
    await uiLogin(page, fx.adminEmail);
    await page.goto('/en/dashboard/promotions');
    await page.getByRole('tab', { name: 'Businesses' }).click();
    await page.getByRole('button', { name: 'Add business' }).click();
    await page.getByLabel('Name (English)').fill(`Spice Bazaar ${SUFFIX}`);
    await page.getByLabel('Logo: Upload image').setInputFiles({ name: 'logo.png', mimeType: 'image/png', buffer: png(32, 32, [230, 190, 70]) });
    const [logoSrc] = await expectImagesLoaded(page, 'img[alt="Logo"]', 1);
    expect(logoSrc).toContain('/api/proxy/v1/promotions/images/');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText(`Spice Bazaar ${SUFFIX}`)).toBeVisible();
    // Saved on an active business: the renter app's anonymous route now serves it.
    const file = logoSrc.split('/').pop()!;
    const pub = await page.request.get(`/api/v1/public/promo-images/${file}`);
    expect(pub.status()).toBe(200);
    expect(pub.headers()['cache-control']).toContain('public');

    await page.getByRole('tab', { name: 'Ads' }).click();
    await page.getByRole('button', { name: 'Add ad' }).click();
    await page.getByLabel('Title (English)').fill(`Brunch ${SUFFIX}`);
    await page.getByLabel('Artwork: Upload image').setInputFiles({ name: 'art.png', mimeType: 'image/png', buffer: png(120, 60, [40, 90, 60]) });
    await expectImagesLoaded(page, 'img[alt="Artwork"]', 1);
    await page.getByLabel('Starts').fill('2026-10-05');
    await page.getByLabel('Ends').fill('2026-11-04');
    await page.getByRole('button', { name: 'Save' }).click();
    await expect(page.getByText(`Brunch ${SUFFIX}`)).toBeVisible();

    // Bug 28: reopen — the dates are the ones entered; save again — still unchanged.
    for (let round = 0; round < 2; round++) {
        await page.getByRole('row', { name: new RegExp(`Brunch ${SUFFIX}`) }).getByRole('button', { name: 'Edit' }).click();
        await expect(page.getByLabel('Starts')).toHaveValue('2026-10-05');
        await expect(page.getByLabel('Ends')).toHaveValue('2026-11-04');
        await page.getByRole('button', { name: 'Save' }).click();
        await expect(page.getByText(`Brunch ${SUFFIX}`)).toBeVisible();
    }
});

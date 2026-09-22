/**
 * Idempotently expands the isolated Demo Tutorial tenant to 504 properties.
 *
 * This intentionally targets only property names prefixed with DT26 inside the
 * synthetic tenant recorded in tutorials/state. Existing customer data is never
 * queried or mutated. Progress is inferred from production state, so rerunning
 * after an interruption continues instead of importing duplicates.
 *
 * VERIFY: this spec drives `/v1/import/portfolio` (the bulk workbook
 * importer), which is NOT part of accounting-v2 plan 2's brief and was not
 * audited here — whether it now creates v2 `lines`-based leases (and, if
 * so, whether each property's lease carries a RENT line only or also a
 * SECURITY_DEPOSIT line that folds into cheque #1 by default) is unconfirmed.
 * The filter below assumes four PDC cheques per lease are all ordinary rent
 * rows with no deposit folded in; if the importer's leases carry a deposit
 * line, `getScaleCheques` will pick up a heavier first cheque per property
 * and the per-property "four rent rows" assumption breaks. Needs runtime
 * confirmation in 17b.
 */
import { test, expect } from '@playwright/test';
import * as fs from 'node:fs';
import * as path from 'node:path';
import { api, loginAsNextAuth, ProdContext, setActiveTenant } from '../helpers/prod-client';

const BASE_URL = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';
const REPO_ROOT = path.resolve(__dirname, '../../..');
const MAIN_STATE_PATH = path.join(REPO_ROOT, 'tutorials/state/miftah-demo-tutorial-mtbzmkbe.json');
const SCALE_STATE_PATH = path.join(REPO_ROOT, 'tutorials/state/miftah-demo-tutorial-mtbzmkbe-portfolio-scale.json');
const SECRET_PATH = path.join(REPO_ROOT, 'tutorials/state/miftah-demo-tutorial-mtbzmkbe-secrets.local.json');
const WORKBOOK_PATH = path.join(REPO_ROOT, 'tutorials/rentaxis-demo/generated/dt26-campus-portfolio-v1.xlsx');
const GENERATOR_SUMMARY_PATH = path.join(REPO_ROOT, 'tutorials/rentaxis-demo/generated/dt26-campus-portfolio-v1-summary.json');
const PREFIX = 'DT26 ';
const EXPECTED_PROPERTIES = 504;
const EXPECTED_RENT_ROWS = EXPECTED_PROPERTIES * 4;

type Json = Record<string, any>;
type PropertyRecord = { id: string; nameEn: string; nameAr?: string | null; address?: string | null };
/** ChequeDTO's own shape (`web/src/lib/api/leasing.ts`) — replaces v1's PaymentScheduleDTO. */
type Cheque = {
  id: string;
  propertyId: string;
  propertyName: string;
  seqNo: number;
  chequeDate: string;
  amount: number;
  status: string;
  mode: string;
};

function readJson<T>(file: string): T {
  return JSON.parse(fs.readFileSync(file, 'utf8')) as T;
}

function writeScaleState(patch: Json): Json {
  const state = { ...readJson<Json>(SCALE_STATE_PATH), ...patch, updatedAt: new Date().toISOString() };
  fs.writeFileSync(SCALE_STATE_PATH, `${JSON.stringify(state, null, 2)}\n`, 'utf8');
  return state;
}

async function requestJson<T>(
  ctx: ProdContext,
  method: 'get' | 'post' | 'put',
  endpoint: string,
  body?: unknown,
): Promise<T> {
  const response = await ctx.request[method](`/api/proxy${endpoint}`, body === undefined ? {
    failOnStatusCode: false,
  } : {
    data: body,
    headers: { 'Content-Type': 'application/json' },
    failOnStatusCode: false,
  });
  if (!response.ok()) {
    throw new Error(`${method.toUpperCase()} ${endpoint} -> ${response.status()}: ${(await response.text()).slice(0, 600)}`);
  }
  if (response.status() === 204) return undefined as T;
  return response.json() as Promise<T>;
}

function normalizeProperties(payload: unknown): PropertyRecord[] {
  const rows = Array.isArray(payload) ? payload : (payload as Json)?.content || [];
  return rows.map((row: Json) => row.property || row).filter((row: Json) => row?.id && row?.nameEn);
}

async function getProperties(ctx: ProdContext): Promise<PropertyRecord[]> {
  return normalizeProperties(await requestJson<unknown>(ctx, 'get', '/v1/properties'));
}

async function getScaleCheques(ctx: ProdContext): Promise<Cheque[]> {
  const all: Cheque[] = [];
  for (let page = 0; ; page++) {
    const result = await requestJson<Json>(
      ctx,
      'get',
      `/v1/cheques?search=${encodeURIComponent('DT26')}&mode=PDC&page=${page}&size=200`,
    );
    all.push(...(result.content || []));
    if (page + 1 >= (result.totalPages || 1)) break;
  }
  // VERIFY: no isSecurityDeposit/isBookingDeposit/isCharge flags exist on
  // ChequeDTO in v2 — every PDC row for a DT26 property is assumed to be an
  // ordinary rent installment (see the file-level VERIFY above).
  return all.filter((cheque) => cheque.propertyName?.startsWith(PREFIX) && cheque.seqNo >= 1 && cheque.seqNo <= 4);
}

async function mapLimited<T>(items: T[], concurrency: number, worker: (item: T, index: number) => Promise<void>) {
  let cursor = 0;
  const runners = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (true) {
      const index = cursor++;
      if (index >= items.length) return;
      await worker(items[index], index);
    }
  });
  await Promise.all(runners);
}

function plusDays(isoDate: string, days: number): string {
  const value = new Date(`${isoDate}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

/**
 * v2's register has no PENDING/"collect" step — every generated PDC row
 * starts REGISTERED, cheque number already assigned at generation. Walk
 * REGISTERED -> DEPOSITED -> CLEARED/BOUNCED via `/v1/cheques/{id}/...`.
 */
async function transitionCheque(ctx: ProdContext, cheque: Cheque, target: string): Promise<void> {
  let status = cheque.status;
  if (target === 'REGISTERED') {
    expect(status).toBe('REGISTERED');
    return;
  }
  if (status === 'REGISTERED') {
    const deposited = await requestJson<Json>(ctx, 'put', `/v1/cheques/${cheque.id}/deposit`, {
      date: plusDays(cheque.chequeDate, 1),
      notes: 'Synthetic historical deposit / إيداع تاريخي اصطناعي',
    });
    status = deposited.status;
  }
  if (target === 'DEPOSITED') {
    expect(status).toBe('DEPOSITED');
    return;
  }
  if (target === 'BOUNCED') {
    if (status === 'DEPOSITED') {
      const bounced = await requestJson<Json>(ctx, 'put', `/v1/cheques/${cheque.id}/bounce`, {
        failureReason: 'BOUNCE',
        notes: 'Synthetic bounced-cheque example / مثال اصطناعي لشيك مرتجع',
        date: plusDays(cheque.chequeDate, 6),
      });
      status = bounced.status;
    }
    expect(status).toBe('BOUNCED');
    return;
  }
  if (status === 'DEPOSITED') {
    const cleared = await requestJson<Json>(ctx, 'put', `/v1/cheques/${cheque.id}/clear`, {
      date: plusDays(cheque.chequeDate, 5),
      notes: 'Synthetic historical clearance / تسوية تاريخية اصطناعية',
    });
    status = cleared.status;
  }
  expect(status).toBe('CLEARED');
}

test('seed and verify the 504-property bilingual campus portfolio', async () => {
  test.setTimeout(35 * 60_000);

  expect(fs.existsSync(WORKBOOK_PATH), `generate workbook first: ${WORKBOOK_PATH}`).toBeTruthy();
  const mainState = readJson<Json>(MAIN_STATE_PATH);
  const secrets = readJson<Json>(SECRET_PATH);
  const generator = readJson<Json>(GENERATOR_SUMMARY_PATH);
  expect(mainState.synthetic).toBe(true);
  expect(mainState.tenant.id).toBe('d49a5016-cced-4f81-a4f3-354714120c65');
  expect(generator.propertyCount).toBe(EXPECTED_PROPERTIES);
  expect(generator.campusCount).toBe(8);

  const adminCtx = await loginAsNextAuth(BASE_URL, mainState.users.admin.email, secrets.password);
  await setActiveTenant(adminCtx, mainState.tenant.id);

  let scaleProperties = (await getProperties(adminCtx)).filter((property) => property.nameEn.startsWith(PREFIX));
  if (scaleProperties.length === 0) {
    writeScaleState({ phase: 'uploading', generatedAt: new Date().toISOString(), generator });
    const upload = await adminCtx.request.post('/api/proxy/v1/import/portfolio', {
      multipart: {
        file: {
          name: path.basename(WORKBOOK_PATH),
          mimeType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          buffer: fs.readFileSync(WORKBOOK_PATH),
        },
      },
      failOnStatusCode: false,
    });
    expect(upload.ok(), `portfolio upload failed: ${upload.status()} ${(await upload.text()).slice(0, 1000)}`).toBeTruthy();
    const { jobId } = await upload.json();
    expect(jobId).toBeTruthy();
    writeScaleState({ phase: 'importing', importJobId: jobId });

    let final: Json | null = null;
    const deadline = Date.now() + 12 * 60_000;
    while (Date.now() < deadline) {
      const result = await requestJson<Json>(adminCtx, 'get', `/v1/import/portfolio/${jobId}/status`);
      if (['COMPLETED', 'VALIDATION_FAILED', 'FAILED'].includes(result.status)) {
        final = result;
        break;
      }
      await new Promise((resolve) => setTimeout(resolve, 2000));
    }
    expect(final, 'portfolio import did not finish within 12 minutes').not.toBeNull();
    expect(final!.status, JSON.stringify(final!.errors || final, null, 2).slice(0, 4000)).toBe('COMPLETED');
    writeScaleState({ phase: 'imported', importResult: final });
    scaleProperties = (await getProperties(adminCtx)).filter((property) => property.nameEn.startsWith(PREFIX));
  }

  expect(
    scaleProperties.length,
    `Refusing to continue with a partial DT26 import; expected 0 or ${EXPECTED_PROPERTIES}`,
  ).toBe(EXPECTED_PROPERTIES);
  scaleProperties.sort((a, b) => a.nameEn.localeCompare(b.nameEn));
  expect(scaleProperties.filter((property) => Boolean(property.nameAr?.trim())).length).toBe(EXPECTED_PROPERTIES);
  writeScaleState({
    phase: 'assigning-manager',
    progress: { propertiesImported: scaleProperties.length, managerAssignments: 0, paymentsTransitioned: 0 },
    propertyIds: scaleProperties.map(({ id, nameEn }) => ({ id, nameEn })),
  });

  const assigned = new Set(await requestJson<string[]>(adminCtx, 'get', `/admin/users/${mainState.users.manager.id}/properties`));
  const missingAssignments = scaleProperties.filter((property) => !assigned.has(property.id));
  let assignedNow = 0;
  await mapLimited(missingAssignments, 12, async (property) => {
    await api.assignUserToProperty(adminCtx, mainState.users.manager.id, property.id);
    assignedNow++;
    if (assignedNow % 50 === 0) {
      writeScaleState({
        phase: 'assigning-manager',
        progress: { propertiesImported: EXPECTED_PROPERTIES, managerAssignments: assigned.size + assignedNow, paymentsTransitioned: 0 },
      });
    }
  });
  const finalAssignments = await requestJson<string[]>(adminCtx, 'get', `/admin/users/${mainState.users.manager.id}/properties`);
  expect(scaleProperties.every((property) => finalAssignments.includes(property.id))).toBeTruthy();

  const cheques = await getScaleCheques(adminCtx);
  expect(cheques.length).toBe(EXPECTED_RENT_ROWS);
  const chequesByProperty = new Map<string, Cheque[]>();
  for (const cheque of cheques) {
    const rows = chequesByProperty.get(cheque.propertyId) || [];
    rows.push(cheque);
    chequesByProperty.set(cheque.propertyId, rows);
  }
  expect(chequesByProperty.size).toBe(EXPECTED_PROPERTIES);

  const superEmail = process.env.PROD_SUPERADMIN_EMAIL;
  const superPassword = process.env.PROD_SUPERADMIN_PASSWORD;
  expect(superEmail && superPassword, 'super-admin credentials are required to pause demo email dispatch during history generation').toBeTruthy();
  const superCtx = await loginAsNextAuth(BASE_URL, superEmail!, superPassword!);
  await api.setTenantFeature(superCtx, mainState.tenant.id, 'EMAIL_NOTIFICATIONS', false);
  let completed = 0;
  try {
    writeScaleState({
      phase: 'creating-history',
      progress: { propertiesImported: EXPECTED_PROPERTIES, managerAssignments: EXPECTED_PROPERTIES, paymentsTransitioned: 0 },
    });
    await mapLimited(scaleProperties, 10, async (property, propertyIndex) => {
      const rows = (chequesByProperty.get(property.id) || []).sort((a, b) => a.seqNo - b.seqNo);
      expect(rows.length, `four rent rows required for ${property.nameEn}`).toBe(4);
      await transitionCheque(adminCtx, rows[0], 'CLEARED');
      if (propertyIndex % 20 === 0) await transitionCheque(adminCtx, rows[1], 'BOUNCED');
      else if (propertyIndex % 20 === 1) await transitionCheque(adminCtx, rows[1], 'DEPOSITED');
      else if (propertyIndex % 20 === 2) await transitionCheque(adminCtx, rows[1], 'REGISTERED');
      completed++;
      if (completed % 25 === 0) {
        writeScaleState({
          phase: 'creating-history',
          progress: { propertiesImported: EXPECTED_PROPERTIES, managerAssignments: EXPECTED_PROPERTIES, paymentsTransitioned: completed },
        });
      }
    });
  } finally {
    await api.setTenantFeature(superCtx, mainState.tenant.id, 'EMAIL_NOTIFICATIONS', true);
    await superCtx.request.dispose();
  }

  const verifiedCheques = await getScaleCheques(adminCtx);
  const statusCounts = verifiedCheques.reduce<Record<string, number>>((counts, cheque) => {
    counts[cheque.status] = (counts[cheque.status] || 0) + 1;
    return counts;
  }, {});
  expect(statusCounts.CLEARED).toBeGreaterThanOrEqual(EXPECTED_PROPERTIES);
  expect(statusCounts.BOUNCED).toBeGreaterThanOrEqual(20);
  expect(statusCounts.DEPOSITED).toBeGreaterThanOrEqual(20);
  // v2 has no COLLECTED status — the third bucket is left untouched (REGISTERED).
  expect(statusCounts.REGISTERED).toBeGreaterThanOrEqual(20);

  const managerCtx = await loginAsNextAuth(BASE_URL, mainState.users.manager.email, secrets.password);
  await setActiveTenant(managerCtx, mainState.tenant.id);
  const managerScaleProperties = (await getProperties(managerCtx)).filter((property) => property.nameEn.startsWith(PREFIX));
  expect(managerScaleProperties.length).toBe(EXPECTED_PROPERTIES);

  const arabicSearch = await requestJson<Json>(
    adminCtx,
    'get',
    `/v1/cheques?search=${encodeURIComponent('حرم بوابة الشمال')}&page=0&size=25`,
  );
  expect(arabicSearch.totalElements).toBeGreaterThan(0);

  const campusCounts = generator.campuses.map((campus: Json) => ({
    nameEn: campus.nameEn,
    nameAr: campus.nameAr,
    propertyCount: scaleProperties.filter((property) => property.nameEn.includes(campus.nameEn)).length,
  }));
  expect(campusCounts.every((campus: Json) => campus.propertyCount === 63)).toBeTruthy();

  writeScaleState({
    phase: 'complete',
    completedAt: new Date().toISOString(),
    progress: {
      propertiesImported: EXPECTED_PROPERTIES,
      managerAssignments: EXPECTED_PROPERTIES,
      paymentsTransitioned: EXPECTED_PROPERTIES,
    },
    verification: {
      campusCounts,
      propertyCount: scaleProperties.length,
      bilingualPropertyCount: scaleProperties.filter((property) => Boolean(property.nameAr?.trim())).length,
      unitCount: generator.unitCount,
      renterCount: generator.renterCount,
      activeLeaseCount: generator.leaseCount,
      rentPaymentCount: verifiedCheques.length,
      paymentStatusCounts: statusCounts,
      managerVisiblePropertyCount: managerScaleProperties.length,
      representativePropertyId: scaleProperties[0].id,
      arabicPaymentSearchMatches: arabicSearch.totalElements,
      emailNotificationsRestored: true,
    },
  });

  await managerCtx.request.dispose();
  await adminCtx.request.dispose();
});

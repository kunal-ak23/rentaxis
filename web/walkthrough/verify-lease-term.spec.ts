import { test, expect, request as playwrightRequest } from '@playwright/test';
import * as path from 'node:path';
import { api, setActiveTenant, type ProdContext } from '../e2e-prod/helpers/prod-client';

/**
 * The lease total is computed on save, so unlike the preview checks this one
 * has to create a real lease. It provisions a disposable organization, proves
 * the point, and deletes the organization again — which also exercises the
 * delete capability from #252 against production.
 *
 * It deliberately posts the WRONG total (13 months' worth), the way a stale
 * client would, to prove the server derives its own rather than trusting it.
 */

const AUTH = path.join(__dirname, '..', 'e2e-prod', '.auth', 'superadmin.json');
const BASE = process.env.PROD_BASE_URL || 'https://rentaxis.uaenorth.cloudapp.azure.com';
const SUFFIX = Math.random().toString(36).slice(2, 7);
const ORG = `VERIFY-LEASE-TERM ${new Date().toISOString().slice(0, 10)} ${SUFFIX}`;

test('production derives the lease total from the dates, not from the caller', async () => {
    const su: ProdContext = {
        baseURL: BASE,
        request: await playwrightRequest.newContext({ baseURL: BASE, storageState: AUTH }),
        user: { role: 'SUPER_ADMIN' } as never,
    };

    const tenant = await api.createTenant(su, ORG);
    expect(tenant.id, 'disposable organization must be created').toBeTruthy();
    console.log(`  provisioned ${ORG} (${tenant.id})`);

    try {
        await setActiveTenant(su, tenant.id);
        const property = await api.createProperty(su, { nameEn: `Term Tower ${SUFFIX}`, emirate: 'DUBAI' });
        const unit = await api.createUnit(su, { propertyId: property.id, unitNumber: `T${SUFFIX}` });
        const renter = await api.createRenter(su, {
            nameEn: `Term Renter ${SUFFIX}`,
            email: `term-${SUFFIX}@example.invalid`,
        });

        // The reported lease. rentAmount is what the old wizard would have
        // sent: 5,000 x 13 months, because its count ignored the day.
        const res = await su.request.post('/api/proxy/v1/leases', {
            data: {
                unitId: unit.id,
                renterId: renter.id,
                startDate: '2026-10-03',
                endDate: '2027-10-03',
                monthlyRent: 5000,
                rentAmount: 65000,
                depositAmount: 5000,
                paymentTerms: 6,
                paymentMethod: 'CHEQUE',
            },
        });
        expect(res.ok(), `lease create failed ${res.status()}: ${await res.text()}`).toBeTruthy();
        const lease = await res.json();
        console.log(`  posted rentAmount 65000, stored ${lease.rentAmount}`);

        expect(Number(lease.rentAmount)).toBe(60000);

        const schedRes = await su.request.get(`/api/proxy/v1/payments/lease/${lease.id}`);
        expect(schedRes.ok(), `schedule fetch ${schedRes.status()}`).toBeTruthy();
        const sched = await schedRes.json();
        const all = Array.isArray(sched) ? sched : sched.content ?? [];
        // Rent rows only. The flags serialize with their "is" prefix, and the
        // security deposit rides in the same list — without dropping it the
        // total comes to 65,000, which is the very number this is checking is
        // gone.
        const rows = all.filter((r: any) => !r.isCharge && !r.isSecurityDeposit && !r.isBookingDeposit);
        const total = rows.reduce((a: number, r: any) => a + Number(r.amount), 0);
        console.log(`  schedule: ${rows.length} rent rows totalling ${total}`);
        expect(total).toBe(60000);
    } finally {
        const del = await su.request.delete(
            `/api/proxy/admin/tenants/${tenant.id}?confirmName=${encodeURIComponent(ORG)}`);
        console.log(`  cleanup: delete ${ORG} -> ${del.status()}`);
        expect(del.ok() || del.status() === 404, 'the disposable organization must be removed').toBeTruthy();
    }
});

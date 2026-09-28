// Seed-manifest records and dates shared by several scenarios.
import { seed } from './context.mjs';

export const towerId = seed.properties?.tower;
export const towerName = seed.properties?.towerName
  || process.env.TUTORIAL_TOWER_NAME
  || 'RentAxis Tutorial Residence Tower';
export const ahmedLeaseId = seed.leases?.ahmed;
export const saraLeaseId = seed.leases?.sara;
export const ticketId = seed.ticketId;
export const meetingId = seed.meetings?.['Replacement cheque — Fatima Al Zaabi (A-102)'];
export const listingId = seed.listings?.['Bright 2BR in Al Barsha'];
export const parkingSpotNumber = 'TUTORIAL-B2-18';
export const bookingPreferredDate = new Date(Date.now() + 14 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
// ── accounting v2 (tutorials 34–37) ──────────────────────────────────────────
// The seed manifest is the contract between `scripts/seed_demo_tenant.py` and
// these scenarios: the lease ids above, the renter ids, and the register rows
// with the status the seed left each one in. Everything is read optionally —
// this module is evaluated for every tutorial, including the ones recorded
// against a manifest that predates these keys.
export const fatimaRenterId = seed.renterIds?.fatima;
export const advanceRentAccountId = seed.accounts?.tower?.ADVANCE_RENT;

/** A seeded register row by contract key and sequence number, or undefined. */
export function chequeIdAt(leaseKey, seqNo) {
  return seed.cheques?.[leaseKey]?.find((row) => row.seqNo === seqNo)?.id;
}

export const isoDate = (date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
/**
 * The furthest date recognition will accept. `RecognitionController#notInTheFuture`
 * rejects a `to` past today, and a period that has not ended has nothing to
 * close — so month-end close always means the end of LAST month, which is also
 * what the storyboard narration says out loud.
 */
export const lastMonthEnd = () => {
  const now = new Date();
  return isoDate(new Date(now.getFullYear(), now.getMonth(), 0));
};
/** These contracts are dated 1 January; the ledger filters open on this month. */
export const contractYearStart = () => `${new Date().getFullYear()}-01-01`;
export const todayIso = () => isoDate(new Date());

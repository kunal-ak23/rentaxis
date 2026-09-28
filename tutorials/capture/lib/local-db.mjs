// Off-camera purge of records a tutorial take created, for records the app
// cannot delete (projects, units, tenants and what hangs off them).
//
// LOCAL RECORDING STACK ONLY. It refuses unless the recorder targets
// localhost and the database is a `rentaxis_tutorials*` one, it is scoped by
// organisation id and an exact name/email, and it refuses (rolls back) when
// the record has anything with history: a tenancy contract, a cheque, a
// journal entry or line, a ticket, a meeting, a booking or a listing. It is
// how a retake starts from the same state, never a way to hide real data.
import { execFileSync } from 'node:child_process';
import { baseURL } from './context.mjs';

const db = process.env.TUTORIAL_DB || 'rentaxis_tutorials';
const dbHost = process.env.TUTORIAL_DB_HOST || '127.0.0.1';
const dbPort = process.env.TUTORIAL_DB_PORT || '5432';
const dbUser = process.env.TUTORIAL_DB_USER || 'postgres';

function assertLocal() {
  const host = new URL(baseURL).hostname;
  if (!['localhost', '127.0.0.1'].includes(host)) throw new Error(`Refusing a database purge for ${baseURL}: local stack only.`);
  if (!/^rentaxis_tutorials\w*$/.test(db)) throw new Error(`Refusing a database purge on ${db}: tutorial databases only.`);
  if (!['127.0.0.1', 'localhost'].includes(dbHost)) throw new Error(`Refusing a database purge on host ${dbHost}.`);
}

/** Run a script with psql variables (referenced as :'name'); returns stdout. */
function psql(script, vars) {
  assertLocal();
  const args = ['-h', dbHost, '-p', dbPort, '-U', dbUser, '-d', db, '-X', '-q', '-At', '-v', 'ON_ERROR_STOP=1'];
  for (const [key, value] of Object.entries(vars)) args.push('-v', `${key}=${value}`);
  return execFileSync('psql', args, { input: script, encoding: 'utf8' }).trim();
}

/**
 * Remove a project (a `properties` row) by exact English name, with its
 * units, buildings, contacts, amenities, parking spots and its generated
 * ledger accounts. Returns how many projects were removed (0 or 1+).
 */
export function purgeProject(tenantId, nameEn) {
  const out = psql(`
BEGIN;
CREATE TEMP TABLE p ON COMMIT DROP AS
  SELECT id FROM properties WHERE tenant_id = :'tenant'::uuid AND name_en = :'name';
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM leases l JOIN units u ON u.id = l.unit_id WHERE u.property_id IN (SELECT id FROM p))
     OR EXISTS (SELECT 1 FROM cheques WHERE property_id IN (SELECT id FROM p))
     OR EXISTS (SELECT 1 FROM journal_entries WHERE property_id IN (SELECT id FROM p))
     OR EXISTS (SELECT 1 FROM journal_lines jl JOIN accounts a ON a.id = jl.account_id WHERE a.property_id IN (SELECT id FROM p))
     OR EXISTS (SELECT 1 FROM maintenance_tickets WHERE property_id IN (SELECT id FROM p))
     OR EXISTS (SELECT 1 FROM meetings WHERE property_id IN (SELECT id FROM p))
     OR EXISTS (SELECT 1 FROM booking_requests WHERE property_id IN (SELECT id FROM p))
     OR EXISTS (SELECT 1 FROM unit_listings ul JOIN units u ON u.id = ul.unit_id WHERE u.property_id IN (SELECT id FROM p))
  THEN RAISE EXCEPTION 'project has history; not purged';
  END IF;
END $$;
DELETE FROM amenity_building_scopes WHERE amenity_id IN (SELECT id FROM property_amenities WHERE property_id IN (SELECT id FROM p));
DELETE FROM parking_spot_building_scopes WHERE parking_spot_id IN (SELECT id FROM parking_spots WHERE property_id IN (SELECT id FROM p));
DELETE FROM property_amenities WHERE property_id IN (SELECT id FROM p);
DELETE FROM parking_spots WHERE property_id IN (SELECT id FROM p);
DELETE FROM property_contacts WHERE property_id IN (SELECT id FROM p);
DELETE FROM gate_access_policies WHERE property_id IN (SELECT id FROM p);
DELETE FROM units WHERE property_id IN (SELECT id FROM p);
DELETE FROM buildings WHERE property_id IN (SELECT id FROM p);
DELETE FROM user_property_assignments WHERE property_id IN (SELECT id FROM p);
DELETE FROM guard_property_assignments WHERE property_id IN (SELECT id FROM p);
DELETE FROM rent_collection_settings WHERE property_id IN (SELECT id FROM p);
DELETE FROM property_geo WHERE property_id IN (SELECT id FROM p);
DELETE FROM property_account_mappings WHERE property_id IN (SELECT id FROM p);
DELETE FROM accounts WHERE property_id IN (SELECT id FROM p);
DELETE FROM properties WHERE id IN (SELECT id FROM p);
SELECT count(*) FROM p;
COMMIT;
`, { tenant: tenantId, name: nameEn });
  return Number(out.split('\n').pop());
}

/**
 * Remove tenant (renter) profiles by exact email. The portal account must be
 * deleted through the app first (users API); a profile that still has a
 * sign-in, or any contract, cheque, journal entry, ticket or tax invoice, is
 * refused. Returns how many were removed.
 */
export function purgeTenantProfile(tenantId, email) {
  const out = psql(`
BEGIN;
CREATE TEMP TABLE r ON COMMIT DROP AS
  SELECT id, user_id FROM renters WHERE tenant_id = :'tenant'::uuid AND lower(email) = lower(:'email');
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM r WHERE user_id IS NOT NULL AND EXISTS (SELECT 1 FROM users u WHERE u.id = r.user_id))
     OR EXISTS (SELECT 1 FROM leases WHERE renter_id IN (SELECT id FROM r))
     OR EXISTS (SELECT 1 FROM cheques WHERE renter_id IN (SELECT id FROM r))
     OR EXISTS (SELECT 1 FROM journal_entries WHERE renter_id IN (SELECT id FROM r))
     OR EXISTS (SELECT 1 FROM maintenance_tickets WHERE on_behalf_of_renter_id IN (SELECT id FROM r))
     OR EXISTS (SELECT 1 FROM tax_invoices WHERE renter_id IN (SELECT id FROM r))
  THEN RAISE EXCEPTION 'tenant profile has a sign-in or history; not purged';
  END IF;
END $$;
DELETE FROM renters WHERE id IN (SELECT id FROM r);
SELECT count(*) FROM r;
COMMIT;
`, { tenant: tenantId, email });
  return Number(out.split('\n').pop());
}

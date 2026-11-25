# Rental index on renewal (Dubai) — Design

Date: 2026-11-25. Status: draft for Kunal's approval.

## 1. Intent

In Dubai, a renewal may raise the rent only as far as Decree No. 43 of 2013 allows. The cap depends on how far the
current rent sits below the average rent in the RERA rental index (now the DLD Smart Rental Index). Staff check
this today on the Dubai Land Department (DLD) website, one unit at a time, and nothing in Miftah records it.

This feature puts the cap on the renewal screen. Staff see the highest legal new rent before they renew, Miftah
warns when an escalation is above it, and the DLD result backing each decision is kept. The design keeps the manual
work small: **one DLD check per building and unit type per year**, reused for every renewal of that type in that
building. An official DLD API can later supply the same figures with no clicks at all.

This reverses one line of the 2026-09-24 decisions ("escalation = whole AED, no RERA cap; no RERA index"). The
renewal still allows any whole-AED rent. What changes is that, for Dubai units, Miftah now knows and shows the cap.

## 2. Goals, non-goals, anti-goals

Goals
- For a Dubai unit with an index range on file, the renew dialog, bulk renew and the renewal queue show the allowed
  increase and the highest new rent.
- An escalation above the cap needs a written override reason, recorded with who and when.
- A DLD result PDF can be uploaded; Miftah reads it, checks it against the unit and lease, and stores it.
- One stored range covers every unit of that type in that building until it expires.
- Staff can open DLD with the unit's details ready to fill, without retyping them.

Non-goals (this release)
- Fetching index figures automatically. DLD's public calculator is behind a reCAPTCHA; see §4.
- Generating the 90-day renewal notice document. The deadline is shown; the notice itself is later work.
- Emirates other than Dubai, and commercial or industrial units (DLD's other calculator tabs).

Anti-goals (a failure even if it works)
- Defeating, solving or sidestepping DLD's reCAPTCHA, or scraping DLD pages in the background.
- A cap Miftah cannot explain: every figure traces to a stored range and its source (PDF, hand entry, API).
- Silently reading a wrong figure from a changed DLD layout. A PDF that does not add up is refused.
- Showing a renter the index check or override reasons.

## 3. What the proof of concept established (2026-11-25)

Throwaway POC in the session scratch folder, run against two real DLD results (kept in Kunal's Downloads, never in
the repo) and DLD's live page.

- DLD's result PDF is text, not an image. PDFBox 3.0.7, already a backend dependency, reads it.
- DLD changed the PDF layout between 23 Oct and 25 Nov 2026 (new Area and Property Type cells, rent printed without
  decimals). A row-position reader broke; a reader that finds each value by its shape and then cross-checks the
  figures read both layouts. See §7.
- The calculation reproduces DLD exactly on the real sample: range 117,499–143,609, midpoint 130,554, rent 103,500,
  gap 20.72% → 10% → increase 10,350 → 113,850. DLD's published worked example (80,000 / 60,000 → 25% → 10%) passes.
- DLD's rental index page reads nothing from its URL, so a link cannot pre-fill it. Every form has a reCAPTCHA.
- A small script run by the person on DLD's page (a bookmark) fills the five fields; the person ticks the
  reCAPTCHA and presses Calculate. DLD's page reacts only to choices made through its visible dropdown lists.
- DLD's form offers "search by Ejari contract number", but selecting it did not swap the input on 2026-11-25;
  DEWA premise number is the route that works.

## 4. Approaches

| | Approach | Trade-off | Verdict |
|---|---|---|---|
| A | Miftah fetches results from DLD's public calculator in the background | Requires beating a reCAPTCHA; against DLD's intent; breaks on every page change; risks blocking the client's IP | Rejected (anti-goal) |
| B | A DLD check on every renewal, per unit | Exact per unit, but one manual lookup per renewal: hundreds a year for a large portfolio | Kept only as optional per-unit evidence |
| C | One stored range per building and unit type, reused for a year | A handful of checks a year; a range applies to sibling units of the same type | **Chosen (Kunal, 2026-11-25)** |
| D | DLD's official Rental Index API (listed on DLD's API gateway registration page) | Fully automatic, but access, eligibility and terms are unconfirmed | **Pursued in parallel; plugs into C as a new source** |

Why C is sound: DLD's range is "for same property type", and the Smart Rental Index is built from the building's
rating, the rents registered in the building and area, and is updated once a year (DLD FAQ,
`https://dubailand.gov.ae/media/thwlcxn4/smart_rental_index_en.pdf`). Units of one type in one building share it.

## 5. Model (Liquibase changeset `194-rental-index`)

`units`
- `dewa_premise_number VARCHAR(12) NULL`, digits only. Unique per tenant when not null. Shown and edited on the unit
  form and in the bulk portfolio import (new optional column).

`rental_index_rates` — one row per check; the newest unexpired row for a (building, DLD property type, category) is
the one in force.
- `id`, `tenant_id`, `property_id`, `building_id NULL` (a property without buildings uses property only)
- `dld_property_type` (`APARTMENT` | `VILLA` | `HOTEL_APARTMENT`), `category` (DLD's text, e.g. "3 bed rooms+hall"),
  `unit_type` (Miftah `UnitType` it applies to)
- `index_min`, `index_max` (AED a year), `area_name NULL`
- `source` (`DLD_PDF` | `MANUAL` | `DLD_API`), `source_unit_id NULL` (the unit the DLD check was run for),
  `document_url NULL` (the stored PDF), `dld_percent NULL`, `dld_issued_at NULL`
- `checked_on` (date), `expires_on` (default `checked_on` + 12 months)
- `created_by`, `created_at`, `revoked_by NULL`, `revoked_at NULL` (a wrong entry is revoked, never edited)

`renewal_index_checks` — what was shown and decided for one renewal.
- `id`, `tenant_id`, `lease_id` (the predecessor), `successor_lease_id NULL`, `rate_id`
- `base_rent`, `average`, `gap_percent`, `allowed_percent`, `cap_rent`, `new_rent`
- `unit_document_url NULL`, `unit_dld_percent NULL` (when a unit-specific DLD result was attached)
- `above_cap BOOLEAN`, `override_reason NULL` (required when `above_cap`), `decided_by`, `decided_at`

Mapping Miftah unit types to DLD: `STUDIO`, `BHK1`–`BHK3`, `PENTHOUSE` → Apartment (category from the PDF);
`RETAIL`, `OFFICE` → not residential, no check. Villas need a unit type or flag Miftah does not have yet: left to
the builder (§12).

## 6. Calculation (Decree 43/2013, Art. 1; DLD "how to calculate the increase")

- `average` = midpoint of the range, `(index_min + index_max) / 2`. Assumption, matched on the one sample; when a
  unit's own DLD result is attached, DLD's printed percentage wins and Miftah's figure is only a cross-check.
- `gap_percent = (average − base_rent) / average × 100`, four decimals.
- `allowed_percent`: gap ≤ 10 → 0; ≤ 20 → 5; ≤ 30 → 10; ≤ 40 → 15; above 40 → 20. Fractions fall to the next band
  (DLD put 20.72% in the 21–30% band). A rent at or above the average → 0.
- `cap_rent = round(base_rent × (1 + allowed_percent / 100))` to whole AED, like the renewal's PERCENT mode.
- `base_rent` is the same base the renewal preview already uses (`RenewalPreviewDTO.baseRent`): the annual rent of
  the contract being renewed. A term that is not one year is annualised for the comparison only.
- No range in force → no cap is shown, and the dialog says so with a "Check on DLD" action. Nothing is blocked.

## 7. Reading a DLD result PDF

Backend service, PDFBox text with `setSortByPosition(true)`. Each value is found by its shape, anywhere on the page:
title "Rental Index Result" and "Dubai Land Department" (both required), the percentage (`\d{1,2}%`), the range
("From N to N AED per year"), the 9-digit premise number, the `dd/mm/yyyy` end date, the two "… AED Annually"
amounts (smaller = increase, larger = rent after), the current rent ("N AED" not followed by Annually or per year),
the category, building, area and issue time when present.

Then it must add up, or the upload is refused with "This result's figures don't add up":
rent + increase = rent after, and increase = rent × percentage to the dirham.

On upload Miftah compares the PDF with its own data and shows each as ✓ or ✗: premise number vs the unit, end date
vs the lease, rent vs the lease. A ✗ on the premise number blocks using it as that unit's evidence; it can still
create a building range (the person confirms which building and type it covers).

Test fixtures: synthetic DLD-style PDFs in both layouts with fictional data, generated by the builder (the real
results must not enter the public repo). Every future layout change adds a fixture.

## 8. Flows and screens

**Unit form and bulk import.** "DEWA premise no." field. Bulk import gains an optional column.

**Rental index ranges** (Property → new "Rental index" tab, Dubai properties only). A table per building and type:
range, average, source, checked on, expires on, who added it, and a link to the PDF. Actions: "Add from DLD result"
(upload; the type and building are proposed from the PDF and the chosen unit), "Enter by hand" (range, type,
building, checked-on date; marked as hand-entered), "Revoke". An expired range is shown greyed and no longer applies.

**Check on DLD.** From a unit, a range row or the renew dialog: copies the unit's details and opens DLD's rental
index page in a new tab. Two ways to fill DLD's form:
- copy buttons next to each value (always works);
- a "Fill from Miftah" bookmark, offered once from the Rental index tab with setup steps. It reads the copied
  details and fills DLD's five fields through DLD's visible dropdowns; the person does the reCAPTCHA and Calculate.

**Renew dialog** (`RenewLeaseDialog`). For a Dubai unit, an "Rental index" panel above the rent step: the range in
force and its age, average, gap, allowed percentage and highest new rent. The rent preview gains the cap; when the
new rent is above it, a red notice replaces the property's informational "above X%" notice and an override reason
(required, 1–500 characters) must be entered before Renew. "Attach this unit's DLD result" is optional evidence.
The panel shows the 90-day notice deadline (lease end − 90 days) and warns when it has passed.

**Bulk renew** (`BulkRenewDialog`). Each row shows its cap. Rows whose new rent is above their cap are listed and
need one shared override reason, or are left out; rows with no range in force renew as today, flagged "no index".

**Renewal queue.** A "Max increase" column: "+10% (113,850)", "no index", or "not Dubai".

## 9. Permissions

- Edit the DEWA premise number: whoever can edit units today.
- Add, enter or revoke a range: Company Admin and Property Manager (property-scoped).
- Override above the cap: whoever can renew; the reason, user and time are stored and shown on the lease.
- Renters never see ranges, checks or override reasons.

## 10. Feature flag and scope

`TenantFeature.RENTAL_INDEX`, off by default. Applies only to properties whose emirate is Dubai and to residential
unit types. Endpoints return 404 when the flag is off, like `RENTER_RATING`.

Kunal, 2026-11-25: only the index check is behind the flag. The DEWA premise number (unit form, unit API, bulk
import column) is captured whether the flag is on or off, so data can be collected before the check is switched
on. With the flag off, renewals behave exactly as today: no cap, no override reason, no `renewal_index_checks` rows.

Approved 2026-11-25 ("build this"): above the cap is warn and override with a reason (§15's first question,
answered by approving the design as written). The 90-day deadline warns only.

## 11. Official API (route D)

A separate track, not code yet: Kunal or the client applies through DLD's API gateway for the Rental Index API and
learns eligibility, terms and what it returns. If granted, a scheduled job refreshes `rental_index_rates` with
`source = DLD_API` for each building and type that has a Dubai unit, and the manual check becomes the fallback.

## 12. Left to the builder

- Villas: Miftah's `UnitType` has no villa. Add one, or derive from the property type; ask Kunal if unclear.
- Whether a range is keyed by building or by property when a property has buildings with different ratings: default
  building, falling back to property.
- The exact PDF regexes and the fixture generator; keep the self-check.
- Annualising a non-yearly term for the comparison.
- Whether the "Fill from Miftah" bookmark ships as a bookmark (no install) or a small browser extension later.

## 13. Testing

- Unit: band edges (10, 10.4, 20, 20.72, 30, 40, 40.01, negative gap), DLD's worked example, rounding to whole AED.
- Reader: both layouts, a PDF that does not add up, a non-DLD PDF, premise mismatch.
- Integration: range in force vs expired vs revoked; renew above cap without a reason is refused; with a reason it
  succeeds and stores the check; bulk renew with mixed rows; tenant isolation on every new table; flag off → 404.
- Web: renew dialog panel, override reason required, queue column, Rental index tab.

## 14. Settled after the builder check (Claude's calls, for Kunal to confirm while reading)

1. **The server enforces the cap.** The renew request (single and bulk) gains `indexOverrideReason`. The backend
   recomputes the cap from the range in force, refuses an above-cap renewal without a reason
   (`renewal.indexOverrideRequired`), and writes `renewal_index_checks` in the renew transaction. The dialog's
   check is only a preview, so mobile and API callers are held to the same rule.
2. **Both sides compare on the same basis.** `base_rent` and `new_rent` are both the RENT lines' annual net:
   headline less discount, VAT-exclusive, matching today's `RenewalPreviewDTO.baseRent`. Either side whose term is
   not 12 whole months is annualised by days (× 365 / term days). One-off lines and other charges are left out.
3. **How a unit finds its range.** A range row covers exactly one Miftah `unit_type`. The person adding it picks
   the type, proposed from DLD's category ("Studio" → STUDIO, "N bed rooms+hall" → BHK N); `category` stays as DLD
   wrote it for display. PENTHOUSE has no automatic mapping: its range is added explicitly for PENTHOUSE.
4. **A unit's own DLD result beats the building range.** When a unit's result is attached, DLD's printed
   percentage and new rent set the cap for that renewal. If they differ from the building-range figure, the panel
   shows both and notes the difference; there's no separate override.
5. **Bulk takes one reason.** The bulk request carries one `indexOverrideReason`, stored on every above-cap row's
   check. Each row already runs in its own transaction (`LeaseBatchService`, `REQUIRES_NEW`), so outcomes are per
   row: an above-cap row without a reason fails alone and the rest go through.
6. **Lookup order.** The newest unexpired, unrevoked range for (the unit's building, unit type), else for
   (property with no building, unit type), else none. "Newest" means latest `checked_on`, then `created_at`. No
   uniqueness constraint; history stays.
7. **Checks are immutable snapshots.** `renewal_index_checks` stores the figures used (average, gap, percentage,
   cap) at decision time and never changes. Revoking a range affects future renewals only. A lease shows its stored
   check, not a recomputed one.
8. **Emirate not set.** A property with no emirate shows "Set the emirate to check the rental index" in the queue
   and the dialog, instead of "not Dubai".

## 15. Open with Kunal and the client

- **Above the cap: block, or warn and override with a reason?** The design assumes override (§8, §14.1), in line
  with the 2026-09-24 informational-warning ruling. Blocking would remove the reason field and make §14.1 a refusal.
- **The 90-day notice:** when the deadline has passed, warn only (assumed), or block any increase?
- **Evidence (client):** is a range checked on a sibling unit enough for a tenant's notice, or do they want each
  unit's own DLD result?

## 16. Privacy

DEWA premise numbers and DLD results are client data: tenant-scoped, never in logs, tutorial recordings, fixtures
or the public repo. Tutorials use fictional premise numbers.

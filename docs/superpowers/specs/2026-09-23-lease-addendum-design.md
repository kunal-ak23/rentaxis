# Mid-term contract variation (addendum) — design

**Status: APPROVED 2026-09-23** — Approach A, with the four formerly open questions
answered in *Decisions taken*. No code has been written yet.

Addresses findings #15 and #17 from the two-year simulation (`tutorials/miftah-2y/gaps.md`).

## The problem, as actually diagnosed

A tenant asks for a parking bay in February, mid-way through a posted tenancy. Today there
is no path:

- *Amend Lines* refuses outright once any cheque has cleared (#15).
- Even before clearing, an amendment can only redistribute the contract value, never raise
  it — and the error tells the operator to "change the cheque grid first", which the UI
  cannot do (#17).

**Both findings overstated the gap.** They were logged from the screens. Reading the
backend shows the ledger already does most of this; the capability is unreachable, not
absent. The findings were re-classified in commit `08b84f99`.

## What already exists (verified)

| Capability | Where | Note |
|---|---|---|
| Append charge lines to a posted lease, raising contract value | `LeaseRenewalService.extend` → `leaseService.appendLines` | Additive: original TCO is **not** reversed |
| Post a **second TCO** for just the delta | `postingService.postTco` | `postingJournalId` deliberately still names the first |
| Append cheque rows and register a PDR each | `chequeGeneration.appendRows`, `LeaseChequeRegistrar` | |
| Add one instrument to an already-posted contract | `ChequeService.addRowToPostedLease` (`ChequeService.java:783`) | Javadoc names "an extension's extra instalment" |
| Pin a charge window and validate it | `LeaseRenewalService.datedLines(inputs, windowStart, windowEnd)` | Already generic over an arbitrary window |
| Per-day recognition over a line's **own** window | `RecognitionService:851-852` | `from = line.getPeriodStart() ?: lease.getStartDate()` |
| Hand an uncleared instrument back | `ChequeService.returnToTenant` | Deliberately **not** exposed over HTTP |

Two consequences worth stating plainly:

1. **The cleared-cheque block is specific to `amendLines`, not to the ledger.** `amendLines`
   reverses and reposts *every* TCO, so it must refuse once money has settled — its javadoc
   gives the reason: after a repost "the new TCO raises would no longer be the receivable
   that money settled." That is a correct invariant. The additive path has no such gate
   because appending never disturbs settled money.
2. **Recognition needs no changes.** A line dated 15 Feb → 30 Sep yields its own
   `RentSegment` with its own `days` and `dayRate = amount ÷ days`. Critically, the window
   *must* be pinned: `datedLines`' javadoc warns that falling back to the lease default
   would give the line the whole term and "every month of the original term recognised a
   second time."

## Decisions taken

| Decision | Choice |
|---|---|
| v1 scope | Additions **and** corrections. Negotiated reductions and refunds of cleared cash are out. |
| Correction model | Industry-standard hybrid, split on settlement (below) |
| Downward-correction surplus | Return uncleared instruments first; any remainder becomes a credit balance |
| Approach | **A** — new entry point reaching the existing additive path (approved 2026-09-23) |
| Ejari on an addendum | Not required at creation; an optional Ejari field, and a follow-up task is recorded when it is blank |
| Extra deposit | Keep `extend`'s DEPOSIT refusal in v1; extra deposit is charged as a separate act |
| Document number | Own series `ADD-yy/n`, distinct from TCO; backs `DocumentType.ADDENDUM` |
| Credit balance | An `ADVANCE_RENT` credit on the tenant ledger, consumed by later charges or at settlement |

### The correction rule

This is the standard every audited ledger uses, and it matches what the code already
enforces:

- **Nothing settled and the period is open → reverse and repost.** Exactly today's
  `amendLines`. Correcting a document nothing has been paid against leaves no useful audit
  trail behind. Its all-REGISTERED gate is the standard condition, not an arbitrary one.
- **Anything cleared, or the period closed → never touch the original.** Post a
  compensating delta document (credit/debit note). This is also the UAE legal shape: a
  variation to an Ejari-registered contract is recorded as a signed addendum, not by
  reissuing the original.

One mechanism, two uses: the addition and the settled-correction are the same additive
document with a different sign.

## Approach

**A — reach the existing additive path from a new entry point.** Rejected alternatives: a
unified variation subsystem (requires refactoring `extend`, which is correct and covered by
the golden replays), and making `amendLines` delta-aware (rewrites the riskiest method and
inverts the invariant its javadoc is built on). A gets the capability by *reaching* proven
code; the others get it by *rewriting* it.

### Components

- **`LeaseVariationService`** (new) — `addCharge(leaseId, request)` and
  `correct(leaseId, request)`. Composes existing collaborators; owns no posting logic of
  its own.
- **`POST /api/v1/leases/{id}/variations`** (new) — staff-only, `requireManageable`.
- **A general endpoint for `addRowToPostedLease`** — today only the `cash-receipt` wrapper
  reaches it.
- **UI** — an *Add charge* action on the lease detail page; the correction flow reuses the
  termination screen's whole-register shape (below).

### Data flow — a mid-term addition

1. Validate the lease is ACTIVE and posted.
2. `datedLines(lines, effectiveFrom, lease.endDate)` — pins the window **inside** the
   remaining term. DEPOSIT lines refused, as on an extension.
3. Σ new cheque rows must equal the addendum value (VAT included), mirroring
   `extensionValue`.
4. Period-lock check for the entry date **and** for every new row's own date.
5. `appendLines` → `appendRows` → `postTco` (second TCO, narration "Addendum …") →
   `chequeRegistrar.register` per new row.
6. `syncDerivedTotals`; record a lease event; publish a `LeaseVariedEvent`.

The original TCO, `postingJournalId`, and every existing cheque are untouched — which is
why a cleared cheque is no obstacle.

### Data flow — a correction

Route on settlement state:

- **Unsettled + open period** → delegate to `amendLines` unchanged. No new behaviour.
- **Settled** → post a signed delta TCO for the difference. If the delta is negative and
  Σ cheques now exceeds contract value, the flow presents **the whole register** and the
  operator chooses which uncleared rows to hand back — mirroring termination's Return/Keep
  screen, and honouring `ChequeController`'s stated position that returning paper "belongs
  to the termination flow, which owns deciding what to do with every uncleared instrument
  at once." Any remainder becomes a credit balance settled at move-out.

We do **not** expose `returnToTenant` as a per-row endpoint.

### Error handling

Reuse `extend`'s three-stage shape, which exists to avoid burning an entry number on a
correction that never happened: validate the request with nothing persisted; write lines
and rows; resolve accounts and check the period lock; only then post journals. One
transaction throughout. Unmapped account roles surface as `UnmappedAccountRoleException`
with the property id, as elsewhere.

### Testing

- Golden ledger gate (`./gradlew test -PincludeTags=golden`) must stay green — it is the
  reason A avoids refactoring `extend`.
- New ITs: addition on a lease with a **cleared** cheque (the #15 case); contract value
  rises by exactly the delta (#17); recognition produces a segment over the addendum window
  only, and the original term is not recognised twice; Σ-cheques mismatch refused; DEPOSIT
  line refused; period-locked date refused; correction routes to `amendLines` when
  unsettled and to a delta when settled.

## Out of scope for v1

Negotiated rent reductions, refunds of cleared cash, and any automatic Ejari
re-registration. Renewal-time escalation (#24) and recurring-vs-one-off charge typing (#23)
are separate findings and unchanged here.

## Resolved questions (answers in *Decisions taken*)

1. **Ejari.** A material variation legally needs a fresh registration. Prompt for a new
   Ejari number on an addendum, or record it as a follow-up task? (Related: #24 leaves
   Ejari blank on renewal.)
2. **Extra deposit.** `extend` refuses a DEPOSIT line outright — "a renter who genuinely
   owes more deposit is charged it as a separate act". A parking bay sometimes carries its
   own deposit. Keep the refusal in v1?
3. **Does an addendum need its own document number** (e.g. `ADD-25/1`) distinct from the
   TCO series, given `DocumentType.ADDENDUM` currently exists only as a file-attachment
   category and is unused in code?
4. **How is a credit balance represented?** The design says a remainder after returning
   uncleared instruments "becomes a credit balance", but does not say where it lives. The
   candidates are an `ADVANCE_RENT`-style credit on the tenant's ledger (an existing
   `AccountRole`) or a settlement-time adjustment carried until move-out. This must be
   pinned down before implementation — left as-is it would be read two different ways.
   Note this is also the one place v1 touches the same ground as the deferred reductions
   work, so a deliberately minimal answer is fine.

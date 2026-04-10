# Cost Centre Split Transactions — Design

**Date:** 2026-04-10
**Status:** Approved

## Problem

A single maintenance team may serve multiple properties. When recording a maintenance bill (or any shared expense), the user needs to split the cost across multiple properties/units as cost centres. Currently, each transaction supports only one property and one unit.

## Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Entry point | Toggle on existing form | No separate UI path; split is an opt-in mode |
| Split level | Mixed (property or unit per row) | Each split row independently picks a property or a unit within a property |
| Data model | Denormalized children (Option C) | Child rows are full `FinancialTransaction` entities — existing property/unit reports get split costs for free |
| VAT | Per-split (pro-rated) | Each child stores its share of VAT so property P&L reports reflect true gross cost |
| Editability | Immutable (same as normal transactions) | Create-only, no edit/delete |

## Database Schema

**Migration `44-transaction-splits.yaml`** — adds two columns to `financial_transactions`:

| Column | Type | Notes |
|---|---|---|
| `parent_transaction_id` | `UUID` nullable FK → `financial_transactions.id` | Set on child rows only |
| `is_split_parent` | `boolean` default `false` | `true` on parent row of a split group |

Child rows are full `FinancialTransaction` entities with all fields populated (account, date, description, debit/credit, vat_amount, property_id, unit_id). The parent has no `property_id`/`unit_id` (org-level) and its debit/credit equals the sum of all children.

### Invariants (enforced at service layer)

- `SUM(children.debit) == parent.debit` and `SUM(children.credit) == parent.credit`
- `child.vat_amount = (child_net / parent_net) * parent_vat_amount`
- Children inherit parent's `account`, `date`, `description`

## Backend

### Entity changes (`FinancialTransaction`)

- Add `parentTransaction` (`@ManyToOne`, nullable, FK `parent_transaction_id`)
- Add `isSplitParent` (`boolean`, default `false`)
- Existing `@PrePersist` handles `property ← unit` auto-resolution for children

### New DTO: `CreateSplitTransactionDTO`

```
date, description, accountId, debit, credit, vatApplicable, vatRate, notes
splits: [{ propertyId?, unitId?, amount }]
```

### Service changes (`FinancialTransactionService`)

- New method: `createSplitTransaction(CreateSplitTransactionDTO dto)` — validates sum invariant, saves parent (org-level, `isSplitParent=true`), then saves each child (inheriting account/date/description, deriving VAT share, setting `parentTransaction`)
- `getTransactions(...)` — add filter `WHERE parent_transaction_id IS NULL` to exclude children from the main ledger
- Existing `findByPropertyId` / `findByUnitId` — **unchanged** — children naturally surface in property/unit reports

### Controller changes (`FinancialTransactionController`)

- New endpoint: `POST /api/v1/finance/transactions/split`
- Existing `GET /api/v1/finance/transactions/{id}` — include children in response when `isSplitParent` is true

## Frontend

### Form (Add Transaction modal)

- New toggle below debit/credit fields: **"Split across multiple properties/units"**
- **Toggle OFF (default):** current single property + unit dropdowns
- **Toggle ON:** property/unit dropdowns replaced by a split allocation table:

```
┌─────────────────────────┬──────────┬────────────┬──────────┐
│ Property (Project)      │ Unit     │ Amount     │          │
├─────────────────────────┼──────────┼────────────┼──────────┤
│ [dropdown]              │ [drop.]  │ [input]    │ [× del]  │
│ [dropdown]              │ [drop.]  │ [input]    │ [× del]  │
└─────────────────────────┴──────────┴────────────┴──────────┘
                                 Total: 10,000 / 10,000 ✓
                         [+ Add Row]
```

- Starts with 2 rows, equal split auto-calculated from the main debit/credit amount
- Amount inputs freely editable (no auto-rebalance)
- Running total with green checkmark (match) or red warning (mismatch)
- "Create" button disabled until total matches exactly
- Each row: property dropdown → unit dropdown (lazy-loaded, optional)

### Ledger display

- Main list filters `parent_transaction_id IS NULL` — only parents and non-split transactions show
- Split parent rows show a **chevron expand icon** and a **"Split" badge**
- Expanding reveals indented child rows with property/unit name and allocated amount
- Parent row shows total amount, no property/unit label

```
▼ Maintenance contract Q1        —        — 10,000   -2,400
    ├ Villa A (Tower 1)          —        —  3,000
    ├ Villa B                    —        —  3,000
    ├ Villa C (Unit 201)         —        —  2,000
    └ Villa D (Unit 305)         —        —  2,000
```

### Property/unit reports

No changes — child rows already have `property_id`/`unit_id` set, so existing `findByPropertyId`/`findByUnitId` queries pick them up automatically.

# Settlement Additions Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add "additions" (repayments to renter) alongside deductions in the settlement flow, with `refundAmount = depositAmount - totalDeductions + totalAdditions`.

**Architecture:** Extend the existing `lease_settlement_deductions` table with a `type` discriminator column (`DEDUCTION`/`ADDITION`) and an `addition_category` column. Reuse all existing attachment infrastructure. Update the settlement math, DTOs, service layer, web UI, and mobile UI.

**Tech Stack:** Java 21 + Spring Boot + Liquibase, Next.js + TypeScript, Flutter

---

### Task 1: Liquibase Migration 40

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/40-settlement-additions.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`

**Step 1: Create the migration file**

```yaml
databaseChangeLog:
  - changeSet:
      id: 40-add-type-to-deductions
      author: rentaxis
      changes:
        - addColumn:
            tableName: lease_settlement_deductions
            columns:
              - column:
                  name: type
                  type: varchar(20)
                  defaultValue: DEDUCTION
                  constraints:
                    nullable: false
              - column:
                  name: addition_category
                  type: varchar(50)
                  constraints:
                    nullable: true
      comment: "Add type discriminator (DEDUCTION/ADDITION) and addition_category to settlement line items."

  - changeSet:
      id: 40-add-total-additions-to-settlements
      author: rentaxis
      changes:
        - addColumn:
            tableName: lease_settlements
            columns:
              - column:
                  name: total_additions
                  type: decimal(15,2)
                  defaultValueNumeric: 0
                  constraints:
                    nullable: false
      comment: "Add total_additions column to lease_settlements for refund calculation."
```

**Step 2: Register migration in master changelog**

Add to `db.changelog-master.yaml` after the line including `39-settlement-deduction-attachments.yaml`:

```yaml
  - include:
      file: db/changelog/changesets/40-settlement-additions.yaml
```

**Step 3: Verify**

Run: `cd backend && ./gradlew bootRun` (start and stop — Liquibase auto-applies)

**Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/
git commit -m "feat: add migration 40 for settlement additions type discriminator"
```

---

### Task 2: New Enums — LineItemType and AdditionCategory

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/LineItemType.java`
- Create: `backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/AdditionCategory.java`

**Step 1: Create LineItemType enum**

```java
package com.datagami.rentaxis.domain.entity.enums;

public enum LineItemType {
    DEDUCTION,
    ADDITION
}
```

**Step 2: Create AdditionCategory enum**

```java
package com.datagami.rentaxis.domain.entity.enums;

public enum AdditionCategory {
    PREPAID_RENT,
    UTILITY_OVERPAYMENT,
    DEPOSIT_INTEREST,
    LANDLORD_COMPENSATION,
    OTHER
}
```

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/enums/
git commit -m "feat: add LineItemType and AdditionCategory enums"
```

---

### Task 3: Update LeaseSettlementDeduction Entity

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseSettlementDeduction.java`

**Step 1: Add type and additionCategory fields**

After the `autoCalculated` field (~line 36), add:

```java
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LineItemType type = LineItemType.DEDUCTION;

    @Enumerated(EnumType.STRING)
    @Column(name = "addition_category", length = 50)
    private AdditionCategory additionCategory;
```

Add imports for `LineItemType` and `AdditionCategory`.

**Step 2: Update LeaseSettlement entity — add totalAdditions field**

Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/LeaseSettlement.java`

After the `totalDeductions` field (~line 29), add:

```java
    @Column(name = "total_additions", nullable = false)
    private BigDecimal totalAdditions = BigDecimal.ZERO;
```

**Step 3: Verify compilation**

Run: `cd backend && ./gradlew compileJava -q`

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/
git commit -m "feat: add type and additionCategory to LeaseSettlementDeduction, totalAdditions to LeaseSettlement"
```

---

### Task 4: Update DTOs

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/SaveSettlementDTO.java`
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/dto/SettlementResponseDTO.java`

**Step 1: Update SaveSettlementDTO.DeductionItemDTO**

Make `category` nullable (additions don't use DeductionCategory). Add new fields:

```java
    public static class DeductionItemDTO {
        private UUID id;
        private DeductionCategory category; // remove @NotNull — nullable for additions
        private String description;
        @NotNull
        private BigDecimal amount;
        private boolean autoCalculated;
        private String type; // "DEDUCTION" (default) or "ADDITION"
        private String additionCategory; // required when type = ADDITION
    }
```

**Step 2: Update SettlementResponseDTO**

Add `totalAdditions` field to the main DTO:

```java
    private BigDecimal totalAdditions;
```

Add `type` and `additionCategory` to `DeductionDTO`:

```java
    public static class DeductionDTO {
        private UUID id;
        private String category;
        private String description;
        private BigDecimal amount;
        private boolean autoCalculated;
        private String type;
        private String additionCategory;
        private List<DeductionAttachmentDTO> attachments;
    }
```

**Step 3: Verify compilation**

Run: `cd backend && ./gradlew compileJava -q`

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/
git commit -m "feat: add type and additionCategory to settlement DTOs"
```

---

### Task 5: Update SettlementService

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/SettlementService.java`

**Step 1: Update saveDraft — split totals by type**

Replace the total calculation block (lines 134-141) with:

```java
        BigDecimal totalDeductions = BigDecimal.ZERO;
        BigDecimal totalAdditions = BigDecimal.ZERO;
        if (dto.getDeductions() != null) {
            for (SaveSettlementDTO.DeductionItemDTO item : dto.getDeductions()) {
                if ("ADDITION".equals(item.getType())) {
                    totalAdditions = totalAdditions.add(item.getAmount());
                } else {
                    totalDeductions = totalDeductions.add(item.getAmount());
                }
            }
        }
        settlement.setTotalDeductions(totalDeductions);
        settlement.setTotalAdditions(totalAdditions);
        settlement.setRefundAmount(depositAmount.subtract(totalDeductions).add(totalAdditions));
```

**Step 2: Update deduction creation in saveDraft — set type and additionCategory**

In the reconcile loop, when creating/updating deductions, add:

```java
    // For both new and existing deductions:
    LineItemType lineItemType = "ADDITION".equals(item.getType())
            ? LineItemType.ADDITION : LineItemType.DEDUCTION;
    deduction.setType(lineItemType);
    if (lineItemType == LineItemType.ADDITION && item.getAdditionCategory() != null) {
        deduction.setAdditionCategory(AdditionCategory.valueOf(item.getAdditionCategory()));
        deduction.setCategory(null); // additions don't use DeductionCategory
    } else {
        deduction.setAdditionCategory(null);
    }
```

**Step 3: Update buildSettlementResponse — include new fields**

In the `buildSettlementResponse` method, after `response.setTotalDeductions(...)`, add:

```java
        response.setTotalAdditions(settlement.getTotalAdditions());
```

In the deduction mapping lambda, add:

```java
            dto.setType(d.getType().name());
            dto.setAdditionCategory(d.getAdditionCategory() != null ? d.getAdditionCategory().name() : null);
```

**Step 4: Update createSettlement (legacy quick-terminate) — set totalAdditions to zero**

In `createSettlement`, after `settlement.setTotalDeductions(totalDeductions)`, add:

```java
        settlement.setTotalAdditions(BigDecimal.ZERO);
```

And update the refund calculation (it stays the same since totalAdditions = 0 for legacy flow).

**Step 5: Verify compilation**

Run: `cd backend && ./gradlew compileJava -q`

**Step 6: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/SettlementService.java
git commit -m "feat: update SettlementService for additions — split totals, new refund formula"
```

---

### Task 6: Update Web Settlement Page — Types and State

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/[id]/settlement/page.tsx`

**Step 1: Update types and constants**

Add to the `DeductionItem` type:

```typescript
type DeductionItem = {
    id?: string;
    category: string;
    description: string;
    amount: number;
    autoCalculated: boolean;
    attachments: AttachmentItem[];
    type?: string;          // "DEDUCTION" or "ADDITION"
    additionCategory?: string;
};
```

Update `Settlement` type — add `totalAdditions`:

```typescript
    totalAdditions: number;
```

Add `ADDITION_CATEGORIES` constant:

```typescript
const ADDITION_CATEGORIES = [
    { value: "PREPAID_RENT", label: "Prepaid Rent" },
    { value: "UTILITY_OVERPAYMENT", label: "Utility Overpayment" },
    { value: "DEPOSIT_INTEREST", label: "Deposit Interest" },
    { value: "LANDLORD_COMPENSATION", label: "Landlord Compensation" },
    { value: "OTHER", label: "Other" },
];

const ADDITION_CATEGORY_LABELS: Record<string, string> = {
    PREPAID_RENT: "Prepaid Rent",
    UTILITY_OVERPAYMENT: "Utility Overpayment",
    DEPOSIT_INTEREST: "Deposit Interest",
    LANDLORD_COMPENSATION: "Landlord Compensation",
    OTHER: "Other",
};
```

**Step 2: Add additions state**

Add new state variable:

```typescript
const [additions, setAdditions] = useState<DeductionItem[]>([]);
```

**Step 3: Update loadSettlement to parse additions**

In the `loadSettlement` callback, after splitting auto/manual deductions, add:

```typescript
const additionItems = s.deductions.filter((d: DeductionItem) => d.type === "ADDITION");
setAdditions(additionItems);
// Update manual filter to exclude additions:
const manual = s.deductions.filter((d: DeductionItem) => !d.autoCalculated && d.type !== "ADDITION");
```

**Step 4: Update computed totals**

Replace the `totalDeductions` and `refundAmount` calculations:

```typescript
const totalDeductions = [
    ...autoDeductions,
    ...manualDeductions,
].reduce((sum, d) => sum + (d.amount || 0), 0);
const totalAdditions = additions.reduce((sum, d) => sum + (d.amount || 0), 0);
const refundAmount = depositAmount - totalDeductions + totalAdditions;
```

**Step 5: Update buildDeductionPayload to include additions**

```typescript
const buildDeductionPayload = () => {
    return [
        ...autoDeductions.map(d => ({
            id: d.id || undefined,
            category: d.category,
            description: d.description,
            amount: d.amount,
            autoCalculated: true,
            type: "DEDUCTION",
        })),
        ...manualDeductions.map(d => ({
            id: d.id || undefined,
            category: d.category,
            description: d.description,
            amount: d.amount,
            autoCalculated: false,
            type: "DEDUCTION",
        })),
        ...additions.map(d => ({
            id: d.id || undefined,
            additionCategory: d.additionCategory || d.category,
            description: d.description,
            amount: d.amount,
            autoCalculated: false,
            type: "ADDITION",
        })),
    ].filter(d => d.amount > 0);
};
```

**Step 6: Update handleFileUpload and handleDeleteAttachment**

These already work for any deduction ID. Just add `setAdditions` alongside the existing state updaters:

```typescript
setAdditions(prev =>
    prev.map(d => d.id === deductionId ? { ...d, attachments: newAttachments } : d)
);
```

Add this line in both `handleFileUpload` (after the setManualDeductions call) and `handleDeleteAttachment` (same place).

**Step 7: Commit**

```bash
git add web/src/app/\[locale\]/dashboard/leases/\[id\]/settlement/page.tsx
git commit -m "feat: add additions state, types, and payload to web settlement page"
```

---

### Task 7: Web Settlement Page — Additions UI Section

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/[id]/settlement/page.tsx`

**Step 1: Add the Additions section JSX**

Insert after the Manual Deductions section closing `</div>` (after ~line 593) and before the Notes section:

```tsx
{/* Additions (Repayments to Renter) */}
<div className="bg-surface rounded-xl border border-success/30">
    <div className="px-5 py-3.5 border-b border-success/20 flex items-center justify-between">
        <p className="text-[10px] font-semibold text-success uppercase tracking-wider">Additions (Repayments)</p>
        {!isFinalized && (
            <button
                onClick={() => setAdditions(prev => [
                    ...prev,
                    { category: "PREPAID_RENT", additionCategory: "PREPAID_RENT", description: "", amount: 0, autoCalculated: false, attachments: [], type: "ADDITION" },
                ])}
                className="flex items-center gap-1 text-[10px] font-semibold text-success hover:text-success/80 cursor-pointer"
            >
                <Plus size={12} /> Add Repayment
            </button>
        )}
    </div>
    <div className="p-4">
        {additions.length > 0 ? (
            <div className="space-y-3">
                {additions.map((d, i) => (
                    <div key={d.id ?? `add-${i}`} className="bg-success/5 rounded-lg border border-success/20 p-3 space-y-3">
                        {/* Category, Amount, Delete */}
                        <div className="flex items-center gap-2">
                            <select
                                value={d.additionCategory || d.category}
                                disabled={isFinalized}
                                onChange={(e) => setAdditions(prev =>
                                    prev.map((dd, ii) => ii === i ? { ...dd, additionCategory: e.target.value, category: e.target.value } : dd)
                                )}
                                className="flex-1 border border-success/30 rounded-lg bg-surface px-2 py-1.5 text-xs text-foreground focus:ring-2 focus:ring-success/20 focus:border-success focus:outline-none disabled:opacity-60 disabled:cursor-not-allowed"
                            >
                                {ADDITION_CATEGORIES.map(c => (
                                    <option key={c.value} value={c.value}>{c.label}</option>
                                ))}
                            </select>
                            <div className="flex items-center gap-1.5 shrink-0">
                                <span className="text-[10px] text-success font-semibold">+ AED</span>
                                <input
                                    type="number"
                                    value={d.amount}
                                    min={0}
                                    step={0.01}
                                    placeholder="0"
                                    disabled={isFinalized}
                                    onChange={(e) => {
                                        const val = parseFloat(e.target.value) || 0;
                                        setAdditions(prev =>
                                            prev.map((dd, ii) => ii === i ? { ...dd, amount: val } : dd)
                                        );
                                    }}
                                    className="w-24 border border-success/30 rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground text-end tabular-nums focus:ring-2 focus:ring-success/20 focus:border-success focus:outline-none disabled:opacity-60 disabled:cursor-not-allowed"
                                />
                            </div>
                            {!isFinalized && (
                                <button
                                    onClick={() => setAdditions(prev => prev.filter((_, ii) => ii !== i))}
                                    className="p-1 text-error hover:text-error/80 cursor-pointer shrink-0"
                                >
                                    <Trash2 size={13} />
                                </button>
                            )}
                        </div>

                        {/* Description */}
                        <input
                            type="text"
                            value={d.description}
                            placeholder="Description (optional)"
                            disabled={isFinalized}
                            onChange={(e) => setAdditions(prev =>
                                prev.map((dd, ii) => ii === i ? { ...dd, description: e.target.value } : dd)
                            )}
                            className="w-full border border-success/20 rounded-lg bg-surface px-3 py-1.5 text-xs text-foreground placeholder:text-muted/50 focus:ring-2 focus:ring-success/20 focus:border-success focus:outline-none disabled:opacity-60 disabled:cursor-not-allowed"
                        />

                        {/* Attachments — same pattern as deductions */}
                        <div>
                            <div className="flex items-center justify-between mb-2">
                                <p className="text-[10px] font-semibold text-muted uppercase tracking-wider flex items-center gap-1">
                                    <Paperclip size={9} /> Attachments ({d.attachments.length}/10)
                                </p>
                                {d.id ? (
                                    d.attachments.length < 10 && (
                                        <label className={cn(
                                            "flex items-center gap-1 px-2 py-1 rounded-lg text-[9px] font-semibold transition-colors cursor-pointer",
                                            uploadingDeductionId === d.id
                                                ? "bg-input text-muted cursor-not-allowed"
                                                : "bg-success/10 text-success hover:bg-success/20"
                                        )}>
                                            {uploadingDeductionId === d.id
                                                ? <Loader2 size={9} className="animate-spin" />
                                                : <Upload size={9} />
                                            }
                                            Add Files
                                            <input
                                                ref={el => { fileInputRefs.current[d.id!] = el; }}
                                                type="file"
                                                accept="image/*,video/*,.pdf"
                                                className="hidden"
                                                disabled={uploadingDeductionId === d.id}
                                                onChange={(e) => {
                                                    const file = e.target.files?.[0];
                                                    if (file && d.id) handleFileUpload(d.id, file);
                                                    if (e.target) e.target.value = "";
                                                }}
                                            />
                                        </label>
                                    )
                                ) : (
                                    <p className="text-[9px] text-muted italic">Save draft to enable file attachments</p>
                                )}
                            </div>
                            {d.attachments.length > 0 && (
                                <div className="flex flex-wrap gap-2">
                                    {d.attachments.map(att => (
                                        <AttachmentThumbnail
                                            key={att.id}
                                            attachment={att}
                                            onDelete={(id) => d.id && handleDeleteAttachment(id, d.id)}
                                            isDraft={isDraft}
                                        />
                                    ))}
                                </div>
                            )}
                        </div>
                    </div>
                ))}
            </div>
        ) : (
            <p className="text-xs text-muted text-center py-4 bg-success/5 rounded-lg border border-dashed border-success/20">
                No additions. Add repayments owed to the renter.
            </p>
        )}
    </div>
</div>
```

**Step 2: Update the Summary section**

Replace the Summary inner content with:

```tsx
<div className="flex justify-between items-center">
    <span className="text-xs text-muted">Security Deposit</span>
    <span className="text-xs font-semibold text-foreground tabular-nums">{formatCurrency(depositAmount)}</span>
</div>
<div className="flex justify-between items-center">
    <span className="text-xs text-muted">Total Deductions</span>
    <span className="text-xs font-semibold text-error tabular-nums">
        {totalDeductions > 0 ? `- ${formatCurrency(totalDeductions)}` : formatCurrency(0)}
    </span>
</div>
{totalAdditions > 0 && (
    <div className="flex justify-between items-center">
        <span className="text-xs text-muted">Total Additions</span>
        <span className="text-xs font-semibold text-success tabular-nums">
            + {formatCurrency(totalAdditions)}
        </span>
    </div>
)}
<div className="border-t-2 border-border pt-3 flex justify-between items-center">
    <span className="text-sm font-bold text-foreground">Refund to Renter</span>
    <span className={cn(
        "text-sm font-bold tabular-nums",
        refundAmount >= 0 ? "text-success" : "text-error"
    )}>
        {formatCurrency(refundAmount)}
    </span>
</div>
```

**Step 3: Verify**

Run: `cd web && npx tsc --noEmit`

**Step 4: Commit**

```bash
git add web/src/app/\[locale\]/dashboard/leases/\[id\]/settlement/page.tsx
git commit -m "feat: add additions UI section with green styling to web settlement page"
```

---

### Task 8: Update Mobile Settlement Screen — Additions State and Data

**Files:**
- Modify: `mobile/apps/manager/lib/screens/lease_settlement_screen.dart`

**Step 1: Add addition categories constant**

After the `_manualCategories` constant (~line 43-50), add:

```dart
  static const _additionCategories = [
    {'value': 'PREPAID_RENT', 'label': 'Prepaid Rent'},
    {'value': 'UTILITY_OVERPAYMENT', 'label': 'Utility Overpayment'},
    {'value': 'DEPOSIT_INTEREST', 'label': 'Deposit Interest'},
    {'value': 'LANDLORD_COMPENSATION', 'label': 'Landlord Compensation'},
    {'value': 'OTHER', 'label': 'Other'},
  ];
```

**Step 2: Add additions state list**

After the `_deductions` state declaration (~line 35), add:

```dart
  List<Map<String, dynamic>> _additions = [];
```

**Step 3: Add addition amount controllers map**

After `_amountControllers` (~line 39), add:

```dart
  final Map<int, TextEditingController> _additionAmountControllers = {};
```

Dispose them in `dispose()`:

```dart
    for (final ctrl in _additionAmountControllers.values) {
      ctrl.dispose();
    }
```

**Step 4: Update _loadData to parse additions**

In `_loadData`, after building `rawDeductions` from the settlement response, add:

```dart
        final rawAdditions = (settlement['deductions'] as List? ?? [])
            .where((d) => d['type'] == 'ADDITION')
            .map((d) => <String, dynamic>{
                  'id': d['id'],
                  'additionCategory': d['additionCategory'] ?? 'OTHER',
                  'description': d['description'] ?? '',
                  'amount': d['amount']?.toString() ?? '0',
                  'autoCalculated': false,
                  'type': 'ADDITION',
                  'attachments': List<Map<String, dynamic>>.from(d['attachments'] ?? []),
                })
            .toList();
```

Filter `rawDeductions` to exclude additions:

```dart
        final filteredDeductions = rawDeductions
            .where((d) => d['type'] != 'ADDITION')
            .toList();
```

In the `setState` block, set both:

```dart
            _deductions = filteredDeductions;
            _additions = rawAdditions;
```

**Step 5: Update computed totals**

Add a `_totalAdditions` getter:

```dart
  double get _totalAdditions => _additions.fold(
      0.0,
      (sum, d) =>
          sum + (double.tryParse(d['amount']?.toString() ?? '0') ?? 0.0));
```

Update `_refundAmount`:

```dart
  double get _refundAmount => _depositAmount - _totalDeductions + _totalAdditions;
```

**Step 6: Update _saveDraft to include additions**

In `_saveDraft`, update the `data` map to merge additions into the deductions list:

```dart
      final data = {
        'notes': _notes,
        'deductions': [
          ..._deductions.map((d) => {
                'id': d['id'],
                'category': d['category'],
                'description': d['description'],
                'amount': double.tryParse(d['amount']?.toString() ?? '0') ?? 0.0,
                'autoCalculated': d['autoCalculated'],
                'type': 'DEDUCTION',
              }),
          ..._additions.map((d) => {
                'id': d['id'],
                'additionCategory': d['additionCategory'],
                'description': d['description'],
                'amount': double.tryParse(d['amount']?.toString() ?? '0') ?? 0.0,
                'autoCalculated': false,
                'type': 'ADDITION',
              }),
        ],
      };
```

After save, sync addition IDs from response (similar to deductions sync).

**Step 7: Add _categoryLabel for additions**

```dart
  String _additionCategoryLabel(String category) {
    const labels = {
      'PREPAID_RENT': 'Prepaid Rent',
      'UTILITY_OVERPAYMENT': 'Utility Overpayment',
      'DEPOSIT_INTEREST': 'Deposit Interest',
      'LANDLORD_COMPENSATION': 'Landlord Compensation',
      'OTHER': 'Other',
    };
    return labels[category] ?? category;
  }
```

**Step 8: Commit**

```bash
git add mobile/apps/manager/lib/screens/lease_settlement_screen.dart
git commit -m "feat: add additions state, data parsing, and save logic to mobile settlement"
```

---

### Task 9: Mobile Settlement Screen — Additions UI Section

**Files:**
- Modify: `mobile/apps/manager/lib/screens/lease_settlement_screen.dart`

**Step 1: Add additions section to the build method**

In the editable view, after the manual deductions section and before the notes section, add an additions section. Follow the same card pattern as manual deductions but with green (`AppColors.success`) styling:

- Section header: "Additions (Repayments)" with an "Add" button
- Each addition card has: category dropdown (from `_additionCategories`), amount field, description, attachment grid
- Green border/accent: `AppColors.success.withValues(alpha: 0.1)` background, `AppColors.success.withValues(alpha: 0.3)` border
- "+" AED prefix on amount field in green
- Same attachment upload/delete pattern using `_uploadAttachment` and `_deleteAttachment` (these work with any line item ID)

**Step 2: Update the summary card**

Add a row between "Total Deductions" and "Refund to Renter" showing "Total Additions" in green when `_totalAdditions > 0`.

**Step 3: Update the finalized read-only view**

Show additions alongside deductions in the finalized view, with green category labels to distinguish them.

**Step 4: Verify**

Run: `cd mobile && flutter analyze apps/manager/lib/screens/lease_settlement_screen.dart`

**Step 5: Commit**

```bash
git add mobile/apps/manager/lib/screens/lease_settlement_screen.dart
git commit -m "feat: add additions UI section with green styling to mobile settlement screen"
```

---

### Task 10: Update Web Lease Detail Page

**Files:**
- Modify: `web/src/app/[locale]/dashboard/leases/[id]/page.tsx`

**Step 1: Update Settlement type**

Add `totalAdditions: number` to the Settlement type used on this page.

**Step 2: Update settlement summary display**

In the settlement summary section (shown for FINALIZED settlements), add a row for "Total Additions" in green between deductions and refund, matching the settlement page pattern.

**Step 3: Verify**

Run: `cd web && npx tsc --noEmit`

**Step 4: Commit**

```bash
git add web/src/app/\[locale\]/dashboard/leases/\[id\]/page.tsx
git commit -m "feat: show additions in lease detail settlement summary"
```

---

### Task 11: Final Verification

**Step 1: Backend compile**

Run: `cd backend && ./gradlew compileJava -q`

**Step 2: Web TypeScript check**

Run: `cd web && npx tsc --noEmit`

**Step 3: Flutter analyze**

Run: `cd mobile && flutter analyze apps/manager/lib/screens/lease_settlement_screen.dart`

**Step 4: Commit any remaining fixes**

If any issues found, fix and commit with: `fix: address compilation issues in settlement additions`

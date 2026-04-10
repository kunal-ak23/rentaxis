# Cost Centre Split Transactions — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Allow a single financial transaction to be split across multiple properties/units as cost centres, with configurable allocation amounts.

**Architecture:** Self-referencing parent-child model on `financial_transactions` — parent row holds full amount + metadata, denormalized child rows hold per-property/unit allocations. Existing property/unit reports automatically pick up child rows with zero query changes.

**Tech Stack:** Java 21, Spring Boot 4, Liquibase, PostgreSQL, Next.js 16, TypeScript, Tailwind CSS

---

### Task 1: Liquibase Migration

**Files:**
- Create: `backend/src/main/resources/db/changelog/changesets/44-transaction-splits.yaml`
- Modify: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`

**Step 1: Create the migration file**

Create `44-transaction-splits.yaml`:

```yaml
databaseChangeLog:
  - changeSet:
      id: 44-transaction-splits
      author: system
      comment: "Add parent-child split support to financial_transactions"
      changes:
        - addColumn:
            tableName: financial_transactions
            columns:
              - column:
                  name: parent_transaction_id
                  type: uuid
                  constraints:
                    nullable: true
              - column:
                  name: is_split_parent
                  type: boolean
                  defaultValueBoolean: false
                  constraints:
                    nullable: false
        - addForeignKeyConstraint:
            baseTableName: financial_transactions
            baseColumnNames: parent_transaction_id
            referencedTableName: financial_transactions
            referencedColumnNames: id
            constraintName: fk_fin_txn_parent
            onDelete: CASCADE
        - createIndex:
            tableName: financial_transactions
            indexName: idx_fin_txn_parent_id
            columns:
              - column:
                  name: parent_transaction_id
        - createIndex:
            tableName: financial_transactions
            indexName: idx_fin_txn_is_split_parent
            columns:
              - column:
                  name: is_split_parent
```

**Step 2: Register the migration in the master changelog**

Add to end of `db.changelog-master.yaml`:

```yaml
  - include:
      file: db/changelog/changesets/44-transaction-splits.yaml
```

**Step 3: Verify migration runs**

Run: `cd backend && ./gradlew bootRun`
Expected: Application starts without Liquibase errors.

**Step 4: Commit**

```bash
git add backend/src/main/resources/db/changelog/changesets/44-transaction-splits.yaml \
       backend/src/main/resources/db/changelog/db.changelog-master.yaml
git commit -m "feat: add migration for transaction split parent-child columns"
```

---

### Task 2: Entity Changes

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/entity/FinancialTransaction.java`

**Step 1: Add parent-child fields to the entity**

Add these fields to `FinancialTransaction.java` (after the existing `notes` field at line ~77):

```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "parent_transaction_id")
@com.fasterxml.jackson.annotation.JsonIgnore
private FinancialTransaction parentTransaction;

@Column(name = "is_split_parent")
private boolean splitParent = false;

@OneToMany(mappedBy = "parentTransaction", fetch = FetchType.LAZY)
private List<FinancialTransaction> splitChildren;
```

Add the required import at the top:

```java
import java.util.List;
```

Add getter/setter annotations are handled by Lombok `@Getter @Setter`.

**Note:** `parentTransaction` is `@JsonIgnore` to prevent circular serialization. `splitChildren` will be serialized when the parent is fetched — this is intentional so the GET endpoint can return children. Use `@JsonInclude(JsonInclude.Include.NON_NULL)` or a DTO if needed.

**Step 2: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/entity/FinancialTransaction.java
git commit -m "feat: add parent-child split fields to FinancialTransaction entity"
```

---

### Task 3: DTO for Split Transaction Creation

**Files:**
- Create: `backend/src/main/java/com/datagami/rentaxis/api/dto/CreateSplitTransactionDTO.java`

**Step 1: Create the DTO**

```java
package com.datagami.rentaxis.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class CreateSplitTransactionDTO {

    private LocalDate date;
    private String description;
    private UUID accountId;
    private BigDecimal debit;
    private BigDecimal credit;
    private boolean vatApplicable;
    private BigDecimal vatRate;
    private String notes;
    private UUID vendorId;
    private UUID staffId;
    private List<SplitAllocation> splits;

    public static class SplitAllocation {
        private UUID propertyId;
        private UUID unitId;
        private BigDecimal amount;

        public UUID getPropertyId() { return propertyId; }
        public void setPropertyId(UUID propertyId) { this.propertyId = propertyId; }
        public UUID getUnitId() { return unitId; }
        public void setUnitId(UUID unitId) { this.unitId = unitId; }
        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
    }

    // Getters and setters
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public BigDecimal getDebit() { return debit; }
    public void setDebit(BigDecimal debit) { this.debit = debit; }
    public BigDecimal getCredit() { return credit; }
    public void setCredit(BigDecimal credit) { this.credit = credit; }
    public boolean isVatApplicable() { return vatApplicable; }
    public void setVatApplicable(boolean vatApplicable) { this.vatApplicable = vatApplicable; }
    public BigDecimal getVatRate() { return vatRate; }
    public void setVatRate(BigDecimal vatRate) { this.vatRate = vatRate; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public UUID getVendorId() { return vendorId; }
    public void setVendorId(UUID vendorId) { this.vendorId = vendorId; }
    public UUID getStaffId() { return staffId; }
    public void setStaffId(UUID staffId) { this.staffId = staffId; }
    public List<SplitAllocation> getSplits() { return splits; }
    public void setSplits(List<SplitAllocation> splits) { this.splits = splits; }
}
```

**Step 2: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/dto/CreateSplitTransactionDTO.java
git commit -m "feat: add CreateSplitTransactionDTO for split transaction creation"
```

---

### Task 4: Repository Changes

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/domain/repository/FinancialTransactionRepository.java`

**Step 1: Add query methods for split filtering**

Add these methods to `FinancialTransactionRepository`:

```java
// Main ledger: exclude child transactions
List<FinancialTransaction> findByParentTransactionIsNullOrderByDateAsc();

List<FinancialTransaction> findByParentTransactionIsNullAndPropertyIdOrderByDateAsc(UUID propertyId);

List<FinancialTransaction> findByParentTransactionIsNullAndAccountTypeOrderByDateAsc(AccountType accountType);

List<FinancialTransaction> findByParentTransactionIsNullAndDateBetweenOrderByDateAsc(LocalDate startDate, LocalDate endDate);

List<FinancialTransaction> findByParentTransactionIsNullAndPropertyIdAndDateBetweenOrderByDateAsc(UUID propertyId, LocalDate startDate, LocalDate endDate);

// Fetch children for a parent
List<FinancialTransaction> findByParentTransactionId(UUID parentTransactionId);
```

**Step 2: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/domain/repository/FinancialTransactionRepository.java
git commit -m "feat: add repository methods for split transaction filtering"
```

---

### Task 5: Service Changes

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/core/service/FinancialTransactionService.java`

**Step 1: Add the `createSplitTransaction` method**

Add these imports at the top:

```java
import com.datagami.rentaxis.api.dto.CreateSplitTransactionDTO;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.entity.Staff;
import com.datagami.rentaxis.domain.repository.VendorRepository;
import com.datagami.rentaxis.domain.repository.StaffRepository;
import java.math.RoundingMode;
```

Inject `VendorRepository` and `StaffRepository` in the constructor (if not already present — check first).

Add this method:

```java
@Transactional
public FinancialTransaction createSplitTransaction(CreateSplitTransactionDTO dto) {
    // Validate splits sum
    BigDecimal splitTotal = dto.getSplits().stream()
            .map(CreateSplitTransactionDTO.SplitAllocation::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal parentAmount = dto.getDebit().compareTo(BigDecimal.ZERO) > 0 ? dto.getDebit() : dto.getCredit();
    if (splitTotal.compareTo(parentAmount) != 0) {
        throw new RuntimeException("Split amounts (" + splitTotal + ") must equal transaction amount (" + parentAmount + ")");
    }

    // Resolve account
    Account account = accountRepository.findById(dto.getAccountId())
            .orElseThrow(() -> new RuntimeException("Account not found: " + dto.getAccountId()));

    // Compute parent VAT
    BigDecimal parentVatAmount = BigDecimal.ZERO;
    BigDecimal parentGrossAmount = BigDecimal.ZERO;
    BigDecimal parentNetAmount = parentAmount;
    if (dto.isVatApplicable() && dto.getVatRate() != null) {
        parentVatAmount = parentAmount.multiply(dto.getVatRate())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        parentGrossAmount = parentAmount.add(parentVatAmount);
        parentNetAmount = parentAmount;
    }

    // Create parent transaction (org-level, no property/unit)
    FinancialTransaction parent = new FinancialTransaction();
    parent.setDate(dto.getDate());
    parent.setDescription(dto.getDescription());
    parent.setAccount(account);
    parent.setAccountCode(account.getCode());
    parent.setAccountType(account.getAccountType());
    parent.setDebit(dto.getDebit());
    parent.setCredit(dto.getCredit());
    parent.setVatApplicable(dto.isVatApplicable());
    parent.setVatRate(dto.isVatApplicable() ? dto.getVatRate() : BigDecimal.ZERO);
    parent.setVatAmount(parentVatAmount);
    parent.setNetAmount(parentNetAmount);
    parent.setGrossAmount(parentGrossAmount);
    parent.setNotes(dto.getNotes());
    parent.setSplitParent(true);

    // Optional vendor/staff on parent
    if (dto.getVendorId() != null) {
        Vendor vendor = vendorRepository.findById(dto.getVendorId())
                .orElseThrow(() -> new RuntimeException("Vendor not found"));
        parent.setVendor(vendor);
    }
    if (dto.getStaffId() != null) {
        Staff staff = staffRepository.findById(dto.getStaffId())
                .orElseThrow(() -> new RuntimeException("Staff not found"));
        parent.setStaff(staff);
    }

    parent = repository.save(parent);

    // Create child transactions
    for (CreateSplitTransactionDTO.SplitAllocation split : dto.getSplits()) {
        FinancialTransaction child = new FinancialTransaction();
        child.setParentTransaction(parent);
        child.setDate(dto.getDate());
        child.setDescription(dto.getDescription());
        child.setAccount(account);
        child.setAccountCode(account.getCode());
        child.setAccountType(account.getAccountType());

        // Set debit or credit based on which one the parent uses
        if (dto.getDebit().compareTo(BigDecimal.ZERO) > 0) {
            child.setDebit(split.getAmount());
            child.setCredit(BigDecimal.ZERO);
        } else {
            child.setCredit(split.getAmount());
            child.setDebit(BigDecimal.ZERO);
        }

        // Pro-rate VAT
        if (dto.isVatApplicable() && parentAmount.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal ratio = split.getAmount().divide(parentAmount, 10, RoundingMode.HALF_UP);
            BigDecimal childVat = parentVatAmount.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
            child.setVatApplicable(true);
            child.setVatRate(dto.getVatRate());
            child.setVatAmount(childVat);
            child.setNetAmount(split.getAmount());
            child.setGrossAmount(split.getAmount().add(childVat));
        }

        // Resolve property/unit
        if (split.getUnitId() != null) {
            Unit unit = unitRepository.findById(split.getUnitId())
                    .orElseThrow(() -> new RuntimeException("Unit not found: " + split.getUnitId()));
            child.setUnit(unit);
            child.setProperty(unit.getProperty());
        } else if (split.getPropertyId() != null) {
            Property property = propertyRepository.findById(split.getPropertyId())
                    .orElseThrow(() -> new RuntimeException("Property not found: " + split.getPropertyId()));
            child.setProperty(property);
        }

        child.setNotes(dto.getNotes());
        repository.save(child);
    }

    // Re-fetch parent with children populated
    return repository.findById(parent.getId()).orElse(parent);
}
```

**Step 2: Update `getTransactions` to exclude children from main ledger**

Replace the current `getTransactions` method body with queries that filter `parentTransaction IS NULL`. Update each branch:

- `findAllByOrderByDateAsc()` → `findByParentTransactionIsNullOrderByDateAsc()`
- `findByPropertyId(propertyId)` → `findByParentTransactionIsNullAndPropertyIdOrderByDateAsc(propertyId)`
- `findByAccountType(accountType)` → `findByParentTransactionIsNullAndAccountTypeOrderByDateAsc(accountType)`
- `findByDateBetweenOrderByDateAsc(startDate, endDate)` → `findByParentTransactionIsNullAndDateBetweenOrderByDateAsc(startDate, endDate)`
- `findByPropertyIdAndDateBetween(propertyId, startDate, endDate)` → `findByParentTransactionIsNullAndPropertyIdAndDateBetweenOrderByDateAsc(propertyId, startDate, endDate)`
- `findByUnitId(unitId)` and `findByUnitIdAndDateBetween(unitId, ...)` — keep as-is (unit-level filtering includes children, which is correct for unit reports)

**Step 3: Add `getTransactionWithChildren` method**

```java
@Transactional(readOnly = true)
public FinancialTransaction getTransactionWithChildren(UUID id) {
    FinancialTransaction txn = repository.findById(id)
            .orElseThrow(() -> new RuntimeException("Transaction not found"));
    if (txn.isSplitParent()) {
        txn.setSplitChildren(repository.findByParentTransactionId(id));
    }
    return txn;
}
```

**Step 4: Inject VendorRepository and StaffRepository**

Check if they're already injected. If not, add them to the constructor. Look at the existing constructor and add:

```java
private final VendorRepository vendorRepository;
private final StaffRepository staffRepository;
```

And update the constructor parameters accordingly.

**Step 5: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/core/service/FinancialTransactionService.java
git commit -m "feat: add split transaction creation and ledger filtering in service"
```

---

### Task 6: Controller Endpoint

**Files:**
- Modify: `backend/src/main/java/com/datagami/rentaxis/api/FinancialTransactionController.java`

**Step 1: Add the split transaction endpoint**

Add this import:

```java
import com.datagami.rentaxis.api.dto.CreateSplitTransactionDTO;
```

Add this endpoint method:

```java
@PostMapping("/transactions/split")
public ResponseEntity<FinancialTransaction> createSplitTransaction(@RequestBody CreateSplitTransactionDTO dto) {
    return ResponseEntity.ok(service.createSplitTransaction(dto));
}
```

**Step 2: Update the get-by-id endpoint to include children**

Replace the existing `getTransactionById` method:

```java
@GetMapping("/transactions/{id}")
public ResponseEntity<FinancialTransaction> getTransactionById(@PathVariable UUID id) {
    return ResponseEntity.ok(service.getTransactionWithChildren(id));
}
```

**Step 3: Verify compilation**

Run: `cd backend && ./gradlew compileJava`
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add backend/src/main/java/com/datagami/rentaxis/api/FinancialTransactionController.java
git commit -m "feat: add POST /transactions/split endpoint and include children in GET by id"
```

---

### Task 7: Frontend — Split Toggle and Allocation Table

**Files:**
- Modify: `web/src/app/[locale]/dashboard/finance/transactions/page.tsx`

This is the largest task. It modifies the existing transaction form.

**Step 1: Add split state to the component**

After the existing `formData` state (~line 78-92), add:

```typescript
const [splitMode, setSplitMode] = useState(false);
const [splits, setSplits] = useState<Array<{
    propertyId: string;
    unitId: string;
    amount: number;
    units: UnitData[];
}>>([
    { propertyId: "", unitId: "", amount: 0, units: [] },
    { propertyId: "", unitId: "", amount: 0, units: [] },
]);
```

**Step 2: Add split helper functions**

After the existing helper functions (around line 200), add:

```typescript
const transactionAmount = formData.debit || formData.credit || 0;
const splitTotal = splits.reduce((sum, s) => sum + (s.amount || 0), 0);
const splitBalanced = Math.abs(splitTotal - transactionAmount) < 0.01 && transactionAmount > 0;

const handleSplitToggle = (enabled: boolean) => {
    setSplitMode(enabled);
    if (enabled && transactionAmount > 0) {
        const equalShare = Math.round((transactionAmount / 2) * 100) / 100;
        setSplits([
            { propertyId: "", unitId: "", amount: equalShare, units: [] },
            { propertyId: "", unitId: "", amount: transactionAmount - equalShare, units: [] },
        ]);
    }
};

const addSplitRow = () => {
    setSplits([...splits, { propertyId: "", unitId: "", amount: 0, units: [] }]);
};

const removeSplitRow = (index: number) => {
    if (splits.length <= 2) return;
    setSplits(splits.filter((_, i) => i !== index));
};

const updateSplitProperty = async (index: number, propertyId: string) => {
    const updated = [...splits];
    updated[index] = { ...updated[index], propertyId, unitId: "", units: [] };
    if (propertyId) {
        try {
            const res = await fetch(`/api/proxy/v1/units/property/${propertyId}`);
            if (res.ok) {
                updated[index].units = await res.json();
            }
        } catch (err) {
            console.error(err);
        }
    }
    setSplits(updated);
};

const updateSplitUnit = (index: number, unitId: string) => {
    const updated = [...splits];
    updated[index] = { ...updated[index], unitId };
    setSplits(updated);
};

const updateSplitAmount = (index: number, amount: number) => {
    const updated = [...splits];
    updated[index] = { ...updated[index], amount };
    setSplits(updated);
};

const distributeSplitsEqually = () => {
    if (transactionAmount <= 0) return;
    const equalShare = Math.round((transactionAmount / splits.length) * 100) / 100;
    const remainder = Math.round((transactionAmount - equalShare * splits.length) * 100) / 100;
    setSplits(splits.map((s, i) => ({
        ...s,
        amount: i === 0 ? equalShare + remainder : equalShare,
    })));
};
```

**Step 3: Modify `handleSubmit` to handle split mode**

In the existing `handleSubmit` function (~line 152), wrap the existing body fetch in a conditional:

```typescript
const handleSubmit = async (ev: React.FormEvent) => {
    ev.preventDefault();
    setSubmitting(true);
    try {
        if (splitMode) {
            // Split transaction
            const body = {
                date: formData.date,
                description: formData.description,
                accountId: formData.accountId,
                debit: formData.debit,
                credit: formData.credit,
                vatApplicable: formData.vatApplicable,
                vatRate: formData.vatApplicable ? formData.vatRate : 0,
                notes: formData.notes,
                splits: splits.map(s => ({
                    propertyId: s.propertyId || null,
                    unitId: s.unitId || null,
                    amount: s.amount,
                })),
            };
            const res = await fetch("/api/proxy/v1/finance/transactions/split", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body),
            });
            if (res.ok) {
                setShowForm(false);
                setSplitMode(false);
                setSplits([
                    { propertyId: "", unitId: "", amount: 0, units: [] },
                    { propertyId: "", unitId: "", amount: 0, units: [] },
                ]);
                fetchTransactions();
                resetForm();
            }
        } else {
            // Existing single transaction logic (keep current code)
            const body: Record<string, unknown> = {
                date: formData.date,
                description: formData.description,
                account: { id: formData.accountId },
                debit: formData.debit,
                credit: formData.credit,
                vatApplicable: formData.vatApplicable,
                vatAmount: formData.vatAmount,
                vatRate: formData.vatApplicable ? formData.vatRate : 0,
                netAmount: formData.vatApplicable ? formData.netAmount : 0,
                grossAmount: formData.vatApplicable ? formData.grossAmount : 0,
                notes: formData.notes
            };
            if (formData.propertyId) body.property = { id: formData.propertyId };
            if (formData.unitId) body.unit = { id: formData.unitId };

            const res = await fetch("/api/proxy/v1/finance/transactions", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(body)
            });
            if (res.ok) {
                setShowForm(false);
                fetchTransactions();
                resetForm();
            }
        }
    } catch (err) {
        console.error(err);
    } finally {
        setSubmitting(false);
    }
};
```

Extract a `resetForm` helper from the existing reset logic:

```typescript
const resetForm = () => {
    setFormData({
        date: new Date().toISOString().split("T")[0],
        description: "",
        accountId: "",
        debit: 0,
        credit: 0,
        propertyId: "",
        unitId: "",
        vatApplicable: false,
        vatAmount: 0,
        vatRate: 5,
        grossAmount: 0,
        netAmount: 0,
        notes: ""
    });
    setUnits([]);
};
```

**Step 4: Add the split toggle and allocation table to the form JSX**

In the form, after the VAT section (~line 440) and before the Notes section (~line 460), replace the existing property/unit dropdowns (lines 396-421) with a conditional block.

Replace lines 396-421 (the two `col-span-1` divs for property and unit selects) with:

```tsx
{/* Split toggle */}
<div className="col-span-2 flex items-center gap-3">
    <label className="flex items-center gap-2 cursor-pointer">
        <input
            type="checkbox"
            className="rounded border-border"
            checked={splitMode}
            onChange={ev => handleSplitToggle(ev.target.checked)}
        />
        <span className="text-xs font-bold text-muted">Split across multiple properties/units</span>
    </label>
</div>

{!splitMode ? (
    <>
        {/* Existing single property/unit dropdowns — keep exactly as-is */}
        <div className="col-span-1">
            <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("property")} (Project)</label>
            <select
                className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                value={formData.propertyId}
                onChange={ev => {
                    setFormData({ ...formData, propertyId: ev.target.value, unitId: "" });
                    fetchUnitsForProperty(ev.target.value);
                }}
            >
                <option value="">Organisation Level</option>
                {properties.map(s => <option key={s.property.id} value={s.property.id}>{s.property.nameEn}</option>)}
            </select>
        </div>
        <div className="col-span-1">
            <label className="block text-xs font-semibold text-muted uppercase tracking-[0.15em] mb-1.5 ml-1">{t("unit")} (Property)</label>
            <select
                className="w-full border border-border rounded-lg bg-surface p-3 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                value={formData.unitId}
                onChange={ev => setFormData({ ...formData, unitId: ev.target.value })}
                disabled={!formData.propertyId}
            >
                <option value="">Property Level (No Unit)</option>
                {units.map(u => <option key={u.id} value={u.id}>{u.unitNumber}{u.currentTenantName ? ` — ${u.currentTenantName}` : ""}</option>)}
            </select>
        </div>
    </>
) : (
    <div className="col-span-2">
        <div className="border border-border rounded-xl overflow-hidden">
            {/* Header */}
            <div className="flex items-center justify-between bg-input/50 px-4 py-2.5">
                <span className="text-[10px] font-bold text-muted uppercase tracking-wider">Cost Centre Allocation</span>
                <button type="button" onClick={distributeSplitsEqually} className="text-[10px] font-bold text-primary hover:underline cursor-pointer">
                    Distribute Equally
                </button>
            </div>
            {/* Split rows */}
            <div className="divide-y divide-border">
                {splits.map((split, index) => (
                    <div key={index} className="flex items-center gap-3 px-4 py-3">
                        <div className="flex-1">
                            <select
                                className="w-full border border-border rounded-lg bg-surface p-2.5 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                value={split.propertyId}
                                onChange={ev => updateSplitProperty(index, ev.target.value)}
                            >
                                <option value="">Select Property</option>
                                {properties.map(s => <option key={s.property.id} value={s.property.id}>{s.property.nameEn}</option>)}
                            </select>
                        </div>
                        <div className="flex-1">
                            <select
                                className="w-full border border-border rounded-lg bg-surface p-2.5 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                value={split.unitId}
                                onChange={ev => updateSplitUnit(index, ev.target.value)}
                                disabled={!split.propertyId}
                            >
                                <option value="">Property Level</option>
                                {split.units.map(u => <option key={u.id} value={u.id}>{u.unitNumber}{u.currentTenantName ? ` — ${u.currentTenantName}` : ""}</option>)}
                            </select>
                        </div>
                        <div className="w-32">
                            <input
                                type="number"
                                step="0.01"
                                placeholder="0.00"
                                className="w-full border border-border rounded-lg bg-surface p-2.5 text-xs text-right focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none transition-all duration-200"
                                value={split.amount || ""}
                                onChange={ev => updateSplitAmount(index, Number(ev.target.value))}
                            />
                        </div>
                        <button
                            type="button"
                            onClick={() => removeSplitRow(index)}
                            disabled={splits.length <= 2}
                            className="p-1.5 text-muted hover:text-red-500 disabled:opacity-30 disabled:cursor-not-allowed cursor-pointer transition-all"
                        >
                            <X size={14} />
                        </button>
                    </div>
                ))}
            </div>
            {/* Footer: add row + total */}
            <div className="flex items-center justify-between bg-input/30 px-4 py-2.5 border-t border-border">
                <button type="button" onClick={addSplitRow} className="text-xs font-bold text-primary hover:underline cursor-pointer flex items-center gap-1">
                    <Plus size={12} /> Add Row
                </button>
                <div className="flex items-center gap-2">
                    <span className="text-[10px] font-bold text-muted uppercase tracking-wider">Total:</span>
                    <span className={cn(
                        "text-xs font-bold tabular-nums",
                        splitBalanced ? "text-emerald-600" : "text-red-500"
                    )}>
                        {formatNumber(splitTotal)} / {formatNumber(transactionAmount)}
                    </span>
                    {splitBalanced && <span className="text-emerald-600 text-xs">✓</span>}
                </div>
            </div>
        </div>
    </div>
)}
```

**Step 5: Disable Create button when split mode and not balanced**

Update the submit button disabled condition (~line 466):

```tsx
<button type="submit" disabled={submitting || (splitMode && !splitBalanced)} className="...">
```

**Step 6: Add `Split` icon import**

At the top of the file (line 6), add `Split` to the lucide-react imports (or use `GitBranch` if `Split` is not available):

```typescript
import { ..., GitBranch } from "lucide-react";
```

**Step 7: Verify frontend builds**

Run: `cd web && npm run build`
Expected: Build succeeds with no TypeScript errors.

**Step 8: Commit**

```bash
git add web/src/app/[locale]/dashboard/finance/transactions/page.tsx
git commit -m "feat: add split toggle and allocation table to transaction form"
```

---

### Task 8: Frontend — Ledger Expansion for Split Transactions

**Files:**
- Modify: `web/src/app/[locale]/dashboard/finance/transactions/page.tsx`

**Step 1: Update the Transaction type to include split fields**

Update the `Transaction` type (around line 33) to add:

```typescript
type Transaction = {
    // ... existing fields ...
    splitParent: boolean;
    splitChildren?: Transaction[];
    parentTransaction?: { id: string } | null;
};
```

**Step 2: Add expanded state tracking**

After existing state declarations:

```typescript
const [expandedSplits, setExpandedSplits] = useState<Set<string>>(new Set());

const toggleSplitExpand = async (txnId: string) => {
    const next = new Set(expandedSplits);
    if (next.has(txnId)) {
        next.delete(txnId);
    } else {
        // Fetch children if not already loaded
        const txn = transactions.find(t => t.id === txnId);
        if (txn && !txn.splitChildren) {
            try {
                const res = await fetch(`/api/proxy/v1/finance/transactions/${txnId}`);
                if (res.ok) {
                    const data = await res.json();
                    setTransactions(prev => prev.map(t =>
                        t.id === txnId ? { ...t, splitChildren: data.splitChildren } : t
                    ));
                }
            } catch (err) {
                console.error(err);
            }
        }
        next.add(txnId);
    }
    setExpandedSplits(next);
};
```

**Step 3: Update the Simple ledger view table rows**

In the Simple ledger view (around line 511), replace the `<tbody>` section. After each parent row that has `splitParent`, render expansion children:

Replace the `{paginatedSimple.map(row => (` block (lines ~511-548) with logic that also checks `splitParent`. 

First, update the `simpleLedger` computation to carry `splitParent` and `splitChildren`:

In the `simpleLedger` computation (~line 221), update the map to include:

```typescript
return {
    id: t.id,
    date: t.date,
    description: t.description,
    property: t.property,
    unit: t.unit,
    moneyIn: isIncome ? (t.credit || t.debit || 0) : 0,
    moneyOut: !isIncome ? (t.debit || t.credit || 0) : 0,
    notes: t.notes,
    splitParent: t.splitParent || false,
    splitChildren: t.splitChildren,
};
```

Then in the JSX tbody, after each row add a conditional for expanded children:

```tsx
{paginatedSimple.map(row => (
    <React.Fragment key={row.id}>
        <tr className="hover:bg-input/30 transition-colors">
            <td className="px-5 py-3 text-xs text-foreground font-medium">
                <div className="flex items-center gap-1.5">
                    {row.splitParent && (
                        <button onClick={() => toggleSplitExpand(row.id)} className="cursor-pointer text-muted hover:text-foreground transition-all">
                            <ChevronDown size={14} className={cn("transition-transform", expandedSplits.has(row.id) && "rotate-180")} />
                        </button>
                    )}
                    {row.date}
                </div>
            </td>
            <td className="px-5 py-3">
                <div className="flex items-center gap-2">
                    <p className="text-xs font-bold text-foreground">{row.description}</p>
                    {row.splitParent && (
                        <span className="text-[9px] font-bold uppercase tracking-wider bg-primary/10 text-primary px-1.5 py-0.5 rounded">Split</span>
                    )}
                </div>
                {row.notes && <p className="text-[10px] text-muted mt-0.5">{row.notes}</p>}
            </td>
            {/* ... rest of existing td cells unchanged ... */}
        </tr>
        {row.splitParent && expandedSplits.has(row.id) && row.splitChildren && row.splitChildren.map((child: Transaction, idx: number) => (
            <tr key={child.id} className="bg-input/10">
                <td className="px-5 py-2 text-xs text-muted"></td>
                <td className="px-5 py-2">
                    <div className="flex items-center gap-1.5 pl-4">
                        <span className="text-muted text-xs">{idx < row.splitChildren!.length - 1 ? "├" : "└"}</span>
                        <span className="text-xs text-foreground font-medium">
                            {child.property?.nameEn || "Org"}
                            {child.unit && <span className="text-[9px] text-primary bg-primary/5 px-1.5 py-0.5 rounded font-bold ml-1">Unit {child.unit.unitNumber}</span>}
                        </span>
                    </div>
                </td>
                <td className="px-5 py-2"></td>
                <td className="px-5 py-2 text-right text-xs font-medium tabular-nums text-muted">
                    {(child.credit || child.debit || 0) > 0 && child.accountType === "INCOME" ? `+${formatNumber(child.credit || child.debit)}` : "—"}
                </td>
                <td className="px-5 py-2 text-right text-xs font-medium tabular-nums text-muted">
                    {(child.debit || child.credit || 0) > 0 && child.accountType === "EXPENSE" ? `-${formatNumber(child.debit || child.credit)}` : "—"}
                </td>
                <td className="px-5 py-2"></td>
            </tr>
        ))}
    </React.Fragment>
))}
```

**Step 4: Apply the same pattern to the Accounting view**

In the accounting view tbody (~line 591), add similar expand/collapse logic using `React.Fragment`, `ChevronDown` toggle, `Split` badge, and child rows showing each child's debit/credit with its property/unit.

**Step 5: Add React import if not present**

Ensure `React` is imported for `React.Fragment`:

```typescript
import React, { useState, useEffect } from "react";
```

Or use `<>...</>` fragments instead.

**Step 6: Verify frontend builds**

Run: `cd web && npm run build`
Expected: Build succeeds with no TypeScript errors.

**Step 7: Commit**

```bash
git add web/src/app/[locale]/dashboard/finance/transactions/page.tsx
git commit -m "feat: add split transaction expand/collapse in ledger views"
```

---

### Task 9: End-to-End Manual Test

**Step 1: Start the full stack**

Run: `docker compose up -d`

**Step 2: Test split transaction creation**

1. Navigate to Finance → Transactions
2. Click "Add Transaction"
3. Fill in: Date, Account (pick an EXPENSE), Description, Debit amount (e.g., 10,000)
4. Check "Split across multiple properties/units"
5. Verify: 2 rows appear with equal split (5,000 each)
6. Select different properties for each row
7. Adjust amounts to unequal (e.g., 6,000 + 4,000)
8. Verify total shows "10,000 / 10,000 ✓"
9. Click Create
10. Verify transaction appears on the ledger with a "Split" badge

**Step 3: Test ledger expansion**

1. Click the chevron on the split transaction
2. Verify child rows appear indented below, showing property names and allocated amounts
3. Verify child amounts sum to parent amount

**Step 4: Test property reports**

1. Go to Finance → Reports → select a property used in the split
2. Verify the property report includes only the child amount allocated to that property, not the full parent amount

**Step 5: Test validation**

1. Create a new split with amounts that don't sum correctly (e.g., 6,000 + 5,000 for a 10,000 transaction)
2. Verify the Create button is disabled

**Step 6: Commit any fixes**

```bash
git add -u
git commit -m "fix: address issues found during split transaction e2e testing"
```

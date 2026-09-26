package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.OpeningBalancePosting;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.repository.ImportBatchRepository;
import com.datagami.rentaxis.domain.repository.VatTaxPointRepository;
import com.datagami.rentaxis.domain.entity.enums.VatTaxPointStatus;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.OpeningBalancePostingRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The tenant's accounting calendar: when its fiscal year starts, when its books
 * opened, and how far back they are closed. Every posting date is checked against
 * the period lock before an entry is written.
 */
@Service
public class TenantFiscalSettingsService {

    private final TenantFiscalSettingsRepository repo;

    /**
     * The opening-balance marker and the journals it points at, read directly rather
     * than through {@code OpeningBalanceService}.
     *
     * <p>The invariant belongs here — the books start date cannot move while the
     * books are open as at the day before it — but depending on the cut-over service
     * from the ledger's own calendar would be a dependency cycle
     * ({@code OpeningBalanceService} needs this one to answer {@code asOf()}). Two
     * repositories cost nothing and point the right way.</p>
     */
    private final OpeningBalancePostingRepository openingBalances;
    private final JournalEntryRepository journals;

    /**
     * For the second half of the same invariant: a posted cut-over is dated against
     * the books start date too (review I4, ruling R21). Read directly, for the reason
     * above — depending on {@code ImportBatchService} from the ledger's calendar would
     * be a cycle, because that service now asks this one about the opening balances.
     */
    private final ImportBatchRepository importBatches;

    public TenantFiscalSettingsService(TenantFiscalSettingsRepository repo,
                                       OpeningBalancePostingRepository openingBalances,
                                       JournalEntryRepository journals,
                                       ImportBatchRepository importBatches,
                                       VatTaxPointRepository vatTaxPoints,
                                       jakarta.persistence.EntityManager entityManager) {
        this.entityManager = entityManager;
        this.repo = repo;
        this.openingBalances = openingBalances;
        this.journals = journals;
        this.importBatches = importBatches;
        this.vatTaxPoints = vatTaxPoints;
    }

    /** Read directly, for the dependency reason above: the lock must not strand a PLANNED tax point. */
    private final VatTaxPointRepository vatTaxPoints;

    private final jakarta.persistence.EntityManager entityManager;

    /** Settings for the current tenant; a default row is created on first access. */
    @Transactional
    public TenantFiscalSettings get() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) throw new IllegalStateException("No tenant in context");
        return repo.findById(tenantId).orElseGet(() -> {
            // Seed the row with a conflict-safe insert rather than save(): the primary
            // key is assigned, so save() is a merge, and a merge that loses the race
            // overwrites the winner's row with these defaults — which would blank the
            // account-code counter AccountService keeps in it. Read it back instead.
            repo.insertDefaultIfAbsent(tenantId);
            return repo.findById(tenantId).orElseGet(() -> {
                TenantFiscalSettings s = new TenantFiscalSettings();
                s.setTenantId(tenantId);
                return repo.save(s);
            });
        });
    }

    /**
     * The date the tenant's books open, or null when nobody has set one.
     *
     * <p>Unlike {@link #get()} this never creates the settings row, which is what
     * makes it safe from a genuinely read-only transaction: {@code get()} falls back
     * to {@code insertDefaultIfAbsent}, and Postgres refuses an INSERT on a read-only
     * connection. The cut-over screens (the opening-balance grid, the reconciliation
     * report) are read-only and ask this question before anything has been
     * configured, which is exactly the case that would otherwise fail.</p>
     */
    @Transactional(readOnly = true)
    public LocalDate booksStartDate() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) throw new IllegalStateException("No tenant in context");
        return repo.findById(tenantId).map(TenantFiscalSettings::getBooksStartDate).orElse(null);
    }

    /** The fiscal year is labelled by the calendar year in which it starts. */
    @Transactional(readOnly = true)
    public int fiscalYearOf(LocalDate date) {
        int startMonth = get().getFiscalYearStartMonth();
        return date.getMonthValue() >= startMonth ? date.getYear() : date.getYear() - 1;
    }

    /** Throws when {@code date} falls in a locked period. Import and OB postings bypass this in PostingService. */
    @Transactional(readOnly = true)
    public void assertOpen(LocalDate date) {
        LocalDate locked = get().getBooksLockedThrough();
        if (locked != null && !date.isAfter(locked)) {
            throw new BusinessRuleViolationException(
                    "Cannot post on " + date + ": books are locked through " + locked);
        }
    }

    /**
     * Whether {@code date} is outside the locked period — the question {@link #assertOpen}
     * asks, answered without throwing. A caller that only wants to know (and would catch
     * the refusal) must use this: an exception leaving this bean marks the caller's
     * shared transaction rollback-only, so catching it still fails the caller at commit
     * with UnexpectedRollbackException (sim4y S16-01, the bank-reconciliation workspace).
     */
    @Transactional(readOnly = true)
    public boolean isOpen(LocalDate date) {
        LocalDate locked = get().getBooksLockedThrough();
        return locked == null || date.isAfter(locked);
    }

    /**
     * Close the books through {@code date}.
     *
     * <p><b>Refused while a VAT tax point dated on or before {@code date} is still
     * PLANNED</b> (spec 2026-09-24 §1): the nightly job skips a locked date, so the
     * point's VAT would sit in {@code OUTPUT_VAT_DEFERRED} for ever, undeclared. Post
     * the tax points through {@code date} first.</p>
     */
    @Transactional
    public TenantFiscalSettings lockThrough(LocalDate date) {
        TenantFiscalSettings s = get();
        // FOR UPDATE, re-read: a writer creating or moving a VAT tax point holds this
        // row FOR SHARE while it checks the lock (VatTaxPointService), so the check
        // below sees every point such a writer has committed, and none can be created
        // behind it at a date this lock covers (PR #348 review P3-3).
        entityManager.refresh(s, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        if (s.getBooksLockedThrough() != null && date.isBefore(s.getBooksLockedThrough())) {
            throw new BusinessRuleViolationException("Period lock cannot move backwards (currently " + s.getBooksLockedThrough() + ")");
        }
        vatTaxPoints.findFirstByTenantIdAndStatusAndTaxPointDateLessThanEqualOrderByTaxPointDateAsc(
                        s.getTenantId(), VatTaxPointStatus.PLANNED, date)
                .ifPresent(p -> {
                    throw new BusinessRuleViolationException("Post the VAT tax points through " + date
                            + " first: one dated " + p.getTaxPointDate() + " has not been declared yet, and locking"
                            + " the books would leave it undeclared.");
                });
        s.setBooksLockedThrough(date);
        return repo.save(s);
    }

    /**
     * PR #358 R1 (close race): a posting dated in an earlier fiscal year than today's
     * holds the settings row FOR SHARE until it commits. A year-end close takes the
     * same row FOR UPDATE ({@link #lockRow}) before it reads the balances, so a
     * concurrent post either commits first and is inside the closing entry, or waits
     * for the close and then meets the moved lock. Posts dated in the current year
     * — every ordinary one — take no lock: a year that has not ended cannot be closed.
     * A native lock, so the managed settings entity is not refreshed underneath a
     * caller that has changed it in this transaction.
     */
    @Transactional
    public void shareLockIfPastYear(LocalDate date) {
        lockedThroughSharingIfPastYear(date);
    }

    /**
     * The posting path's check. For a date in an earlier fiscal year the lock is read
     * FOR SHARE straight from the row — the latest committed value, not the
     * persistence context's copy, which may predate a close that committed while this
     * transaction waited for the row.
     */
    @Transactional
    public void assertOpenForPosting(LocalDate date) {
        java.util.Optional<LocalDate> locked = lockedThroughSharingIfPastYear(date);
        if (locked == null) {
            assertOpen(date);
            return;
        }
        if (locked.isPresent() && !date.isAfter(locked.get())) {
            throw new BusinessRuleViolationException(
                    "Cannot post on " + date + ": books are locked through " + locked.get());
        }
    }

    /** null when no lock was taken (current year, no tenant or date); else the committed lock. */
    private java.util.Optional<LocalDate> lockedThroughSharingIfPastYear(LocalDate date) {
        if (date == null) return null;
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) return null;
        if (fiscalYearOf(date) >= fiscalYearOf(LocalDate.now())) return null;
        List<?> rows = entityManager.createNativeQuery(
                        "select books_locked_through from tenant_fiscal_settings where tenant_id = :t for share")
                .setParameter("t", tenantId).getResultList();
        Object v = rows.isEmpty() ? null : rows.get(0);
        if (v == null) return java.util.Optional.empty();
        return java.util.Optional.of(v instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) v);
    }

    /**
     * The one backwards move the period lock allows (spec 2026-09-24 §3): a fiscal
     * year re-open sets the lock to {@code date} when that is earlier. Package-private
     * on purpose — only {@link YearEndCloseService}, in this package, may call it, and
     * it records who and why on the close row. Returns the lock it replaced.
     */
    @Transactional
    LocalDate reopenTo(LocalDate date) {
        TenantFiscalSettings s = get();
        entityManager.refresh(s, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        LocalDate before = s.getBooksLockedThrough();
        if (before != null && date != null && date.isBefore(before)) {
            s.setBooksLockedThrough(date);
            repo.save(s);
        }
        return before;
    }

    /** The settings row, locked FOR UPDATE — the close and re-open serialise on it. */
    @Transactional
    TenantFiscalSettings lockRow() {
        TenantFiscalSettings s = get();
        entityManager.refresh(s, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        return s;
    }

    /**
     * Moves the day the books open.
     *
     * <p><b>Refused while an opening-balance journal is live.</b> The OB entry is
     * dated {@code booksStartDate − 1}, and {@code OpeningBalanceService} derives that
     * date from this field every time it is asked. Move the field and the derived date
     * no longer matches the entry that is actually on the books: a reversal or a
     * re-post would then write its mirror on a different day from the entry it
     * mirrors, leaving the whole opening balance standing at the old date while the
     * screen says the books are not open. That is the same defect as a
     * caller-supplied reversal date, arriving with no caller input at all — so this is
     * the second half of that fix, and {@code reverse}/{@code repost} pinning the
     * mirror to {@code live.getEntryDate()} is the first.</p>
     *
     * <p><b>And refused while a cut-over batch is POSTED</b> (review I4, ruling R21).
     * The books start date D is baked into three things a bulk post already did:
     * every imported contract was recognised through {@code D − 1}
     * ({@code ContractImportPostService}), {@code books_locked_through} was set from
     * the old D and is never moved again by this method, and a later opening balance
     * would be dated against the <em>new</em> D − 1. Nothing double-counts, but the
     * three dates that define the cut-over stop agreeing with each other and nothing
     * says so — a silence worth a sentence. The remedy is the same shape as the
     * opening-balance one: take the batch off the books, move the date, put it back.</p>
     *
     * <p>Setting it to the value it already holds is not a change and is allowed, so a
     * settings screen that PUTs the whole form back is not punished for it.</p>
     */
    @Transactional
    public TenantFiscalSettings setBooksStartDate(LocalDate date) {
        TenantFiscalSettings s = get();
        boolean changing = date == null ? s.getBooksStartDate() != null : !date.equals(s.getBooksStartDate());
        if (changing && hasLiveOpeningBalance()) {
            throw new BusinessRuleViolationException(
                    "Reverse or replace the opening balances before changing the books start date: "
                            + "the opening-balance journal is dated the day before it.");
        }
        if (changing && importBatches.existsByStatus(ImportBatchStatus.POSTED)) {
            throw new BusinessRuleViolationException(BOOKS_START_FROZEN_BY_A_POSTED_BATCH);
        }
        s.setBooksStartDate(date);
        if (date != null && s.getBooksLockedThrough() == null) s.setBooksLockedThrough(date.minusDays(1));
        return repo.save(s);
    }

    /** Ruling R21's sentence, shared so the guard and its test cannot drift apart. */
    public static final String BOOKS_START_FROZEN_BY_A_POSTED_BATCH =
            "Books start is frozen while a posted cut-over batch exists; reverse the batch first.";

    /** True when this tenant has an opening-balance journal that has not been reversed. */
    @Transactional(readOnly = true)
    public boolean hasLiveOpeningBalance() {
        return openingBalances.findFirstByOrderByCreatedAtAsc()
                .map(OpeningBalancePosting::getJournalId)
                .flatMap(journals::findById)
                .filter(e -> e.getStatus() == JournalStatus.POSTED)
                .isPresent();
    }

    @Transactional
    public TenantFiscalSettings setFiscalYearStartMonth(int month) {
        if (month < 1 || month > 12) throw new BusinessRuleViolationException("Fiscal year start month must be 1-12");
        TenantFiscalSettings s = get();
        s.setFiscalYearStartMonth(month);
        return repo.save(s);
    }
}

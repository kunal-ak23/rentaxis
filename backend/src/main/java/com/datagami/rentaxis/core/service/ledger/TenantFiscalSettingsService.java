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
import com.datagami.rentaxis.domain.repository.RecognitionEntryRepository;
import com.datagami.rentaxis.domain.entity.enums.RecognitionStatus;
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
                                       RecognitionEntryRepository recognitionEntries,
                                       jakarta.persistence.EntityManager entityManager) {
        this.entityManager = entityManager;
        this.recognitionEntries = recognitionEntries;
        this.repo = repo;
        this.openingBalances = openingBalances;
        this.journals = journals;
        this.importBatches = importBatches;
        this.vatTaxPoints = vatTaxPoints;
    }

    /** Read directly, for the dependency reason above: the lock must not strand a PLANNED tax point. */
    private final VatTaxPointRepository vatTaxPoints;

    /** The same, for a PLANNED recognition entry a books-start lock would cover (review of R4-B I3). */
    private final RecognitionEntryRepository recognitionEntries;

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
     * Break-it round 2 (money2) F3: the lock as a user sets it, from the fiscal
     * page. A period that has not happened yet cannot be closed — 2062 typed for
     * 2026 used to be accepted, refuse every posting in the organisation, and could
     * not be taken back ("cannot move backwards"). The year-end close locks through
     * a period end it has validated itself and goes through {@link #lockThrough}.
     */
    @Transactional
    public TenantFiscalSettings lockThroughAsUser(LocalDate date) {
        manualDates.require(PostingDatePath.PERIOD_LOCK, date);
        return lockThrough(date);
    }

    /** Break-it round 2 (money2) F3: "today" for the lock, on the app clock. */
    private ManualPostingDates manualDates = ManualPostingDates.system();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setManualPostingDates(ManualPostingDates manualDates) {
        this.manualDates = manualDates;
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
        // Break-it R3 money3 N1: the lock protects posted journals; while the organisation
        // has none there is nothing to protect, so a lock set by mistake can be taken back.
        if (s.getBooksLockedThrough() != null && date.isBefore(s.getBooksLockedThrough()) && hasJournals()) {
            String was = s.getBooksLockedThrough().format(DMY);
            throw new BusinessRuleViolationException("Period lock cannot move backwards (currently " + was
                    + "): journals are posted inside it. Re-open the latest closed fiscal year instead.",
                    "fiscal.lockBackwards", java.util.Map.of("current", was));
        }
        requireNoPlannedVatThrough(s, date);
        s.setBooksLockedThrough(date);
        s.setBooksLockFromStart(false);   // R4 money4 F3: a user's lock only moves forward
        return repo.save(s);
    }

    /** {@link #lockThrough}'s refusal: a PLANNED VAT tax point on or before {@code date} would never be declared. */
    private void requireNoPlannedVatThrough(TenantFiscalSettings s, LocalDate date) {
        vatTaxPoints.findFirstByTenantIdAndStatusAndTaxPointDateLessThanEqualOrderByTaxPointDateAsc(
                        s.getTenantId(), VatTaxPointStatus.PLANNED, date)
                .ifPresent(p -> {
                    throw new BusinessRuleViolationException("Post the VAT tax points through " + date
                            + " first: one dated " + p.getTaxPointDate() + " has not been declared yet, and locking"
                            + " the books would leave it undeclared.",
                            "fiscal.vatPendingInLock", java.util.Map.of("through", date.format(DMY),
                                    "date", p.getTaxPointDate().format(DMY)));
                });
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
            s.setBooksLockFromStart(false);
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
        // Review of PR #392 I1: the settings row FOR UPDATE, re-read, before anything is
        // decided — every lock the books start may write is checked against the lock as
        // committed now, and a user's lockThrough committed meanwhile is seen (and kept).
        entityManager.refresh(s, jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
        boolean changing = date == null ? s.getBooksStartDate() != null : !date.equals(s.getBooksStartDate());
        if (changing && hasLiveOpeningBalance()) {
            throw new BusinessRuleViolationException(
                    "Reverse or replace the opening balances before changing the books start date: "
                            + "the opening-balance journal is dated the day before it.");
        }
        if (changing && importBatches.existsByStatus(ImportBatchStatus.POSTED)) {
            throw new BusinessRuleViolationException(BOOKS_START_FROZEN_BY_A_POSTED_BATCH);
        }
        // Break-it R2 money2 review N2: the opening-balance journal is dated the day
        // before the books start, so that day must be one the ledger can post.
        if (changing && date != null && !PostingService.isNumberable(date.minusDays(1))) {
            throw new BusinessRuleViolationException("The books cannot start on " + date + ": the opening-balance"
                    + " journal is dated the day before, and the ledger posts from " + PostingService.EARLIEST_ENTRY_DATE
                    + " to " + PostingService.LATEST_ENTRY_DATE + ". Check the year.",
                    "fiscal.booksStartOutOfRange", java.util.Map.of("date", date.toString(),
                            "earliest", PostingService.EARLIEST_ENTRY_DATE.plusDays(1).toString(),
                            "latest", PostingService.LATEST_ENTRY_DATE.plusDays(1).toString()));
        }
        // Break-it R3 money3 N1: the books start implies the first lock (the day before
        // it), so it may be at most three months ahead — 2062 typed for 2026 locked the
        // organisation out of every posting.
        if (changing) manualDates.requireBooksStart(date);
        LocalDate implied = date == null ? null : impliedLock(date);
        boolean journalsExist = hasJournals();
        LocalDate current = s.getBooksLockedThrough();
        // What the lock becomes (below): the implied lock when there is none yet, or when
        // it follows the books start (nothing posted, or the books start's own lock).
        boolean writesLock = implied != null
                && (current == null || (changing && (!journalsExist || s.isBooksLockFromStart())));
        if (writesLock && (current == null || implied.isAfter(current))) {
            // Review of R4-B I3 and PR #392 I1: a lock the books start writes — the first
            // one, or its own moved forward — is a lock like any other: the same refusals
            // as lockThrough (and the year-end close's recognition check), so no PLANNED
            // VAT tax point or recognition period is stranded behind it.
            requireNoPlannedVatThrough(s, implied);
            requireNoPlannedRecognitionThrough(s, implied);
        }
        s.setBooksStartDate(date);
        if (date != null) {
            // Break-it R4 money4 F3: the implied lock is the day before the books start,
            // but never after yesterday. A lock after today refuses every deposit, clearing
            // and receipt (all dated today or earlier) until then, and once anything is
            // posted it could not be taken back — a +3-month books start bricked posting.
            // Review of R4-B I2: the lock is inclusive (assertOpen refuses a date not after
            // it), so a lock through today would still refuse today's receipts; the cap is
            // yesterday on the business (Dubai) clock.
            if (current == null) {
                s.setBooksLockedThrough(implied);
                s.setBooksLockFromStart(true);
            } else if (changing && (!journalsExist || s.isBooksLockFromStart())) {
                // N1 ruling: with nothing posted yet the implied lock follows the books
                // start both ways — the way back from a mistyped year. R4 money4 F3: so
                // does a lock the books start itself set, journals or not. A lock a user
                // or a year-end close set only moves forward (lockThrough) or by a re-open.
                s.setBooksLockedThrough(implied);
                s.setBooksLockFromStart(true);
            }
        }
        return repo.save(s);
    }

    private static final java.time.format.DateTimeFormatter DMY = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /**
     * The lock a books start implies: the day before it, but never after yesterday.
     * A lock after today refuses every deposit, clearing and receipt (all dated today
     * or earlier) until then, and once anything is posted it could not be taken back —
     * a +3-month books start bricked posting (break-it R4 money4 F3). The lock is
     * inclusive ({@link #assertOpen} refuses a date not after it), so a lock through
     * today would still refuse today's receipts: the cap is yesterday on the business
     * (Dubai) clock (review of R4-B I2).
     */
    private LocalDate impliedLock(LocalDate booksStart) {
        LocalDate implied = booksStart.minusDays(1);
        LocalDate latest = manualDates.today().minusDays(1);
        return implied.isAfter(latest) ? latest : implied;
    }

    /** A PLANNED recognition period ending on or before {@code date} could never post once it is locked. */
    private void requireNoPlannedRecognitionThrough(TenantFiscalSettings s, LocalDate date) {
        recognitionEntries.findFirstByTenantIdAndStatusAndPeriodEndLessThanEqualOrderByPeriodEndAsc(
                        s.getTenantId(), RecognitionStatus.PLANNED, date)
                .ifPresent(e -> {
                    throw new BusinessRuleViolationException("Run month-end recognition through " + date.format(DMY)
                            + " first: a period ending " + e.getPeriodEnd().format(DMY) + " is not recognised yet,"
                            + " and moving the books start would lock it for ever.",
                            "fiscal.recognitionPendingInLock", java.util.Map.of("through", date.format(DMY),
                                    "date", e.getPeriodEnd().format(DMY)));
                });
    }

    /** True once this tenant has any journal entry (posted or reversed); the tenant is bound explicitly. */
    @Transactional(readOnly = true)
    public boolean hasJournals() {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) return true;
        return journals.existsByTenantId(tenantId);
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

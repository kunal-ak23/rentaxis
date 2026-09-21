package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.OpeningBalancePosting;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.OpeningBalancePostingRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
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

    public TenantFiscalSettingsService(TenantFiscalSettingsRepository repo,
                                       OpeningBalancePostingRepository openingBalances,
                                       JournalEntryRepository journals) {
        this.repo = repo;
        this.openingBalances = openingBalances;
        this.journals = journals;
    }

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

    @Transactional
    public TenantFiscalSettings lockThrough(LocalDate date) {
        TenantFiscalSettings s = get();
        if (s.getBooksLockedThrough() != null && date.isBefore(s.getBooksLockedThrough())) {
            throw new BusinessRuleViolationException("Period lock cannot move backwards (currently " + s.getBooksLockedThrough() + ")");
        }
        s.setBooksLockedThrough(date);
        return repo.save(s);
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
        s.setBooksStartDate(date);
        if (date != null && s.getBooksLockedThrough() == null) s.setBooksLockedThrough(date.minusDays(1));
        return repo.save(s);
    }

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

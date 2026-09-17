package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
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

    public TenantFiscalSettingsService(TenantFiscalSettingsRepository repo) { this.repo = repo; }

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

    @Transactional
    public TenantFiscalSettings setBooksStartDate(LocalDate date) {
        TenantFiscalSettings s = get();
        s.setBooksStartDate(date);
        if (s.getBooksLockedThrough() == null) s.setBooksLockedThrough(date.minusDays(1));
        return repo.save(s);
    }

    @Transactional
    public TenantFiscalSettings setFiscalYearStartMonth(int month) {
        if (month < 1 || month > 12) throw new BusinessRuleViolationException("Fiscal year start month must be 1-12");
        TenantFiscalSettings s = get();
        s.setFiscalYearStartMonth(month);
        return repo.save(s);
    }
}

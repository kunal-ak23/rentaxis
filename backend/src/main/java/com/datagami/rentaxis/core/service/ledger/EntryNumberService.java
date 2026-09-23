package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.JournalEntrySequence;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.repository.JournalEntrySequenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * Hands out "TCO-26/1629"-style numbers: doc type, two-digit fiscal year,
 * per-tenant per-type per-year counter. Runs in the caller's transaction so
 * a rolled-back posting releases its number (gaps are acceptable; duplicates
 * are not — the row lock guarantees that).
 */
@Service
public class EntryNumberService {

    private final JournalEntrySequenceRepository repo;
    private final TenantFiscalSettingsService fiscal;

    public EntryNumberService(JournalEntrySequenceRepository repo, TenantFiscalSettingsService fiscal) {
        this.repo = repo;
        this.fiscal = fiscal;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public String next(JournalDocType docType, LocalDate entryDate) {
        return nextDocumentNumber(docType.name(), entryDate);
    }

    /**
     * The same counter for a document that is not a journal — an addendum's
     * {@code ADD-yy/n}. The sequence table is keyed by a free-text series, so
     * a series that is not a {@link JournalDocType} name cannot collide with one.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public String nextDocumentNumber(String series, LocalDate date) {
        UUID tenantId = TenantContextHolder.getTenantId();
        int fy = fiscal.fiscalYearOf(date);
        JournalEntrySequence seq = repo.lock(tenantId, series, fy).orElseGet(() -> create(tenantId, series, fy));
        long value = seq.getNextValue();
        seq.setNextValue(value + 1);
        repo.save(seq);
        return series + "-" + String.format(Locale.ROOT, "%02d", fy % 100) + "/" + value;
    }

    /**
     * Creates the counter row the first time a tenant posts this series in this
     * fiscal year, then reads it back under the lock. The insert yields to whoever
     * wins the race and commits on its own — see the repository for why neither
     * half of that is optional.
     */
    private JournalEntrySequence create(UUID tenantId, String series, int fy) {
        repo.insertIfAbsent(tenantId, series, fy);
        return repo.lock(tenantId, series, fy).orElseThrow();
    }
}

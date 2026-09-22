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
        UUID tenantId = TenantContextHolder.getTenantId();
        int fy = fiscal.fiscalYearOf(entryDate);
        JournalEntrySequence seq = repo.lock(tenantId, docType.name(), fy).orElseGet(() -> create(tenantId, docType, fy));
        long value = seq.getNextValue();
        seq.setNextValue(value + 1);
        repo.save(seq);
        return docType.name() + "-" + String.format(Locale.ROOT, "%02d", fy % 100) + "/" + value;
    }

    /**
     * Creates the counter row the first time a tenant posts this doc type in this
     * fiscal year, then reads it back under the lock. The insert yields to whoever
     * wins the race and commits on its own — see the repository for why neither
     * half of that is optional.
     */
    private JournalEntrySequence create(UUID tenantId, JournalDocType docType, int fy) {
        repo.insertIfAbsent(tenantId, docType.name(), fy);
        return repo.lock(tenantId, docType.name(), fy).orElseThrow();
    }
}

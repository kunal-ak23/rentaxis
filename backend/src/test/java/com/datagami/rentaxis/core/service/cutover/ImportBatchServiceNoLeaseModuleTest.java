package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.ImportBatchLease;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.repository.ImportBatchEntityRepository;
import com.datagami.rentaxis.domain.repository.ImportBatchLeaseRepository;
import com.datagami.rentaxis.domain.repository.ImportBatchRepository;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reversing a batch with no {@code LeaseReverter} bean in the context.
 *
 * <p>A unit test on purpose. The condition is "the application was assembled
 * without the lease-side undo", which a {@code @SpringBootTest} cannot express —
 * a context either has the bean or it does not, and once plan 4 Task 11 declares
 * {@code LeaseService implements LeaseReverter} every context in the suite will
 * have one. Wiring the collaborators by hand is the only way to keep asserting the
 * guard after that, and it is the honest assertion besides: what has to be true is
 * that nothing is written before the refusal.</p>
 *
 * <p>Why the guard exists at all: journals reversed with the leases left posted is
 * a half-undo nobody can read afterwards. Refusing outright is recoverable;
 * half-doing it is not.</p>
 */
class ImportBatchServiceNoLeaseModuleTest {

    private final ImportBatchRepository batches = mock(ImportBatchRepository.class);
    private final ImportBatchLeaseRepository links = mock(ImportBatchLeaseRepository.class);
    private final ImportBatchEntityRepository entityLinks = mock(ImportBatchEntityRepository.class);
    private final JournalEntryRepository journals = mock(JournalEntryRepository.class);
    private final PostingService posting = mock(PostingService.class);
    private final EntityManager entityManager = mock(EntityManager.class);
    private final TenantFiscalSettingsService fiscal = mock(TenantFiscalSettingsService.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<LeaseReverter> noReverter = mock(ObjectProvider.class);

    private final ImportBatchService service =
            new ImportBatchService(batches, links, entityLinks, journals, posting, entityManager, fiscal, noReverter);

    private ImportBatch postedBatch(UUID id) {
        ImportBatch b = new ImportBatch();
        b.setId(id);
        b.setStatus(ImportBatchStatus.POSTED);
        when(entityManager.find(ImportBatch.class, id)).thenReturn(b);
        return b;
    }

    @Test
    void aBatchWithLeasesIsRefusedOutrightAndNothingIsReversed() {
        UUID batchId = UUID.randomUUID();
        ImportBatch b = postedBatch(batchId);
        when(links.findByBatchIdOrderByLeaseIdAsc(batchId))
                .thenReturn(List.of(new ImportBatchLease(batchId, UUID.randomUUID()),
                        new ImportBatchLease(batchId, UUID.randomUUID())));
        when(noReverter.getIfAvailable()).thenReturn(null);

        assertThatThrownBy(() -> service.reverse(batchId, "redo"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("2 leases")
                .hasMessageContaining("no lease module");

        // Not one journal read, not one reversed, and the batch left POSTED.
        verifyNoInteractions(posting);
        verifyNoInteractions(journals);
        assertThat(b.getStatus()).isEqualTo(ImportBatchStatus.POSTED);
        assertThat(b.getReversedAt()).isNull();
    }

    /** Opening balances alone: no leases, so no lease module is needed. */
    @Test
    void aBatchWithNoLeasesReversesWithoutALeaseModule() {
        UUID batchId = UUID.randomUUID();
        ImportBatch b = postedBatch(batchId);
        when(links.findByBatchIdOrderByLeaseIdAsc(batchId)).thenReturn(List.of());
        when(noReverter.getIfAvailable()).thenReturn(null);
        when(journals.findByImportBatchIdOrderByCreatedAtAsc(batchId)).thenReturn(List.of());
        when(batches.save(any(ImportBatch.class))).thenAnswer(i -> i.getArgument(0));

        assertThatCode(() -> service.reverse(batchId, "redo"))
                .doesNotThrowAnyException();
        assertThat(b.getStatus()).isEqualTo(ImportBatchStatus.REVERSED);
    }
}

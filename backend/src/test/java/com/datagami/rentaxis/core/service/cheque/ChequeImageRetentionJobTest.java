package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.core.service.BlobStorageService;
import com.datagami.rentaxis.domain.repository.ChequeImagePurgeRow;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChequeImageRetentionJobTest {

    @Mock
    private ChequeRepository repo;

    @Mock
    private BlobStorageService blob;

    private ChequeImageRetentionJob job;

    @BeforeEach
    void setUp() {
        job = new ChequeImageRetentionJob(repo, blob);
        ReflectionTestUtils.setField(job, "retentionDays", 90);
    }

    @Test
    void purge_noOldRows_doesNothing() {
        when(repo.findImagePurgeBatch(any(), any())).thenReturn(List.of());

        job.purge();

        verify(blob, never()).delete(any(), any());
        verify(repo, never()).clearImage(any());
    }

    @Test
    void purge_someOldRows_deletesEachAndClearsColumns() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(repo.findImagePurgeBatch(any(), any())).thenReturn(List.of(
                new ChequeImagePurgeRow(id1, t1, "cheques/a.jpg"),
                new ChequeImagePurgeRow(id2, t2, "cheques/b.jpg")
        ));

        job.purge();

        verify(blob).delete(t1, "cheques/a.jpg");
        verify(blob).delete(t2, "cheques/b.jpg");
        verify(repo).clearImage(id1);
        verify(repo).clearImage(id2);
    }

    @Test
    void purge_oneDeleteThrows_continuesWithOthers() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(repo.findImagePurgeBatch(any(), any())).thenReturn(List.of(
                new ChequeImagePurgeRow(id1, t1, "cheques/a.jpg"),
                new ChequeImagePurgeRow(id2, t2, "cheques/b.jpg")
        ));
        doThrow(new RuntimeException("boom")).when(blob).delete(t1, "cheques/a.jpg");

        job.purge();

        verify(repo, never()).clearImage(id1);
        verify(blob).delete(t2, "cheques/b.jpg");
        verify(repo).clearImage(id2);
    }

    @Test
    void purge_usesConfiguredRetentionDays() {
        ReflectionTestUtils.setField(job, "retentionDays", 45);
        when(repo.findImagePurgeBatch(any(), any())).thenReturn(List.of());

        job.purge();

        verify(repo).findImagePurgeBatch(eq(LocalDate.now().minusDays(45)), any());
    }

    /** Audit C-F2: the purge deletes cheque scans only, whatever path a row carries. */
    @Test
    void purge_neverDeletesABlobOutsideTheChequeFolder() {
        UUID t = UUID.randomUUID();
        UUID contract = UUID.randomUUID();
        UUID traversal = UUID.randomUUID();
        UUID scan = UUID.randomUUID();
        when(repo.findImagePurgeBatch(any(), any())).thenReturn(List.of(
                new ChequeImagePurgeRow(contract, t, "lease-docs/ab12cd34.pdf"),
                new ChequeImagePurgeRow(traversal, t, "cheques/../contracts/RA-1.pdf"),
                new ChequeImagePurgeRow(scan, t, "cheques/c.jpg")
        ));

        job.purge();

        verify(blob, never()).delete(t, "lease-docs/ab12cd34.pdf");
        verify(blob, never()).delete(t, "cheques/../contracts/RA-1.pdf");
        verify(blob).delete(t, "cheques/c.jpg");
        // The stray pointers are dropped all the same, so the row is not retried nightly.
        verify(repo).clearImage(contract);
        verify(repo).clearImage(traversal);
        verify(repo).clearImage(scan);
    }
}

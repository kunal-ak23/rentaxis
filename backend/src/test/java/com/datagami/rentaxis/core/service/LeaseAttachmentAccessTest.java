package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseAttachment;
import com.datagami.rentaxis.domain.repository.LeaseAttachmentRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lease attachments are contracts, ID scans and cheque images. Both read paths
 * grant RENTER, and neither asked whose lease it was — so any renter holding
 * any lease or attachment id could read someone else's.
 *
 * <p>{@code LeaseAccessPolicyTest} covers who is allowed to see what. This
 * covers the wiring: that the service consults the policy at all, on both
 * paths, and does no work when refused. Without it, deleting either guard was
 * invisible to the whole suite — which is exactly what a mutation check showed
 * before this file existed.</p>
 */
class LeaseAttachmentAccessTest {

    private LeaseAttachmentRepository attachmentRepository;
    private LeaseRepository leaseRepository;
    private LeaseAccessPolicy policy;
    private LeaseAttachmentService service;

    private final UUID leaseId = UUID.randomUUID();
    private final UUID attachmentId = UUID.randomUUID();
    private Lease lease;

    @BeforeEach
    void setUp() {
        attachmentRepository = mock(LeaseAttachmentRepository.class);
        leaseRepository = mock(LeaseRepository.class);
        policy = mock(LeaseAccessPolicy.class);
        service = new LeaseAttachmentService(attachmentRepository, leaseRepository, policy);

        lease = new Lease();
        lease.setId(leaseId);

        when(leaseRepository.findById(leaseId)).thenReturn(Optional.of(lease));
        when(attachmentRepository.findByLeaseId(leaseId)).thenReturn(List.of());
    }

    private LeaseAttachment attachment() {
        LeaseAttachment a = new LeaseAttachment();
        a.setId(attachmentId);
        a.setLease(lease);
        a.setFileUrl("/api/v1/assets/serve/some-file");
        return a;
    }

    @Test
    void listingAttachmentsAsksThePolicyAboutTheLease() {
        service.getAttachments(leaseId);

        verify(policy).requireReadable(lease);
    }

    @Test
    void listingIsRefusedWhenThePolicyRefusesTheLease() {
        doThrow(new NotFoundException("Lease not found")).when(policy).requireReadable(any());

        assertThatThrownBy(() -> service.getAttachments(leaseId))
                .isInstanceOf(NotFoundException.class);

        verify(attachmentRepository, never()).findByLeaseId(any());
    }

    @Test
    void downloadingAsksThePolicyAboutTheOwningLease() {
        // The download takes an attachment id directly, so it is reachable
        // without ever calling the list endpoint. Guarding one is not guarding
        // the other.
        when(attachmentRepository.findById(attachmentId)).thenReturn(Optional.of(attachment()));
        doThrow(new NotFoundException("Lease not found")).when(policy).requireReadable(any());

        assertThatThrownBy(() -> service.downloadAttachment(attachmentId))
                .isInstanceOf(NotFoundException.class);

        verify(policy).requireReadable(lease);
    }

    @Test
    void anUnknownAttachmentIsStillNotFound() {
        when(attachmentRepository.findById(attachmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.downloadAttachment(attachmentId))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * Documents are generated contracts, and their endpoints grant RENTER
     * exactly like attachments — the same exposure, a different table.
     * Covered here rather than in a separate file because the shape is
     * identical: list guarded by lease, download guarded by the owning lease.
     */
    @Test
    void documentPathsAreGuardedTheSameWay() throws Exception {
        var docRepo = mock(com.datagami.rentaxis.domain.repository.LeaseDocumentRepository.class);
        var contracts = new ContractGenerationService(
                leaseRepository,
                policy,
                docRepo,
                mock(com.datagami.rentaxis.domain.repository.LandlordOrgRepository.class),
                mock(com.datagami.rentaxis.domain.repository.ChequeRepository.class),
                mock(com.datagami.rentaxis.domain.repository.LeaseLineRepository.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));

        doThrow(new NotFoundException("Lease not found")).when(policy).requireReadable(any());

        assertThatThrownBy(() -> contracts.getDocuments(leaseId))
                .isInstanceOf(NotFoundException.class);
        verify(docRepo, never()).findByLeaseId(any());

        var doc = new com.datagami.rentaxis.domain.entity.LeaseDocument();
        doc.setId(UUID.randomUUID());
        doc.setLease(lease);
        when(docRepo.findById(doc.getId())).thenReturn(Optional.of(doc));

        assertThatThrownBy(() -> contracts.getDocumentContent(doc.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * A missing lease must reach the policy as null rather than short-circuit
     * past it, so the refusal is uniform: "not found" either way, revealing
     * nothing about which case it was.
     */
    @Test
    void listingAgainstAnUnknownLeaseStillConsultsThePolicy() {
        UUID unknown = UUID.randomUUID();
        when(leaseRepository.findById(unknown)).thenReturn(Optional.empty());
        doThrow(new NotFoundException("Lease not found")).when(policy).requireReadable(any());

        assertThatThrownBy(() -> service.getAttachments(unknown))
                .isInstanceOf(NotFoundException.class);

        verify(policy).requireReadable(null);
    }
}

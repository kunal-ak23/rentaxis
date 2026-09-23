package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.security.LeaseAccessPolicy;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseSettlement;
import com.datagami.rentaxis.domain.entity.LeaseSettlementDeduction;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.SettlementDeductionAttachment;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.SettlementStatus;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementDeductionRepository;
import com.datagami.rentaxis.domain.repository.LeaseSettlementRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.SettlementDeductionAttachmentRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Audit B-F4: settlement deduction evidence (move-out photos, invoices) follows its
 * lease's building. A property manager of another building gets "not found" on list,
 * download and delete; the manager of this building, and a tenant admin, still work.
 */
class DeductionAttachmentScopeTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID managerId = UUID.randomUUID();
    private final UUID propertyId = UUID.randomUUID();

    private final SettlementDeductionAttachmentRepository attachments = mock(SettlementDeductionAttachmentRepository.class);
    private final LeaseSettlementDeductionRepository deductions = mock(LeaseSettlementDeductionRepository.class);
    private final LeaseSettlementRepository settlements = mock(LeaseSettlementRepository.class);
    private final LeaseRepository leases = mock(LeaseRepository.class);
    private final UserPropertyAssignmentRepository assignments = mock(UserPropertyAssignmentRepository.class);
    private DeductionAttachmentService service;

    private final UUID deductionId = UUID.randomUUID();
    private final UUID attachmentId = UUID.randomUUID();
    private SettlementDeductionAttachment attachment;

    @BeforeEach
    void setUp() {
        TenantContextHolder.setTenantId(tenantId);
        service = new DeductionAttachmentService(attachments, deductions, settlements, leases,
                new LeaseAccessPolicy(assignments, mock(RenterRepository.class)));

        Property property = new Property();
        property.setId(propertyId);
        Unit unit = new Unit();
        unit.setProperty(property);
        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setUnit(unit);
        LeaseSettlement settlement = new LeaseSettlement();
        settlement.setId(UUID.randomUUID());
        settlement.setLeaseId(lease.getId());
        settlement.setStatus(SettlementStatus.DRAFT);
        LeaseSettlementDeduction deduction = new LeaseSettlementDeduction();
        deduction.setId(deductionId);
        deduction.setSettlementId(settlement.getId());
        deduction.setTenantId(tenantId);
        attachment = new SettlementDeductionAttachment();
        attachment.setId(attachmentId);
        attachment.setDeductionId(deductionId);
        attachment.setTenantId(tenantId);
        attachment.setFileUrl("/api/v1/assets/serve/deductions/x.jpg");

        when(deductions.findById(deductionId)).thenReturn(Optional.of(deduction));
        when(settlements.findById(settlement.getId())).thenReturn(Optional.of(settlement));
        when(leases.findById(lease.getId())).thenReturn(Optional.of(lease));
        when(attachments.findById(attachmentId)).thenReturn(Optional.of(attachment));
        when(attachments.findByDeductionIdOrderByUploadedAtAsc(deductionId)).thenReturn(List.of(attachment));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void aManagerOfAnotherBuildingCannotListReadOrDeleteTheEvidence() {
        as("ROLE_PROPERTY_MANAGER");
        when(assignments.findByUserId(managerId)).thenReturn(List.of());

        assertThatThrownBy(() -> service.getAttachments(deductionId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getAttachmentById(attachmentId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.downloadAttachmentStream(attachmentId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.deleteAttachment(attachmentId)).isInstanceOf(NotFoundException.class);
        verify(attachments, never()).delete(attachment);
    }

    @Test
    void theBuildingsManagerAndATenantAdminStillCan() {
        as("ROLE_PROPERTY_MANAGER");
        UserPropertyAssignment a = new UserPropertyAssignment();
        a.setPropertyId(propertyId);
        when(assignments.findByUserId(managerId)).thenReturn(List.of(a));
        assertThat(service.getAttachments(deductionId)).hasSize(1);
        service.deleteAttachment(attachmentId);
        verify(attachments).delete(attachment);

        as("ROLE_TENANT_ADMIN");
        assertThat(service.getAttachmentById(attachmentId).getId()).isEqualTo(attachmentId);
    }

    private void as(String authority) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                managerId.toString(), null, List.of(new SimpleGrantedAuthority(authority))));
    }
}

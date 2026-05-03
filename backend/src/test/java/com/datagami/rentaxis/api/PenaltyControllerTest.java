package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.PenaltyDTO;
import com.datagami.rentaxis.api.dto.PenaltyPaymentDTO;
import com.datagami.rentaxis.api.dto.RecordPenaltyPaymentRequestDTO;
import com.datagami.rentaxis.api.dto.WaivePenaltyRequestDTO;
import com.datagami.rentaxis.api.exception.AccessDeniedException;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.PenaltyPaymentService;
import com.datagami.rentaxis.core.service.PenaltyService;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.PaymentPenalty;
import com.datagami.rentaxis.domain.entity.PenaltyPayment;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PaymentPenaltyRepository;
import com.datagami.rentaxis.domain.repository.PaymentScheduleRepository;
import com.datagami.rentaxis.domain.repository.PenaltyPaymentRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PenaltyControllerTest {

    @Mock PaymentPenaltyRepository penaltyRepo;
    @Mock PenaltyPaymentRepository paymentRepo;
    @Mock PaymentScheduleRepository scheduleRepo;
    @Mock PenaltyService penaltyService;
    @Mock PenaltyPaymentService penaltyPaymentService;
    @Mock RenterRepository renterRepository;
    @Mock LeaseRepository leaseRepository;

    @InjectMocks
    PenaltyController controller;

    private final UUID tenantAdminUserId = UUID.randomUUID();
    private final UUID renterUserId = UUID.randomUUID();
    private final UUID leaseId = UUID.randomUUID();
    private final UUID penaltyId = UUID.randomUUID();
    private final Pageable pageable = PageRequest.of(0, 10);

    @BeforeEach
    void setUp() {
        // Default stubs for penalty-to-DTO mapping
        when(scheduleRepo.findById(any())).thenReturn(Optional.empty());
        when(paymentRepo.findByPaymentPenaltyIdOrderByReceivedAtAscCreatedAtAsc(any()))
                .thenReturn(List.of());
        when(penaltyPaymentService.currentTotal(any())).thenReturn(new BigDecimal("500"));
        when(penaltyPaymentService.outstanding(any())).thenReturn(new BigDecimal("500"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // -----------------------------------------------------------------------
    // GET /api/v1/penalties
    // -----------------------------------------------------------------------

    @Test
    void get_asTenantAdmin_returnsAllForTenant() {
        setAuth("ROLE_TENANT_ADMIN", tenantAdminUserId);
        PaymentPenalty p = openPenalty();
        Page<PaymentPenalty> pageResult = new PageImpl<>(List.of(p));
        when(penaltyRepo.findFiltered(null, false, false, pageable)).thenReturn(pageResult);

        ResponseEntity<Page<PenaltyDTO>> resp = controller.list(null, "all", tenantAdminUserId, pageable);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getTotalElements()).isEqualTo(1);
    }

    @Test
    void get_asRenter_scopesToOwnLeasesOnly() {
        setAuth("ROLE_RENTER", renterUserId);

        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setUserId(renterUserId);

        Lease lease = new Lease();
        lease.setId(leaseId);

        PaymentPenalty p = openPenalty();
        p.setLeaseId(leaseId);

        when(renterRepository.findByUserId(renterUserId)).thenReturn(Optional.of(renter));
        when(leaseRepository.findByRenterId(renter.getId())).thenReturn(List.of(lease));
        Page<PaymentPenalty> pageResult = new PageImpl<>(List.of(p));
        when(penaltyRepo.findFilteredForRenter(List.of(leaseId), null, false, false, pageable))
                .thenReturn(pageResult);

        ResponseEntity<Page<PenaltyDTO>> resp = controller.list(null, "all", renterUserId, pageable);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().getTotalElements()).isEqualTo(1);
    }

    @Test
    void get_asRenter_filterByOtherLease_returns403() {
        setAuth("ROLE_RENTER", renterUserId);

        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setUserId(renterUserId);

        UUID ownLeaseId = UUID.randomUUID();
        UUID otherLeaseId = UUID.randomUUID();  // a different lease the renter doesn't own

        Lease lease = new Lease();
        lease.setId(ownLeaseId);

        when(renterRepository.findByUserId(renterUserId)).thenReturn(Optional.of(renter));
        when(leaseRepository.findByRenterId(renter.getId())).thenReturn(List.of(lease));

        assertThatThrownBy(() -> controller.list(otherLeaseId, "all", renterUserId, pageable))
                .isInstanceOf(AccessDeniedException.class);
    }

    // -----------------------------------------------------------------------
    // POST /api/v1/penalties/{id}/payments
    // -----------------------------------------------------------------------

    @Test
    void recordPayment_happyPath_returns200_withDto() {
        setAuth("ROLE_TENANT_ADMIN", tenantAdminUserId);
        RecordPenaltyPaymentRequestDTO body = new RecordPenaltyPaymentRequestDTO(
                new BigDecimal("200"), "BANK_TRANSFER", "REF123", LocalDate.now(), null);

        PenaltyPayment pp = new PenaltyPayment();
        pp.setId(UUID.randomUUID());
        pp.setAmount(new BigDecimal("200"));
        pp.setPaymentMethod("BANK_TRANSFER");
        pp.setReceivedAt(LocalDate.now());
        pp.setReceivedBy(tenantAdminUserId);

        when(penaltyPaymentService.recordReceipt(eq(penaltyId), any(), eq(tenantAdminUserId)))
                .thenReturn(pp);

        ResponseEntity<PenaltyPaymentDTO> resp = controller.recordPayment(penaltyId, body, tenantAdminUserId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().amount()).isEqualByComparingTo("200");
    }

    @Test
    void recordPayment_amountExceedsOutstanding_returns400() {
        setAuth("ROLE_TENANT_ADMIN", tenantAdminUserId);
        RecordPenaltyPaymentRequestDTO body = new RecordPenaltyPaymentRequestDTO(
                new BigDecimal("9999"), "CASH", null, LocalDate.now(), null);

        when(penaltyPaymentService.recordReceipt(eq(penaltyId), any(), eq(tenantAdminUserId)))
                .thenThrow(new BusinessRuleViolationException("amount 9999 exceeds outstanding 500"));

        assertThatThrownBy(() -> controller.recordPayment(penaltyId, body, tenantAdminUserId))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("exceeds outstanding");
    }

    @Test
    void recordPayment_asRenter_returns403_annotationPresent() throws NoSuchMethodException {
        var method = PenaltyController.class.getMethod("recordPayment",
                UUID.class, RecordPenaltyPaymentRequestDTO.class, UUID.class);
        var annotation = method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).doesNotContain("RENTER");
    }

    // -----------------------------------------------------------------------
    // POST /api/v1/penalties/{id}/waive
    // -----------------------------------------------------------------------

    @Test
    void waive_happyPath_returns200() {
        setAuth("ROLE_TENANT_ADMIN", tenantAdminUserId);
        WaivePenaltyRequestDTO body = new WaivePenaltyRequestDTO("goodwill gesture");

        PaymentPenalty waived = openPenalty();
        waived.setWaived(true);
        waived.setWaivedReason("goodwill gesture");
        waived.setClearedAt(LocalDateTime.now());

        when(penaltyService.waivePenalty(penaltyId, "goodwill gesture", tenantAdminUserId))
                .thenReturn(waived);

        ResponseEntity<PenaltyDTO> resp = controller.waive(penaltyId, body, tenantAdminUserId);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().waived()).isTrue();
        assertThat(resp.getBody().status()).isEqualTo("WAIVED");
    }

    @Test
    void waive_asPropertyManager_returns403_annotationPresent() throws NoSuchMethodException {
        var method = PenaltyController.class.getMethod("waive",
                UUID.class, WaivePenaltyRequestDTO.class, UUID.class);
        var annotation = method.getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).doesNotContain("PROPERTY_MANAGER");
        assertThat(annotation.value()).doesNotContain("RENTER");
    }

    @Test
    void waive_blankReason_returns400_viaValidation() {
        // Test @NotBlank on WaivePenaltyRequestDTO via Bean Validation
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        WaivePenaltyRequestDTO dto = new WaivePenaltyRequestDTO("  ");  // blank whitespace
        Set<ConstraintViolation<WaivePenaltyRequestDTO>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage()).containsAnyOf("blank", "must not be blank");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private PaymentPenalty openPenalty() {
        PaymentPenalty p = new PaymentPenalty();
        p.setId(penaltyId);
        p.setLeaseId(leaseId);
        p.setPaymentScheduleId(UUID.randomUUID());
        p.setPenaltyType("CHEQUE_FAILURE");
        p.setPenaltyAmount(new BigDecimal("500"));
        p.setDaysOverdue(0);
        p.setFineGraceDays(7);
        p.setFinePerDayRate(new BigDecimal("25"));
        p.setCreatedAt(LocalDateTime.now());
        return p;
    }

    private void setAuth(String role, UUID userId) {
        var auth = new UsernamePasswordAuthenticationToken(
                userId.toString(), null,
                Collections.singletonList(new SimpleGrantedAuthority(role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}

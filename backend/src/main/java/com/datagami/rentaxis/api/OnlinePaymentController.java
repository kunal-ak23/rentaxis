package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.CreateOrderRequestDTO;
import com.datagami.rentaxis.api.dto.CreateOrderResponseDTO;
import com.datagami.rentaxis.api.dto.RenterChequeDTO;
import com.datagami.rentaxis.api.dto.UnappliedOnlinePaymentDTO;
import com.datagami.rentaxis.api.dto.UnappliedOnlinePaymentTotalsDTO;
import com.datagami.rentaxis.api.dto.VerifyPaymentRequestDTO;
import com.datagami.rentaxis.api.dto.VerifyPaymentResponseDTO;
import com.datagami.rentaxis.core.service.OnlinePaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The renter's payment screen. The paths are unchanged from v1; what they name is
 * now a cheque-register row rather than a payment schedule (spec §9.3).
 */
@RestController
@RequestMapping("/api/v1/online-payments")
@RequiredArgsConstructor
public class OnlinePaymentController {

    private final OnlinePaymentService onlinePaymentService;

    @GetMapping("/my-payments")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<List<RenterChequeDTO>> getMyPayments(HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        UUID userId = UUID.fromString(userIdStr);
        return ResponseEntity.ok(onlinePaymentService.getMyPayments(userId));
    }

    @PostMapping("/create-order")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<CreateOrderResponseDTO> createOrder(@RequestBody CreateOrderRequestDTO dto) {
        return ResponseEntity.ok(onlinePaymentService.createOrder(dto.getChequeId()));
    }

    @PostMapping("/verify")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<VerifyPaymentResponseDTO> verifyPayment(@RequestBody VerifyPaymentRequestDTO dto) {
        return ResponseEntity.ok(onlinePaymentService.verifyPayment(dto));
    }

    @PostMapping("/cancel/{chequeId}")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<Void> cancelPendingPayment(@PathVariable UUID chequeId) {
        onlinePaymentService.cancelPendingOnlinePayment(chequeId);
        return ResponseEntity.ok().build();
    }

    // ------------------------------------------------------------------
    // finance's refund worklist
    // ------------------------------------------------------------------

    /**
     * Money the gateway took that the register refused, newest first (spec §9.3).
     *
     * <p>Finance's, not the renter's: the renter has already been told their
     * payment could not be applied, and what is on this list is which of the
     * landlord's own collections need reversing in the gateway's dashboard. So it
     * sits alongside the renter's endpoints on the same base path — they are all
     * one gateway session's life — but behind the tenant-wide finance roles.
     * PROPERTY_MANAGER is deliberately not among them: a refund is a movement of
     * money out, which is the same line the register draws for clearing and
     * bouncing.</p>
     */
    @GetMapping("/unapplied")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<Page<UnappliedOnlinePaymentDTO>> unapplied(
            @PageableDefault(size = 25) Pageable pageable) {
        // No `sort` attribute on the default: the service applies "newest first"
        // when the request carries no sort of its own, and naming one here would
        // mean the service could never tell "the caller wants this order" from
        // "Spring filled one in".
        return ResponseEntity.ok(onlinePaymentService.unapplied(pageable));
    }

    /** The same worklist as a dashboard tile: how many, and how much is owed back. */
    @GetMapping("/unapplied/count")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
    public ResponseEntity<UnappliedOnlinePaymentTotalsDTO> unappliedCount() {
        return ResponseEntity.ok(onlinePaymentService.unappliedTotals());
    }
}

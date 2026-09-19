package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.CreateOrderRequestDTO;
import com.datagami.rentaxis.api.dto.CreateOrderResponseDTO;
import com.datagami.rentaxis.api.dto.RenterChequeDTO;
import com.datagami.rentaxis.api.dto.VerifyPaymentRequestDTO;
import com.datagami.rentaxis.api.dto.VerifyPaymentResponseDTO;
import com.datagami.rentaxis.core.service.OnlinePaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
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
}

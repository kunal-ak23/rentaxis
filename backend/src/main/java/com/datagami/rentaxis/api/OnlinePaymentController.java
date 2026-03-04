package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.*;
import com.datagami.rentaxis.core.service.OnlinePaymentService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/online-payments")
@RequiredArgsConstructor
public class OnlinePaymentController {

    private final OnlinePaymentService onlinePaymentService;

    @GetMapping("/my-payments")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<List<RenterPaymentScheduleDTO>> getMyPayments(HttpServletRequest request) {
        String userIdStr = request.getHeader("X-User-Id");
        UUID userId = UUID.fromString(userIdStr);
        return ResponseEntity.ok(onlinePaymentService.getMyPayments(userId));
    }

    @PostMapping("/create-order")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<CreateOrderResponseDTO> createOrder(@RequestBody CreateOrderRequestDTO dto) {
        return ResponseEntity.ok(onlinePaymentService.createOrder(dto.getPaymentScheduleId()));
    }

    @PostMapping("/verify")
    @PreAuthorize("hasRole('RENTER')")
    public ResponseEntity<VerifyPaymentResponseDTO> verifyPayment(@RequestBody VerifyPaymentRequestDTO dto) {
        return ResponseEntity.ok(onlinePaymentService.verifyPayment(dto));
    }
}

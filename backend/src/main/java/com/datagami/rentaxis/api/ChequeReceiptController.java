package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.RentReceiptService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The receipt for a cleared register row (spec §9.3).
 *
 * <p>Its own controller, mapped where the register's controller will be, purely
 * as sequencing: {@code ChequeController} arrives with Task 11 and folds this one
 * endpoint in. The route is the final one, so nothing downstream has to move.</p>
 *
 * <p>The role check here answers "may this kind of user download a receipt at
 * all"; whether this particular caller may have <em>this</em> receipt is
 * {@code LeaseAccessPolicy}'s, applied inside the service — a RENTER reaches only
 * their own tenancy's rows.</p>
 */
@RestController
@RequestMapping("/api/v1/cheques")
@RequiredArgsConstructor
public class ChequeReceiptController {

    private final RentReceiptService rentReceiptService;

    @GetMapping("/{id}/receipt")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT', 'PROPERTY_MANAGER', 'RENTER')")
    public ResponseEntity<byte[]> downloadReceipt(@PathVariable UUID id) {
        byte[] pdf = rentReceiptService.generateReceipt(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=receipt-" + id.toString().substring(0, 8) + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}

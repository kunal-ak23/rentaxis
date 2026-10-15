package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.BankAccountRequest;
import com.datagami.rentaxis.core.service.BankAccountService;
import com.datagami.rentaxis.domain.entity.BankAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

/**
 * The organisation's bank accounts.
 *
 * <p>Reading is the Accountant's too (tutorial 19): bank reconciliation, payment runs and
 * receipts are their work and all of them pick a bank account. Creating, editing and
 * deleting one stays with the Company Admin — a bank account decides where receipts
 * land and which ledger leaf they post to, which is configuration rather than
 * bookkeeping (finance-ops spec keeps set-up with the admin).</p>
 */
@RestController
@RequestMapping("/api/v1/bank-accounts")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class BankAccountController {

    static final String MANAGE = "hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')";

    private final BankAccountService service;

    public BankAccountController(BankAccountService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<BankAccount>> getAllBankAccounts() {
        return ResponseEntity.ok(service.getAllBankAccounts());
    }

    @GetMapping("/by-property/{propertyId}")
    public ResponseEntity<List<BankAccount>> getByProperty(@PathVariable UUID propertyId) {
        return ResponseEntity.ok(service.getByProperty(propertyId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<BankAccount> getBankAccountById(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getBankAccountById(id));
    }

    @PostMapping
    @PreAuthorize(MANAGE)
    public ResponseEntity<BankAccount> createBankAccount(@RequestBody BankAccountRequest request) {
        return ResponseEntity.ok(service.createBankAccount(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE)
    public ResponseEntity<BankAccount> updateBankAccount(@PathVariable UUID id, @RequestBody BankAccountRequest request) {
        return ResponseEntity.ok(service.updateBankAccount(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(MANAGE)
    public ResponseEntity<Void> deleteBankAccount(@PathVariable UUID id) {
        service.deleteBankAccount(id);
        return ResponseEntity.noContent().build();
    }
}

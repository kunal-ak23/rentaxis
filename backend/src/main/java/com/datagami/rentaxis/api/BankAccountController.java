package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.BankAccountService;
import com.datagami.rentaxis.domain.entity.BankAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bank-accounts")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
public class BankAccountController {

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
    public ResponseEntity<BankAccount> createBankAccount(@RequestBody BankAccount bankAccount) {
        return ResponseEntity.ok(service.createBankAccount(bankAccount));
    }

    @PutMapping("/{id}")
    public ResponseEntity<BankAccount> updateBankAccount(@PathVariable UUID id, @RequestBody BankAccount bankAccount) {
        return ResponseEntity.ok(service.updateBankAccount(id, bankAccount));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteBankAccount(@PathVariable UUID id) {
        service.deleteBankAccount(id);
        return ResponseEntity.noContent().build();
    }
}

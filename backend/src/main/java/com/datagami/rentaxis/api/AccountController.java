package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountImportService;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/accounts")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
public class AccountController {

    private final AccountService service;
    private final AccountImportService importService;

    public AccountController(AccountService service, AccountImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    @GetMapping
    public ResponseEntity<List<Account>> getAllAccounts() {
        return ResponseEntity.ok(service.getAllAccounts());
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<List<Account>> getAccountsByType(@PathVariable AccountType type) {
        return ResponseEntity.ok(service.getAccountsByType(type));
    }

    @GetMapping("/code/{code}")
    public ResponseEntity<Account> getAccountByCode(@PathVariable String code) {
        return ResponseEntity.ok(service.getAccountByCode(code));
    }

    @PostMapping
    public ResponseEntity<Account> createAccount(@RequestBody Account account) {
        return ResponseEntity.ok(service.createAccount(account));
    }

    @PostMapping("/seed")
    public ResponseEntity<List<Account>> seedDefaultAccounts() {
        return ResponseEntity.ok(service.seedDefaultAccounts());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Account> updateAccount(@PathVariable UUID id, @RequestBody Account account) {
        return ResponseEntity.ok(service.updateAccount(id, account));
    }

    @PostMapping("/import")
    public ResponseEntity<List<Account>> importAccounts(@RequestParam("file") MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();
        if (filename != null && (filename.endsWith(".xlsx") || filename.endsWith(".xls"))) {
            return ResponseEntity.ok(importService.importFromExcel(file));
        }
        return ResponseEntity.ok(importService.importFromCsv(file));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteAccount(@PathVariable UUID id) {
        service.deleteAccount(id);
        return ResponseEntity.noContent().build();
    }
}

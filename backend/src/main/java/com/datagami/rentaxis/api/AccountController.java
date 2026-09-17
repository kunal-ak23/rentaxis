package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountImportService;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.AccountSubType;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/finance/accounts")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class AccountController {

    private final AccountService service;
    private final AccountImportService importService;

    public AccountController(AccountService service, AccountImportService importService) {
        this.service = service;
        this.importService = importService;
    }

    /**
     * The tree is bound through request records rather than the entity itself:
     * {@code parent} and {@code property} are {@code @JsonIgnore} associations,
     * so a request body cannot name them, and binding the entity would once
     * again let a caller set server-owned fields.
     */
    public record CreateAccountRequest(String code, String name, String nameEn, String nameAr, String alias,
                                       AccountType accountType, AccountSubType accountSubType, String description,
                                       UUID parentId, UUID propertyId, boolean group) {}

    public record UpdateAccountRequest(String name, String nameEn, String nameAr, String alias, String description,
                                       AccountSubType accountSubType, boolean active, int displayOrder,
                                       UUID propertyId) {}

    @GetMapping
    public ResponseEntity<List<Account>> getAllAccounts() {
        return ResponseEntity.ok(service.getAllAccounts());
    }

    @GetMapping("/tree")
    public ResponseEntity<List<Account>> getTree() {
        return ResponseEntity.ok(service.getTree());
    }

    @GetMapping("/{id}/children")
    public ResponseEntity<List<Account>> getChildren(@PathVariable UUID id) {
        return ResponseEntity.ok(service.getChildren(id));
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
    public ResponseEntity<Account> createAccount(@RequestBody CreateAccountRequest r) {
        Account a = new Account();
        a.setCode(r.code());
        a.setName(r.name() != null ? r.name() : r.nameEn());
        a.setNameEn(r.nameEn());
        a.setNameAr(r.nameAr());
        a.setAlias(r.alias());
        a.setAccountType(r.accountType());
        a.setAccountSubType(r.accountSubType());
        a.setDescription(r.description());
        a.setGroup(r.group());
        if (r.parentId() != null) {
            Account p = new Account();
            p.setId(r.parentId());
            a.setParent(p);
        }
        if (r.propertyId() != null) {
            Property p = new Property();
            p.setId(r.propertyId());
            a.setProperty(p);
        }
        return ResponseEntity.ok(service.createAccount(a));
    }

    @PostMapping("/seed")
    public ResponseEntity<List<Account>> seedDefaultAccounts() {
        return ResponseEntity.ok(service.seedDefaultAccounts());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Account> updateAccount(@PathVariable UUID id, @RequestBody UpdateAccountRequest r) {
        Account a = new Account();
        a.setName(r.name());
        a.setNameEn(r.nameEn());
        a.setNameAr(r.nameAr());
        a.setAlias(r.alias());
        a.setDescription(r.description());
        a.setAccountSubType(r.accountSubType());
        a.setActive(r.active());
        a.setDisplayOrder(r.displayOrder());
        if (r.propertyId() != null) {
            Property p = new Property();
            p.setId(r.propertyId());
            a.setProperty(p);
        }
        return ResponseEntity.ok(service.updateAccount(id, a));
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

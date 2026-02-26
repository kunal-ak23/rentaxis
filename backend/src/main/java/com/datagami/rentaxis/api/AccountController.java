package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/finance/accounts")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
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
}

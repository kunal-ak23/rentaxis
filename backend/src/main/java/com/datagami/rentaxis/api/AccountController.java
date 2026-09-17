package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountImportService;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.enums.AccountSubType;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
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
     *
     * <p>Both collect anything they do not recognise into {@code unknown} and
     * refuse the request. The field that used to carry the tree — v1's
     * {@code parentCode} — is gone, and the accounts page still sends it on
     * create: without this the value was dropped in silence and the caller was
     * told 200 for an account filed at the root of the chart instead of under
     * its parent. A misfiled account nobody is told about is worse than a
     * refused request.
     *
     * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} does NOT do this,
     * which is worth recording because it looks like it should. Jackson fails on
     * an unknown property only when {@code ignoreUnknown} is false AND
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} is enabled, and Spring's
     * {@code Jackson2ObjectMapperBuilder} — behind both the Boot app and
     * standalone MockMvc — disables that feature globally. The annotation is
     * inert here; enabling the feature globally instead would start 400-ing
     * tolerant clients on every other endpoint, the mobile apps included.
     *
     * <p>{@code group} is boxed for the same reason {@code active} is on the
     * update record: a primitive turns "field absent" into a real value.
     */
    public record CreateAccountRequest(String code, String name, String nameEn, String nameAr, String alias,
                                       AccountType accountType, AccountSubType accountSubType, String description,
                                       UUID parentId, UUID propertyId, Boolean group,
                                       @JsonAnySetter Map<String, Object> unknown) {}

    /**
     * {@code active} and {@code displayOrder} are boxed: null leaves the stored
     * value alone, so a partial body cannot deactivate the account or reset its
     * ordering. {@code propertyId} is different — it is always applied, so
     * sending null clears the account's property tag.
     */
    public record UpdateAccountRequest(String name, String nameEn, String nameAr, String alias, String description,
                                       AccountSubType accountSubType, Boolean active, Integer displayOrder,
                                       UUID propertyId,
                                       @JsonAnySetter Map<String, Object> unknown) {}

    /** A body this endpoint does not understand is refused, never partially applied. */
    private static void rejectUnknownFields(Map<String, Object> unknown) {
        if (unknown != null && !unknown.isEmpty()) {
            throw new BusinessRuleViolationException(
                    "Unrecognised field(s): " + String.join(", ", new TreeSet<>(unknown.keySet())));
        }
    }

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
        rejectUnknownFields(r.unknown());
        Account a = new Account();
        a.setCode(r.code());
        a.setName(r.name() != null ? r.name() : r.nameEn());
        a.setNameEn(r.nameEn());
        a.setNameAr(r.nameAr());
        a.setAlias(r.alias());
        a.setAccountType(r.accountType());
        a.setAccountSubType(r.accountSubType());
        a.setDescription(r.description());
        a.setGroup(Boolean.TRUE.equals(r.group()));
        if (r.parentId() != null) {
            Account p = new Account();
            p.setId(r.parentId());
            a.setParent(p);
        }
        return ResponseEntity.ok(service.createAccount(a, r.propertyId()));
    }

    @PostMapping("/seed")
    public ResponseEntity<List<Account>> seedDefaultAccounts() {
        return ResponseEntity.ok(service.seedDefaultAccounts());
    }

    @PutMapping("/{id}")
    public ResponseEntity<Account> updateAccount(@PathVariable UUID id, @RequestBody UpdateAccountRequest r) {
        rejectUnknownFields(r.unknown());
        return ResponseEntity.ok(service.updateAccount(id, new AccountService.AccountUpdate(
                r.name(), r.nameEn(), r.nameAr(), r.alias(), r.description(),
                r.accountSubType(), r.active(), r.displayOrder(), r.propertyId())));
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

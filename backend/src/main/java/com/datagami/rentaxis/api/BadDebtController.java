package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.baddebt.BadDebtService;
import com.datagami.rentaxis.core.service.baddebt.BadDebtService.Item;
import com.datagami.rentaxis.core.service.baddebt.BadDebtService.ProposeRequest;
import com.datagami.rentaxis.core.service.baddebt.BadDebtService.RecoveryRequest;
import com.datagami.rentaxis.core.service.baddebt.BadDebtService.WriteOffDTO;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** F14-38: bad-debt write-offs. Finance proposes and records recoveries; an organisation admin decides and reverses. */
@RestController
@RequestMapping("/api/v1/finance/bad-debts")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN', 'ACCOUNTANT')")
public class BadDebtController {

    private final BadDebtService service;

    public BadDebtController(BadDebtService service) {
        this.service = service;
    }

    public record DecisionRequest(String note, LocalDate date) { }

    @GetMapping("/candidates")
    public List<Item> candidates(@RequestParam UUID leaseId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate on) {
        return service.candidates(leaseId, on);
    }

    @GetMapping
    public List<WriteOffDTO> forLease(@RequestParam UUID leaseId) {
        return service.forLease(leaseId);
    }

    @PostMapping
    public WriteOffDTO propose(@RequestBody ProposeRequest r) {
        return service.propose(r);
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public WriteOffDTO approve(@PathVariable UUID id, @RequestBody(required = false) DecisionRequest r) {
        return service.approve(id, r == null ? null : r.note());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public WriteOffDTO reject(@PathVariable UUID id, @RequestBody DecisionRequest r) {
        return service.reject(id, r == null ? null : r.note());
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
    public WriteOffDTO reverse(@PathVariable UUID id, @RequestBody DecisionRequest r) {
        return service.reverse(id, r == null ? null : r.date(), r == null ? null : r.note());
    }

    @PostMapping("/{id}/recoveries")
    public WriteOffDTO recover(@PathVariable UUID id, @RequestBody RecoveryRequest r) {
        return service.recover(id, r);
    }
}

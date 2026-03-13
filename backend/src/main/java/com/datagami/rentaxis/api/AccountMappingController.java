package com.datagami.rentaxis.api;

import com.datagami.rentaxis.api.dto.AccountMappingDTO;
import com.datagami.rentaxis.api.dto.SaveAccountMappingDTO;
import com.datagami.rentaxis.core.service.AccountMappingService;
import com.datagami.rentaxis.domain.entity.enums.TransactionNature;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/finance/account-mappings")
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'TENANT_ADMIN')")
@RequiredArgsConstructor
public class AccountMappingController {

    private final AccountMappingService service;

    @GetMapping
    public ResponseEntity<List<AccountMappingDTO>> getAllMappings() {
        return ResponseEntity.ok(service.getAllMappings());
    }

    @PostMapping
    public ResponseEntity<AccountMappingDTO> saveMapping(@Valid @RequestBody SaveAccountMappingDTO dto) {
        return ResponseEntity.ok(service.saveMapping(dto));
    }

    @PutMapping("/bulk")
    public ResponseEntity<List<AccountMappingDTO>> saveMappings(@Valid @RequestBody List<SaveAccountMappingDTO> dtos) {
        return ResponseEntity.ok(service.saveMappings(dtos));
    }

    @GetMapping("/natures")
    public ResponseEntity<List<String>> getTransactionNatures() {
        return ResponseEntity.ok(
                Arrays.stream(TransactionNature.values())
                        .map(Enum::name)
                        .collect(Collectors.toList())
        );
    }
}

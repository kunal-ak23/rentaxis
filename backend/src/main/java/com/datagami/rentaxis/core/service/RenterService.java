package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateRenterDTO;
import com.datagami.rentaxis.api.dto.RenterDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RenterService {

    private final RenterRepository renterRepository;
    private final UserService userService;

    @Transactional(readOnly = true)
    public List<RenterDTO> getAllRenters() {
        UUID tenantId = TenantContextHolder.getTenantId();
        return renterRepository.findByTenantId(tenantId).stream()
                .map(this::mapToDTO)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RenterDTO getRenterById(UUID id) {
        Renter renter = renterRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Renter not found"));
        return mapToDTO(renter);
    }

    @Transactional
    public RenterDTO createRenter(CreateRenterDTO dto) {
        Renter renter = new Renter();
        renter.setNameEn(dto.getNameEn());
        renter.setNameAr(dto.getNameAr());
        renter.setEmail(dto.getEmail());
        renter.setPhone(dto.getPhone());
        if (dto.getPrimaryLanguage() != null) {
            renter.setPrimaryLanguage(dto.getPrimaryLanguage());
        }

        Renter saved = renterRepository.save(renter);

        String generatedPassword = null;

        // Auto-create portal user account if requested
        if (dto.isCreatePortalAccount() && dto.getEmail() != null && !dto.getEmail().isBlank()) {
            try {
                UUID tenantId = TenantContextHolder.getTenantId();
                generatedPassword = "Renter@" + saved.getId().toString().substring(0, 6);
                User user = userService.createUser(
                        dto.getEmail(),
                        generatedPassword,
                        dto.getNameEn(),
                        UserRole.RENTER,
                        tenantId != null ? tenantId.toString() : null,
                        dto.getPhone(),
                        "system"
                );
                saved.setUserId(user.getId());
                renterRepository.save(saved);
                // USER_INVITED is now fired by UserService.createUser for RENTER role — no duplicate needed.
            } catch (Exception e) {
                generatedPassword = null;
                System.err.println("Failed to create portal account for renter " + saved.getId() + ": " + e.getMessage());
            }
        }

        RenterDTO result = mapToDTO(saved);
        result.setPortalPassword(generatedPassword);
        return result;
    }

    @Transactional
    public RenterDTO updateRenter(UUID id, CreateRenterDTO dto) {
        Renter renter = renterRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Renter not found"));

        renter.setNameEn(dto.getNameEn());
        renter.setNameAr(dto.getNameAr());
        renter.setEmail(dto.getEmail());
        renter.setPhone(dto.getPhone());
        if (dto.getPrimaryLanguage() != null) {
            renter.setPrimaryLanguage(dto.getPrimaryLanguage());
        }

        return mapToDTO(renterRepository.save(renter));
    }

    private RenterDTO mapToDTO(Renter renter) {
        RenterDTO dto = new RenterDTO();
        dto.setId(renter.getId());
        dto.setNameEn(renter.getNameEn());
        dto.setNameAr(renter.getNameAr());
        dto.setEmail(renter.getEmail());
        dto.setPhone(renter.getPhone());
        dto.setPrimaryLanguage(renter.getPrimaryLanguage());
        dto.setUserId(renter.getUserId());
        return dto;
    }
}

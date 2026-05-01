package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class LandlordOrgService {

    private final LandlordOrgRepository repository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    public LandlordOrgService(LandlordOrgRepository repository,
                               UserRepository userRepository,
                               NotificationService notificationService) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    @Transactional
    public LandlordOrg provisionTenant(String name) {
        LandlordOrg org = new LandlordOrg();
        org.setName(name);
        LandlordOrg saved = repository.save(org);

        // Notify all SUPER_ADMINs
        try {
            userRepository.findByRole(UserRole.SUPER_ADMIN).forEach(admin -> {
                notificationService.notify(null, admin.getId(),
                        "TENANT_PROVISIONED", "New Organization Created",
                        "A new organization '" + name + "' has been provisioned.",
                        "TENANT", saved.getId());
            });
        } catch (Exception e) {
            log.warn("Failed to send tenant provisioned notification: {}", e.getMessage());
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public List<LandlordOrg> listAllTenants() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Optional<LandlordOrg> findById(UUID id) {
        return repository.findById(id);
    }

    @Transactional
    public LandlordOrg save(LandlordOrg org) {
        return repository.save(org);
    }

}

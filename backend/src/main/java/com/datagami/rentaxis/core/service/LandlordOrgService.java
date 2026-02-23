package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class LandlordOrgService {

    private final LandlordOrgRepository repository;

    public LandlordOrgService(LandlordOrgRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public LandlordOrg provisionTenant(String name) {
        LandlordOrg org = new LandlordOrg();
        org.setName(name);
        // Default status is ACTIVE per entity definition
        return repository.save(org);
    }

    @Transactional(readOnly = true)
    public List<LandlordOrg> listAllTenants() {
        return repository.findAll();
    }
}

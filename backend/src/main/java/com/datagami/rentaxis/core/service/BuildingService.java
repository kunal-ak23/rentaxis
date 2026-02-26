package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.repository.BuildingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class BuildingService {

    private final BuildingRepository repository;

    public BuildingService(BuildingRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Building createBuilding(Building building) {
        return repository.save(building);
    }

    @Transactional(readOnly = true)
    public List<Building> getBuildingsByProperty(UUID propertyId) {
        return repository.findByPropertyId(propertyId);
    }

    @Transactional(readOnly = true)
    public List<Building> getAllBuildings() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Building getBuildingById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("Building not found"));
    }

    @Transactional
    public void deleteBuilding(UUID id) {
        repository.deleteById(id);
    }
}

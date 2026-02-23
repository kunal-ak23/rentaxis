package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PropertyService {

    private final PropertyRepository repository;

    public PropertyService(PropertyRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Property createProperty(Property property) {
        return repository.save(property);
    }

    @Transactional(readOnly = true)
    public List<Property> getAllProperties() {
        return repository.findAll();
    }

    @Transactional(readOnly = true)
    public Property getPropertyById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("Property not found"));
    }
}

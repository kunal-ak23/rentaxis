package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserPropertyAssignmentRepository extends JpaRepository<UserPropertyAssignment, UUID> {

    List<UserPropertyAssignment> findByUserId(UUID userId);

    List<UserPropertyAssignment> findByPropertyId(UUID propertyId);

    boolean existsByUserIdAndPropertyId(UUID userId, UUID propertyId);

    void deleteByUserIdAndPropertyId(UUID userId, UUID propertyId);
}

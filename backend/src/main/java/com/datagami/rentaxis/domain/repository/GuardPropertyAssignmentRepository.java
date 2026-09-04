package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.GuardPropertyAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GuardPropertyAssignmentRepository extends JpaRepository<GuardPropertyAssignment, UUID> {

    List<GuardPropertyAssignment> findByUserId(UUID userId);

    void deleteByUserIdAndPropertyId(UUID userId, UUID propertyId);

    /** Account deletion: a guard's postings have no FK to users and would otherwise be orphaned. */
    void deleteByUserId(UUID userId);
}

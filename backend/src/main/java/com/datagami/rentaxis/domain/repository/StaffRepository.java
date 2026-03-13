package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Staff;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface StaffRepository extends JpaRepository<Staff, UUID> {
    List<Staff> findByPropertyId(UUID propertyId);
    List<Staff> findByIsActiveTrue();
    List<Staff> findAllByOrderByNameEnAsc();
}

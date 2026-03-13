package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface VendorRepository extends JpaRepository<Vendor, UUID> {
    List<Vendor> findByIsActiveTrue();
    List<Vendor> findAllByOrderByNameEnAsc();
}

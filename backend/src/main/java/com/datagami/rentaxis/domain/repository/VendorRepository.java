package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.Vendor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface VendorRepository extends JpaRepository<Vendor, UUID> {
    List<Vendor> findByIsActiveTrue();
    List<Vendor> findAllByOrderByNameEnAsc();

    /**
     * Which vendors own these payable leaves — one query for a whole voucher's line
     * set, so "is this line somebody's payable?" does not become a query per line.
     * Call it inside a transaction: the tenant filter is what keeps another
     * landlord's vendor out of the answer.
     */
    List<Vendor> findByPayableAccount_IdIn(Collection<UUID> accountIds);
}

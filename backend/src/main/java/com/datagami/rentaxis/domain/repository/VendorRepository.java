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

    /** Scale P1-3: the vendors list, searched and paged; {@code q} is {@code %term%}, lowercased. */
    @org.springframework.data.jpa.repository.Query("""
        select v from Vendor v
        where v.tenantId = :tenantId
          and (cast(:q as string) is null
               or lower(v.nameEn) like :q or lower(v.nameAr) like :q or lower(v.trn) like :q
               or lower(v.email) like :q or lower(v.phone) like :q or lower(v.contactPerson) like :q)
        """)
    org.springframework.data.domain.Page<Vendor> searchPaged(
            @org.springframework.data.repository.query.Param("tenantId") UUID tenantId,
            @org.springframework.data.repository.query.Param("q") String q,
            org.springframework.data.domain.Pageable pageable);
}

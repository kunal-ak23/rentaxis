package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.TaxInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TaxInvoiceRepository extends JpaRepository<TaxInvoice, UUID> {

    List<TaxInvoice> findByLeaseIdOrderByIssueDateAscCreatedAtAsc(UUID leaseId);

    List<TaxInvoice> findByRenterIdOrderByIssueDateDescCreatedAtDesc(UUID renterId);

    Optional<TaxInvoice> findByTaxPointId(UUID taxPointId);

    List<TaxInvoice> findByTaxPointIdIn(Collection<UUID> taxPointIds);
}

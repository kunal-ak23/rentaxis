package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseAddendumCredit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface LeaseAddendumCreditRepository extends JpaRepository<LeaseAddendumCredit, UUID> {

    List<LeaseAddendumCredit> findByAddendumIdIn(Collection<UUID> addendumIds);

    /** The credits on a lease's lines, oldest addendum first is not guaranteed; callers order. */
    List<LeaseAddendumCredit> findByLeaseLineIdIn(Collection<UUID> lineIds);
}

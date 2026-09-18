package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.LeaseLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LeaseLineRepository extends JpaRepository<LeaseLine, UUID> {

    /** The lease's lines in entry order — the order the contract renders them in. */
    List<LeaseLine> findByLease_IdOrderBySeqNoAsc(UUID leaseId);

    /**
     * Delete-then-insert is how a draft's lines are replaced, so this runs on
     * every edit. {@code @Modifying} rather than a {@code deleteAll(findAll…)}
     * round trip: the rows are about to be superseded, there is nothing to read
     * off them first.
     */
    @Modifying
    void deleteByLease_Id(UUID leaseId);
}

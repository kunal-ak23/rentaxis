package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.JournalLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface JournalLineRepository extends JpaRepository<JournalLine, UUID> {
    List<JournalLine> findByEntry_IdOrderByLineNoAsc(UUID entryId);
    boolean existsByAccount_Id(UUID accountId);
}

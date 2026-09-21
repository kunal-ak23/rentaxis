package com.datagami.rentaxis.domain.repository;

import com.datagami.rentaxis.domain.entity.VoucherLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface VoucherLineRepository extends JpaRepository<VoucherLine, UUID> {
    List<VoucherLine> findByVoucher_IdOrderByLineNoAsc(UUID voucherId);
    boolean existsByAccount_Id(UUID accountId);
}

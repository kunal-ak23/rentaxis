package com.datagami.rentaxis.api.dto.lease;

import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;

import java.time.LocalDate;
import java.util.UUID;

/**
 * #100-e: one contract in a lease's history (spec §2, "One audit trail").
 *
 * @param link how this contract followed the one before it: {@code RENEWED} or
 *             {@code TRANSFERRED}; null for the first contract of the chain
 */
public record LeaseChainLinkDTO(UUID id,
                                String unitIdentifier,
                                String displayContractNumber,
                                LocalDate startDate,
                                LocalDate endDate,
                                LeaseStatus status,
                                String link) {

    public static final String RENEWED = "RENEWED";
    public static final String TRANSFERRED = "TRANSFERRED";
}

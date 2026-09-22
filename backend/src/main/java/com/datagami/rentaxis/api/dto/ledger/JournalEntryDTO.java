package com.datagami.rentaxis.api.dto.ledger;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** A posted journal entry. List rows carry {@code total} but leave {@code lines} empty. */
public record JournalEntryDTO(
        UUID id,
        String entryNumber,
        String docType,
        LocalDate entryDate,
        String narration,
        String status,
        UUID propertyId,
        UUID unitId,
        UUID leaseId,
        UUID renterId,
        String sourceType,
        UUID sourceId,
        UUID reversalOfId,
        UUID reversedById,
        UUID importBatchId,
        UUID postedBy,
        Instant postedAt,
        BigDecimal total,
        List<JournalLineDTO> lines) {}

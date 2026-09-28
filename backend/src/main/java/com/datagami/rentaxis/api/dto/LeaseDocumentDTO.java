package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.DocumentType;
import lombok.Data;

import java.util.UUID;

@Data
public class LeaseDocumentDTO {
    private UUID id;
    private UUID leaseId;
    private String documentUrl;
    private DocumentType type;
    /** "Signed contract" or "Executed copy" (the web and apps translate by type). */
    private String label;
    private java.time.Instant createdAt;
}

package com.datagami.rentaxis.api.dto;

import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
public class LeaseAttachmentDTO {
    private UUID id;
    private UUID leaseId;
    private String name;
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private Instant uploadedAt;
}

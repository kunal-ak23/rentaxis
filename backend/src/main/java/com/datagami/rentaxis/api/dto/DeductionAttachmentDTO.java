package com.datagami.rentaxis.api.dto;

import lombok.Data;
import java.time.Instant;
import java.util.UUID;

@Data
public class DeductionAttachmentDTO {
    private UUID id;
    private UUID deductionId;
    private String name;
    /** The name to save the file as: the display name plus the original file's extension. */
    private String downloadName;
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private Instant uploadedAt;
}

package com.datagami.rentaxis.api.dto.voucher;

import com.datagami.rentaxis.domain.entity.VoucherAttachment;

import java.time.Instant;
import java.util.UUID;

public record VoucherAttachmentDTO(UUID id, UUID voucherId, String name, String fileUrl,
                                   String fileType, Long fileSize, Instant uploadedAt) {

    public static VoucherAttachmentDTO of(VoucherAttachment a) {
        return new VoucherAttachmentDTO(a.getId(), a.getVoucherId(), a.getName(), a.getFileUrl(),
                a.getFileType(), a.getFileSize(), a.getUploadedAt());
    }
}

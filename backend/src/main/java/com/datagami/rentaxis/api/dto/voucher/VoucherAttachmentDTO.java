package com.datagami.rentaxis.api.dto.voucher;

import com.datagami.rentaxis.domain.entity.VoucherAttachment;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code fileUrl} is deliberately absent (security ruling, Task 5 fix round 1). A
 * voucher attachment is a private document — a supplier invoice with a TRN and
 * bank details on it — and the storage key behind {@code VoucherAttachment.fileUrl}
 * points at {@code /api/v1/assets/serve/**}, which {@code SecurityConfig}
 * {@code permitAll()}s with no auth check at all. Putting that key on the wire
 * would hand any caller who has it (a browser referrer, a log, a screenshot) an
 * unauthenticated, cross-tenant bypass of every guard {@link
 * com.datagami.rentaxis.api.VoucherController} builds. Downloads go only through
 * the controller's own authenticated {@code GET …/attachments/{id}/download},
 * which streams the bytes without ever revealing the storage key.
 */
public record VoucherAttachmentDTO(UUID id, UUID voucherId, String name,
                                   String fileType, Long fileSize, Instant uploadedAt) {

    public static VoucherAttachmentDTO of(VoucherAttachment a) {
        return new VoucherAttachmentDTO(a.getId(), a.getVoucherId(), a.getName(),
                a.getFileType(), a.getFileSize(), a.getUploadedAt());
    }
}

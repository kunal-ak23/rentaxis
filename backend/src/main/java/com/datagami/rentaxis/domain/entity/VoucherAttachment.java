package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An invoice scan or payment proof attached to a {@link Voucher}. A copy of
 * {@link SettlementDeductionAttachment}, keyed on {@code voucher_id} (changeset 87).
 */
@Entity
@Table(name = "voucher_attachments")
@Getter
@Setter
public class VoucherAttachment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "voucher_id", nullable = false)
    private UUID voucherId;

    @Column(nullable = false)
    private String name;

    @Column(name = "file_url", nullable = false, length = 1024)
    private String fileUrl;

    @Column(name = "file_type", length = 100)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "uploaded_at")
    private Instant uploadedAt = Instant.now();
}

package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A cheque image the server itself stored, through {@code POST /api/v1/cheques/extract}.
 *
 * <p>The only paths bulk-attach will put on a cheque (audit C-F2): the retention
 * purge deletes a cheque's image path from the tenant's container, so a path taken
 * from the request body let staff schedule the deletion of any blob in the tenant.
 * {@code chequeId} records which cheque claimed the image; one image, one cheque.</p>
 */
@Entity
@Table(name = "cheque_image_uploads")
@Getter
@Setter
public class ChequeImageUpload extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "blob_path", nullable = false, length = 300)
    private String blobPath;

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt = Instant.now();

    @Column(name = "cheque_id")
    private UUID chequeId;
}

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

    /**
     * The upload this image was cut from (a crop of a multi-cheque photo, or a
     * rendered PDF page); null for an original. Changeset 163.
     */
    @Column(name = "source_upload_id")
    private UUID sourceUploadId;

    /**
     * Whether bulk-attach may put this image on a cheque. False for a PDF and for
     * a photo that holds several cheques — each cheque gets its own crop instead.
     */
    @Column(name = "attachable", nullable = false)
    private boolean attachable = true;

    /** 1-based PDF page a crop came from; null for a photo. */
    @Column(name = "page_number")
    private Integer pageNumber;

    /**
     * The payee the OCR read off this scan at extract time (changeset 162).
     * Bulk-attach checks this, not a value from the request, against the
     * organisation's valid payee names.
     */
    @Column(name = "extracted_payee_name", length = 300)
    private String extractedPayeeName;
}

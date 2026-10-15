package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ticket_attachments")
@Getter
@Setter
public class TicketAttachment extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private MaintenanceTicket ticket;

    @Column(name = "file_url", nullable = false, columnDefinition = "text")
    private String fileUrl;

    /**
     * The name of the file as uploaded ("Ejari contract.pdf"), path stripped (S17-04,
     * changeset 168). The download is saved under it. NULL on older rows.
     */
    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "file_type", length = 100)
    private String fileType;

    @Column(name = "file_size")
    private Long fileSize;

    /**
     * Who uploaded it (PR #342 review r3 M8): a renter deletes only their own
     * uploads. NULL on rows from before the column, whose uploader is unknown.
     */
    @Column(name = "uploaded_by")
    private UUID uploadedBy;

    @Column(name = "uploaded_at")
    private Instant uploadedAt = Instant.now();
}

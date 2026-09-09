package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.ScanDirection;
import com.datagami.rentaxis.domain.entity.enums.ScanResult;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "gate_pass_scans")
@Getter
@Setter
public class GatePassScan extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "gate_pass_id", nullable = false)
    private UUID gatePassId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private ScanDirection direction;

    // Nullable since 79-account-deletion-detach — see GatePass.createdByUserId.
    @Column(name = "scanned_by_user_id")
    private UUID scannedByUserId;

    @Column(name = "scanned_at", nullable = false)
    private Instant scannedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "result", nullable = false, length = 12)
    private ScanResult result;

    @Column(name = "rejection_reason", length = 120)
    private String rejectionReason;
}

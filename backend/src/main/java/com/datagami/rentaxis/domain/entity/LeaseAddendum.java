package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A mid-term charge on a posted lease, as a document: its own {@code ADD-yy/n}
 * number, the window it took effect from, and the {@code TCO} it posted. The
 * lines it charged carry {@code addendum_id}; the cheques that pay for it are
 * ordinary rows on the lease's register.
 *
 * <p>Immutable once written except {@code ejariNumber}, which is blank until the
 * variation is re-registered and is the whole of "Ejari pending".</p>
 */
@Entity
@Table(name = "lease_addenda")
@Getter
@Setter
public class LeaseAddendum extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", nullable = false)
    private Lease lease;

    @Column(name = "addendum_number", nullable = false, length = 40)
    private String addendumNumber;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "contract_date", nullable = false)
    private LocalDate contractDate;

    @Column(name = "ejari_number")
    private String ejariNumber;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "value", nullable = false, precision = 14, scale = 2)
    private BigDecimal value;

    /** Always set when the addendum posts; nullable only so a tenant purge can release it (changeset 92). */
    @Column(name = "tco_journal_id")
    private UUID tcoJournalId;

    @Column(name = "tco_entry_number", nullable = false, length = 40)
    private String tcoEntryNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private UUID createdBy;

    /** F14-32: CHARGE (adds lines and a TCO) or CREDIT (a mid-term reduction; a TCC). */
    @Column(name = "kind", nullable = false, length = 12)
    private String kind = KIND_CHARGE;

    /** For a CREDIT: CHEQUES (instalments handed back / replaced) or CREDIT (left on the receivable). */
    @Column(name = "excess", length = 12)
    private String excess;

    public static final String KIND_CHARGE = "CHARGE";
    public static final String KIND_CREDIT = "CREDIT";
}

package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Immutable after insert. The database forbids UPDATE of every column except
 * status/reversed_by_id and forbids DELETE (changeset 81). Never expose setters
 * through an API; the only mutation path is PostingService.reverse().
 */
@Entity
@Table(name = "journal_entries")
@Getter
@Setter
public class JournalEntry extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "entry_number", nullable = false, length = 40)
    private String entryNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "doc_type", nullable = false, length = 10)
    private JournalDocType docType;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(columnDefinition = "text")
    private String narration;

    @Column(name = "property_id") private UUID propertyId;
    @Column(name = "unit_id") private UUID unitId;
    @Column(name = "lease_id") private UUID leaseId;
    @Column(name = "renter_id") private UUID renterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", length = 30)
    private JournalSourceType sourceType;

    @Column(name = "source_id") private UUID sourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private JournalStatus status = JournalStatus.POSTED;

    @Column(name = "reversal_of_id") private UUID reversalOfId;
    @Column(name = "reversed_by_id") private UUID reversedById;
    @Column(name = "import_batch_id") private UUID importBatchId;
    @Column(name = "posted_by") private UUID postedBy;
    @Column(name = "posted_at") private Instant postedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
    @OrderBy("lineNo ASC")
    private List<JournalLine> lines = new ArrayList<>();

    public void addLine(JournalLine line) {
        line.setEntry(this);
        line.setLineNo(lines.size() + 1);
        lines.add(line);
    }
}

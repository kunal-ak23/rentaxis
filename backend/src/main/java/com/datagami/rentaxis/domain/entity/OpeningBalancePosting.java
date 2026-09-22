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
import java.time.LocalDate;
import java.util.UUID;

/**
 * At most one per tenant ({@code ux_opening_balance_postings_tenant}, changeset 88):
 * the books open once.
 *
 * <p>The row exists even before the journal does, and outlives it: posting fills
 * {@code journalId}, reversing clears it. That is what makes it the thing to take a
 * row lock on — "has this tenant opened its books, and is anybody opening them right
 * now?" is one question, answered by one row, whatever the journal is doing.</p>
 *
 * <p>It also records the opening journal's id <em>explicitly</em> rather than leaving
 * the reconciliation screen to find it by scanning for {@code doc_type = 'OB'}: that
 * scan would also match the journal's own reversal, and the derived column has to
 * subtract exactly the live opening entry and nothing else.</p>
 *
 * <p>No {@code @Version}: the write paths hold the row's pessimistic lock, and a
 * version column would only add a second, weaker answer to the same question — the
 * argument {@code ImportBatch} records for the same reason.</p>
 */
@Entity
@Table(name = "opening_balance_postings")
@Getter
@Setter
public class OpeningBalancePosting extends BaseTenantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    /** The cut-over date the books were opened as at — books start date minus one day. */
    @Column(name = "as_of", nullable = false) private LocalDate asOf;

    /** The live OB journal, or null when the books have never been opened or have been reversed. */
    @Column(name = "journal_id") private UUID journalId;

    @Column(name = "posted_by") private UUID postedBy;
    @Column(name = "posted_at") private Instant postedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
}

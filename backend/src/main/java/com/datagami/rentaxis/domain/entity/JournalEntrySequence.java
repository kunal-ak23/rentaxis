package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-tenant, per-doc-type, per-fiscal-year counter behind {@code EntryNumberService}.
 * Not a {@link BaseTenantEntity}: tenant_id is part of the primary key and the row is
 * always looked up explicitly under a row lock rather than through the tenant filter.
 */
@Entity
@Table(name = "journal_entry_sequences")
@IdClass(JournalEntrySequence.Key.class)
@Getter
@Setter
public class JournalEntrySequence {

    @Id @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Id @Column(name = "doc_type", nullable = false, length = 10) private String docType;
    @Id @Column(name = "fiscal_year", nullable = false) private int fiscalYear;

    @Column(name = "next_value", nullable = false)
    private long nextValue = 1;

    @Getter @Setter
    public static class Key implements Serializable {
        private UUID tenantId;
        private String docType;
        private int fiscalYear;
        public Key() {}
        public Key(UUID tenantId, String docType, int fiscalYear) { this.tenantId = tenantId; this.docType = docType; this.fiscalYear = fiscalYear; }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Key k)) return false;
            return Objects.equals(tenantId, k.tenantId) && Objects.equals(docType, k.docType) && fiscalYear == k.fiscalYear;
        }
        @Override public int hashCode() { return Objects.hash(tenantId, docType, fiscalYear); }
    }
}

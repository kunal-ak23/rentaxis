package com.datagami.rentaxis.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Which leases one cut-over import created.
 *
 * <p>Not tenant-filtered and carrying no {@code tenant_id}: it is reachable only
 * through its batch, which is — {@code ImportBatchService} loads the batch first
 * (and so fails with "not found" for another tenant's id) before it will read or
 * write any link. <b>Anyone adding a second reader of this repository has to do
 * the same</b>, or the link table becomes a way to ask which leases another
 * organisation imported.</p>
 *
 * <p>{@code lease_id} is deliberately not a foreign key — see changeset 88 for why
 * — so a link may outlive the lease it names. {@code LeaseReverter}'s contract
 * covers that case: an id that no longer resolves is ignored.</p>
 */
@Entity
@Table(name = "import_batch_leases")
@IdClass(ImportBatchLease.Key.class)
@Getter
@Setter
public class ImportBatchLease {

    @Id @Column(name = "batch_id", nullable = false) private UUID batchId;
    @Id @Column(name = "lease_id", nullable = false) private UUID leaseId;

    public ImportBatchLease() {}

    public ImportBatchLease(UUID batchId, UUID leaseId) {
        this.batchId = batchId;
        this.leaseId = leaseId;
    }

    public static class Key implements Serializable {
        private UUID batchId;
        private UUID leaseId;
        public Key() {}
        public Key(UUID batchId, UUID leaseId) { this.batchId = batchId; this.leaseId = leaseId; }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(batchId, k.batchId) && Objects.equals(leaseId, k.leaseId);
        }
        @Override public int hashCode() { return Objects.hash(batchId, leaseId); }
    }
}

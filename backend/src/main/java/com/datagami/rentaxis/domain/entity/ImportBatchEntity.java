package com.datagami.rentaxis.domain.entity;

import com.datagami.rentaxis.domain.entity.enums.ImportedEntityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * What ELSE one cut-over import created: the properties, buildings, units and
 * renters, beside the leases {@code ImportBatchLease} already records.
 *
 * <p>It exists for the undo. Reversing a batch returns its leases to DRAFT and
 * deliberately leaves everything else standing (R12), which means the corrected
 * workbook cannot simply be loaded again — "a property named X already exists" is
 * an import error by design, because merging two properties would join two
 * towers' ledgers. Task 11 closes that loop with a discard that deletes what the
 * batch made, and a discard can only be exact if the import wrote down what that
 * was. Reconstructing it afterwards from timestamps would delete rows somebody
 * else happened to create in the same minute.</p>
 *
 * <p>Not tenant-filtered and carrying no {@code tenant_id}, for the same reason as
 * {@code ImportBatchLease}: it is reachable only through its batch, which is.
 * <b>Anyone adding a second reader of this repository has to resolve the batch
 * first</b>, or the table becomes a way to ask what another organisation
 * imported.</p>
 *
 * <p>{@code entity_id} is deliberately not a foreign key — one column cannot point
 * at four tables, and a dangling link is harmless: a discard skips an id that no
 * longer resolves.</p>
 */
@Entity
@Table(name = "import_batch_entities")
@IdClass(ImportBatchEntity.Key.class)
@Getter
@Setter
public class ImportBatchEntity {

    @Id @Column(name = "batch_id", nullable = false) private UUID batchId;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "entity_type", nullable = false, length = 20)
    private ImportedEntityType entityType;

    @Id @Column(name = "entity_id", nullable = false) private UUID entityId;

    public ImportBatchEntity() {}

    public ImportBatchEntity(UUID batchId, ImportedEntityType entityType, UUID entityId) {
        this.batchId = batchId;
        this.entityType = entityType;
        this.entityId = entityId;
    }

    public static class Key implements Serializable {
        private UUID batchId;
        private ImportedEntityType entityType;
        private UUID entityId;

        public Key() {}

        public Key(UUID batchId, ImportedEntityType entityType, UUID entityId) {
            this.batchId = batchId;
            this.entityType = entityType;
            this.entityId = entityId;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return Objects.equals(batchId, k.batchId)
                    && entityType == k.entityType
                    && Objects.equals(entityId, k.entityId);
        }

        @Override public int hashCode() { return Objects.hash(batchId, entityType, entityId); }
    }
}

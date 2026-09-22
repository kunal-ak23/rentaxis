package com.datagami.rentaxis.domain.entity.enums;

/**
 * The kinds of row a cut-over import creates beside the leases, and therefore the
 * kinds a discard has to be able to delete again (spec §10.3).
 *
 * <p>Leases are not here: they have their own link table, written before this one
 * existed, and the reverse path already walks it.</p>
 */
public enum ImportedEntityType {
    PROPERTY,
    BUILDING,
    UNIT,
    RENTER
}

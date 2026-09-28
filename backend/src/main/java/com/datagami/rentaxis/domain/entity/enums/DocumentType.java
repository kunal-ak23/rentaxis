package com.datagami.rentaxis.domain.entity.enums;

public enum DocumentType {
    CONTRACT,
    /**
     * The signed contract re-issued at posting with the organisation's digital
     * stamp (Kunal, 2026-09-28). A separate document: the CONTRACT the renter
     * signed is never changed. At most one per lease (changeset 158).
     */
    EXECUTED_COPY,
    ADDENDUM,
    SIGNATURE_DOC,
    ID_PROOF,
    CHEQUE_SCANS
}

package com.datagami.rentaxis.domain.entity.enums;

/**
 * How a lease receipt arrives. The register is named for cheques because PDCs are
 * the UAE norm, but the same row models a cash or transfer receipt so that one
 * instalment schedule can mix instruments.
 *
 * <p>Only {@link #PDC} rows are physically banked, so only they appear in the
 * day's deposit run and only they carry the unique cheque-number constraint.</p>
 */
public enum ChequeMode {
    PDC,
    CASH,
    TRANSFER,
    ONLINE
}

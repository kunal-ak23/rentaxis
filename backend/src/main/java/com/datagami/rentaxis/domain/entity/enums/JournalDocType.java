package com.datagami.rentaxis.domain.entity.enums;

/** Journal number prefixes. Match PACT's vocabulary so the client's accountant recognises them (spec §3). */
public enum JournalDocType {
    TCO,  // tenancy contract posting
    TCR,  // contract reversal / unearned rent on termination
    PDR,  // PDC registered
    CRT,  // cheque cleared / receipt realised
    CBR,  // cheque bounced / returned
    CIL,  // monthly income recognition
    RCP,  // cash/online receipt
    STL,  // settlement
    PEN,  // penalty
    PISR, // purchase / service invoice
    BPV,  // bank / cash payment voucher
    OB,   // opening balance
    JV,   // manual journal
    VTP   // VAT tax point: deferred output VAT becomes due (spec 2026-09-24 §1)
}

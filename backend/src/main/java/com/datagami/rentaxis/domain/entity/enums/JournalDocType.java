package com.datagami.rentaxis.domain.entity.enums;

/** Journal number prefixes. Match PACT's vocabulary so the client's accountant recognises them (spec §3). */
public enum JournalDocType {
    PCN,  // F14-40: supplier credit note (our debit note)
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
    VTP,  // VAT tax point: deferred output VAT becomes due (spec 2026-09-24 §1)
    BPC,  // issued (supplier) cheque presented: Dr PDC_PAYABLE / Cr bank (finance-ops spec §2)
    BNK,  // a bank-only item booked from a statement line: charges, interest, unidentified receipt (§3)
    YEC   // year-end close: income and expense into Retained Earnings (spec 2026-09-24 §3)
}

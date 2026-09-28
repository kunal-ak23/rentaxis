package com.datagami.rentaxis.core.service.ledger;

import java.util.List;

/**
 * Break-it round 3 (money3): the one register of how far ahead each posting path
 * may be dated. Round 2 put the date rules on individual actions, and the paths it
 * did not name (cash receive, cheque cancel, settlement, the books start date) kept
 * accepting 2099. Every path that turns a user- or client-supplied date into a
 * journal date is classified here, and checks it through
 * {@link ManualPostingDates#require(PostingDatePath, java.time.LocalDate)}:
 *
 * <ul>
 *   <li>{@link DateClass#EVENT} — records something that has already happened
 *       (money received, a cheque cancelled, a tenant moved out): not after today
 *       in Asia/Dubai.</li>
 *   <li>{@link DateClass#PLANNED} — a manual or planned accounting entry: not more
 *       than a year after today.</li>
 *   <li>{@link DateClass#SCHEDULE} — derived by the system (a contract's schedule,
 *       today, the entry's own date, a period end): only the 2000–2099 range every
 *       journal meets in {@link PostingService}.</li>
 * </ul>
 *
 * <p>{@link #sites()} names the {@code Class#method} that calls {@code PostingService}
 * for the path. {@code PostingDatePathRegistryTest} scans the main sources and fails
 * when a {@code PostingService.post/reverse} call appears in a method no path names,
 * so a new posting path cannot be added without being classified here, and when an
 * EVENT or PLANNED path is never asked for.</p>
 */
public enum PostingDatePath {

    // ---- (a) events that have happened: not after today ------------------------------
    CHEQUE_DEPOSIT(DateClass.EVENT, "deposit",
            "A cheque cannot have been banked yet, and nothing was deposited."),
    CHEQUE_CLEAR(DateClass.EVENT, "clearing",
            "Funds cannot have cleared yet, and nothing was cleared.", "ChequeService#applyClearing"),
    CHEQUE_RECEIVE(DateClass.EVENT, "receipt",
            "Money cannot have been received yet, and nothing was received.", "ChequeService#applyClearing"),
    CHEQUE_BOUNCE(DateClass.EVENT, "bounce",
            "A cheque cannot have been returned yet, and nothing was bounced.", "ChequeService#bounce"),
    CHEQUE_CANCEL(DateClass.EVENT, "cancellation",
            "Date the cancellation today or earlier; nothing was cancelled.", "ChequeService#reversePdr"),
    CASH_RECEIPT(DateClass.EVENT, "receipt",
            "Money cannot have been received yet, and nothing was received.",
            "LeaseChequeRegistrar#post", "ChequeService#applyClearing"),
    BAD_DEBT_WRITE_OFF(DateClass.EVENT, "write-off",
            "A debt is written off when the decision is made; date it today or earlier.",
            "BadDebtService#approve", "ChequeService#reversePdr"),
    BAD_DEBT_RECOVERY(DateClass.EVENT, "recovery",
            "Money cannot have been recovered yet, and nothing was recorded.", "BadDebtService#recover"),
    LEASE_ASSIGNMENT(DateClass.EVENT, "assignment",
            "An assignment takes effect once it has happened; nothing was assigned.", "LeaseAssignmentService#post"),
    SETTLEMENT(DateClass.EVENT, "settlement",
            "A settlement records a move-out that has happened; date it today or earlier. Nothing was posted.",
            "SettlementService#postSettlement"),
    ISSUED_CHEQUE_PRESENT(DateClass.EVENT, "presentation",
            "The bank cannot have paid the cheque yet, and nothing was recorded.", "IssuedChequeService#present"),
    ISSUED_CHEQUE_RETURN(DateClass.EVENT, "return",
            "The cheque cannot have been returned yet, and nothing was recorded.", "IssuedChequeService#unpresent"),
    ISSUED_CHEQUE_CANCEL(DateClass.EVENT, "cancellation",
            "Date the cancellation today or earlier; nothing was cancelled.", "IssuedChequeService#cancel"),
    BANK_STATEMENT_LINE(DateClass.EVENT, "statement line",
            "Nothing can be booked from a line dated in the future.", "BankStatementPostingService#post"),
    PERIOD_LOCK(DateClass.EVENT, "period lock",
            "Only a period that has already ended can be locked; nothing was locked."),

    // ---- (b) manual / planned entries: not more than a year ahead --------------------
    MANUAL_JOURNAL(DateClass.PLANNED, "A journal voucher", null, "JournalService#postManual"),
    JOURNAL_REVERSAL(DateClass.PLANNED, "A reversal", null, "JournalService#reverse"),
    VOUCHER(DateClass.PLANNED, "A voucher", null, "VoucherService#post"),
    VOUCHER_REVERSAL(DateClass.PLANNED, "A reversal", null,
            "VoucherService#reversePayment", "VoucherService#voidVoucher", "VoucherService#amend"),
    PAYMENT_RUN(DateClass.PLANNED, "A payment run", null),
    CHEQUE_ROW(DateClass.PLANNED, "A cheque's posting date", null, "LeaseChequeRegistrar#post"),
    PENALTY_CHARGE(DateClass.PLANNED, "A penalty charge", null, "PenaltyAssessmentService#approve"),
    PENALTY_REVERSAL(DateClass.PLANNED, "A penalty reversal", null, "PenaltyAssessmentService#reverse"),
    BAD_DEBT_REVERSAL(DateClass.PLANNED, "A write-off reversal", null, "BadDebtService#reverse"),
    LEASE_ADDENDUM(DateClass.PLANNED, "An addendum", null,
            "LeasePostingService#postTco", "LeaseReductionService#reduce", "LeaseChequeRegistrar#post",
            "ChequeService#reversePdr"),
    LEASE_TERMINATION(DateClass.PLANNED, "A termination", null,
            "LeaseTerminationService#postUnearnedReversal", "ChequeService#reversePdr"),
    LEASE_TRANSFER(DateClass.PLANNED, "A unit transfer", null,
            "LeaseTransferService#completeForPosting", "LeaseTerminationService#postUnearnedReversal",
            "ChequeService#reversePdr"),
    BANK_MATCH_UNDO(DateClass.PLANNED, "A reversal", null, "BankMatchService#undo"),

    // ---- (c) system-derived dates: the 2000–2099 range only --------------------------
    LEASE_POST(DateClass.SCHEDULE, "contract date", null,
            "LeasePostingService#postTco", "LeaseChequeRegistrar#post", "DepositCarryForward#carry"),
    LEASE_AMENDMENT(DateClass.SCHEDULE, "today", null, "LeasePostingService#amendLines"),
    ONLINE_PAYMENT(DateClass.SCHEDULE, "gateway capture date", null, "ChequeService#applyClearing"),
    IMPORT(DateClass.SCHEDULE, "import file (checked row by row by the import validator)", null,
            "ContractImportLeasePoster#postOpeningPosition", "ImportBatchService#reverse",
            "ChequeService#applyClearing", "ChequeService#bounce"),
    OPENING_BALANCE(DateClass.SCHEDULE, "books start − 1 / the entry's own date", null,
            "OpeningBalanceService#postFresh", "OpeningBalanceService#repost", "OpeningBalanceService#reverse"),
    YEAR_END(DateClass.SCHEDULE, "fiscal period end", null, "YearEndCloseService#close", "YearEndCloseService#reopen"),
    INTER_PROPERTY_REPAIR(DateClass.SCHEDULE, "the entry's own date", null, "InterPropertyRepairService#repair"),
    RECOGNITION(DateClass.SCHEDULE, "recognition schedule", null,
            "RecognitionPoster#postJoining", "RecognitionService#postCatchUp", "RecognitionService#acquireFrom",
            "RecognitionService#retire", "RecognitionService#recut"),
    VAT_TAX_POINT(DateClass.SCHEDULE, "tax point schedule", null, "VatTaxPointPoster#postJoining");

    /** The three rules (brief r3F). */
    public enum DateClass { EVENT, PLANNED, SCHEDULE }

    private final DateClass dateClass;
    private final String label;
    private final String why;
    private final List<String> sites;

    PostingDatePath(DateClass dateClass, String label, String why, String... sites) {
        this.dateClass = dateClass;
        this.label = label;
        this.why = why;
        this.sites = List.of(sites);
    }

    public DateClass dateClass() {
        return dateClass;
    }

    /**
     * EVENT: the noun in "The {label} date … is in the future" ("receipt");
     * PLANNED: the subject of "{label} cannot be dated more than a year ahead" ("A voucher");
     * SCHEDULE: where the date comes from (documentation only).
     */
    public String label() {
        return label;
    }

    /** EVENT only: what the user should know when refused. */
    public String why() {
        return why;
    }

    /** The {@code Class#method}s that call {@code PostingService} on this path's date. */
    public List<String> sites() {
        return sites;
    }
}

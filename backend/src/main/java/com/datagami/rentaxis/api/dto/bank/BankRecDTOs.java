package com.datagami.rentaxis.api.dto.bank;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response shapes of Finance → Bank reconciliation (finance-ops spec §3). */
public final class BankRecDTOs {

    private BankRecDTOs() { }

    public record Leaf(UUID id, String code, String name, UUID propertyId) { }

    /** A bank account as the reconciliation list shows it. {@code needsLeaf}: no leaf yet, so nothing can be matched. */
    public record BankAccountRow(UUID id, String bankName, String accountNumber, String iban, String currency,
                                 String bankTrn, boolean active, List<Leaf> leaves, boolean needsLeaf,
                                 Instant lastImportAt, String lastImportFile, LocalDate lastLineDate,
                                 long unmatchedLines, boolean hasProfile, LocalDate reconciledThrough,
                                 LocalDate recStartDate, UUID draftReconciliationId,
                                 UUID latestFinalizedReconciliationId) { }

    public record LedgerSetInput(@NotNull List<UUID> accountIds) { }

    public record BankTrnInput(@Size(max = 20) String bankTrn) { }

    public record Profile(String fileKind, String sheetName, Integer headerRow, Integer firstDataRow,
                          String csvDelimiter, List<String> dateFormats, Map<String, String> columns,
                          String amountMode, String chequeNoPattern, Integer matchWindowDays, String decimalSeparator) {
        public Profile(String fileKind, String sheetName, Integer headerRow, Integer firstDataRow, String csvDelimiter,
                       List<String> dateFormats, Map<String, String> columns, String amountMode, String chequeNoPattern,
                       Integer matchWindowDays) {
            this(fileKind, sheetName, headerRow, firstDataRow, csvDelimiter, dateFormats, columns, amountMode,
                    chequeNoPattern, matchWindowDays, null);
        }
    }

    public record PreviewRow(int fileRow, LocalDate txnDate, LocalDate valueDate, String description,
                             String reference, String chequeNo, BigDecimal amount, BigDecimal balance,
                             boolean duplicate) { }

    /**
     * What an import (or its dry run) came to. {@code status}: PROFILE_REQUIRED (with
     * the first rows as a grid), INVALID (row errors or a balance break; nothing was
     * written), PREVIEW (a dry run) or IMPORTED.
     */
    public record ImportResult(String status, String reason, List<List<String>> grid, List<String> sheetNames,
                               String sheetName, String fileKind, List<String> missingColumns, List<String> errors,
                               List<String> warnings, int linesRead, int linesNew, int linesDuplicate,
                               LocalDate firstDate, LocalDate lastDate, BigDecimal openingBalance,
                               BigDecimal closingBalance, String order, List<PreviewRow> rows, UUID importId) { }

    public record ImportRow(UUID id, String fileName, int linesRead, int linesNew, int linesDuplicate,
                            LocalDate firstDate, LocalDate lastDate, BigDecimal openingBalance,
                            BigDecimal closingBalance, List<String> warnings, Instant importedAt,
                            boolean deletable) { }

    public record StatementLine(UUID id, long seq, LocalDate txnDate, LocalDate valueDate, String description,
                                String reference, String chequeNo, BigDecimal amount, BigDecimal runningBalance,
                                UUID matchId, String matchStatus) { }

    public record BookItem(UUID journalLineId, UUID entryId, String entryNumber, String docType, LocalDate entryDate,
                           String narration, UUID accountId, String accountName, String counterAccount,
                           BigDecimal amount, String chequeNo, UUID matchId, String matchStatus,
                           UUID reversalOfId, UUID reversedById) { }

    /**
     * {@code createdDocTypes}: for a CREATED match, the documents it booked.
     * {@code reverseOnDefault}: the date "undo and reverse" would use — the entry's
     * own date while its period is open, else today — or null when the match's
     * entries cannot be reversed from here (a cheque's CRT or CBR).
     */
    public record Match(UUID id, String method, String status, String confidence, List<UUID> statementLineIds,
                        List<UUID> journalLineIds, BigDecimal statementTotal, BigDecimal bookTotal,
                        Instant createdAt, Instant confirmedAt, List<String> createdDocTypes,
                        LocalDate reverseOnDefault, List<UUID> openingItemIds) { }

    /**
     * {@code openingItems}: the first reconciliation's outstanding items (spec §4),
     * matchable like book items. {@code reconciledThrough}: the lock; matches on
     * lines up to it are frozen.
     */
    public record Workspace(UUID bankAccountId, List<Leaf> leaves, boolean needsLeaf, List<StatementLine> statementLines,
                            List<BookItem> bookItems, List<Match> matches, List<OpeningItem> openingItems,
                            LocalDate reconciledThrough) { }

    // ------------------------------------------------------------------ §4 reconciliation

    /** An item outstanding at the first reconciliation's start. {@code amount}: + in transit, − unpresented. */
    public record OpeningItem(UUID id, UUID bankAccountId, LocalDate itemDate, String description, String reference,
                              String chequeNo, BigDecimal amount, UUID matchId, String matchStatus) { }

    public record OpeningItemInput(@NotNull LocalDate itemDate, @NotBlank @Size(max = 500) String description,
                                   @Size(max = 200) String reference, @Size(max = 50) String chequeNo,
                                   @NotNull BigDecimal amount) { }

    /**
     * {@code periodFrom} and {@code statementOpening}: the first reconciliation
     * only; later ones start where the previous finalized one ended.
     * {@code statementClosing}: typed from the paper statement when the lines
     * carry no running balance.
     */
    public record ReconciliationInput(LocalDate periodFrom, @NotNull LocalDate periodTo, BigDecimal statementOpening,
                                      BigDecimal statementClosing) { }

    public record ReopenInput(@NotBlank @Size(max = 1000) String reason) { }

    /** A row of the history list: stored figures (null while DRAFT). */
    public record ReconciliationRow(UUID id, LocalDate periodFrom, LocalDate periodTo, String status,
                                    BigDecimal statementClosing, BigDecimal bookBalance, BigDecimal difference,
                                    Instant createdAt, Instant finalizedAt, String finalizedByName,
                                    Instant reopenedAt, String reopenedByName, String reopenReason) { }

    /**
     * One outstanding or unrecorded item. {@code kind}: JOURNAL (a journal line),
     * OPENING (an opening item) or STATEMENT (a statement line).
     * {@code withoutEvidence}: a cheque cleared by hand that no statement line
     * shows yet (the product owner's default lets property managers clear; the
     * reconciliation exposes it).
     */
    public record RecItem(String kind, UUID id, LocalDate date, String document, String narration, String chequeNo,
                          BigDecimal amount, boolean withoutEvidence) { }

    /** A finalize precondition. {@code code}: CONTINUITY, UNRECORDED, DIFFERENCE, SUGGESTED, NOT_FUTURE, OPENING_ITEMS, CHAIN. */
    public record Check(String code, boolean ok, String message) { }

    /**
     * The reconciliation statement (spec §4 "Goal"): live while DRAFT, the
     * snapshot taken at finalize afterwards.
     *
     * <pre>
     * adjustedBank = statementClosing + depositsInTransit − unpresentedPayments − bookedAfterPeriod
     * adjustedBook = bookBalance + unrecordedCredits − unrecordedDebits
     * difference   = adjustedBank − adjustedBook
     * </pre>
     */
    public record Reconciliation(UUID id, UUID bankAccountId, String bankLabel, String bankName, String ibanMasked,
                                 List<Leaf> leaves, LocalDate periodFrom, LocalDate periodTo, String status,
                                 boolean first, BigDecimal statementOpening, BigDecimal statementClosing,
                                 boolean closingTyped, BigDecimal statementMovement, BigDecimal bookBalance,
                                 BigDecimal bookBalanceAtStart, BigDecimal openingItemsTotal,
                                 BigDecimal depositsInTransit, BigDecimal unpresentedPayments,
                                 BigDecimal bookedAfterPeriod, BigDecimal unrecordedCredits,
                                 BigDecimal unrecordedDebits, BigDecimal adjustedBank, BigDecimal adjustedBook,
                                 BigDecimal difference, List<RecItem> depositsInTransitItems,
                                 List<RecItem> unpresentedItems, List<RecItem> bookedAfterItems,
                                 List<RecItem> unrecordedItems, int withoutEvidenceCount,
                                 Map<String, Integer> matchedByMethod, List<Check> checks, boolean canFinalize,
                                 Instant preparedAt, String preparedByName, Instant finalizedAt,
                                 String finalizedByName, Instant reopenedAt, String reopenedByName,
                                 String reopenReason) { }

    public record AutoMatchResult(int proposed, Map<String, Integer> byMethod) { }

    public record ManualMatchInput(List<UUID> statementLineIds, List<UUID> journalLineIds, List<UUID> openingItemIds) { }

    public record ClearChequesInput(@NotNull List<UUID> statementLineIds, @NotNull List<UUID> chequeIds) { }

    public record ReceiveInput(@NotNull UUID statementLineId, @NotNull UUID chequeId, Boolean fromSuspense,
                               UUID bankLeafId) { }

    public record BounceInput(@NotNull UUID statementLineId, @NotNull UUID chequeId, String reason) { }

    public record PresentInput(@NotNull UUID statementLineId, @NotNull UUID issuedChequeId) { }

    /** {@code kind}: CHARGE, INTEREST, SUSPENSE or OTHER. {@code bankLeafId} picks the leaf when the set has several. */
    public record PostLinesInput(@NotNull List<UUID> statementLineIds, @NotNull String kind, Boolean vatIncluded,
                                 UUID accountId, UUID propertyId, UUID bankLeafId, Boolean shared,
                                 @Size(max = 500) String narration, BigDecimal net, BigDecimal vat) {
        /** Without the stated split (a single line, or a kind that has none). */
        public PostLinesInput(List<UUID> statementLineIds, String kind, Boolean vatIncluded, UUID accountId,
                              UUID propertyId, UUID bankLeafId, Boolean shared, String narration) {
            this(statementLineIds, kind, vatIncluded, accountId, propertyId, bankLeafId, shared, narration, null, null);
        }
    }

    public record ActionResult(UUID matchId, List<UUID> journalEntryIds, List<String> entryNumbers) { }

    public record Candidate(UUID id, String kind, String label, BigDecimal amount, LocalDate date, String chequeNo,
                            UUID propertyId, String status, boolean preselected) { }

    /** The documents a statement line's actions would use, and the charge split it would post. */
    public record LineCandidates(UUID statementLineId, List<Candidate> clear, List<Candidate> receive,
                                 List<Candidate> bounce, List<Candidate> present, BigDecimal suspenseBalance,
                                 boolean bankTrnSet, List<Leaf> leaves) { }

    /** The register's Bank column, per cheque: CONFIRMED (with the statement date), NOT_ON_STATEMENT, or CASH. */
    public record ChequeEvidence(UUID chequeId, String state, LocalDate statementDate) { }
}

package com.datagami.rentaxis.api.dto.bank;

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
                                 long unmatchedLines, boolean hasProfile) { }

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
                               BigDecimal closingBalance, String order, List<PreviewRow> rows, UUID importId,
                               /* F14-04: a CSV's delimiter as detected from the file (null for .xlsx). */
                               String csvDelimiter,
                               /* F14-09: {@code reason} as a translatable key and its values (null when no reason). */
                               String reasonCode, Map<String, Object> reasonArgs) { }

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
                        LocalDate reverseOnDefault) { }

    public record Workspace(UUID bankAccountId, List<Leaf> leaves, boolean needsLeaf, List<StatementLine> statementLines,
                            List<BookItem> bookItems, List<Match> matches) { }

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
                                 boolean bankTrnSet, List<Leaf> leaves, List<Refused> refused) { }

    /**
     * A document that fits the line by amount but that the action would refuse, and
     * why (F14-06: an issued cheque presented before its date; F14-02: a receipt row
     * put on the books after the line's date). {@code kind} is the action
     * ("present", "receive"); {@code code}/{@code args} translate {@code reason}.
     */
    public record Refused(UUID id, String kind, String label, BigDecimal amount, LocalDate date, String code,
                          java.util.Map<String, Object> args, String reason) { }

    /** The register's Bank column, per cheque: CONFIRMED (with the statement date), NOT_ON_STATEMENT, or CASH. */
    public record ChequeEvidence(UUID chequeId, String state, LocalDate statementDate) { }
}

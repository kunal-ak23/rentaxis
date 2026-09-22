package com.datagami.rentaxis.api.dto;

import lombok.Data;

/**
 * One thing wrong with an uploaded workbook, addressed so the person who typed it
 * can go straight to the cell.
 *
 * <p><b>{@code row} is a boxed {@link Integer} and may be null.</b> Not every
 * complaint has a row: "this file is not an .xlsx", "sheet 'Contracts' is
 * missing", "this property has no account for PDC_RECEIVABLE" are statements about
 * the file or the organisation, and they used to be reported as row {@code 0} — a
 * row number that does not exist, rendered as a row number. Null says "no row"
 * honestly, and the screen already treats the two the same way.</p>
 *
 * <p>Rows that do have one are <b>1-based</b>, counted as the spreadsheet counts
 * them, so row 2 is the first row under the header.</p>
 *
 * <p><b>{@code severity}</b> makes the validator's existing split — it has always
 * kept two lists, {@code errors} and {@code warnings} — readable on the row itself
 * rather than only from which array it arrived in. An ERROR stops the import; a
 * WARNING is a fact the accountant has to know and can proceed past ("this property
 * has no account mapped for X", "this cheque's date falls outside the contract").
 * The field defaults to ERROR, which is what an older stored row without it means,
 * and {@code PortfolioImportController.mapToResult} stamps every row it parses from
 * whichever list held it, so a row saved before this field existed still reads
 * correctly.</p>
 */
@Data
@lombok.NoArgsConstructor
public class ImportErrorDTO {

    /** ERROR stops the import; WARNING is reported beside a result that went through. */
    public enum Severity { ERROR, WARNING }

    private String sheet;
    private Integer row;
    private String field;
    private String message;

    /**
     * ERROR by default, deliberately: a complaint nobody classified is the kind that
     * should stop somebody, and a row stored before this field existed means exactly
     * that.
     */
    private Severity severity = Severity.ERROR;

    /**
     * The four-argument shape every producer already uses. Kept as its own
     * constructor rather than as a generated all-args one so that adding
     * {@code severity} did not silently break ~40 call sites.
     */
    public ImportErrorDTO(String sheet, Integer row, String field, String message) {
        this(sheet, row, field, message, Severity.ERROR);
    }

    public ImportErrorDTO(String sheet, Integer row, String field, String message, Severity severity) {
        this.sheet = sheet;
        this.row = row;
        this.field = field;
        this.message = message;
        this.severity = severity == null ? Severity.ERROR : severity;
    }

    /**
     * A complaint about the file or the organisation rather than about a cell.
     *
     * <p>A named factory rather than {@code new ImportErrorDTO(sheet, null, …)} so
     * that "there is no row here" reads as a decision at the call site instead of
     * looking like a value somebody forgot to fill in.</p>
     */
    public static ImportErrorDTO file(String sheet, String field, String message) {
        return new ImportErrorDTO(sheet, null, field, message);
    }
}

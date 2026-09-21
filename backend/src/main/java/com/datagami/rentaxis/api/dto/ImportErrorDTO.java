package com.datagami.rentaxis.api.dto;

import lombok.AllArgsConstructor;
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
 */
@Data
@AllArgsConstructor
@lombok.NoArgsConstructor
public class ImportErrorDTO {
    private String sheet;
    private Integer row;
    private String field;
    private String message;

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

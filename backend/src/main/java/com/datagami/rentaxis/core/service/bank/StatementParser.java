package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.domain.entity.BankStatementProfile;

/**
 * Reads a statement file into a {@link StatementGrid} (finance-ops spec §3). CSV
 * and XLSX today; MT940 and camt.053 would slot in behind the same interface.
 */
public interface StatementParser {

    /** The most rows a statement may have (spec §3 import step 1), plus room for headers and footers. */
    int MAX_ROWS = 20_000;
    int MAX_GRID_ROWS = MAX_ROWS + 200;
    /** The widest statement row read (PR #353 review P1-1); a cell further right refuses the file. */
    int MAX_COLS = 64;

    BankStatementProfile.FileKind kind();

    /** {@code sheetName} and {@code delimiter} may be null: the first sheet, a detected delimiter. */
    StatementGrid read(byte[] bytes, String sheetName, String delimiter);
}

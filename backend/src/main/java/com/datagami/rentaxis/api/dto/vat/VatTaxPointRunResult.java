package com.datagami.rentaxis.api.dto.vat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What a "run tax points to date" did, or would do (spec 2026-09-24 §1). Same
 * shape and the same meaning of each count as the recognition run's result: a
 * preview posts nothing, a locked date is skipped rather than failed.
 */
public record VatTaxPointRunResult(boolean preview,
                                   int posted,
                                   int wouldPost,
                                   BigDecimal vatAmount,
                                   List<VatTaxPointDTO> points,
                                   int skippedLocked,
                                   LocalDate booksLockedThrough,
                                   List<String> errors) {
}

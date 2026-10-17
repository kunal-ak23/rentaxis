package com.datagami.rentaxis.api.dto.cheque;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /cheques/post-dated/totals}: what the paged post-dated book needs beyond one page —
 * the window's count and total, each maturity date's full count and subtotal (a day can span
 * two pages), and {@code amountBefore}, the running total of every row ahead of the page asked
 * about.
 */
public record PostDatedTotalsDTO(long count, BigDecimal total, List<Day> days, BigDecimal amountBefore) {

    public record Day(LocalDate date, long count, BigDecimal amount) {
    }
}

package com.datagami.rentaxis.api.dto.cutover;

import java.math.BigDecimal;

/**
 * One hand-typed cell of the opening-balance grid.
 *
 * <p>Both fields are boxed and both are optional: {@code {"debit": 5000}} means
 * "debit 5,000, credit nothing", and a body with neither clears the row. Primitives
 * would have made a missing field a 400 under
 * {@code FAIL_ON_NULL_FOR_PRIMITIVES}, which is the wrong answer to the ordinary
 * case of filling in one column.</p>
 */
public record ManualOpeningBalanceDTO(BigDecimal debit, BigDecimal credit) {}

package com.datagami.rentaxis.core.service.report;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One (effective property, account) sum of posted INCOME / EXPENSE lines in a
 * period — the unit the P&L is folded from (finance-ops spec §1, "the owner
 * seam"). A later owner layer folds these over its own ownership shares without
 * the P&L changing. {@code propertyId} null is Unassigned; {@code reportLine}
 * null means the account is its own row.
 */
public record PnlCell(UUID propertyId, String reportLine, UUID accountId, BigDecimal debit, BigDecimal credit) { }

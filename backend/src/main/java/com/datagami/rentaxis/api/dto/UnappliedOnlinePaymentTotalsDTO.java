package com.datagami.rentaxis.api.dto;

import java.math.BigDecimal;

/**
 * The dashboard tile behind {@link UnappliedOnlinePaymentDTO}: how many refunds are
 * outstanding and what they add up to.
 *
 * <p>Its own endpoint rather than a field on the paged list, because the tile is
 * rendered on a screen that is not the list and asking for page 0 of a list to read
 * two numbers off it is a query nobody would write twice.</p>
 *
 * <p>{@code totalAmount} is zero, never null, so the tile has nothing to guard.
 * It sums across currencies — the gateway settles one tenant in one currency
 * today, and if that ever stops being true this becomes a breakdown.</p>
 */
public record UnappliedOnlinePaymentTotalsDTO(long count, BigDecimal totalAmount) {
}

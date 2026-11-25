package com.datagami.rentaxis.api.dto.rentalindex;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The renewal queue's "Max increase" for one lease (spec 2026-11-25 §8): the rental index cap on today's rent for a
 * 12-month successor with the rent unchanged. {@code status} is OK, NO_INDEX, NOT_DUBAI, NO_EMIRATE or
 * NOT_RESIDENTIAL; the figures are null unless OK and the lease has a contract rent.
 *
 * @param allowedPercent the increase the index allows, as a whole percent
 * @param capRent        the most the annual rent may be
 * @param baseRent       the annual rent being renewed
 */
public record LeaseCapDTO(UUID leaseId, String status, Integer allowedPercent, BigDecimal capRent,
                          BigDecimal baseRent) {
}

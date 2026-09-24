package com.datagami.rentaxis.api.dto.settlement;

import com.datagami.rentaxis.api.dto.DeductionAttachmentDTO;
import com.datagami.rentaxis.domain.entity.enums.DeductionCategory;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One charge against the renter's deposit, and the income account the {@code STL}
 * will credit for it.
 *
 * @param accountId   the line's own account when it names one, else the leaf the
 *                    category resolves to for this property. Null only for a
 *                    legacy line whose category is no longer a valid one — the
 *                    statement still renders it, finalise refuses it.
 * @param accountName that account's name, so the screen can show where the money
 *                    is going without a second call.
 */
public record DeductionLineDTO(
        UUID id,
        DeductionCategory category,
        String description,
        BigDecimal amount,
        UUID accountId,
        String accountName,
        boolean autoCalculated,
        List<DeductionAttachmentDTO> attachments,
        /* F14-37: 5 % output VAT on a taxable recharge of a VAT lease; zero otherwise. */
        BigDecimal vatAmount,
        /* F14-61: amount + vatAmount — what this line actually takes off the refund. */
        BigDecimal grossAmount) {
}

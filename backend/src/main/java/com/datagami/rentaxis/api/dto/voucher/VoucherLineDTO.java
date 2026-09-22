package com.datagami.rentaxis.api.dto.voucher;

import com.datagami.rentaxis.domain.entity.VoucherLine;

import java.math.BigDecimal;
import java.util.UUID;

public record VoucherLineDTO(int lineNo, UUID accountId, String accountCode, String accountName,
                             String description, BigDecimal amount, BigDecimal vatRate,
                             BigDecimal vatAmount, UUID propertyId, UUID unitId) {

    public static VoucherLineDTO of(VoucherLine l) {
        return new VoucherLineDTO(l.getLineNo(), l.getAccount().getId(), l.getAccount().getCode(),
                l.getAccount().getName(), l.getDescription(), l.getAmount(), l.getVatRate(),
                l.getVatAmount(), l.getPropertyId(), l.getUnitId());
    }
}

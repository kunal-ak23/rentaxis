package com.datagami.rentaxis.api.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class VatReturnDTO {
    private String period;
    private BigDecimal totalOutputVat = BigDecimal.ZERO;
    private BigDecimal totalInputVat = BigDecimal.ZERO;
    private BigDecimal netVatPayable = BigDecimal.ZERO;
    private BigDecimal totalTaxableSales = BigDecimal.ZERO;
    private BigDecimal totalTaxablePurchases = BigDecimal.ZERO;
    private List<VatLine> salesLines;
    private List<VatLine> purchaseLines;

    @Getter
    @Setter
    public static class VatLine {
        private String description;
        private BigDecimal taxableAmount;
        private BigDecimal vatAmount;
    }
}

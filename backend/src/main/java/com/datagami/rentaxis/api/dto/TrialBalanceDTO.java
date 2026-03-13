package com.datagami.rentaxis.api.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class TrialBalanceDTO {
    private String dateRange;
    private List<TrialBalanceLine> lines;
    private BigDecimal totalDebit = BigDecimal.ZERO;
    private BigDecimal totalCredit = BigDecimal.ZERO;

    @Getter
    @Setter
    public static class TrialBalanceLine {
        private String accountCode;
        private String accountName;
        private String accountType;
        private BigDecimal debit = BigDecimal.ZERO;
        private BigDecimal credit = BigDecimal.ZERO;
        private BigDecimal balance = BigDecimal.ZERO;
    }
}

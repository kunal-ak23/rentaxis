package com.datagami.rentaxis.api.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class PaymentPreviewDTO {
    private List<PaymentPreviewLine> lines;
    private BigDecimal totalAmount;
    private int totalPayments;
    private int dueDayOfMonth;
    private String defaultPaymentMethod;

    @Getter
    @Setter
    public static class PaymentPreviewLine {
        private int installmentNumber;
        private LocalDate dueDate;
        private LocalDate periodStart;
        private LocalDate periodEnd;
        private BigDecimal amount;
        private boolean proRata;
    }
}

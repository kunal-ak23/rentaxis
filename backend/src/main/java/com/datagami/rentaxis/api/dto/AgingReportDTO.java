package com.datagami.rentaxis.api.dto;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.List;

@Getter
@Setter
public class AgingReportDTO {
    private List<AgingBucket> buckets;
    private BigDecimal totalOutstanding = BigDecimal.ZERO;

    @Getter
    @Setter
    public static class AgingBucket {
        private String label;
        private BigDecimal amount = BigDecimal.ZERO;
        private int count;
        private List<AgingDetail> details;
    }

    @Getter
    @Setter
    public static class AgingDetail {
        private String renterName;
        private String propertyName;
        private String unitNumber;
        private BigDecimal amount;
        private int daysOverdue;
        private String dueDate;
    }
}

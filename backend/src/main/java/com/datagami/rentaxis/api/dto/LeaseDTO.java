package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class LeaseDTO {
    private UUID id;
    private UUID unitId;
    private UUID renterId;
    private String unitIdentifier; // Added for convenience
    private String renterName; // Added for convenience
    private LocalDate startDate;
    private LocalDate endDate;
    private LeaseStatus status;
    private BigDecimal rentAmount;
    private BigDecimal depositAmount;
    private String ejariNumber;
    private Integer paymentTerms;
    private UUID propertyId;
    private String propertyName;
    private boolean hasContract;
}

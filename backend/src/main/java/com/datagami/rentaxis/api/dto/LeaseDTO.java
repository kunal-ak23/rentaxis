package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.PaymentMethod;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Data
public class LeaseDTO {
    private UUID id;
    private UUID unitId;
    private UUID renterId;
    private String unitIdentifier;
    private String renterName;
    private LocalDate startDate;
    private LocalDate endDate;
    private LeaseStatus status;
    private BigDecimal rentAmount;
    private BigDecimal depositAmount;
    private String ejariNumber;
    private Integer paymentTerms;
    private PaymentMethod paymentMethod;
    private PaymentMethod depositPaymentMethod;
    private String paymentReferenceNumber;
    private BigDecimal monthlyRent;
    private UUID propertyId;
    private String propertyName;
    private boolean hasContract;
}

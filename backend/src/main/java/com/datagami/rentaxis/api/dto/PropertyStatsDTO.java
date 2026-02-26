package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.Property;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class PropertyStatsDTO {
    private Property property;
    private long propertyCount; // Number of units in this project
    private BigDecimal revenueAtCapacity;
    private BigDecimal actualRevenue;
    private long vacancies;
    private java.util.List<com.datagami.rentaxis.domain.entity.User> assignedManagers;
}

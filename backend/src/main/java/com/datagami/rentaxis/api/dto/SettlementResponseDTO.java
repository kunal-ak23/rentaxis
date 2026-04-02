package com.datagami.rentaxis.api.dto;

import com.datagami.rentaxis.domain.entity.LeaseSettlement;
import com.datagami.rentaxis.domain.entity.LeaseSettlementDeduction;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SettlementResponseDTO {
    private LeaseSettlement settlement;
    private List<LeaseSettlementDeduction> deductions;
}

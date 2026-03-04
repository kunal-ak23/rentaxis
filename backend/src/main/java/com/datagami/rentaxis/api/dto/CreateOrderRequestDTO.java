package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class CreateOrderRequestDTO {
    private UUID paymentScheduleId;
}

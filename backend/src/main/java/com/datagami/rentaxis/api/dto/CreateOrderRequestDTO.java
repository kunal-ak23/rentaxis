package com.datagami.rentaxis.api.dto;

import lombok.Data;

import java.util.UUID;

@Data
public class CreateOrderRequestDTO {
    /** The register row being paid — a {@code cheques} id (spec §9.3). */
    private UUID chequeId;
}

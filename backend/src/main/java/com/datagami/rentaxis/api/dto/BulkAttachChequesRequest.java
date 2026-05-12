package com.datagami.rentaxis.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BulkAttachChequesRequest {
    @NotEmpty
    @Valid
    private List<BulkAttachChequeItem> items;
}

package com.datagami.rentaxis.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@lombok.NoArgsConstructor
public class ImportErrorDTO {
    private String sheet;
    private int row;
    private String field;
    private String message;
}

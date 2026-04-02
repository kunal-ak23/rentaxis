package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WaivePenaltyDTO {

    @NotBlank(message = "Reason is required")
    private String reason;
}

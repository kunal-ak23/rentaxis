package com.datagami.rentaxis.api.dto.lease;

import jakarta.validation.constraints.NotBlank;

public record RecordEjariRequest(@NotBlank(message = "An Ejari number is required") String ejariNumber) {
}

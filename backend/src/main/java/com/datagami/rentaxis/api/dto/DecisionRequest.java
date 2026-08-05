package com.datagami.rentaxis.api.dto;

import jakarta.validation.constraints.Size;

public record DecisionRequest(@Size(max = 2000) String adminNote) {
}

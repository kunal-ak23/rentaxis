package com.datagami.rentaxis.api.dto.payables;

import jakarta.validation.constraints.NotBlank;

/** Body of {@code DELETE /voucher-allocations/{id}}: why the allocation is released. */
public record ReleaseAllocationDTO(@NotBlank String reason) { }

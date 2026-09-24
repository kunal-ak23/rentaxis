package com.datagami.rentaxis.api.dto.voucher;

import jakarta.validation.Valid;

import java.util.List;

/**
 * Optional body of {@code POST /vouchers/{id}/post}: for a BPV, the invoices it
 * settles (spec §2). Allocations are written in the post's own transaction; a
 * draft holds none, because only a POSTED payment can settle anything.
 */
public record PostVoucherDTO(@Valid List<AllocationInputDTO> allocations, Boolean notOnStatement,
                             Boolean allowNegativeCash) {

    public PostVoucherDTO(List<AllocationInputDTO> allocations) {
        this(allocations, null, null);
    }
}

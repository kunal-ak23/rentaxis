package com.datagami.rentaxis.api.dto.payables;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The new-run list (spec §2 step 1): open items with what is open now, the draft
 * runs already holding each one (a flag, not a reservation), and each vendor's
 * unallocated advance ("apply advance 5,000 first").
 */
public record PaymentRunCandidatesDTO(List<Candidate> items, List<VendorAdvance> advances) {
    public record Candidate(OpenItemDTO item, List<String> draftRuns) { }
    public record VendorAdvance(UUID vendorId, String vendorName, BigDecimal unallocated) { }
}

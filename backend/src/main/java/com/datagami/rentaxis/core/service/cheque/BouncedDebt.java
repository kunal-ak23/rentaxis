package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.core.service.lease.LeaseClosureService;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.ChequeStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How much of each due row the ledger still carries (F14-08).
 *
 * <p>A bounced cheque puts its debt back on the lease's rent receivable (the CBR).
 * Whatever later pays that debt — a settlement that absorbs it (STL), a
 * replacement, a receipt — credits the same receivable, so the receivable's balance
 * on the lease dimension is the bounced debt still open. The register row itself
 * stays BOUNCED forever; counting its face value as overdue after a finalized
 * settlement nets the receivable to nil is how the dashboard came to show 23,000
 * the books no longer carry.</p>
 *
 * <p>So a lease's BOUNCED rows share its receivable balance: the newest bounce is
 * the last one paid (payments settle the oldest debt first), so the balance is
 * allocated newest-first and an older row whose debt has been paid shows nothing.
 * Every other due row (REGISTERED or DEPOSITED, still on paper) is open in full.</p>
 */
@Component
public class BouncedDebt {

    private final LeaseClosureService closure;

    public BouncedDebt(LeaseClosureService closure) {
        this.closure = closure;
    }

    /** The open amount of each row, by cheque id; a row the ledger has closed maps to zero. */
    public Map<UUID, BigDecimal> openAmounts(List<Cheque> due) {
        Map<UUID, BigDecimal> open = new LinkedHashMap<>();
        Map<UUID, List<Cheque>> bouncedByLease = new HashMap<>();
        Map<UUID, Lease> leases = new HashMap<>();
        for (Cheque c : due) {
            BigDecimal amount = c.getAmount() == null ? BigDecimal.ZERO : c.getAmount();
            open.put(c.getId(), amount);
            if (closure != null && c.getStatus() == ChequeStatus.BOUNCED && c.getLease() != null) {
                bouncedByLease.computeIfAbsent(c.getLease().getId(), k -> new ArrayList<>()).add(c);
                leases.putIfAbsent(c.getLease().getId(), c.getLease());
            }
        }
        bouncedByLease.forEach((leaseId, rows) -> {
            BigDecimal left = closure.receivableBalance(leases.get(leaseId)).max(BigDecimal.ZERO);
            rows.sort(Comparator.comparing((Cheque c) -> c.getBouncedAt() == null ? LocalDate.MIN : c.getBouncedAt())
                    .thenComparing(c -> c.getChequeDate() == null ? LocalDate.MIN : c.getChequeDate())
                    .reversed());
            for (Cheque c : rows) {
                BigDecimal part = open.get(c.getId()).min(left);
                open.put(c.getId(), part);
                left = left.subtract(part);
            }
        });
        return open;
    }
}

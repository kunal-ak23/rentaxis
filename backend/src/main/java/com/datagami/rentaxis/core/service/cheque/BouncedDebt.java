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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BouncedDebt.class);

    private final LeaseClosureService closure;

    public BouncedDebt(LeaseClosureService closure) {
        this.closure = closure;
    }

    /** The open amount of each row, by cheque id; a row the ledger has closed maps to zero. */
    public Map<UUID, BigDecimal> openAmounts(List<Cheque> due) {
        return allocate(due, lease -> java.util.Optional.of(closure.receivableBalance(lease)), true,
                c -> c.getStatus() == ChequeStatus.BOUNCED);
    }

    /**
     * {@link #openAmounts} as the books stood at the end of {@code at} (an as-of report:
     * the owner statement's Outstanding section). Pass only rows that had bounced by
     * {@code at}; a row whose lease's receivable cannot be read stays at its face value.
     */
    public Map<UUID, BigDecimal> openAmountsAt(List<Cheque> bouncedByThen, LocalDate at) {
        // Every row passed had bounced by `at`, whatever its status today (PR #399 R1 P2-1:
        // a later write-off, replacement or return moves it on, and the closed period must not
        // notice), so each shares the receivable as at `at`.
        return allocate(bouncedByThen, lease -> closure.receivableBalanceIfKnownAt(lease, at), true, c -> true);
    }

    /**
     * {@link #openAmounts} for the BOUNCED rows only, leaving out every row whose lease's
     * receivable cannot be read (an unmapped RENT_RECEIVABLE, a dangling account id) —
     * the caller treats a missing row as "not known" rather than failing. Never throws
     * for those leases, so it is safe inside a transaction that must still commit.
     */
    public Map<UUID, BigDecimal> knownBouncedOpenAmounts(List<Cheque> rows) {
        Map<UUID, BigDecimal> all = allocate(rows, closure::receivableBalanceIfKnown, false,
                c -> c.getStatus() == ChequeStatus.BOUNCED);
        Map<UUID, BigDecimal> out = new LinkedHashMap<>();
        for (Cheque c : rows) {
            if (c.getStatus() == ChequeStatus.BOUNCED && all.containsKey(c.getId())) {
                out.put(c.getId(), all.get(c.getId()));
            }
        }
        return out;
    }

    /**
     * @param keepUnknown whether a row of a lease whose balance is unknown stays at its
     *        face value (true) or is left out (false).
     */
    private Map<UUID, BigDecimal> allocate(List<Cheque> due,
                                           java.util.function.Function<Lease, java.util.Optional<BigDecimal>> balance,
                                           boolean keepUnknown,
                                           java.util.function.Predicate<Cheque> bounced) {
        Map<UUID, BigDecimal> open = new LinkedHashMap<>();
        Map<UUID, List<Cheque>> bouncedByLease = new HashMap<>();
        Map<UUID, Lease> leases = new HashMap<>();
        for (Cheque c : due) {
            BigDecimal amount = c.getAmount() == null ? BigDecimal.ZERO : c.getAmount();
            open.put(c.getId(), amount);
            if (closure != null && bounced.test(c) && c.getLease() != null) {
                bouncedByLease.computeIfAbsent(c.getLease().getId(), k -> new ArrayList<>()).add(c);
                leases.putIfAbsent(c.getLease().getId(), c.getLease());
            }
        }
        bouncedByLease.forEach((leaseId, rows) -> {
            java.util.Optional<BigDecimal> known = balance.apply(leases.get(leaseId));
            if (known.isEmpty()) {
                log.warn("bounced-debt: receivable of lease {} cannot be read (unmapped or missing account); "
                        + "its {} bounced row(s) are counted at face value", leaseId, rows.size());
                if (!keepUnknown) rows.forEach(c -> open.remove(c.getId()));
                return;
            }
            BigDecimal left = known.get().max(BigDecimal.ZERO);
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

package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.LeaseAddendum;
import com.datagami.rentaxis.domain.entity.LeaseAddendumCredit;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.repository.LeaseAddendumCreditRepository;
import com.datagami.rentaxis.domain.repository.LeaseAddendumRepository;
import com.datagami.rentaxis.domain.repository.LeaseLineRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PR #359 R1 P2-1: a lease's terms as they stand after its credit addenda (F14-32).
 *
 * <p>The lease lines are the contract as signed and are never rewritten; a credit
 * addendum records, per line it cut, the line's new value over its whole window.
 * Anything that reads "what this lease charges now" — a renewal's defaults and its
 * escalation base, a transfer's suggested lines, the header's current rent, the
 * unit's rent and the dashboard — reads it here, so the reduced rate is the one
 * carried forward and a removed charge does not come back.</p>
 */
@Component
public class LeaseEffectiveTerms {

    private final LeaseLineRepository leaseLines;
    private final LeaseAddendumCreditRepository credits;
    private final LeaseAddendumRepository addenda;

    public LeaseEffectiveTerms(LeaseLineRepository leaseLines, LeaseAddendumCreditRepository credits,
                               LeaseAddendumRepository addenda) {
        this.leaseLines = leaseLines;
        this.credits = credits;
        this.addenda = addenda;
    }

    /**
     * One line and what it charges now.
     *
     * @param amount   the line's value over its whole window: the latest credit addendum's
     *                 new amount on or before the date, else the line's own net
     * @param credited whether a credit addendum set it (the discount and rent-free concession
     *                 are then already inside {@code amount})
     */
    public record EffectiveLine(LeaseLine line, BigDecimal amount, boolean credited) {
        public boolean removed() {
            return credited && amount.signum() == 0;
        }
    }

    /**
     * @param onDate credits effective on or before this date count; null counts every
     *               credit (the terms agreed going forward)
     */
    public List<EffectiveLine> effectiveLines(Lease lease, LocalDate onDate) {
        List<LeaseLine> lines = leaseLines.findByLease_IdOrderBySeqNoAsc(lease.getId());
        Map<UUID, LeaseAddendumCredit> latest = new HashMap<>();
        if (!lines.isEmpty()) {
            Map<UUID, LeaseAddendum> byId = new HashMap<>();
            for (LeaseAddendum a : addenda.findByLease_IdOrderByCreatedAtAsc(lease.getId())) byId.put(a.getId(), a);
            List<LeaseAddendumCredit> all = credits.findByLeaseLineIdIn(lines.stream().map(LeaseLine::getId).toList());
            all.stream()
                    .filter(c -> byId.containsKey(c.getAddendumId()))
                    .filter(c -> onDate == null || !byId.get(c.getAddendumId()).getEffectiveFrom().isAfter(onDate))
                    .sorted(Comparator.comparing((LeaseAddendumCredit c) -> byId.get(c.getAddendumId()).getEffectiveFrom())
                            .thenComparing(c -> byId.get(c.getAddendumId()).getCreatedAt()))
                    .forEach(c -> latest.put(c.getLeaseLineId(), c));
        }
        return lines.stream().map(l -> {
            LeaseAddendumCredit c = latest.get(l.getId());
            return c != null ? new EffectiveLine(l, c.getNewLineAmount(), true)
                    : new EffectiveLine(l, l.getNetAmount() == null ? BigDecimal.ZERO : l.getNetAmount(), false);
        }).toList();
    }

    /** The one line's effective entry, or null. */
    public EffectiveLine of(List<EffectiveLine> lines, LeaseLine line) {
        return line == null ? null : lines.stream().filter(e -> e.line().getId().equals(line.getId())).findFirst().orElse(null);
    }

    /** Σ RENT lines' effective amounts: the rent the renter pays now. */
    public BigDecimal currentRent(Lease lease) {
        return effectiveLines(lease, null).stream()
                .filter(e -> e.line().getChargeType() != null
                        && e.line().getChargeType().getBehaviour() == ChargeBehaviour.RENT)
                .map(EffectiveLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

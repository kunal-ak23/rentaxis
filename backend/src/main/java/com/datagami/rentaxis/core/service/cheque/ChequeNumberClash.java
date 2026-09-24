package com.datagami.rentaxis.core.service.cheque;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.enums.ChequeMode;
import com.datagami.rentaxis.domain.repository.ChequeRepository;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F14-19: one physical cheque is one register row. A cheque is identified by the
 * bank it is drawn on, its number and the account it is drawn from — the renter's —
 * so the same (drawer bank, number, renter) on two leases is the same paper
 * registered twice. Uniqueness within one lease is {@link ChequeRowRules}' job; this
 * looks across the tenant's other leases, ignoring rows that are drafts, replaced,
 * cancelled or returned (handed back: the paper is no longer held, and a unit
 * transfer re-registers it on the new lease). Bank names are compared trimmed and case-insensitively.
 */
@Component
public class ChequeNumberClash {

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final ChequeRepository cheques;

    public ChequeNumberClash(ChequeRepository cheques) {
        this.cheques = cheques;
    }

    /** One sentence per row of {@code rows} that duplicates a live cheque on another lease. */
    public List<String> clashes(Lease lease, Collection<Cheque> rows) {
        List<Cheque> numbered = rows.stream()
                .filter(c -> c.getMode() == ChequeMode.PDC && ChequeRowRules.blankToNull(c.getChequeNumber()) != null)
                .toList();
        if (numbered.isEmpty() || lease == null || lease.getRenter() == null) return List.of();
        Set<String> numbers = numbered.stream().map(c -> number(c.getChequeNumber())).collect(Collectors.toSet());
        List<Cheque> elsewhere = cheques.findLiveNumberedOnOtherLeases(lease.getRenter().getId(), numbers,
                lease.getId() == null ? new UUID(0, 0) : lease.getId());
        List<String> out = new ArrayList<>();
        for (Cheque row : numbered) {
            elsewhere.stream()
                    .filter(o -> number(o.getChequeNumber()).equals(number(row.getChequeNumber())))
                    .filter(o -> Objects.equals(bank(o.getPayeeBank()), bank(row.getPayeeBank())))
                    .findFirst()
                    .ifPresent(o -> out.add("Cheque " + row.getChequeNumber().trim()
                            + (row.getPayeeBank() == null ? "" : " (" + row.getPayeeBank().trim() + ")")
                            + " is already registered for this renter on another lease"
                            + where(o) + "; a cheque can be registered once."));
        }
        return out;
    }

    /** The same check for one row, as a refusal. */
    public void requireUnique(Lease lease, Cheque row) {
        List<String> found = clashes(lease, List.of(row));
        if (!found.isEmpty()) throw new BusinessRuleViolationException(found.get(0));
    }

    /** "154 101" and "154101" are the same cheque: whitespace is not part of the number. */
    static String number(String n) {
        return n == null ? "" : n.replaceAll("\\s+", "");
    }

    private static String bank(String name) {
        return name == null || name.isBlank() ? null : name.trim().toLowerCase(Locale.ROOT);
    }

    private static String where(Cheque other) {
        Lease l = other.getLease();
        String unit = l.getUnit() != null ? l.getUnit().getUnitNumber() : null;
        return " (" + (unit == null ? "" : "unit " + unit + ", ")
                + (l.getStartDate() == null ? "" : "from " + l.getStartDate().format(DMY) + ", ")
                + other.getStatus() + ")";
    }
}

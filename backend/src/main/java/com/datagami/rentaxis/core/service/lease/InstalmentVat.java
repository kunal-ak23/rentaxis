package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.ChequeRowKind;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Spreading a contract's VAT over the instalments that collect it (spec
 * 2026-09-24 §1, "Allocating VAT to rows").
 *
 * <p>{@code Σ row.vat_amount = Σ LeaseVat.vatOf(line)} exactly, and the post checks
 * it next to {@code Σ amount = contract value}. The generator writes each row's VAT
 * as it builds the row; this is the default for everything else — a row typed on
 * the grid, an extension's or addendum's rows, a grid saved before this existed —
 * and it is deliberately dull: pro rata to the VAT-bearing part of each row, to the
 * fils, by largest remainder. A deposit never carries VAT.</p>
 *
 * <p>The taxable (net) figure follows the VAT row by row — each row's share of the
 * contract's taxable value is its share of the contract's VAT — so VAT201 box 1
 * never back-computes a net from 5%.</p>
 */
public final class InstalmentVat {

    private InstalmentVat() {
    }

    /** Σ {@code LeaseVat.vatOf} over the lines — the VAT the rows must collect between them. */
    public static BigDecimal contractVat(List<LeaseLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (LeaseLine l : lines) total = total.add(LeaseVat.vatOf(l));
        return total;
    }

    /** Σ of the VAT-bearing lines' net — what that VAT is charged on. */
    public static BigDecimal contractTaxable(List<LeaseLine> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (LeaseLine l : lines) total = total.add(LeaseVat.taxableOf(l));
        return total;
    }

    /** Σ of the rows' own VAT. */
    public static BigDecimal rowVat(List<Cheque> rows) {
        BigDecimal total = BigDecimal.ZERO;
        for (Cheque c : rows) total = total.add(c.getVatAmount() == null ? BigDecimal.ZERO : c.getVatAmount());
        return total;
    }

    /**
     * Fill in the VAT of every row not in {@code fixed}, and the taxable amount of
     * every row, from the lines the rows pay for.
     *
     * <p>The VAT the fixed rows have not claimed is shared among the others in
     * proportion to the <b>VAT-bearing</b> part of each (see {@link
     * #vatBearingWeights}) — never onto the money a row collects for a deposit (PR
     * #348 review P2-3), so a security deposit never gets a tax invoice. The row
     * with the latest cheque date takes ties. If the fixed rows have claimed more
     * than the contract charges, the others get zero and the post-time Σ check names
     * the difference — this does not second-guess a figure the accountant typed.</p>
     *
     * <p>When the lines charge no VAT at all, every row's VAT is zero, fixed or not
     * (review P2-4): a row still carrying VAT from before the lines changed would
     * otherwise declare VAT the contract never charged.</p>
     *
     * @param rows  the rows to write onto; mutated in place
     * @param fixed rows whose {@code vatAmount} the caller has set and this must keep
     * @param lines the lines the rows collect — the whole contract, or an addendum's
     *              or extension's new lines
     */
    public static void fill(List<Cheque> rows, Set<Cheque> fixed, List<LeaseLine> lines) {
        if (rows.isEmpty()) return;
        BigDecimal vatTotal = contractVat(lines);
        if (vatTotal.signum() == 0) {
            for (Cheque c : rows) {
                c.setVatAmount(BigDecimal.ZERO);
                c.setVatTaxableAmount(BigDecimal.ZERO);
            }
            return;
        }
        BigDecimal claimed = BigDecimal.ZERO;
        for (Cheque c : rows) {
            if (fixed.contains(c)) claimed = claimed.add(nz(c.getVatAmount()));
        }
        BigDecimal remaining = vatTotal.subtract(claimed);
        if (remaining.signum() < 0) remaining = BigDecimal.ZERO;

        List<Cheque> ordered = byDate(rows);
        List<BigDecimal> bearing = vatBearingWeights(ordered, lines);
        List<Cheque> open = new ArrayList<>();
        List<BigDecimal> weights = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            if (fixed.contains(ordered.get(i))) continue;
            open.add(ordered.get(i));
            weights.add(bearing.get(i));
        }
        List<BigDecimal> shares = LeaseVat.allocate(remaining, weights);
        for (int i = 0; i < open.size(); i++) {
            open.get(i).setVatAmount(shares.get(i));
        }
        fillTaxable(rows, contractTaxable(lines));
    }

    /**
     * Re-spread the lines' VAT over rows that already carry some, keeping each row's
     * share of it — how an amendment re-prices a grid it is not allowed to re-cut
     * (review P2-3). A row with no VAT (a deposit, a fee without VAT) keeps none.
     * Nothing changes when the rows already add up to the lines' VAT; rows that carry
     * none at all fall back to {@link #fill}.
     */
    public static void respread(List<Cheque> rows, List<LeaseLine> lines) {
        if (rows.isEmpty()) return;
        BigDecimal vatTotal = contractVat(lines);
        BigDecimal current = rowVat(rows);
        if (current.compareTo(vatTotal) == 0) return;
        if (vatTotal.signum() == 0 || current.signum() <= 0) {
            fill(rows, Set.of(), lines);
            return;
        }
        List<Cheque> ordered = byDate(rows);
        List<BigDecimal> shares = LeaseVat.allocate(vatTotal, ordered.stream().map(c -> nz(c.getVatAmount())).toList());
        for (int i = 0; i < ordered.size(); i++) ordered.get(i).setVatAmount(shares.get(i));
        fillTaxable(rows, contractTaxable(lines));
    }

    /**
     * How much of each row (in the order given) is VAT-bearing money. The contract
     * charges one rate, so a row's share of the VAT is its share of that money.
     *
     * <p><b>By what the row is</b> (PR #348 re-review N1, N2), when every row says
     * — the generator, the imports, an addendum or extension and the grid all set
     * {@link ChequeRowKind}. A DEPOSIT row weighs nothing. A RENT row weighs its
     * amount times the VAT-bearing fraction of the rent lines, a FEE row likewise
     * with the fee lines — so VAT on a fee lands on the rows that carry fees, not
     * on exempt rent. A MIXED row (cheque 1 with the extras folded in) carries the
     * deposit and fee money no row of its own collects, and weighs its rent and fee
     * parts by the same fractions.</p>
     *
     * <p><b>Otherwise, by the old heuristic</b> — rows written before the kind
     * existed, or typed on the grid without one. A row is taken for a deposit line
     * when its narration says deposit and its amount is the line's; by amount alone
     * only when exactly one row has that amount and it is not a rent row. What is
     * left of the deposit money comes off the earliest rows (it is due at signing).
     * Other non-VAT money (exempt rent, a fee without VAT) weighs every row alike,
     * which is what leaving it out of the weights does.</p>
     */
    static List<BigDecimal> vatBearingWeights(List<Cheque> ordered, List<LeaseLine> lines) {
        boolean allKnown = !ordered.isEmpty() && ordered.stream().allMatch(c -> c.getRowKind() != null);
        if (allKnown) {
            List<BigDecimal> byKind = weightsByKind(ordered, lines);
            if (byKind.stream().anyMatch(w -> w.signum() > 0)) return byKind;
        }
        return weightsByHeuristic(ordered, lines);
    }

    private static ChargeBehaviour behaviourOf(LeaseLine l) {
        ChargeBehaviour b = l.getChargeType() == null ? null : l.getChargeType().getBehaviour();
        return b == null ? ChargeBehaviour.FEE : b;
    }

    /** The one kind every line is, for rows an addendum or extension adds without saying; else null. */
    public static ChequeRowKind kindOf(List<LeaseLine> lines) {
        if (lines == null || lines.isEmpty()) return null;
        java.util.Set<ChargeBehaviour> kinds = java.util.EnumSet.noneOf(ChargeBehaviour.class);
        for (LeaseLine l : lines) kinds.add(behaviourOf(l));
        if (kinds.size() != 1) return null;
        return switch (kinds.iterator().next()) {
            case RENT -> ChequeRowKind.RENT;
            case FEE -> ChequeRowKind.FEE;
            case DEPOSIT -> ChequeRowKind.DEPOSIT;
        };
    }

    private static List<BigDecimal> weightsByKind(List<Cheque> ordered, List<LeaseLine> lines) {
        java.util.Map<ChargeBehaviour, BigDecimal> gross = new java.util.EnumMap<>(ChargeBehaviour.class);
        java.util.Map<ChargeBehaviour, BigDecimal> taxedGross = new java.util.EnumMap<>(ChargeBehaviour.class);
        for (LeaseLine l : lines) {
            ChargeBehaviour b = behaviourOf(l);
            BigDecimal g = LeaseVat.grossOf(l);
            gross.merge(b, g, BigDecimal::add);
            if (LeaseVat.vatOf(l).signum() > 0) taxedGross.merge(b, g, BigDecimal::add);
        }
        BigDecimal rentFraction = fraction(taxedGross.get(ChargeBehaviour.RENT), gross.get(ChargeBehaviour.RENT));
        BigDecimal feeFraction = fraction(taxedGross.get(ChargeBehaviour.FEE), gross.get(ChargeBehaviour.FEE));

        // Deposit and fee money no row of its own collects rides on the MIXED rows.
        BigDecimal ownFee = BigDecimal.ZERO, ownDeposit = BigDecimal.ZERO, mixedTotal = BigDecimal.ZERO;
        for (Cheque c : ordered) {
            switch (c.getRowKind()) {
                case FEE -> ownFee = ownFee.add(nz(c.getAmount()));
                case DEPOSIT -> ownDeposit = ownDeposit.add(nz(c.getAmount()));
                case MIXED -> mixedTotal = mixedTotal.add(nz(c.getAmount()));
                default -> { }
            }
        }
        BigDecimal foldedFee = nz(gross.get(ChargeBehaviour.FEE)).subtract(ownFee).max(BigDecimal.ZERO);
        BigDecimal foldedDeposit = nz(gross.get(ChargeBehaviour.DEPOSIT)).subtract(ownDeposit).max(BigDecimal.ZERO);

        List<BigDecimal> weights = new ArrayList<>();
        for (Cheque c : ordered) {
            BigDecimal amount = nz(c.getAmount()).max(BigDecimal.ZERO);
            BigDecimal w = switch (c.getRowKind()) {
                case DEPOSIT -> BigDecimal.ZERO;
                case RENT -> amount.multiply(rentFraction);
                case FEE -> amount.multiply(feeFraction);
                case MIXED -> {
                    BigDecimal share = mixedTotal.signum() == 0 ? BigDecimal.ZERO
                            : amount.divide(mixedTotal, 12, java.math.RoundingMode.HALF_UP);
                    BigDecimal fee = foldedFee.multiply(share).min(amount);
                    BigDecimal deposit = foldedDeposit.multiply(share).min(amount.subtract(fee));
                    BigDecimal rent = amount.subtract(fee).subtract(deposit).max(BigDecimal.ZERO);
                    yield rent.multiply(rentFraction).add(fee.multiply(feeFraction));
                }
            };
            weights.add(w);
        }
        return weights;
    }

    private static BigDecimal fraction(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0 || part == null) return BigDecimal.ZERO;
        return part.divide(whole, 12, java.math.RoundingMode.HALF_UP);
    }

    private static boolean saysDeposit(Cheque c) {
        String n = c.getNarration() == null ? "" : c.getNarration().trim().toLowerCase(java.util.Locale.ROOT);
        return n.contains("deposit") || n.equals("sd") || n.equals("parking sd");
    }

    private static boolean saysRent(Cheque c) {
        String n = c.getNarration() == null ? "" : c.getNarration().trim().toLowerCase(java.util.Locale.ROOT);
        return n.startsWith("rent");
    }

    private static List<BigDecimal> weightsByHeuristic(List<Cheque> ordered, List<LeaseLine> lines) {
        List<BigDecimal> deposits = new ArrayList<>();
        for (LeaseLine l : lines) {
            if (behaviourOf(l) == ChargeBehaviour.DEPOSIT) {
                BigDecimal gross = LeaseVat.grossOf(l);
                if (gross.signum() > 0) deposits.add(gross);
            }
        }
        List<BigDecimal> weights = new ArrayList<>();
        for (Cheque c : ordered) weights.add(nz(c.getAmount()).max(BigDecimal.ZERO));
        boolean[] matched = new boolean[ordered.size()];
        BigDecimal left = BigDecimal.ZERO;
        for (BigDecimal g : deposits) {
            int hit = -1;
            // A row that says it is a deposit, of exactly this amount.
            for (int i = 0; i < ordered.size() && hit < 0; i++) {
                if (!matched[i] && saysDeposit(ordered.get(i)) && weights.get(i).compareTo(g) == 0) hit = i;
            }
            // Else the amount alone — only when it points at exactly one row that is not rent.
            if (hit < 0) {
                int only = -1, count = 0;
                for (int i = 0; i < ordered.size(); i++) {
                    if (!matched[i] && weights.get(i).compareTo(g) == 0) {
                        count++;
                        only = i;
                    }
                }
                if (count == 1 && !saysRent(ordered.get(only))) hit = only;
            }
            if (hit >= 0) {
                matched[hit] = true;
                weights.set(hit, BigDecimal.ZERO);
            } else {
                left = left.add(g);
            }
        }
        for (int i = 0; i < ordered.size() && left.signum() > 0; i++) {
            if (matched[i]) continue;
            BigDecimal take = weights.get(i).min(left);
            weights.set(i, weights.get(i).subtract(take));
            left = left.subtract(take);
        }
        return weights;
    }

    private static List<Cheque> byDate(List<Cheque> rows) {
        List<Cheque> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(Cheque::getChequeDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(Cheque::getSeqNo));
        return ordered;
    }

    /**
     * Each row's taxable amount as its share of {@code taxableTotal}, weighted by
     * its VAT; the last VAT-bearing row absorbs the remainder. A row with no VAT
     * has no taxable amount.
     */
    public static void fillTaxable(List<Cheque> rows, BigDecimal taxableTotal) {
        List<Cheque> ordered = new ArrayList<>(rows);
        ordered.sort(Comparator.comparing(Cheque::getChequeDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(Cheque::getSeqNo));
        List<BigDecimal> weights = ordered.stream().map(c -> nz(c.getVatAmount())).toList();
        boolean anyVat = weights.stream().anyMatch(w -> w.signum() > 0);
        List<BigDecimal> taxables = anyVat ? LeaseVat.allocate(nz(taxableTotal), weights) : null;
        for (int i = 0; i < ordered.size(); i++) {
            ordered.get(i).setVatTaxableAmount(anyVat ? taxables.get(i) : BigDecimal.ZERO);
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

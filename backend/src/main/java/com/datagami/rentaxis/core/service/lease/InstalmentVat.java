package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.domain.entity.Cheque;
import com.datagami.rentaxis.domain.entity.LeaseLine;

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
     * How much of each row (in the order given) is VAT-bearing money.
     *
     * <p>A row has no kind column, so this reasons from the lines: the money the
     * lines collect <em>without</em> VAT — deposits, and any charge that carries none
     * — has to sit somewhere on the grid. First, a row whose amount is exactly one
     * such line's gross is that line (the generator and the portfolio import write a
     * deposit as a row of its own, dated the contract date). What is left of that
     * money is taken off the earliest rows first, because it is due at signing: a
     * first cheque with the deposit folded in weighs only its rent. Whatever remains
     * of each row is its weight.</p>
     */
    static List<BigDecimal> vatBearingWeights(List<Cheque> ordered, List<LeaseLine> lines) {
        List<BigDecimal> nonVat = new ArrayList<>();
        for (LeaseLine l : lines) {
            if (LeaseVat.vatOf(l).signum() == 0) {
                BigDecimal gross = LeaseVat.grossOf(l);
                if (gross.signum() > 0) nonVat.add(gross);
            }
        }
        List<BigDecimal> weights = new ArrayList<>();
        for (Cheque c : ordered) weights.add(nz(c.getAmount()).max(BigDecimal.ZERO));
        BigDecimal left = BigDecimal.ZERO;
        boolean[] matched = new boolean[ordered.size()];
        for (BigDecimal g : nonVat) {
            int hit = -1;
            for (int i = 0; i < ordered.size(); i++) {
                if (!matched[i] && weights.get(i).compareTo(g) == 0) {
                    hit = i;
                    break;
                }
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

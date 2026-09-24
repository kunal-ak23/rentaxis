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
 * and it is deliberately dull: pro rata to the row amounts, to the fils, the row
 * with the latest cheque date absorbing the remainder. Pro rata can pull VAT onto an
 * earlier row that also carries a deposit; that errs towards declaring early, which
 * is the safe direction.</p>
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
     * every row.
     *
     * <p>The VAT the fixed rows have not claimed is shared among the others pro rata
     * to their amounts, the latest-dated one absorbing the remainder. If the fixed
     * rows have claimed more than the contract charges, the others get zero and the
     * post-time Σ check names the difference — this does not second-guess a figure
     * the accountant typed.</p>
     *
     * @param rows         the rows to write onto; mutated in place
     * @param fixed        rows whose {@code vatAmount} the caller has set and this must keep
     * @param vatTotal     what the rows must collect between them
     * @param taxableTotal the net that VAT is charged on
     */
    public static void fill(List<Cheque> rows, Set<Cheque> fixed, BigDecimal vatTotal, BigDecimal taxableTotal) {
        if (rows.isEmpty()) return;
        BigDecimal claimed = BigDecimal.ZERO;
        List<Cheque> open = new ArrayList<>();
        for (Cheque c : rows) {
            if (fixed.contains(c)) {
                claimed = claimed.add(c.getVatAmount() == null ? BigDecimal.ZERO : c.getVatAmount());
            } else {
                open.add(c);
            }
        }
        BigDecimal remaining = nz(vatTotal).subtract(claimed);
        if (remaining.signum() < 0) remaining = BigDecimal.ZERO;

        // Latest cheque date last, so it is the one that absorbs the remainder.
        open.sort(Comparator.comparing(Cheque::getChequeDate, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparingInt(Cheque::getSeqNo));
        List<BigDecimal> weights = open.stream().map(c -> nz(c.getAmount())).toList();
        List<BigDecimal> shares = LeaseVat.allocate(remaining, weights);
        for (int i = 0; i < open.size(); i++) {
            open.get(i).setVatAmount(shares.get(i));
        }
        fillTaxable(rows, taxableTotal);
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

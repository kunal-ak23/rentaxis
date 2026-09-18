package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.ChequeRoundingCalculator;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.enums.InstallmentDistribution;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The grid's arithmetic, with no database in sight.
 *
 * <p>The numbers are from a real PACT contract: 61,000 of rent over six cheques
 * with a 3,000 deposit and a 500 admin fee. They are asserted exactly rather than
 * "sums to the total", because the point of the tens rounding is the <em>shape</em>
 * of the split — a 10,166.67 cheque nobody can write sums to the total too.</p>
 */
class ChequeGenerationServiceTest {

    private static BigDecimal bd(String s) { return new BigDecimal(s); }

    @Test
    void pactExampleSixChequesRoundedToTensFirstAbsorbs() {
        var r = ChequeRoundingCalculator.distribute(bd("61000"), 6,
                InstallmentDistribution.FIRST_LARGER, bd("10"));
        assertThat(r.amounts()).containsExactly(
                bd("10200"), bd("10160"), bd("10160"), bd("10160"), bd("10160"), bd("10160"));
        assertThat(r.step()).isEqualByComparingTo("10");
    }

    @Test
    void galahFourChequesUniformWhenDivisible() {
        var r = ChequeRoundingCalculator.distribute(bd("51000"), 4,
                InstallmentDistribution.FIRST_LARGER, bd("10"));
        assertThat(r.amounts()).containsExactly(bd("12750"), bd("12750"), bd("12750"), bd("12750"));
    }

    /**
     * Without the tens step the same 61,000 falls to the default ladder's 1,000 and
     * writes 11,000 + 10,000 × 5 — a whole denomination of rent moved onto the first
     * cheque. This is the assertion that fails if {@code TEN} is ever widened.
     */
    @Test
    void theCoarseLadderWouldHaveWrittenAVeryDifferentGrid() {
        var coarse = ChequeRoundingCalculator.distribute(bd("61000"), 6,
                InstallmentDistribution.FIRST_LARGER).amounts();
        assertThat(coarse).containsExactly(
                bd("11000"), bd("10000"), bd("10000"), bd("10000"), bd("10000"), bd("10000"));
    }

    @Test
    void foldingDepositsAndFeesIntoFirstRowMatchesPact() {
        List<ChequeGenerationService.Row> rows = ChequeGenerationService.buildRows(
                bd("61000"),
                List.of(new ChequeGenerationService.Extra("SD", bd("3000")),
                        new ChequeGenerationService.Extra("Admin", bd("500"))),
                6,
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2027, 9, 10),
                InstallmentDistribution.FIRST_LARGER,
                true);

        assertThat(rows).hasSize(6);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("13700");
        assertThat(rows.get(0).narration()).isEqualTo("Rent - 1st Installment | SD | Admin");
        assertThat(rows.get(1).amount()).isEqualByComparingTo("10160");
        assertThat(rows.get(1).narration()).isEqualTo("Rent - 2nd Installment");
        // 11 Sep + floor(5 * 12 / 6) = 10 months.
        assertThat(rows.get(5).chequeDate()).isEqualTo(LocalDate.of(2027, 7, 11));
        assertThat(rows.stream().map(ChequeGenerationService.Row::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("64500");
        // Every row posts on the contract date, whatever it is due on.
        assertThat(rows).allSatisfy(r ->
                assertThat(r.postingDate()).isEqualTo(LocalDate.of(2026, 9, 11)));
        assertThat(rows).extracting(ChequeGenerationService.Row::seqNo).containsExactly(1, 2, 3, 4, 5, 6);
    }

    /**
     * Unfolded, the deposit and the fee are instruments of their own — and they are
     * dated the contract date, not the first rent instalment, because that is when
     * they are handed over.
     */
    @Test
    void unfoldedExtrasBecomeTheirOwnRowsDatedTheContractDate() {
        List<ChequeGenerationService.Row> rows = ChequeGenerationService.buildRows(
                bd("61000"),
                List.of(new ChequeGenerationService.Extra("SD", bd("3000")),
                        new ChequeGenerationService.Extra("Admin", bd("500"))),
                6,
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2026, 9, 11),
                LocalDate.of(2027, 9, 10),
                InstallmentDistribution.FIRST_LARGER,
                false);

        assertThat(rows).hasSize(8);
        assertThat(rows.get(0).amount()).isEqualByComparingTo("10200");
        assertThat(rows.get(6).narration()).isEqualTo("SD");
        assertThat(rows.get(6).amount()).isEqualByComparingTo("3000");
        assertThat(rows.get(6).chequeDate()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(rows.get(7).narration()).isEqualTo("Admin");
        assertThat(rows).extracting(ChequeGenerationService.Row::seqNo)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }

    /** A zero-value extra is not a charge; folding its label in would say otherwise. */
    @Test
    void waivedExtrasAreNotFoldedIn() {
        List<ChequeGenerationService.Row> rows = ChequeGenerationService.buildRows(
                bd("12000"),
                List.of(new ChequeGenerationService.Extra("Admin", BigDecimal.ZERO)),
                1,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                InstallmentDistribution.FIRST_LARGER, true);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.amount()).isEqualByComparingTo("12000");
            assertThat(r.narration()).isEqualTo("Rent - 1st Installment");
        });
    }

    /**
     * Thirteen months over four cheques: offsets [0, 3, 6, 9], the last instrument
     * covering four months. Same rule the v1 schedule used, so a lease that spans an
     * odd number of months does not acquire a fifth cheque.
     */
    @Test
    void dueDatesSpaceEvenlyOverAnUnevenTerm() {
        List<ChequeGenerationService.Row> rows = ChequeGenerationService.buildRows(
                bd("52000"), List.of(), 4,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 31),
                InstallmentDistribution.FIRST_LARGER, true);

        assertThat(rows).extracting(ChequeGenerationService.Row::chequeDate).containsExactly(
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 4, 1),
                LocalDate.of(2026, 7, 1),
                LocalDate.of(2026, 10, 1));
    }

    @Test
    void numbersFillSequentiallyPreservingWidth() {
        assertThat(ChequeGenerationService.nextNumber("100040", 3)).isEqualTo("100043");
        assertThat(ChequeGenerationService.nextNumber("000028", 1)).isEqualTo("000029");
        // The first row keeps the number the user typed.
        assertThat(ChequeGenerationService.nextNumber("100040", 0)).isEqualTo("100040");
        // Padding widens rather than truncates when the book rolls over.
        assertThat(ChequeGenerationService.nextNumber("998", 5)).isEqualTo("1003");
        // A prefixed book is carried through.
        assertThat(ChequeGenerationService.nextNumber("CHQ-000099", 2)).isEqualTo("CHQ-000101");
    }

    @Test
    void aChequeNumberWithNoDigitsIsRefused() {
        assertThatThrownBy(() -> ChequeGenerationService.nextNumber("ABC", 1))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("does not end in a number");
        assertThatThrownBy(() -> ChequeGenerationService.nextNumber("  ", 1))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    /**
     * The teens are why this is a method and not {@code i + suffix[i % 10]}: the
     * eleventh cheque of a twelve-cheque lease is the commonest row there is.
     */
    @Test
    void ordinalsReadTheWayAPersonWritesThem() {
        assertThat(ChequeGenerationService.ordinal(1)).isEqualTo("1st");
        assertThat(ChequeGenerationService.ordinal(2)).isEqualTo("2nd");
        assertThat(ChequeGenerationService.ordinal(3)).isEqualTo("3rd");
        assertThat(ChequeGenerationService.ordinal(4)).isEqualTo("4th");
        assertThat(ChequeGenerationService.ordinal(11)).isEqualTo("11th");
        assertThat(ChequeGenerationService.ordinal(12)).isEqualTo("12th");
        assertThat(ChequeGenerationService.ordinal(13)).isEqualTo("13th");
        assertThat(ChequeGenerationService.ordinal(21)).isEqualTo("21st");
        assertThat(ChequeGenerationService.ordinal(22)).isEqualTo("22nd");
    }

    /**
     * VAT is rounded per line and then summed, never summed and then rounded: the
     * invoice the renter holds is the per-line one, and the two differ by a fils on
     * some line counts.
     */
    @Test
    void vatIsFivePercentToTheFils() {
        assertThat(LeaseVat.RATE).isEqualByComparingTo("0.05");
        assertThat(LeaseVat.vatOf(bd("2000"))).isEqualByComparingTo("100.00");
        assertThat(LeaseVat.vatOf(bd("60000"))).isEqualByComparingTo("3000.00");
        // 61.7285 → 61.73, HALF_UP at two decimals.
        assertThat(LeaseVat.vatOf(bd("1234.57"))).isEqualByComparingTo("61.73");
        // 0.05 × 0.1 = 0.005 → 0.01, not 0.00.
        assertThat(LeaseVat.vatOf(bd("0.10"))).isEqualByComparingTo("0.01");
        assertThat(LeaseVat.vatOf((BigDecimal) null)).isEqualByComparingTo("0");
    }

    @Test
    void aLineCarriesVatOnlyWhenItSaysSo() {
        LeaseLine taxed = new LeaseLine();
        taxed.setNetAmount(bd("2000"));
        taxed.setVatApplicable(true);
        assertThat(LeaseVat.vatOf(taxed)).isEqualByComparingTo("100.00");
        assertThat(LeaseVat.grossOf(taxed)).isEqualByComparingTo("2100.00");

        LeaseLine exempt = new LeaseLine();
        exempt.setNetAmount(bd("2000"));
        exempt.setVatApplicable(false);
        assertThat(LeaseVat.vatOf(exempt)).isEqualByComparingTo("0");
        // A VAT-free line is collected at its net, with no stray .00 scale change
        // that would make an assertion on the cheque amount read oddly.
        assertThat(LeaseVat.grossOf(exempt)).isEqualByComparingTo("2000");
    }

    /**
     * The grid the VAT ruling produces: 51,000 of VAT-free rent over four cheques
     * plus a 2,000 admin fee that does carry VAT. Row 1 collects 12,750 + 2,100.
     */
    @Test
    void aVatBearingExtraIsFoldedInAtItsGross() {
        List<ChequeGenerationService.Row> rows = ChequeGenerationService.buildRows(
                bd("51000"),
                List.of(new ChequeGenerationService.Extra("Admin", bd("2100.00"))),
                4,
                LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 24), LocalDate.of(2027, 9, 23),
                InstallmentDistribution.FIRST_LARGER, true);

        assertThat(rows.get(0).amount()).isEqualByComparingTo("14850");
        assertThat(rows.get(1).amount()).isEqualByComparingTo("12750");
        assertThat(rows.stream().map(ChequeGenerationService.Row::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("53100");
    }

    /** VAT-bearing rent is split with the tens rounding intact: 63,000 over 4. */
    @Test
    void vatOnRentIsSplitAcrossTheInstallments() {
        List<ChequeGenerationService.Row> rows = ChequeGenerationService.buildRows(
                bd("63000"), List.of(), 4,
                LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 24), LocalDate.of(2027, 9, 23),
                InstallmentDistribution.FIRST_LARGER, true);

        assertThat(rows).extracting(ChequeGenerationService.Row::amount)
                .allSatisfy(a -> assertThat(a).isEqualByComparingTo("15750"));
    }

    @Test
    void aLeaseWithNothingToCollectHasNoGrid() {
        assertThatThrownBy(() -> ChequeGenerationService.buildRows(
                BigDecimal.ZERO, List.of(), 4,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                InstallmentDistribution.FIRST_LARGER, true))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("nothing to collect");
    }

    /**
     * A deposit-only lease still gets a grid: the extras stand on their own,
     * because there is no first instalment to fold them into.
     */
    @Test
    void aLeaseWithNoRentStillCollectsItsDeposits() {
        List<ChequeGenerationService.Row> rows = ChequeGenerationService.buildRows(
                BigDecimal.ZERO,
                List.of(new ChequeGenerationService.Extra("SD", bd("3000"))),
                4,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                InstallmentDistribution.FIRST_LARGER, true);

        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.narration()).isEqualTo("SD");
            assertThat(r.amount()).isEqualByComparingTo("3000");
        });
    }
}

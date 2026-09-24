package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.domain.entity.ChargeType;
import com.datagami.rentaxis.domain.entity.LeaseLine;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one definition of VAT on a lease line.
 *
 * <p>The arithmetic is covered in {@code ChequeGenerationServiceTest}; this class
 * is about the rule that decides <em>whether</em> a line is taxable at all, which
 * used to be written down twice and in two different ways.</p>
 */
class LeaseVatTest {

    private static LeaseLine line(ChargeBehaviour behaviour, AccountRole role, String net, boolean vatFlag) {
        ChargeType type = new ChargeType();
        type.setCode(role.name());
        type.setNameEn(role.name());
        type.setRole(role);
        type.setBehaviour(behaviour);

        LeaseLine l = new LeaseLine();
        l.setChargeType(type);
        l.setNetAmount(new BigDecimal(net));
        l.setVatApplicable(vatFlag);
        return l;
    }

    /**
     * A deposit is refundable money held against the tenancy, not consideration for
     * a supply, so it never carries VAT — whatever the flag on the line says.
     *
     * <p>The flag used to be taken at its word here while the contract PDF forced
     * deposits to zero, so a VAT-flagged deposit would have printed one figure on
     * the paper the renter signed and collected another on the cheques. The two
     * places now ask the same question.</p>
     */
    @Test
    void aDepositNeverCarriesVatEvenWhenTheLineSaysItDoes() {
        LeaseLine deposit = line(ChargeBehaviour.DEPOSIT, AccountRole.SECURITY_DEPOSIT, "3000", true);

        assertThat(LeaseVat.vatOf(deposit)).isEqualByComparingTo("0");
        assertThat(LeaseVat.grossOf(deposit)).isEqualByComparingTo("3000");
    }

    /** A parking deposit is a deposit by behaviour, not by code — the rule follows behaviour. */
    @Test
    void theRuleFollowsBehaviourNotTheChargeTypeCode() {
        LeaseLine parkingDeposit = line(ChargeBehaviour.DEPOSIT, AccountRole.PARKING_DEPOSIT, "1000", true);

        assertThat(LeaseVat.vatOf(parkingDeposit)).isEqualByComparingTo("0");
    }

    /** Fees and rent are taken at their word, which is where the flag earns its keep. */
    @Test
    void feesAndRentAreTaxedWhenTheLineSaysSo() {
        assertThat(LeaseVat.vatOf(line(ChargeBehaviour.FEE, AccountRole.ADMIN_FEE, "2000", true)))
                .isEqualByComparingTo("100.00");
        assertThat(LeaseVat.grossOf(line(ChargeBehaviour.FEE, AccountRole.ADMIN_FEE, "2000", true)))
                .isEqualByComparingTo("2100.00");
        assertThat(LeaseVat.vatOf(line(ChargeBehaviour.RENT, AccountRole.ADVANCE_RENT, "60000", true)))
                .isEqualByComparingTo("3000.00");
        assertThat(LeaseVat.vatOf(line(ChargeBehaviour.FEE, AccountRole.ADMIN_FEE, "2000", false)))
                .isEqualByComparingTo("0");
    }

    /** A line with no charge type at all is still readable: no behaviour, so no deposit rule. */
    @Test
    void aLineWithoutAChargeTypeFallsBackToItsFlag() {
        LeaseLine bare = new LeaseLine();
        bare.setNetAmount(new BigDecimal("500"));
        bare.setVatApplicable(true);

        assertThat(LeaseVat.vatOf(bare)).isEqualByComparingTo("25.00");
        assertThat(LeaseVat.vatOf((LeaseLine) null)).isEqualByComparingTo("0");
    }

    /** The inverse of net + VAT, for the portfolio import's VAT-inclusive Cheques sheet (PR #344 review I1). */
    @Test
    void netOfGross_invertsNetPlusVat_orSaysThereIsNone() {
        var rent = com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour.RENT;
        assertThat(LeaseVat.netOfGross(new java.math.BigDecimal("105000"), true, rent)).isEqualByComparingTo("100000");
        assertThat(LeaseVat.netOfGross(new java.math.BigDecimal("105000"), false, rent)).isEqualByComparingTo("105000");
        // Every net from 0.01 to 30.00 round-trips.
        for (int fils = 1; fils <= 3000; fils++) {
            java.math.BigDecimal net = java.math.BigDecimal.valueOf(fils, 2);
            java.math.BigDecimal gross = net.add(LeaseVat.vatOfNet(net, true, rent));
            assertThat(LeaseVat.netOfGross(gross, true, rent)).as(net.toPlainString()).isEqualByComparingTo(net);
        }
        long gaps = java.util.stream.IntStream.rangeClosed(1, 3000)
                .mapToObj(f -> java.math.BigDecimal.valueOf(f, 2))
                .filter(g -> LeaseVat.netOfGross(g, true, rent) == null).count();
        assertThat(gaps).as("some gross amounts have no net").isPositive();
    }
    // ------------------------------------------------------------------
    // allocate (spec 2026-09-24 §1)
    // ------------------------------------------------------------------

    private static java.util.List<BigDecimal> bds(String... v) {
        return java.util.Arrays.stream(v).map(BigDecimal::new).toList();
    }

    @Test
    void allocateSplitsProRataAndSumsExactly() {
        java.util.List<BigDecimal> parts = LeaseVat.allocate(new BigDecimal("6000"), bds("31500", "31500", "31500", "31500"));
        assertThat(parts).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bds("1500", "1500", "1500", "1500").toArray(BigDecimal[]::new));
    }

    /** 100 over three equal rows: 33.33 twice, and the last row absorbs the fils. */
    @Test
    void allocateGivesTheRemainderToTheLastRow() {
        java.util.List<BigDecimal> parts = LeaseVat.allocate(new BigDecimal("100"), bds("1", "1", "1"));
        assertThat(parts).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bds("33.33", "33.33", "33.34").toArray(BigDecimal[]::new));
        assertThat(parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100");

        java.util.List<BigDecimal> first = LeaseVat.allocateFirstAbsorbs(new BigDecimal("100"), bds("1", "1", "1"));
        assertThat(first).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bds("33.34", "33.33", "33.33").toArray(BigDecimal[]::new));
    }

    /** Zero-weight rows get nothing; a zero total gives every row zero; no weights at all loses nothing. */
    @Test
    void allocateHandlesZeroRows() {
        assertThat(LeaseVat.allocate(new BigDecimal("50"), bds("0", "10", "0"))).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bds("0", "50", "0").toArray(BigDecimal[]::new));
        assertThat(LeaseVat.allocate(BigDecimal.ZERO, bds("5", "5"))).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bds("0", "0").toArray(BigDecimal[]::new));
        assertThat(LeaseVat.allocate(new BigDecimal("7.50"), bds("0", "0"))).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(bds("0", "7.50").toArray(BigDecimal[]::new));
        assertThat(LeaseVat.allocate(new BigDecimal("10"), java.util.List.of())).isEmpty();
    }

    /**
     * PR #348 review P3-1: the review's two counter-examples, where rounding every
     * share half-up and letting the last row absorb the difference left it at −0.01
     * and −0.03. Largest remainder never goes negative and never moves a share more
     * than a fils from its exact value, whichever row takes ties.
     */
    @Test
    void allocateNeverGivesARowANegativeShare() {
        java.util.List<BigDecimal> six = new java.util.ArrayList<>(java.util.Collections.nCopies(6, new BigDecimal("10500")));
        six.add(new BigDecimal("5.00"));
        java.util.List<BigDecimal> twelve = new java.util.ArrayList<>(java.util.Collections.nCopies(12, new BigDecimal("21015")));
        twelve.add(new BigDecimal("5.00"));
        for (var c : java.util.List.of(new Object[]{"500.03", six}, new Object[]{"1000.05", twelve})) {
            BigDecimal total = new BigDecimal((String) c[0]);
            @SuppressWarnings("unchecked") java.util.List<BigDecimal> w = (java.util.List<BigDecimal>) c[1];
            BigDecimal sum = w.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            for (var parts : java.util.List.of(LeaseVat.allocate(total, w), LeaseVat.allocateFirstAbsorbs(total, w))) {
                assertThat(parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(total);
                for (int i = 0; i < w.size(); i++) {
                    BigDecimal exact = total.multiply(w.get(i)).divide(sum, 10, java.math.RoundingMode.HALF_UP);
                    assertThat(parts.get(i).signum()).as("row %s of %s", i, total).isNotNegative();
                    assertThat(parts.get(i).subtract(exact).abs()).isLessThan(new BigDecimal("0.01"));
                }
            }
            // The 5.00 row's exact share is about 0.04: never below it by a fils or more.
            assertThat(LeaseVat.allocate(total, w).get(w.size() - 1)).isPositive();
        }
    }

    @Test
    void allocateSplitsACreditTheSameWayAsADebit() {
        assertThat(LeaseVat.allocate(new BigDecimal("-100"), bds("1", "1", "1"))).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("-33.33"), new BigDecimal("-33.33"), new BigDecimal("-33.34"));
    }
}

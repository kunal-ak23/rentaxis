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
}

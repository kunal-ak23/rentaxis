package com.datagami.rentaxis.core.service.voucher;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.*;
import com.datagami.rentaxis.domain.entity.enums.*;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class VoucherDraftIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired VoucherService vouchers;
    @Autowired AccountService accounts;
    @Autowired VendorService vendorService;
    @Autowired LandlordOrgRepository orgRepo;

    UUID tenantId;
    Account cleaningExpense;
    Account groupAccount;
    Vendor vendor;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Voucher-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        groupAccount = accounts.getAccountByCode("D-01");           // an EXPENSE group from the seed
        cleaningExpense = accounts.createLeaf("Cleaning Expense", groupAccount, null);
        Vendor v = new Vendor();
        v.setNameEn("Emrill Services");
        vendor = vendorService.createVendor(v);                      // Plan 1 creates payableAccount silently
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private VoucherService.VoucherInput pisr(BigDecimal amount, BigDecimal rate) {
        return new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 5), vendor.getId(), "INV-8812",
                "Monthly cleaning", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(
                        cleaningExpense.getId(), "October cleaning", amount, rate, null, null)));
    }

    @Test
    void creatingADraftComputesPerLineVatAndNumbersTheLines() {
        Voucher v = vouchers.createDraft(pisr(new BigDecimal("1000.00"), new BigDecimal("5")));
        assertThat(v.getStatus()).isEqualTo(VoucherStatus.DRAFT);
        assertThat(v.getJournalId()).isNull();
        assertThat(v.getLines()).singleElement().satisfies(l -> {
            assertThat(l.getLineNo()).isEqualTo(1);
            assertThat(l.getVatAmount()).isEqualByComparingTo("50.00");
        });
    }

    @Test
    void updatingADraftReplacesItsLinesAndRenumbersFromOne() {
        Voucher v = vouchers.createDraft(pisr(new BigDecimal("1000.00"), new BigDecimal("5")));
        VoucherService.VoucherInput two = new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 5), vendor.getId(), "INV-8812",
                "Monthly cleaning", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(cleaningExpense.getId(), "A", new BigDecimal("200.00"), new BigDecimal("5"), null, null),
                        new VoucherService.VoucherLineInput(cleaningExpense.getId(), "B", new BigDecimal("300.00"), BigDecimal.ZERO, null, null)));
        Voucher updated = vouchers.updateDraft(v.getId(), two);
        assertThat(updated.getLines()).extracting(VoucherLine::getLineNo).containsExactly(1, 2);
        assertThat(VoucherMath.grossTotal(updated.getLines())).isEqualByComparingTo("510.00");
    }

    @Test
    void aPisrWithoutAVendorIsRejected() {
        VoucherService.VoucherInput noVendor = new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 5), null, null, "x", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(cleaningExpense.getId(), null, new BigDecimal("10.00"), BigDecimal.ZERO, null, null)));
        assertThatThrownBy(() -> vouchers.createDraft(noVendor))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("vendor");
    }

    @Test
    void aBpvWithoutAPaymentAccountIsRejected() {
        VoucherService.VoucherInput noBank = new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 5), null, null, "x", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(cleaningExpense.getId(), null, new BigDecimal("10.00"), BigDecimal.ZERO, null, null)));
        assertThatThrownBy(() -> vouchers.createDraft(noBank))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("payment account");
    }

    /**
     * Controller ruling (spec §10.2, sharpened during Plan 4 review): a BPV line carries NO
     * VAT — VAT belongs to the purchase invoice the payment settles, never to the payment
     * itself. Exact message: "A payment voucher line cannot carry VAT — record the VAT on
     * the purchase invoice".
     */
    @Test
    void aBpvLineMayNotCarryVat() {
        Account bank = accounts.createLeaf("Emirates Islamic - Ops", accounts.getAccountByCode("A-02-02"), null);
        VoucherService.VoucherInput vatOnBpv = new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 5), null, null, "x", null, null, bank.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(cleaningExpense.getId(), null, new BigDecimal("10.00"), new BigDecimal("5"), null, null)));
        assertThatThrownBy(() -> vouchers.createDraft(vatOnBpv))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("A payment voucher line cannot carry VAT — record the VAT on the purchase invoice");
    }

    /** Same refusal on updateDraft, not just createDraft — an edit path must not reopen the hole. */
    @Test
    void aBpvLineMayNotCarryVatOnUpdateEither() {
        Account bank = accounts.createLeaf("Mashreq - Ops", accounts.getAccountByCode("A-02-02"), null);
        VoucherService.VoucherInput clean = new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 5), null, null, "x", null, null, bank.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(cleaningExpense.getId(), null, new BigDecimal("10.00"), BigDecimal.ZERO, null, null)));
        Voucher v = vouchers.createDraft(clean);
        VoucherService.VoucherInput withVat = new VoucherService.VoucherInput(
                VoucherType.BPV, LocalDate.of(2026, 10, 5), null, null, "x", null, null, bank.getId(), null, null,
                List.of(new VoucherService.VoucherLineInput(cleaningExpense.getId(), null, new BigDecimal("10.00"), new BigDecimal("5"), null, null)));
        assertThatThrownBy(() -> vouchers.updateDraft(v.getId(), withVat))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("A payment voucher line cannot carry VAT — record the VAT on the purchase invoice");
    }

    /** Group accounts cannot carry journal lines (spec §4.2); catch it at draft time, not at post time. */
    @Test
    void aLineOnAGroupAccountIsRejected() {
        VoucherService.VoucherInput onGroup = new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 5), vendor.getId(), null, "x", null, null, null, null, null,
                List.of(new VoucherService.VoucherLineInput(groupAccount.getId(), null, new BigDecimal("10.00"), BigDecimal.ZERO, null, null)));
        assertThatThrownBy(() -> vouchers.createDraft(onGroup))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("group");
    }

    @Test
    void aVoucherWithNoLinesIsRejected() {
        VoucherService.VoucherInput empty = new VoucherService.VoucherInput(
                VoucherType.PISR, LocalDate.of(2026, 10, 5), vendor.getId(), null, "x", null, null, null, null, null, List.of());
        assertThatThrownBy(() -> vouchers.createDraft(empty))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("at least one line");
    }

    @Test
    void draftsCanBeDeletedAndAreFoundByTheListQuery() {
        Voucher v = vouchers.createDraft(pisr(new BigDecimal("1000.00"), new BigDecimal("5")));
        assertThat(vouchers.list(VoucherType.PISR, VoucherStatus.DRAFT, null, null, null, null, PageRequest.of(0, 25))
                .getContent()).extracting(Voucher::getId).contains(v.getId());
        vouchers.deleteDraft(v.getId());
        assertThat(vouchers.list(VoucherType.PISR, null, null, null, null, null, PageRequest.of(0, 25))
                .getContent()).extracting(Voucher::getId).doesNotContain(v.getId());
    }

    /**
     * P0 guard: the Hibernate tenant filter (BaseTenantEntity, applyToLoadByKey = true)
     * must block tenant B from reading, updating or deleting tenant A's draft voucher —
     * even by id, even though the row physically exists in the same table.
     */
    @Test
    void tenantBCannotReadOrEditTenantAsDraftVoucher() {
        Voucher v = vouchers.createDraft(pisr(new BigDecimal("1000.00"), new BigDecimal("5")));
        UUID voucherId = v.getId();

        LandlordOrg orgB = new LandlordOrg();
        orgB.setName("Voucher-B-" + UUID.randomUUID());
        UUID tenantB = orgRepo.save(orgB).getId();
        TenantContextHolder.setTenantId(tenantB);

        assertThatThrownBy(() -> vouchers.get(voucherId)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> vouchers.updateDraft(voucherId, pisr(new BigDecimal("2000.00"), new BigDecimal("5"))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> vouchers.deleteDraft(voucherId)).isInstanceOf(NotFoundException.class);

        TenantContextHolder.setTenantId(tenantId);
        assertThat(vouchers.get(voucherId).getStatus()).isEqualTo(VoucherStatus.DRAFT);
    }
}

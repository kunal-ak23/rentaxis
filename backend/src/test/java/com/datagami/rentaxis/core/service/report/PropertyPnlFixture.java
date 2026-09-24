package com.datagami.rentaxis.core.service.report;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.VendorService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingRequest.Dimensions;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.voucher.VoucherService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.VoucherType;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.core.service.ledger.PostingRequest.cr;
import static com.datagami.rentaxis.core.service.ledger.PostingRequest.dr;

/**
 * The spec §1 worked example — Marina Tower (P1), August and September 2026 — in
 * one tenant, plus Palm (P2) with no activity and the shared bank charge and
 * interest that land in Unassigned. Built from the real posting paths: role
 * postings through {@link PostingService}, supplier invoices through
 * {@link VoucherService}.
 */
final class PropertyPnlFixture {

    static final LocalDate AUG_1 = LocalDate.of(2026, 8, 1), AUG_31 = LocalDate.of(2026, 8, 31);
    static final LocalDate SEP_1 = LocalDate.of(2026, 9, 1), SEP_30 = LocalDate.of(2026, 9, 30);

    final LandlordOrgRepository orgRepo;
    final AccountService accounts;
    final PropertyAccountService propertyAccounts;
    final PropertyService properties;
    final PostingService posting;
    final VoucherService vouchers;
    final VendorService vendorService;
    final AccountRepository accountRepo;
    final AccountResolver resolver;

    UUID tenantId;
    Property p1, p2;
    Vendor vendor;
    Account bankCharges, bankInterest;

    PropertyPnlFixture(LandlordOrgRepository orgRepo, AccountService accounts, PropertyAccountService propertyAccounts,
                       PropertyService properties, PostingService posting, VoucherService vouchers,
                       VendorService vendorService, AccountRepository accountRepo, AccountResolver resolver) {
        this.orgRepo = orgRepo;
        this.accounts = accounts;
        this.propertyAccounts = propertyAccounts;
        this.properties = properties;
        this.posting = posting;
        this.vouchers = vouchers;
        this.vendorService = vendorService;
        this.accountRepo = accountRepo;
        this.resolver = resolver;
    }

    /** A fresh tenant with the chart, P1, P2 and a vendor; the context is left on it. */
    PropertyPnlFixture tenant(String prefix) {
        LandlordOrg org = new LandlordOrg();
        org.setName(prefix + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();
        p1 = property("Marina Tower");
        p2 = property("Palm Residence");
        Vendor v = new Vendor();
        v.setNameEn("Emrill Services LLC");
        v.setTrn("100123456700003");   // a PISR with input VAT needs it (finance-ops spec §2)
        vendor = vendorService.createVendor(v);
        bankCharges = accounts.getAccountByCode("D-02-003");
        bankInterest = accounts.createLeaf("Bank Interest", accounts.getAccountByCode("C-02"), null);
        return this;
    }

    Property property(String name) {
        Property p = new Property();
        p.setNameEn(name);
        p.setEmirate(Emirate.DUBAI);
        return properties.createProperty(p);
    }

    /** The generated leaf of a property carrying a report line (EXP_CLEANING, RENTAL_INCOME, …). */
    UUID leaf(Property p, String reportLine) {
        return accountRepo.findAll().stream()
                .filter(a -> tenantId.equals(a.getTenantId()))
                .filter(a -> p.getId().equals(a.getPropertyId()) && reportLine.equals(a.getReportLine()))
                .map(Account::getId).findFirst()
                .orElseThrow(() -> new IllegalStateException("no " + reportLine + " leaf for " + p.getNameEn()));
    }

    /** Both months of the worked example, plus the shared September bank items. */
    PropertyPnlFixture workedExample() {
        Dimensions d1 = Dimensions.ofProperty(p1.getId());
        // August
        role(JournalDocType.CIL, AUG_31, d1, AccountRole.ADVANCE_RENT, AccountRole.RENTAL_INCOME, "82191.78");
        role(JournalDocType.CIL, AUG_31, d1, AccountRole.ADVANCE_RENT, AccountRole.PARKING_INCOME, "2000.00");
        invoice(LocalDate.of(2026, 8, 20), "INV-7781", p1.getId(), List.of(
                line(leaf(p1, "EXP_REPAIRS_MAINTENANCE"), "1000.00", "5.00"),
                line(leaf(p1, "EXP_CLEANING"), "400.00", "5.00")));
        // September
        role(JournalDocType.CIL, SEP_30, d1, AccountRole.ADVANCE_RENT, AccountRole.RENTAL_INCOME, "82191.78");
        role(JournalDocType.CIL, SEP_30, d1, AccountRole.ADVANCE_RENT, AccountRole.PARKING_INCOME, "2000.00");
        role(JournalDocType.TCO, LocalDate.of(2026, 9, 5), d1, AccountRole.RENT_RECEIVABLE, AccountRole.ADMIN_FEE, "1500.00");
        role(JournalDocType.PEN, LocalDate.of(2026, 9, 12), d1, AccountRole.RENT_RECEIVABLE, AccountRole.CHEQUE_RETURN_PENALTY, "500.00");
        invoice(LocalDate.of(2026, 9, 10), "AN-311", p1.getId(), List.of(line(leaf(p1, "EXP_CLEANING"), "3000.00", "5.00")));
        invoice(LocalDate.of(2026, 9, 15), "DEWA-0925", p1.getId(), List.of(line(leaf(p1, "EXP_UTILITIES"), "4200.00", "0.00")));
        // Shared, from the bank statement: no property anywhere.
        posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 30), "Bank charges", Dimensions.none(),
                JournalSourceType.MANUAL, null, null, List.of(dr(bankCharges.getId(), new BigDecimal("50.00")),
                cr(AccountRole.CASH, new BigDecimal("50.00")))));
        posting.post(new PostingRequest(JournalDocType.JV, LocalDate.of(2026, 9, 30), "Bank interest", Dimensions.none(),
                JournalSourceType.MANUAL, null, null, List.of(dr(AccountRole.CASH, new BigDecimal("120.00")),
                cr(bankInterest.getId(), new BigDecimal("120.00")))));
        return this;
    }

    void role(JournalDocType type, LocalDate date, Dimensions dims, AccountRole debit, AccountRole credit, String amount) {
        BigDecimal a = new BigDecimal(amount);
        posting.post(new PostingRequest(type, date, type + " " + amount, dims, JournalSourceType.MANUAL, null, null,
                List.of(dr(debit, a), cr(credit, a))));
    }

    static VoucherService.VoucherLineInput line(UUID account, String amount, String vat) {
        return new VoucherService.VoucherLineInput(account, "line", new BigDecimal(amount), new BigDecimal(vat), null, null);
    }

    UUID invoice(LocalDate date, String number, UUID propertyId, List<VoucherService.VoucherLineInput> lines) {
        UUID id = vouchers.createDraft(new VoucherService.VoucherInput(VoucherType.PISR, date, vendor.getId(), number,
                number, propertyId, null, null, null, null, lines)).getId();
        vouchers.post(id);
        return id;
    }
}

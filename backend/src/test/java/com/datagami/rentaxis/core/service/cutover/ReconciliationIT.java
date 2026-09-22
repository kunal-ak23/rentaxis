package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.ledger.AccountResolver;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.ledger.TenantFiscalSettingsService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.PropertyAccountMapping;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reconciliation report (spec §10.3: "per account — derived balance, PACT
 * figure, difference").
 *
 * <p>The derived column is what <em>step 1</em> put on the books — the active
 * contracts, imported and bulk-posted. The PACT column is the uploaded trial
 * balance. A clean cut-over has a zero in the difference column for every account
 * step 1 owns; anything else is a contract that was left out, imported twice, or
 * imported with the wrong figure, and the accountant finds it here rather than in
 * next month's balance sheet.</p>
 *
 * <p>The opening-balance journal is dated the same day this report is drawn as at,
 * so it has to be subtracted back out of the derived column — otherwise every
 * manually entered account would read back its own figure and reconcile against
 * itself. That is what {@link #postingTheObJournalDoesNotChangeTheDerivedColumn}
 * is for.</p>
 */
@SpringBootTest
@Testcontainers
class ReconciliationIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OpeningBalanceService ob;
    @Autowired ImportBatchService batches;
    @Autowired PostingService posting;
    @Autowired AccountService accounts;
    @Autowired PropertyAccountService propertyAccounts;
    @Autowired AccountResolver resolver;
    @Autowired TenantFiscalSettingsService fiscal;
    @Autowired PropertyAccountMappingRepository propertyMappings;
    @Autowired PropertyRepository propertyRepo;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired JournalEntryRepository entries;
    @Autowired TransactionTemplate tx;

    static final LocalDate BOOKS_START = LocalDate.of(2026, 10, 1);
    static final LocalDate AS_OF = LocalDate.of(2026, 9, 30);

    UUID tenantId, propertyId;
    Account rentReceivable, advanceRent, cashInHand, obDifference;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Recon-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        propertyAccounts.seedDefaultTemplateAndDefaults();

        Property p = new Property();
        p.setNameEn("Sample Plaza Oasis 7");
        p.setEmirate(Emirate.DUBAI);
        propertyId = propertyRepo.save(p).getId();

        rentReceivable = accounts.createLeaf("Rent Receivable - Sample Plaza 7", accounts.getAccountByCode("A-02-01"), propertyId);
        advanceRent = accounts.createLeaf("Advance Rent - Sample Plaza 7", accounts.getAccountByCode("B-01-01"), propertyId);
        cashInHand = accounts.createLeaf("Cash In Hand", accounts.getAccountByCode("A-02"), null);
        // Seeded as F-02 and mapped by seedDefaultTemplateAndDefaults; resolved by role.
        obDifference = resolver.resolve(AccountRole.OPENING_BALANCE_DIFFERENCE, null);

        mapProperty(AccountRole.RENT_RECEIVABLE, rentReceivable);
        mapProperty(AccountRole.ADVANCE_RENT, advanceRent);

        fiscal.setBooksStartDate(BOOKS_START);
        fiscal.lockThrough(AS_OF);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    private void mapProperty(AccountRole role, Account a) {
        PropertyAccountMapping m = new PropertyAccountMapping();
        m.setPropertyId(propertyId);
        m.setRole(role);
        m.setAccount(a);
        propertyMappings.save(m);
    }

    /** Stands in for the contract import: an IMPORT-sourced journal dated before the cut-over. */
    private void importedContract(String amount) {
        ImportBatch b = batches.create(null, "cut-over");
        posting.post(new PostingRequest(
                JournalDocType.TCO, LocalDate.of(2026, 9, 11), "Imported contract",
                PostingRequest.Dimensions.ofProperty(propertyId), JournalSourceType.IMPORT, UUID.randomUUID(),
                b.getId(),
                List.of(PostingRequest.dr(rentReceivable.getId(), new BigDecimal(amount)),
                        PostingRequest.cr(advanceRent.getId(), new BigDecimal(amount)))));
        batches.markPosted(b.getId(), 1);
    }

    private void upload(String csv) {
        ob.uploadSnapshot(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    private long journalCount() {
        return tx.execute(s -> (long) entries.findAll().size());
    }

    /**
     * Spec §10.3: "per account — derived balance, PACT figure, difference." The derived
     * balance comes from step 1 (the imported contracts); PACT's figure comes from the
     * uploaded trial balance; a clean cut-over has zero in the difference column for
     * every derived account.
     */
    @Test
    void aCleanCutOverReconcilesToZeroOnTheDerivedAccounts() {
        importedContract("61000.00");
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Rent Receivable - Sample Plaza 7,61000.00,0.00
                %s,Advance Rent - Sample Plaza 7,0.00,61000.00
                """.formatted(rentReceivable.getCode(), advanceRent.getCode()));

        List<OpeningBalanceService.ReconciliationRow> rows = ob.reconcile();
        assertThat(rows).filteredOn(r -> rentReceivable.getId().equals(r.accountId()))
                .singleElement().satisfies(r -> {
                    assertThat(r.derived()).isTrue();
                    assertThat(r.derivedBalance()).isEqualByComparingTo("61000.00");
                    assertThat(r.pactBalance()).isEqualByComparingTo("61000.00");
                    assertThat(r.difference()).isEqualByComparingTo("0.00");
                });
        assertThat(rows).filteredOn(r -> advanceRent.getId().equals(r.accountId()))
                .singleElement().satisfies(r -> assertThat(r.difference()).isEqualByComparingTo("0.00"));
    }

    /** A contract left out of the import shows up as exactly the missing amount. */
    @Test
    void aMissingContractShowsAsTheDifference() {
        importedContract("45000.00");
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Rent Receivable - Sample Plaza 7,61000.00,0.00
                """.formatted(rentReceivable.getCode()));
        assertThat(ob.reconcile()).filteredOn(r -> rentReceivable.getId().equals(r.accountId()))
                .singleElement()
                .satisfies(r -> assertThat(r.difference()).isEqualByComparingTo("-16000.00"));
    }

    /**
     * The OB journal must not contaminate the derived column. It posts ON the
     * reconciliation date, so a naive trial balance would include it and every manual
     * account would read back its own figure as "derived".
     */
    @Test
    void postingTheObJournalDoesNotChangeTheDerivedColumn() {
        importedContract("61000.00");
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Rent Receivable - Sample Plaza 7,61000.00,0.00
                %s,Advance Rent - Sample Plaza 7,0.00,61000.00
                %s,Cash In Hand,50000.00,0.00
                """.formatted(rentReceivable.getCode(), advanceRent.getCode(), cashInHand.getCode()));

        List<OpeningBalanceService.ReconciliationRow> before = ob.reconcile();
        ob.post();
        List<OpeningBalanceService.ReconciliationRow> after = ob.reconcile();

        assertThat(after).filteredOn(r -> cashInHand.getId().equals(r.accountId()))
                .singleElement().satisfies(r -> {
                    assertThat(r.derivedBalance()).isEqualByComparingTo("0.00");
                    assertThat(r.pactBalance()).isEqualByComparingTo("50000.00");
                    assertThat(r.difference()).isEqualByComparingTo("-50000.00");
                });
        assertThat(after).filteredOn(r -> rentReceivable.getId().equals(r.accountId()))
                .singleElement().satisfies(r -> assertThat(r.difference()).isEqualByComparingTo("0.00"));
        assertThat(after).hasSameSizeAs(before);
    }

    /**
     * Once the opening journal has been reversed the original and its mirror already
     * net to zero, so there is nothing left to subtract — and subtracting the reversed
     * entry's lines anyway would move the derived column the wrong way by the whole
     * opening balance. Only a LIVE posting is considered.
     */
    @Test
    void aReversedObJournalIsNotSubtractedTwice() {
        importedContract("61000.00");
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Rent Receivable - Sample Plaza 7,61000.00,0.00
                %s,Advance Rent - Sample Plaza 7,0.00,61000.00
                %s,Cash In Hand,50000.00,0.00
                """.formatted(rentReceivable.getCode(), advanceRent.getCode(), cashInHand.getCode()));

        List<OpeningBalanceService.ReconciliationRow> before = ob.reconcile();
        ob.post();
        ob.reverse("wrong file");
        List<OpeningBalanceService.ReconciliationRow> after = ob.reconcile();

        assertThat(after).filteredOn(r -> cashInHand.getId().equals(r.accountId()))
                .singleElement().satisfies(r -> assertThat(r.derivedBalance()).isEqualByComparingTo("0.00"));
        assertThat(after).usingRecursiveComparison().isEqualTo(before);
    }

    /**
     * The same guard from the other direction. {@code reverse} clears the marker's
     * journal id, so after the ordinary path there is nothing to subtract anyway —
     * but an opening journal reversed <em>outside</em> this screen (through the
     * posting service, or by a restore) leaves the marker still citing it. The
     * report must read the journal's status rather than trust the pointer, or a
     * dead entry is subtracted from the derived column of every account it touched.
     */
    @Test
    void anOpeningJournalReversedOutsideTheScreenIsNoLongerSubtracted() {
        importedContract("61000.00");
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Rent Receivable - Sample Plaza 7,61000.00,0.00
                %s,Advance Rent - Sample Plaza 7,0.00,61000.00
                %s,Cash In Hand,50000.00,0.00
                """.formatted(rentReceivable.getCode(), advanceRent.getCode(), cashInHand.getCode()));

        List<OpeningBalanceService.ReconciliationRow> before = ob.reconcile();
        JournalEntry opening = ob.post();
        posting.reverse(opening.getId(), AS_OF, "reversed straight through the ledger");

        assertThat(ob.grid().posted()).isFalse();
        assertThat(ob.reconcile()).usingRecursiveComparison().isEqualTo(before);
    }

    /** A PACT code with no account of ours still gets a row, so nothing is silently lost. */
    @Test
    void anUnmatchedPactCodeStillAppearsWithANullAccountId() {
        upload("""
                Account Code,Account Name,Debit,Credit
                999999,Some PACT Account,0.00,777.00
                """);
        assertThat(ob.reconcile()).filteredOn(r -> "999999".equals(r.code()))
                .singleElement().satisfies(r -> {
                    assertThat(r.accountId()).isNull();
                    assertThat(r.pactBalance()).isEqualByComparingTo("-777.00");
                    assertThat(r.derivedBalance()).isEqualByComparingTo("0.00");
                });
    }

    /**
     * The other half of the same question: an account our books carry a balance on
     * that PACT's export does not mention at all. That is what catches an
     * over-import, and it is invisible if the report only walks the uploaded file.
     */
    @Test
    void anAccountPactDidNotExportStillAppearsWithAZeroPactBalance() {
        importedContract("61000.00");
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Advance Rent - Sample Plaza 7,0.00,61000.00
                """.formatted(advanceRent.getCode()));

        assertThat(ob.reconcile()).filteredOn(r -> rentReceivable.getId().equals(r.accountId()))
                .singleElement().satisfies(r -> {
                    assertThat(r.pactBalance()).isEqualByComparingTo("0.00");
                    assertThat(r.derivedBalance()).isEqualByComparingTo("61000.00");
                    assertThat(r.difference()).isEqualByComparingTo("61000.00");
                });
    }

    /** The screen is read top to bottom by account code, so the report is sorted that way. */
    @Test
    void theReportIsSortedByAccountCode() {
        importedContract("61000.00");
        upload("""
                Account Code,Account Name,Debit,Credit
                999999,Some PACT Account,0.00,777.00
                %s,Cash In Hand,50000.00,0.00
                """.formatted(cashInHand.getCode()));
        List<String> codes = ob.reconcile().stream().map(OpeningBalanceService.ReconciliationRow::code).toList();
        assertThat(codes).isSortedAccordingTo(Comparator.nullsLast(String::compareTo));
    }

    /** A report is a report: drawing it must not write a journal. */
    @Test
    void reconcilingPostsNothing() {
        importedContract("61000.00");
        upload("""
                Account Code,Account Name,Debit,Credit
                %s,Cash In Hand,50000.00,0.00
                """.formatted(cashInHand.getCode()));
        long before = journalCount();
        ob.reconcile();
        ob.reconcile();
        assertThat(journalCount()).isEqualTo(before);
    }
}

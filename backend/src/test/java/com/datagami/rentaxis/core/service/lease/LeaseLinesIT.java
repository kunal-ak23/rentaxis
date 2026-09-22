package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.ChargeType;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.RentCollectionSettings;
import com.datagami.rentaxis.domain.entity.enums.AccountRole;
import com.datagami.rentaxis.domain.entity.enums.AccountType;
import com.datagami.rentaxis.domain.entity.enums.ChargeBehaviour;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.ChargeTypeRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.PropertyAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.RentCollectionSettingsRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.TenantDefaultAccountMappingRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The lease as a document made of charge lines (spec §6.2–6.3).
 *
 * <p>A real database rather than mocks, because the interesting behaviour is the
 * join between three things a mock would just assert away: the charge-type
 * catalogue, the per-property account set the resolver walks, and the
 * {@code ck_lease_lines_net} constraint behind the discount guard.</p>
 */
@SpringBootTest
@Testcontainers
class LeaseLinesIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LeaseService leaseService;
    @Autowired LeaseRepository leaseRepository;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired AccountService accountService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired AccountRepository accountRepository;
    @Autowired ChargeTypeRepository chargeTypeRepository;
    @Autowired PropertyAccountMappingRepository propertyMappingRepo;
    @Autowired TenantDefaultAccountMappingRepository defaultMappingRepo;
    @Autowired RentCollectionSettingsRepository rentCollectionSettingsRepo;
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures fixtures;

    private static final LocalDate START = LocalDate.of(2026, 9, 24);
    private static final LocalDate END = LocalDate.of(2027, 9, 23);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private LeaseDTO draft(LeaseLineInput... lines) {
        return leaseService.createDraftLease(fixtures.draftDto(START, END, List.of(lines)));
    }

    @Test
    void draftWithLinesDerivesTotalsAndDefaultCreditAccounts() {
        CreateLeaseDTO dto = fixtures.draftDto(fixtures.unit(), fixtures.renter(), START, END, List.of(
                line("RENT", "51000"),
                line("ADMIN_FEE", "2000"),
                line("SECURITY_DEPOSIT", "3000")));

        LeaseDTO lease = leaseService.createDraftLease(dto);

        assertThat(lease.getStatus()).isEqualTo(LeaseStatus.DRAFT);
        // The contract is worth every line, not just the rent.
        assertThat(lease.getContractValue()).isEqualByComparingTo("56000");
        // rentAmount and depositAmount are mirrors of the RENT / DEPOSIT lines.
        assertThat(lease.getRentAmount()).isEqualByComparingTo("51000");
        assertThat(lease.getDepositAmount()).isEqualByComparingTo("3000");
        // 24 Sep 2026 → 23 Sep 2027 inclusive. The denominator of per-day rent.
        assertThat(lease.getTotalDays()).isEqualTo(365);
        // A lease that is not a renewal heads its own chain.
        assertThat(lease.getChainId()).isEqualTo(lease.getId());
        assertThat(lease.getContractDate()).isNotNull();
        assertThat(lease.getFirstDueDate()).isEqualTo(START);

        assertThat(lease.getLines()).hasSize(3);
        LeaseLineDTO rent = lease.getLines().get(0);
        assertThat(rent.seqNo()).isEqualTo(1);
        assertThat(rent.chargeTypeCode()).isEqualTo("RENT");
        assertThat(rent.behaviour()).isEqualTo("RENT");
        // Rent credits unearned rent on the property, resolved from the template.
        assertThat(rent.creditAccountName()).isEqualTo("Advance Rent - " + fixtures.propertyName());
        // A rent line covers the term unless told otherwise.
        assertThat(rent.periodStart()).isEqualTo(START);
        assertThat(rent.periodEnd()).isEqualTo(END);
        assertThat(rent.netAmount()).isEqualByComparingTo("51000");

        // Fees and deposits resolve to their own leaves, not to the rent account.
        LeaseLineDTO admin = lease.getLines().get(1);
        assertThat(admin.creditAccountName()).isEqualTo("Admin Fee - " + fixtures.propertyName());
        assertThat(admin.periodStart()).isNull();
        LeaseLineDTO deposit = lease.getLines().get(2);
        assertThat(deposit.behaviour()).isEqualTo("DEPOSIT");
        assertThat(deposit.creditAccountName()).isEqualTo("Security Deposit " + fixtures.propertyName());
    }

    /**
     * A lease drafted without an explicit grace takes the property's collection
     * policy, not zero.
     *
     * <p>{@code rent_collection_settings.grace_period_days} had lost its last
     * consumer: the settings screen went on saving a number that did nothing, while
     * every lease created without an explicit grace was overdue on day one —
     * chased by the reminder job and eligible for a late-payment fine the moment a
     * cheque cleared a day late. Read at draft time and written onto the lease, so
     * the window a renter agreed to does not move when somebody edits the
     * property's policy in month nine.</p>
     */
    @Test
    void aDraftTakesItsGraceFromThePropertysCollectionSettings() {
        propertyGrace(5);

        LeaseDTO fromTheProperty = draft(line("RENT", "51000"));
        assertThat(fromTheProperty.getGracePeriodDays()).isEqualTo(5);

        // An explicit value still wins: this lease's own terms, not the default.
        CreateLeaseDTO explicit = fixtures.draftDto(START, END, List.of(line("RENT", "51000")));
        explicit.setGracePeriodDays(0);
        assertThat(leaseService.createDraftLease(explicit).getGracePeriodDays())
                .as("a renter who agreed to no grace is not given five days")
                .isZero();
    }

    /** No settings row, or none configured: no grace, exactly as before. */
    @Test
    void aPropertyWithNoCollectionSettingsStillDefaultsToNoGrace() {
        assertThat(draft(line("RENT", "51000")).getGracePeriodDays()).isZero();

        propertyGrace(null);
        assertThat(draft(line("RENT", "51000")).getGracePeriodDays()).isZero();
    }

    /** The fixture property's rent-collection policy. */
    private void propertyGrace(Integer days) {
        tx.executeWithoutResult(s -> {
            RentCollectionSettings settings = rentCollectionSettingsRepo
                    .findByPropertyId(fixtures.property().getId())
                    .orElseGet(() -> {
                        RentCollectionSettings fresh = new RentCollectionSettings();
                        fresh.setTenantId(fixtures.tenantId());
                        fresh.setProperty(fixtures.property());
                        return fresh;
                    });
            settings.setGracePeriodDays(days);
            rentCollectionSettingsRepo.save(settings);
        });
    }

    @Test
    void discountReducesNetAndCannotExceedGross() {
        LeaseDTO lease = draft(line("RENT", "51000", "1000"));

        assertThat(lease.getLines().get(0).grossAmount()).isEqualByComparingTo("51000");
        assertThat(lease.getLines().get(0).discountAmount()).isEqualByComparingTo("1000");
        assertThat(lease.getLines().get(0).netAmount()).isEqualByComparingTo("50000");
        // The derived mirror follows the net, not the gross — a discount the lease
        // shows but does not charge for is the whole point of the column.
        assertThat(lease.getRentAmount()).isEqualByComparingTo("50000");
        assertThat(lease.getContractValue()).isEqualByComparingTo("50000");

        // A discount larger than the gross would make the net negative: a line
        // that pays the renter. ck_lease_lines_net refuses it at the database too.
        CreateLeaseDTO bad = fixtures.draftDto(START, END, List.of(line("RENT", "51000", "60000")));
        assertThatThrownBy(() -> leaseService.createDraftLease(bad))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("discount cannot exceed the gross amount");

        // Equal is allowed: a fully waived charge is still a line on the contract.
        LeaseDTO waived = draft(line("RENT", "51000"), line("ADMIN_FEE", "2000", "2000"));
        assertThat(waived.getLines().get(1).netAmount()).isEqualByComparingTo("0");
        assertThat(waived.getContractValue()).isEqualByComparingTo("51000");
    }

    @Test
    void updateDraftReplacesLinesAndRecomputes() {
        LeaseDTO lease = draft(
                line("RENT", "51000"),
                line("ADMIN_FEE", "2000"),
                line("SECURITY_DEPOSIT", "3000"));
        assertThat(lease.getDepositAmount()).isEqualByComparingTo("3000");

        CreateLeaseDTO update = fixtures.draftDto(START, END, List.of(
                line("RENT", "50000"),
                line("ADMIN_FEE", "3000")));
        LeaseDTO updated = leaseService.updateDraftLease(lease.getId(), update);

        // Delete-then-insert: the deposit line is gone, not merged with.
        assertThat(updated.getLines()).hasSize(2);
        assertThat(updated.getLines()).extracting(LeaseLineDTO::chargeTypeCode)
                .containsExactly("RENT", "ADMIN_FEE");
        assertThat(updated.getLines()).extracting(LeaseLineDTO::seqNo).containsExactly(1, 2);
        assertThat(updated.getContractValue()).isEqualByComparingTo("53000");
        assertThat(updated.getRentAmount()).isEqualByComparingTo("50000");
        // The deposit mirror has to fall back to zero. Leaving 3,000 behind would
        // make the lease claim a refundable it never charged for.
        assertThat(updated.getDepositAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(updated.getId()).isEqualTo(lease.getId());
    }

    @Test
    void onlyDraftCanChangeLines() {
        LeaseDTO lease = draft(line("RENT", "51000"));

        // Straight to the repository: the point is the service's own guard, and
        // going through activateLease would drag unit occupancy into this test.
        tx.executeWithoutResult(status -> {
            Lease row = leaseRepository.findById(lease.getId()).orElseThrow();
            row.setStatus(LeaseStatus.ACTIVE);
            leaseRepository.save(row);
        });

        CreateLeaseDTO update = fixtures.draftDto(START, END, List.of(line("RENT", "99000")));
        assertThatThrownBy(() -> leaseService.updateDraftLease(lease.getId(), update))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Only DRAFT");

        // And the lines are untouched — the guard runs before applyLines' delete.
        assertThat(leaseService.getLines(lease.getId()))
                .singleElement()
                .satisfies(l -> assertThat(l.netAmount()).isEqualByComparingTo("51000"));
    }

    @Test
    void unknownChargeTypeCodeIs400() {
        CreateLeaseDTO dto = fixtures.draftDto(START, END, List.of(line("XYZ", "1000")));

        // BusinessRuleViolationException, not NotFoundException: the lease is the
        // resource being created and it is the body that is wrong, so this is a
        // 400 naming the code, not a 404 for a lease that never existed.
        assertThatThrownBy(() -> leaseService.createDraftLease(dto))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("unknown charge type XYZ");
    }

    @Test
    void aLeaseWithNoLinesIsRefused() {
        CreateLeaseDTO dto = fixtures.draftDto(START, END, List.of());
        assertThatThrownBy(() -> leaseService.createDraftLease(dto))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("At least one line is required");

        CreateLeaseDTO nullLines = fixtures.draftDto(START, END, null);
        assertThatThrownBy(() -> leaseService.createDraftLease(nullLines))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessage("At least one line is required");
    }

    /**
     * The contract number is prefixed with the property's code on documents. The
     * code is nullable and the number is null until a contract is generated, so
     * both absences have to produce something renderable.
     */
    @Test
    void displayContractNumberPrefixesThePropertyCode() {
        LeaseDTO lease = draft(line("RENT", "51000"));
        assertThat(lease.getPropertyCode()).isEqualTo(fixtures.property().getCode());
        // No contract generated yet.
        assertThat(lease.getDisplayContractNumber()).isNull();

        tx.executeWithoutResult(status -> {
            Lease row = leaseRepository.findById(lease.getId()).orElseThrow();
            row.setContractNumber(681L);
            leaseRepository.save(row);
        });

        LeaseDTO reread = leaseService.getLeaseById(lease.getId());
        assertThat(reread.getDisplayContractNumber())
                .isEqualTo(fixtures.property().getCode() + "/681");
    }

    /**
     * The migrated tenancy's own contract number (changeset 88, spec §10.3).
     *
     * <p>PACT identifies a contract as "TLP7/681"; {@code contractNumber} is a
     * {@code Long} and the column our next contract number is generated from, so
     * the imported reference gets a column of its own and the two live side by
     * side. The lease screen has to be able to show both — an accountant
     * reconciling against the old system looks the tenancy up by the old number —
     * so the DTO carries it. Written here through the entity because that is what
     * the cut-over import will do; nothing in any request body sets it.</p>
     */
    @Test
    void theDtoCarriesTheMigratedContractReferenceAlongsideOurOwnNumber() {
        LeaseDTO lease = draft(line("RENT", "51000"));
        assertThat(lease.getExternalContractRef()).as("a lease created here has none").isNull();

        tx.executeWithoutResult(status -> {
            Lease row = leaseRepository.findById(lease.getId()).orElseThrow();
            row.setExternalContractRef("TLP7/681");
            row.setContractNumber(681L);
            leaseRepository.save(row);
        });

        LeaseDTO reread = leaseService.getLeaseById(lease.getId());
        assertThat(reread.getExternalContractRef()).isEqualTo("TLP7/681");
        // ...and it has not been confused with our own sequence.
        assertThat(reread.getContractNumber()).isEqualTo(681L);
        assertThat(reread.getDisplayContractNumber()).isEqualTo(fixtures.property().getCode() + "/681");
    }

    /**
     * A charge type whose role is outside the credit allow-list is refused
     * outright when the caller names an explicit account.
     *
     * <p>The role check used to be "if we know the expected account type, enforce
     * it" — so a role with <em>no</em> expected type, which is precisely the roles
     * the allow-list keeps off the credit side, passed unchallenged. An explicit
     * {@code creditAccountId} could then credit a bank or a receivable: the entry
     * balances, nothing downstream objects, and the lease looks paid the moment it
     * posts.</p>
     *
     * <p>The rogue charge type is saved through the repository because
     * {@code ChargeTypeService} would never create it. A row like this comes from a
     * legacy import or a hand-edited catalogue, which is exactly the case the guard
     * is for.</p>
     */
    @Test
    void aChargeTypeWhoseRoleCannotBeCreditedIsRefused() {
        // A real, active, non-group leaf to point at, so the account's own
        // validations pass and the role check is what fires.
        UUID leaf = draft(line("RENT", "51000")).getLines().get(0).creditAccountId();
        assertThat(leaf).isNotNull();

        ChargeType rogue = new ChargeType();
        rogue.setCode("ROGUE_" + UUID.randomUUID().toString().substring(0, 8));
        rogue.setNameEn("Rogue charge");
        rogue.setRole(AccountRole.BANK);
        rogue.setBehaviour(ChargeBehaviour.FEE);
        rogue.setTenantId(fixtures.tenantId());
        tx.executeWithoutResult(status -> chargeTypeRepository.save(rogue));

        CreateLeaseDTO dto = fixtures.draftDto(START, END, List.of(
                new LeaseLineInput(null, rogue.getCode(), new BigDecimal("100"), BigDecimal.ZERO,
                        null, null, leaf, null, null)));

        assertThatThrownBy(() -> leaseService.createDraftLease(dto))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Role BANK cannot be credited by a charge type");
    }

    /**
     * A charge type whose role the property has no leaf for leaves the line
     * unmapped rather than refusing the draft. The gap is the accountant's to
     * close and the posting guard is where it is reported; blocking a draft on it
     * stops work on a problem the person drafting usually cannot fix.
     *
     * <p>Both mappings are removed first — the property's and the tenant default
     * the resolver falls back to. Without that the role resolves and the test
     * asserts nothing: it would pass whether or not the null-account path exists.</p>
     */
    @Test
    void aLineWhoseRoleHasNoMappedAccountIsSavedUnmapped() {
        UUID propertyId = fixtures.property().getId();
        tx.executeWithoutResult(status -> {
            propertyMappingRepo.findByPropertyIdAndRole(propertyId, AccountRole.COOLING_CHARGES)
                    .ifPresent(propertyMappingRepo::delete);
            defaultMappingRepo.findByRole(AccountRole.COOLING_CHARGES)
                    .ifPresent(defaultMappingRepo::delete);
        });

        UUID leaseId = draft(line("RENT", "51000"), line("COOLING", "1200")).getId();

        List<LeaseLineDTO> lines = leaseService.getLines(leaseId);
        assertThat(lines).hasSize(2);
        // Rent still resolves: only the cooling role was unmapped.
        assertThat(lines.get(0).creditAccountId()).isNotNull();
        assertThat(lines.get(1).chargeTypeCode()).isEqualTo("COOLING");
        assertThat(lines.get(1).creditAccountId()).isNull();
        assertThat(lines.get(1).creditAccountName()).isNull();
        // The draft was accepted anyway, with the amount intact.
        assertThat(lines.get(1).netAmount()).isEqualByComparingTo("1200");
    }

    // ---- explicit credit-account overrides ----------------------------------

    /** A leaf the property owns for a given role — the accounts the template generated. */
    private Account propertyLeaf(AccountRole role) {
        return tx.execute(status -> propertyMappingRepo
                .findByPropertyIdAndRole(fixtures.property().getId(), role)
                .orElseThrow(() -> new AssertionError("no " + role + " mapping for the fixture property"))
                .getAccount());
    }

    private void expectRejectedOverride(UUID accountId, String messageFragment) {
        CreateLeaseDTO dto = fixtures.draftDto(START, END, List.of(
                line("RENT", "51000"),
                new LeaseLineInput(null, "ADMIN_FEE", new BigDecimal("2000"), BigDecimal.ZERO,
                        null, null, accountId, null, null)));
        assertThatThrownBy(() -> leaseService.createDraftLease(dto))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Line 2 (ADMIN_FEE): credit account")
                .hasMessageContaining(messageFragment);
    }

    /**
     * A group account has no balance of its own to post to — the template parents
     * every generated leaf on one, so it is the easiest wrong answer to pick.
     */
    @Test
    void anOverrideOntoAGroupAccountIsRefused() {
        Account group = tx.execute(status -> {
            Account leaf = propertyLeaf(AccountRole.ADMIN_FEE);
            return accountRepository.findById(leaf.getParent().getId()).orElseThrow();
        });
        assertThat(group.isGroup()).isTrue();
        expectRejectedOverride(group.getId(), "is a group account");
    }

    /** An inactive leaf is one the accountant has retired; nothing should post to it. */
    @Test
    void anOverrideOntoAnInactiveAccountIsRefused() {
        Account leaf = propertyLeaf(AccountRole.ADMIN_FEE);
        tx.executeWithoutResult(status -> {
            Account a = accountRepository.findById(leaf.getId()).orElseThrow();
            a.setActive(false);
            accountRepository.save(a);
        });
        expectRejectedOverride(leaf.getId(), "is inactive");
    }

    /**
     * The one that would otherwise go unnoticed. Crediting a fee line to the
     * property's bank leaf produces an entry that still balances — it credits the
     * asset the line is supposed to debit, so the receivable nets to nothing and
     * the lease looks paid the moment it posts.
     */
    @Test
    void anOverrideOntoTheWrongAccountTypeIsRefused() {
        Account bank = propertyLeaf(AccountRole.BANK);
        assertThat(bank.getAccountType()).isEqualTo(AccountType.ASSET);
        expectRejectedOverride(bank.getId(), "must be an INCOME account");
    }

    /** A non-existent id came from the request body, so it is a 400, not a 404. */
    @Test
    void anOverrideOntoAnUnknownAccountIsRefused() {
        expectRejectedOverride(UUID.randomUUID(), "does not exist");
    }

    /** A different INCOME leaf is a legitimate override and is what the line keeps. */
    @Test
    void aValidOverrideReplacesTheResolvedAccount() {
        Account parking = propertyLeaf(AccountRole.PARKING_INCOME);
        assertThat(parking.getAccountType()).isEqualTo(AccountType.INCOME);

        CreateLeaseDTO dto = fixtures.draftDto(START, END, List.of(
                line("RENT", "51000"),
                new LeaseLineInput(null, "ADMIN_FEE", new BigDecimal("2000"), BigDecimal.ZERO,
                        null, null, parking.getId(), null, null)));
        LeaseDTO lease = leaseService.createDraftLease(dto);

        LeaseLineDTO admin = lease.getLines().get(1);
        assertThat(admin.creditAccountId()).isEqualTo(parking.getId());
        assertThat(admin.creditAccountName()).isEqualTo(parking.getName());
        // And it survives a re-read, so it was persisted rather than echoed back.
        assertThat(leaseService.getLines(lease.getId()).get(1).creditAccountId())
                .isEqualTo(parking.getId());
    }
}

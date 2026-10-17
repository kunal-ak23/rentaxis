package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.dto.BankAccountRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.BankAccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.ledger.PostingRequest;
import com.datagami.rentaxis.core.service.ledger.PostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.BankAccount;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.repository.*;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #98 (PR #356 follow-ups): which bank account owns a property's BANK leaf.
 *
 * <ul>
 *   <li>M1 — a second bank account for a building takes the building's leaf when
 *       it has no postings, even while the operating bank is reconciled; with
 *       postings it gets a leaf of its own instead of owning nothing. Leaving a
 *       reconciled set, and relinking, are refused only where postings inside the
 *       reconciled period would change.</li>
 *   <li>M2 — a new bank account adopts only the unowned property leaves in its
 *       scope: its own property's, or every property's when it serves the whole
 *       organisation; never a hand-made leaf.</li>
 *   <li>M3 — a leaf reused by name at property creation is not attached to a
 *       reconciled operating bank when it has postings inside that period.</li>
 * </ul>
 *
 * <p>Each case runs in organisation A next to an organisation B set up the same
 * way, and checks B's ownership rows did not move.</p>
 */
@SpringBootTest
class BankLeafOwnershipIT extends AbstractPostgresIT {

    static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);
    static final LocalDate SEP_30 = LocalDate.of(2026, 9, 30);
    static final LocalDate OCT_1 = LocalDate.of(2026, 10, 1);

    @Autowired BankAccountService bankAccounts;
    @Autowired BankAccountLedgerService ledgers;
    @Autowired OwnedBankLeaf ownedBankLeaf;
    @Autowired PostingService posting;
    @Autowired AccountService accountService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired JdbcTemplate jdbc;
    @Autowired BankAccountRepository bankAccountRepo;
    @Autowired BankStatementImportService statementImports;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;

    LeaseTestFixtures a, b;

    @BeforeEach
    void setUp() {
        b = fixtures().bootstrap();
        a = fixtures().bootstrap();
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    private LeaseTestFixtures fixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo, propertyService, accountService,
                propertyAccountService, chargeTypeService);
    }

    private void in(LeaseTestFixtures fx) {
        TenantContextHolder.setTenantId(fx.tenantId());
    }

    private BankAccount bank(String name, String number, Property p) {
        BankAccount x = new BankAccount();
        x.setBankName(name);
        x.setAccountNumber(number);
        x.setProperty(p);
        return bankAccounts.createBankAccount(x);
    }

    private Property property(String name) {
        Property p = new Property();
        p.setNameEn(name);
        p.setEmirate(Emirate.DUBAI);
        return propertyService.createProperty(p);
    }

    /** The property's own BANK leaf (its mapping). */
    private UUID leafOf(Property p) {
        return jdbc.queryForObject("select account_id from property_account_mappings where property_id = ? and role = 'BANK'",
                UUID.class, p.getId());
    }

    private UUID ownerOf(UUID leaf) {
        List<UUID> r = jdbc.queryForList("select bank_account_id from bank_account_ledgers where account_id = ?", UUID.class, leaf);
        return r.isEmpty() ? null : r.get(0);
    }

    private UUID coaOf(BankAccount x) {
        return jdbc.queryForObject("select coa_account_id from bank_accounts where id = ?", UUID.class, x.getId());
    }

    /** What a finalize leaves behind on the bank account (BankReconciliationService.finalizeRec). */
    private void reconcileThrough(BankAccount x, LocalDate d) {
        jdbc.update("update bank_accounts set reconciled_through = ?, rec_start_date = ? where id = ?", d, d.withDayOfMonth(1), x.getId());
    }

    /** Money into the leaf against owner's equity F-01. */
    private void post(UUID leaf, LocalDate d) {
        BigDecimal amt = new BigDecimal("100.00");
        UUID equity = accountService.getAccountByCode("F-01").getId();
        posting.post(new PostingRequest(JournalDocType.JV, d, "Owner cash", null, JournalSourceType.MANUAL, null, null,
                List.of(PostingRequest.dr(leaf, amt), PostingRequest.cr(equity, amt))));
    }

    /** Every (bank account, leaf) row of an organisation, as text, to show it did not move. */
    private List<String> ownership(LeaseTestFixtures fx) {
        return jdbc.queryForList("select bank_account_id || ':' || account_id from bank_account_ledgers where tenant_id = ? order by 1",
                String.class, fx.tenantId());
    }

    /**
     * An operating bank for the organisation, then a building whose leaf it takes.
     * Tests post first and then {@link #reconcileThrough}, as the real lock would
     * refuse a posting inside a finalized period.
     */
    private record Org(BankAccount operating, Property building) { }

    private Org operatingBankThenBuilding(LeaseTestFixtures fx, String tower) {
        in(fx);
        BankAccount ei = bank("Emirates Islamic", "0260000000123", null);
        Property p = property(tower + " " + UUID.randomUUID().toString().substring(0, 8));
        assertThat(ownerOf(leafOf(p))).as("the new building's leaf goes to the operating bank").isEqualTo(ei.getId());
        return new Org(ei, p);
    }

    // ------------------------------------------------------------------ M1

    @Test
    void aBuildingsOwnBankTakesItsUnpostedLeafWhileTheOperatingBankIsReconciled() {
        Org other = operatingBankThenBuilding(b, "Palm Court");
        reconcileThrough(other.operating(), SEP_30);
        List<String> before = ownership(b);
        Org org = operatingBankThenBuilding(a, "Marina Tower");
        post(leafOf(a.property()), SEP_15);
        reconcileThrough(org.operating(), SEP_30);
        UUID leaf = leafOf(org.building());

        BankAccount enbd = bank("ENBD", "1010000000001", org.building());

        assertThat(ownerOf(leaf)).as("the building's bank owns the building's leaf").isEqualTo(enbd.getId());
        assertThat(coaOf(enbd)).isEqualTo(leaf);
        // S20-04: the leaf was named after the operating bank; it now carries its owner's name.
        assertThat(jdbc.queryForObject("select name from accounts where id = ?", String.class, leaf))
                .isEqualTo("ENBD - " + org.building().getNameEn());
        // PR #424 review P2-1: the Chart of Accounts shows and saves name_en.
        assertThat(nameEnOf(leaf)).isEqualTo("ENBD - " + org.building().getNameEn());
        assertThat(ownerOf(leafOf(a.property()))).as("the operating bank keeps the rest").isEqualTo(org.operating().getId());
        assertThat(ownedBankLeaf.forProperty(org.building().getId())).contains(leaf);
        assertThat(ownership(b)).as("organisation B untouched").isEqualTo(before);
        in(b);
        assertThat(ownerOf(leafOf(other.building()))).isEqualTo(other.operating().getId());
    }

    @Test
    void aBuildingsLeafWithPostingsStaysAndItsNewBankGetsALeafOfItsOwn() {
        operatingBankThenBuilding(b, "Palm Court");
        List<String> before = ownership(b);
        Org org = operatingBankThenBuilding(a, "Marina Tower");
        UUID leaf = leafOf(org.building());
        reconcileThrough(org.operating(), SEP_30);
        post(leaf, OCT_1);

        BankAccount enbd = bank("ENBD", "1010000000001", org.building());

        assertThat(ownerOf(leaf)).as("history stays with the bank it was booked to").isEqualTo(org.operating().getId());
        UUID own = coaOf(enbd);
        assertThat(own).isNotEqualTo(leaf);
        assertThat(ownerOf(own)).as("the new bank owns a leaf, not nothing").isEqualTo(enbd.getId());
        assertThat(jdbc.queryForObject("select name from accounts where id = ?", String.class, own)).isEqualTo("ENBD - 0001");
        assertThat(jdbc.queryForObject("select property_id from accounts where id = ?", UUID.class, own))
                .isEqualTo(org.building().getId());
        assertThat(ledgers.requireLeafSet(enbd.getId())).containsExactly(own);
        // Review P3-6: the building's BANK mapping follows, so its new receipts land in its own bank.
        assertThat(leafOf(org.building())).isEqualTo(own);
        assertThat(ownedBankLeaf.forProperty(org.building().getId())).contains(own);
        assertThat(jdbc.queryForObject("select report_line from accounts where id = ?", String.class, own)).isEqualTo("BANK");
        assertThat(jdbc.queryForObject("select count(*) from journal_lines where account_id = ?", Integer.class, leaf))
                .as("the posting stays where it was booked").isEqualTo(1);
        assertThat(ownership(b)).isEqualTo(before);
    }

    @Test
    void aLeafLeavesAReconciledSetOnlyWhenItHasNoPostingsInsideIt() {
        operatingBankThenBuilding(b, "Palm Court");
        List<String> before = ownership(b);
        Org org = operatingBankThenBuilding(a, "Marina Tower");
        BankAccount ei = org.operating();
        UUID gla = leafOf(a.property());
        UUID tower = leafOf(org.building());
        BankAccount enbd = bank("ENBD", "1010000000001", null);
        Account bankGroup = accountService.getAccountByCode("A-02-02");
        UUID loose = accountService.createLeaf("Loose bank leaf", bankGroup, null).getId();
        post(gla, SEP_15);
        post(loose, SEP_15);
        reconcileThrough(ei, SEP_30);
        post(tower, OCT_1);   // after the reconciled period: nothing signed off includes it

        assertThatThrownBy(() -> ledgers.setLeaves(ei.getId(), List.of(tower)))
                .hasMessageContaining("with postings on or before that date cannot leave its set");
        assertThat(ledgers.setLeaves(ei.getId(), List.of(gla))).extracting(com.datagami.rentaxis.api.dto.bank.BankRecDTOs.Leaf::id)
                .containsExactly(gla);
        assertThat(ledgers.setLeaves(enbd.getId(), List.of(coaOf(enbd), tower))).hasSize(2);
        assertThat(ownerOf(tower)).isEqualTo(enbd.getId());
        // Joining a reconciled set is still refused for a leaf with postings inside it…
        assertThatThrownBy(() -> ledgers.setLeaves(ei.getId(), List.of(gla, loose)))
                .hasMessageContaining("with postings on or before that date cannot join its set");
        // …and taking another bank account's leaf is still refused with its name (spec §3).
        assertThatThrownBy(() -> ledgers.setLeaves(ei.getId(), List.of(gla, tower)))
                .hasMessageContaining("already belongs to bank account ENBD");
        assertThat(ownership(b)).isEqualTo(before);
    }

    @Test
    void relinkingMovesAnUnpostedLeafAndRefusesAPostedOne() {
        operatingBankThenBuilding(b, "Palm Court");
        List<String> before = ownership(b);
        Org org = operatingBankThenBuilding(a, "Marina Tower");
        UUID tower = leafOf(org.building());
        UUID gla = leafOf(a.property());
        BankAccount enbd = bank("ENBD", "1010000000001", null);
        UUID ownLeaf = coaOf(enbd);

        // Attached to the building and relinked to its (unposted) leaf: it moves.
        bankAccounts.updateBankAccount(enbd.getId(), new BankAccountRequest("ENBD", "1010000000001", null, null, "AED",
                new BankAccountRequest.Ref(org.building().getId()), new BankAccountRequest.Ref(tower), null, null));
        assertThat(ownerOf(tower)).isEqualTo(enbd.getId());
        assertThat(coaOf(enbd)).isEqualTo(tower);
        assertThat(ownerOf(ownLeaf)).as("the old link stays in its set").isEqualTo(enbd.getId());

        // Moved to the GLA building, whose leaf has postings under the operating bank: refused.
        post(gla, OCT_1);
        assertThatThrownBy(() -> bankAccounts.updateBankAccount(enbd.getId(), new BankAccountRequest("ENBD", "1010000000001",
                null, null, "AED", new BankAccountRequest.Ref(a.property().getId()), new BankAccountRequest.Ref(gla), null, null)))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("belongs to bank account Emirates Islamic 0260000000123 and has postings");
        assertThat(ownerOf(gla)).isEqualTo(org.operating().getId());
        assertThat(coaOf(enbd)).as("the refused relink is rolled back").isEqualTo(tower);
        assertThat(ownership(b)).isEqualTo(before);
    }

    // ------------------------------------------------------------------ M2

    @Test
    void aNewBankAccountAdoptsOnlyTheUnownedLeavesInItsScope() {
        in(b);
        Property bTower = property("Palm Court " + UUID.randomUUID().toString().substring(0, 8));
        in(a);
        Property tower = property("Marina Tower " + UUID.randomUUID().toString().substring(0, 8));
        UUID gla = leafOf(a.property());
        UUID towerLeaf = leafOf(tower);
        Account bankGroup = accountService.getAccountByCode("A-02-02");
        UUID handMade = accountService.createLeaf("Petty bank - GLA", bankGroup, a.property().getId()).getId();
        assertThat(ownerOf(gla)).isNull();
        assertThat(ownerOf(towerLeaf)).isNull();

        BankAccount towerBank = bank("ENBD", "1010000000001", tower);
        assertThat(ownerOf(towerLeaf)).isEqualTo(towerBank.getId());
        assertThat(ownerOf(gla)).as("another building's leaf is not this bank's").isNull();

        BankAccount ei = bank("Emirates Islamic", "0260000000123", null);
        assertThat(ownerOf(gla)).as("an organisation-wide bank takes what is left").isEqualTo(ei.getId());
        assertThat(ownerOf(towerLeaf)).isEqualTo(towerBank.getId());
        assertThat(ownerOf(handMade)).as("a hand-made leaf is never adopted").isNull();

        in(b);
        assertThat(ownership(b)).as("organisation B: nothing adopted").isEmpty();
        assertThat(ownerOf(leafOf(bTower))).isNull();
    }

    // ------------------------------------------------------------------ M3

    @Test
    void aLeafReusedByNameIsNotAttachedIntoAReconciledPeriod() {
        operatingBankThenBuilding(b, "Palm Court");
        List<String> before = ownership(b);
        Org org = operatingBankThenBuilding(a, "Marina Tower");
        Account parent = accountService.getAccountById(jdbc.queryForObject(
                "select parent_id from accounts where id = ?", UUID.class, leafOf(org.building())));
        String old = "Old Tower " + UUID.randomUUID().toString().substring(0, 8);
        String fresh = "New Tower " + UUID.randomUUID().toString().substring(0, 8);
        UUID oldLeaf = accountService.createLeaf("Emirates Islamic - " + old, parent, null).getId();
        UUID freshLeaf = accountService.createLeaf("Emirates Islamic - " + fresh, parent, null).getId();
        post(oldLeaf, SEP_15);    // inside the reconciliation finalized through 30/09
        reconcileThrough(org.operating(), SEP_30);
        post(freshLeaf, OCT_1);   // after it

        Property oldTower = property(old);
        Property newTower = property(fresh);

        assertThat(leafOf(oldTower)).as("reused by name").isEqualTo(oldLeaf);
        assertThat(ownerOf(oldLeaf)).as("not slipped into the signed-off period").isNull();
        assertThat(leafOf(newTower)).isEqualTo(freshLeaf);
        assertThat(ownerOf(freshLeaf)).isEqualTo(org.operating().getId());
        assertThat(ownership(b)).isEqualTo(before);
    }
    // ------------------------------------------------------------------ changeset 169

    /** A building's bank account as the old service left it: linked to the building's leaf, owning nothing. */
    private BankAccount legacyBuildingBank(LeaseTestFixtures fx, Property p, String number) {
        UUID leaf = leafOf(p);
        return tx.execute(st -> {
            BankAccount x = new BankAccount();
            x.setBankName("ENBD");
            x.setAccountNumber(number);
            x.setTenantId(fx.tenantId());
            x.setActive(true);
            x.setProperty(p);
            x.setCoaAccount(accountService.getAccountById(leaf));
            return bankAccountRepo.save(x);
        });
    }

    @Test
    void changeset169MovesOnlyUnpostedBuildingLeavesWithinEachOrganisationAndIsIdempotent() {
        Org other = operatingBankThenBuilding(b, "Palm Court");
        BankAccount bBank = legacyBuildingBank(b, other.building(), "2020000000001");
        Org org = operatingBankThenBuilding(a, "Marina Tower");
        Property posted = property("Posted Tower " + UUID.randomUUID().toString().substring(0, 8));
        post(leafOf(posted), SEP_15);
        reconcileThrough(org.operating(), SEP_30);
        BankAccount aBank = legacyBuildingBank(a, org.building(), "1010000000001");
        BankAccount aPosted = legacyBuildingBank(a, posted, "1010000000002");
        assertThat(ownerOf(leafOf(org.building()))).isEqualTo(org.operating().getId());

        for (int run = 0; run < 2; run++) {
            tx.executeWithoutResult(st -> com.datagami.rentaxis.testsupport.ChangesetSql
                    .of("169-move-unposted-building-bank-leaves.yaml").forEach(jdbc::execute));
        }

        assertThat(ownerOf(leafOf(org.building()))).isEqualTo(aBank.getId());
        assertThat(ownerOf(leafOf(posted))).as("a leaf with postings stays").isEqualTo(org.operating().getId());
        assertThat(ownerOf(leafOf(a.property()))).isEqualTo(org.operating().getId());
        assertThat(jdbc.queryForObject("select count(*) from bank_account_ledgers where bank_account_id = ?", Integer.class,
                aPosted.getId())).isZero();
        in(b);
        assertThat(ownerOf(leafOf(other.building()))).as("B's leaf goes to B's own bank account").isEqualTo(bBank.getId());
        assertThat(jdbc.queryForObject("select count(*) from bank_account_ledgers l join bank_accounts x on x.id = l.bank_account_id "
                + "where l.tenant_id <> x.tenant_id", Integer.class)).isZero();
    }
    /** Review P3-8: two building bank accounts on one leaf — the default one takes it, a NULL is_default is not default. */
    @Test
    void changeset169TieBreakPrefersTheDefaultAndTreatsNullAsNotDefault() {
        Org org = operatingBankThenBuilding(a, "Marina Tower");
        UUID leaf = leafOf(org.building());
        BankAccount older = legacyBuildingBank(a, org.building(), "1010000000001");
        BankAccount flagged = legacyBuildingBank(a, org.building(), "1010000000002");
        BankAccount newest = legacyBuildingBank(a, org.building(), "1010000000003");
        // Only raw SQL can write a NULL (the entity field is a primitive): the oldest has one.
        jdbc.update("update bank_accounts set is_default = null where id = ?", older.getId());
        jdbc.update("update bank_accounts set is_default = true where id = ?", flagged.getId());
        tx.executeWithoutResult(st -> com.datagami.rentaxis.testsupport.ChangesetSql
                .of("169-move-unposted-building-bank-leaves.yaml").forEach(jdbc::execute));
        assertThat(ownerOf(leaf)).isEqualTo(flagged.getId());

        // Without a default, the oldest takes it.
        jdbc.update("update bank_account_ledgers set bank_account_id = ? where account_id = ?", org.operating().getId(), leaf);
        jdbc.update("update bank_accounts set is_default = false where id = ?", flagged.getId());
        jdbc.update("update bank_accounts set created_at = created_at - interval '1 day' where id = ?", older.getId());
        tx.executeWithoutResult(st -> com.datagami.rentaxis.testsupport.ChangesetSql
                .of("169-move-unposted-building-bank-leaves.yaml").forEach(jdbc::execute));
        assertThat(ownerOf(leaf)).isEqualTo(older.getId());
        assertThat(jdbc.queryForObject("select count(*) from bank_account_ledgers where bank_account_id = ?", Integer.class,
                newest.getId())).isZero();
    }
    // ------------------------------------------------------------------ changeset 170

    private void importDay(BankAccount x, String rows) {
        statementImports.importFile(x.getId(), "inline-" + UUID.randomUUID() + ".csv",
                (BankReconciliationStatementIT.HEAD + rows).getBytes(java.nio.charset.StandardCharsets.UTF_8), null, false);
    }

    private List<String> days(LeaseTestFixtures fx) {
        return jdbc.queryForList("""
                select d.txn_date || ':' || d.order_confirmed from bank_statement_days d
                join bank_accounts b on b.id = d.bank_account_id and b.tenant_id = d.tenant_id
                where d.tenant_id = ? order by d.txn_date""", String.class, fx.tenantId());
    }

    /**
     * #97 review P2-1, changeset 170's backfill: a day of an open period whose lines
     * came from more than one import is unconfirmed; a one-file day, and a day inside
     * a finalized reconciliation, get no row (confirmed). Per organisation.
     */
    @Test
    void changeset170MarksMultiFileDaysOfOpenPeriodsUnconfirmed() {
        for (LeaseTestFixtures fx : List.of(b, a)) {
            in(fx);
            BankAccount ei = bank("Emirates Islamic", "0260000000123", null);
            statementImports.saveProfile(ei.getId(), BankReconciliationStatementIT.enbdProfile());
            importDay(ei, "08/09/2026,08/09/2026,FEE ONE,,5.00,,995.00\n");
            importDay(ei, "08/09/2026,08/09/2026,FEE TWO,,5.00,,990.00\n");
            importDay(ei, "10/09/2026,10/09/2026,FEE THREE,,5.00,,985.00\n");
            importDay(ei, "10/09/2026,10/09/2026,FEE FOUR,,5.00,,980.00\n");
            importDay(ei, "11/09/2026,11/09/2026,FEE FIVE,,5.00,,975.00\n11/09/2026,11/09/2026,FEE SIX,,5.00,,970.00\n");
            // P3-14: a second import of 12/09 brought only a duplicate: one import's lines, two imports' ranges.
            importDay(ei, "12/09/2026,12/09/2026,FEE SEVEN,,5.00,,965.00\n");
            importDay(ei, "12/09/2026,12/09/2026,FEE  SEVEN,,5.00,,965.00\n");
            reconcileThrough(ei, LocalDate.of(2026, 9, 9));
            assertThat(days(fx)).as("written by the imports").contains("2026-09-08:false", "2026-09-10:false");
            jdbc.update("delete from bank_statement_days where tenant_id = ?", fx.tenantId());
        }
        for (int run = 0; run < 2; run++) {
            tx.executeWithoutResult(st -> com.datagami.rentaxis.testsupport.ChangesetSql
                    .of("170-bank-statement-day-order.yaml").forEach(jdbc::execute));
        }
        assertThat(days(a)).containsExactly("2026-09-10:false");
        assertThat(days(b)).containsExactly("2026-09-10:false");
        // Changeset 171: those days were de-duplicated by the old rule: unproven too.
        for (int run = 0; run < 2; run++) {
            tx.executeWithoutResult(st -> com.datagami.rentaxis.testsupport.ChangesetSql
                    .of("171-bank-statement-day-evidence.yaml").forEach(jdbc::execute));
        }
        for (LeaseTestFixtures fx : List.of(a, b)) {
            assertThat(jdbc.queryForList("select txn_date || ':' || merge_unproven || ':' || start_known || ':' || end_known "
                    + "from bank_statement_days where tenant_id = ? order by txn_date", String.class, fx.tenantId()))
                    .containsExactly("2026-09-10:true:false:false", "2026-09-12:true:false:false");
        }
        assertThat(jdbc.queryForObject("select count(*) from bank_statement_days d join bank_accounts x on x.id = d.bank_account_id "
                + "where d.tenant_id <> x.tenant_id", Integer.class)).isZero();
    }
    /**
     * Review P3-16: changeset 169 moved building leaves without renaming them; 173 gives
     * them their owner's bank name, in each organisation, once.
     */
    @Test
    void changeset173RenamesLeavesThat169Moved() {
        Org other = operatingBankThenBuilding(b, "Palm Court");
        BankAccount bBank = legacyBuildingBank(b, other.building(), "2020000000001");
        Org org = operatingBankThenBuilding(a, "Marina Tower");
        BankAccount aBank = legacyBuildingBank(a, org.building(), "1010000000001");
        UUID leaf = leafOf(org.building());
        UUID gla = leafOf(a.property());
        String glaName = jdbc.queryForObject("select name from accounts where id = ?", String.class, gla);
        tx.executeWithoutResult(st -> com.datagami.rentaxis.testsupport.ChangesetSql
                .of("169-move-unposted-building-bank-leaves.yaml").forEach(jdbc::execute));
        assertThat(ownerOf(leaf)).isEqualTo(aBank.getId());
        assertThat(jdbc.queryForObject("select name from accounts where id = ?", String.class, leaf))
                .as("169 moves without renaming").startsWith("Emirates Islamic - ");
        // P3-17: a hand-typed leaf of the building, starting with the operating bank's name, owned by its bank.
        UUID petty = accountService.createLeaf("Emirates Islamic - petty cash", accountService.getAccountByCode("A-02-02"),
                org.building().getId()).getId();
        jdbc.update("insert into bank_account_ledgers (tenant_id, bank_account_id, account_id) values (?, ?, ?)",
                a.tenantId(), aBank.getId(), petty);
        for (int run = 0; run < 2; run++) {
            tx.executeWithoutResult(st -> com.datagami.rentaxis.testsupport.ChangesetSql
                    .of("173-rename-moved-building-bank-leaves.yaml").forEach(jdbc::execute));
        }
        assertThat(jdbc.queryForObject("select name from accounts where id = ?", String.class, leaf))
                .isEqualTo("ENBD - " + org.building().getNameEn());
        assertThat(jdbc.queryForObject("select name from accounts where id = ?", String.class, gla))
                .as("a leaf still with the operating bank keeps its name").isEqualTo(glaName);
        assertThat(jdbc.queryForObject("select name from accounts where id = ?", String.class, petty))
                .as("a hand-typed name is kept").isEqualTo("Emirates Islamic - petty cash");
        in(b);
        assertThat(ownerOf(leafOf(other.building()))).isEqualTo(bBank.getId());
        assertThat(jdbc.queryForObject("select name from accounts where id = ?", String.class, leafOf(other.building())))
                .isEqualTo("ENBD - " + other.building().getNameEn());
    }
    /** Review P3-17: the move renames only the generated "<bank> - <building>" name; a typed one stays. */
    @Test
    void aMovedLeafWithAHandTypedNameKeepsIt() {
        Org org = operatingBankThenBuilding(a, "Marina Tower");
        UUID leaf = leafOf(org.building());
        jdbc.update("update accounts set name = 'Emirates Islamic - petty cash' where id = ?", leaf);
        BankAccount enbd = bank("ENBD", "1010000000001", org.building());
        assertThat(ownerOf(leaf)).isEqualTo(enbd.getId());
        assertThat(jdbc.queryForObject("select name from accounts where id = ?", String.class, leaf))
                .isEqualTo("Emirates Islamic - petty cash");
    }

    // ------------------------------------------------------------------ S21-04: names

    private String nameOf(UUID leaf) {
        return jdbc.queryForObject("select name from accounts where id = ?", String.class, leaf);
    }

    private String nameEnOf(UUID leaf) {
        return jdbc.queryForObject("select name_en from accounts where id = ?", String.class, leaf);
    }

    /**
     * Sim r21 S21-04: a property created before the organisation has any bank account
     * gets a neutral bank leaf ("Bank - <property>", not one client's bank). The bank
     * account that adopts it — on creation, or when the leaf joins its set — names it
     * after its own bank. A name someone typed is kept, and so is another
     * organisation's.
     */
    @Test
    void aLeafMadeBeforeAnyBankIsNamedNeutrallyAndThenAfterTheBankThatTakesIt() {
        in(b);
        Property bTower = property("Palm Court " + UUID.randomUUID().toString().substring(0, 8));
        in(a);
        assertThat(propertyAccountService.getTemplate()).filteredOn(r -> r.role() == com.datagami.rentaxis.domain.entity.enums.AccountRole.BANK)
                .singleElement().extracting(com.datagami.rentaxis.api.dto.ledger.TemplateRowDTO::namePattern)
                .isEqualTo("Bank - {property}");
        UUID gla = leafOf(a.property());
        assertThat(nameOf(gla)).isEqualTo("Bank - " + a.property().getNameEn());
        Property typed = property("Sea View " + UUID.randomUUID().toString().substring(0, 8));
        UUID typedLeaf = leafOf(typed);
        jdbc.update("update accounts set name = 'Bank - Sea View escrow' where id = ?", typedLeaf);
        Property later = property("Creek Gate " + UUID.randomUUID().toString().substring(0, 8));
        UUID laterLeaf = leafOf(later);
        Property typedEn = property("Dune Court " + UUID.randomUUID().toString().substring(0, 8));
        UUID typedEnLeaf = leafOf(typedEn);
        jdbc.update("update accounts set name_en = 'Dune Court operating account' where id = ?", typedEnLeaf);

        BankAccount enbd = bank("Emirates NBD", "1010000000001", null);
        assertThat(ownerOf(gla)).isEqualTo(enbd.getId());
        assertThat(nameOf(gla)).isEqualTo("Emirates NBD - " + a.property().getNameEn());
        assertThat(nameEnOf(gla)).as("review P2-1: name_en follows").isEqualTo("Emirates NBD - " + a.property().getNameEn());
        assertThat(nameOf(typedLeaf)).as("a typed name is kept").isEqualTo("Bank - Sea View escrow");
        assertThat(nameOf(typedEnLeaf)).isEqualTo("Emirates NBD - " + typedEn.getNameEn());
        assertThat(nameEnOf(typedEnLeaf)).as("a typed English name is kept").isEqualTo("Dune Court operating account");

        // A leaf joining a set by hand: the old seed's name (as on existing organisations) takes the bank's.
        jdbc.update("delete from bank_account_ledgers where account_id = ?", laterLeaf);
        jdbc.update("update accounts set name = ?, name_en = ? where id = ?", "Emirates Islamic - " + later.getNameEn(),
                "Emirates Islamic - " + later.getNameEn(), laterLeaf);
        BankAccount adcb = bank("ADCB", "2020000000002", later);
        assertThat(ownerOf(laterLeaf)).isEqualTo(adcb.getId());
        assertThat(nameOf(laterLeaf)).isEqualTo("ADCB - " + later.getNameEn());
        assertThat(nameEnOf(laterLeaf)).isEqualTo("ADCB - " + later.getNameEn());
        jdbc.update("delete from bank_account_ledgers where account_id = ?", laterLeaf);
        jdbc.update("update accounts set name = ?, name_en = ? where id = ?", "Bank - " + later.getNameEn(),
                "Bank - " + later.getNameEn(), laterLeaf);
        ledgers.setLeaves(enbd.getId(), List.of(coaOf(enbd), gla, typedLeaf, typedEnLeaf, laterLeaf));
        assertThat(nameOf(laterLeaf)).isEqualTo("Emirates NBD - " + later.getNameEn());
        assertThat(nameEnOf(laterLeaf)).isEqualTo("Emirates NBD - " + later.getNameEn());

        in(b);
        assertThat(nameOf(leafOf(bTower))).as("organisation B: untouched").isEqualTo("Bank - " + bTower.getNameEn());
    }

    /**
     * Sim r21 S21-04, existing data: changeset 176 renames a property leaf still named
     * "Emirates Islamic - <property>" (the old seed) after the bank that owns it, when
     * that bank is not Emirates Islamic. A leaf Emirates Islamic owns, a leaf no bank
     * owns and a typed name are kept. The old seeded template pattern becomes neutral
     * unless the organisation banks with Emirates Islamic. Per organisation, idempotent.
     */
    @Test
    void changeset176RenamesOnlyUntouchedDefaultNamesOfLeavesAnotherBankOwns() {
        in(a);
        BankAccount enbd = bank("Emirates NBD", "1010000000001", null);
        UUID gla = leafOf(a.property());
        Property unowned = property("Old Town " + UUID.randomUUID().toString().substring(0, 8));
        UUID unownedLeaf = leafOf(unowned);
        jdbc.update("delete from bank_account_ledgers where account_id = ?", unownedLeaf);
        UUID petty = accountService.createLeaf("Emirates Islamic - petty cash", accountService.getAccountByCode("A-02-02"),
                a.property().getId()).getId();
        jdbc.update("insert into bank_account_ledgers (tenant_id, bank_account_id, account_id) values (?, ?, ?)",
                a.tenantId(), enbd.getId(), petty);
        Property typedEn = property("Dune Court " + UUID.randomUUID().toString().substring(0, 8));
        UUID typedEnLeaf = leafOf(typedEn);
        jdbc.update("update accounts set name = ?, name_en = 'Dune Court operating account' where id = ?",
                "Emirates Islamic - " + typedEn.getNameEn(), typedEnLeaf);
        in(b);
        BankAccount ei = bank("Emirates Islamic", "0260000000123", null);
        UUID bGla = leafOf(b.property());
        assertThat(ownerOf(bGla)).isEqualTo(ei.getId());
        // As on prod before this change: the old seed's names and pattern.
        jdbc.update("update accounts set name = ?, name_en = ? where id = ?", "Emirates Islamic - " + a.property().getNameEn(),
                "Emirates Islamic - " + a.property().getNameEn(), gla);
        jdbc.update("update accounts set name = ? where id = ?", "Emirates Islamic - " + unowned.getNameEn(), unownedLeaf);
        assertThat(nameOf(bGla)).as("adopted by Emirates Islamic").isEqualTo("Emirates Islamic - " + b.property().getNameEn());
        jdbc.update("update property_account_template_rows set name_pattern = 'Emirates Islamic - {property}' "
                + "where tenant_id in (?, ?) and role = 'BANK'", a.tenantId(), b.tenantId());
        assertThat(nameOf(gla)).isEqualTo("Emirates Islamic - " + a.property().getNameEn());
        assertThat(nameOf(unownedLeaf)).isEqualTo("Emirates Islamic - " + unowned.getNameEn());

        for (int run = 0; run < 2; run++) {
            tx.executeWithoutResult(st -> com.datagami.rentaxis.testsupport.ChangesetSql
                    .of("176-bank-leaf-default-name.yaml").forEach(jdbc::execute));
        }

        assertThat(nameOf(gla)).isEqualTo("Emirates NBD - " + a.property().getNameEn());
        assertThat(nameEnOf(gla)).as("review P2-1: name_en follows").isEqualTo("Emirates NBD - " + a.property().getNameEn());
        assertThat(nameOf(typedEnLeaf)).isEqualTo("Emirates NBD - " + typedEn.getNameEn());
        assertThat(nameEnOf(typedEnLeaf)).as("a typed English name is kept").isEqualTo("Dune Court operating account");
        assertThat(nameOf(unownedLeaf)).as("no bank owns it yet").isEqualTo("Emirates Islamic - " + unowned.getNameEn());
        assertThat(nameOf(petty)).as("a typed name").isEqualTo("Emirates Islamic - petty cash");
        assertThat(nameOf(bGla)).as("Emirates Islamic's own leaf").isEqualTo("Emirates Islamic - " + b.property().getNameEn());
        assertThat(jdbc.queryForObject("select name_pattern from property_account_template_rows where tenant_id = ? and role = 'BANK'",
                String.class, a.tenantId())).isEqualTo("Bank - {property}");
        assertThat(jdbc.queryForObject("select name_pattern from property_account_template_rows where tenant_id = ? and role = 'BANK'",
                String.class, b.tenantId())).as("an organisation banking with Emirates Islamic keeps it")
                .isEqualTo("Emirates Islamic - {property}");
    }

    /**
     * PR #424 review P2-1, existing data: 173 (and the runtime move before this fix) set
     * only {@code name}, so name_en kept "<old bank> - <building>", which the Chart of
     * Accounts shows and saves back. 177 gives such leaves their new name in name_en too:
     * only where name is the owner's generated name and name_en is still a generated
     * default; a typed name_en is kept. Per organisation, idempotent.
     */
    @Test
    void changeset177GivesRenamedLeavesTheirNewNameInNameEnToo() {
        Org other = operatingBankThenBuilding(b, "Palm Court");
        legacyBuildingBank(b, other.building(), "2020000000001");
        Org org = operatingBankThenBuilding(a, "Marina Tower");
        legacyBuildingBank(a, org.building(), "1010000000001");
        UUID leaf = leafOf(org.building());
        Org typed = new Org(org.operating(), property("Sea Gate " + UUID.randomUUID().toString().substring(0, 8)));
        legacyBuildingBank(a, typed.building(), "1010000000002");
        UUID typedLeaf = leafOf(typed.building());
        jdbc.update("update accounts set name_en = 'Sea Gate escrow' where id = ?", typedLeaf);
        // A leaf the pre-fix runtime adopt path left: name the owner's, name_en the template's.
        Property tpl = property("Old Wharf " + UUID.randomUUID().toString().substring(0, 8));
        UUID tplLeaf = leafOf(tpl);
        jdbc.update("update accounts set name = ?, name_en = ? where id = ?", "Emirates Islamic - " + tpl.getNameEn(),
                "Bank - " + tpl.getNameEn(), tplLeaf);
        Property pier = property("Pier " + UUID.randomUUID().toString().substring(0, 8));
        UUID pierLeaf = leafOf(pier);
        jdbc.update("update accounts set name = 'Pier operating (typed)' where id = ?", pierLeaf);
        String glaEn = nameEnOf(leafOf(a.property()));
        for (String cs : List.of("169-move-unposted-building-bank-leaves.yaml", "173-rename-moved-building-bank-leaves.yaml")) {
            tx.executeWithoutResult(st -> com.datagami.rentaxis.testsupport.ChangesetSql.of(cs).forEach(jdbc::execute));
        }
        assertThat(nameOf(leaf)).isEqualTo("ENBD - " + org.building().getNameEn());
        assertThat(nameEnOf(leaf)).as("the gap 177 closes").isEqualTo("Emirates Islamic - " + org.building().getNameEn());

        for (int run = 0; run < 2; run++) {
            tx.executeWithoutResult(st -> com.datagami.rentaxis.testsupport.ChangesetSql
                    .of("177-bank-leaf-name-en-follows-rename.yaml").forEach(jdbc::execute));
        }
        assertThat(nameEnOf(leaf)).isEqualTo("ENBD - " + org.building().getNameEn());
        assertThat(nameOf(typedLeaf)).isEqualTo("ENBD - " + typed.building().getNameEn());
        assertThat(nameEnOf(typedLeaf)).as("a typed English name is kept").isEqualTo("Sea Gate escrow");
        assertThat(nameEnOf(tplLeaf)).as("a template default left in name_en takes the owner's name")
                .isEqualTo("Emirates Islamic - " + tpl.getNameEn());
        assertThat(nameEnOf(leafOf(a.property()))).as("a leaf nobody renamed").isEqualTo(glaEn);
        assertThat(nameEnOf(pierLeaf)).as("a typed name: name_en is not ours to change")
                .isEqualTo("Emirates Islamic - " + pier.getNameEn());
        in(b);
        assertThat(nameEnOf(leafOf(other.building()))).isEqualTo("ENBD - " + other.building().getNameEn());
    }

    /**
     * Sim r22 S22-01 (scen/leafname.py): the usual set-up order. Two properties, then the
     * org-wide Emirates NBD adopts both leaves and names them after itself; its set is then
     * cut to the first property's leaf; ADCB, created for the second property, takes that
     * leaf and must name it after ADCB (name and name_en), not keep "Emirates NBD - P2".
     */
    @Test
    void aLeafALaterBankTakesIsNamedAfterThatBankNotTheFirstOne() {
        in(a);
        Property p1 = property("Marina Crest " + UUID.randomUUID().toString().substring(0, 8));
        Property p2 = property("SZR Office Tower " + UUID.randomUUID().toString().substring(0, 8));
        UUID l1 = leafOf(p1), l2 = leafOf(p2);
        BankAccount enbd = bank("Emirates NBD", "1010000000001", null);
        assertThat(nameOf(l2)).isEqualTo("Emirates NBD - " + p2.getNameEn());
        ledgers.setLeaves(enbd.getId(), List.of(coaOf(enbd), l1, leafOf(a.property())));
        assertThat(ownerOf(l2)).isNull();
        BankAccount adcb = bank("ADCB", "2020000000002", p2);
        assertThat(ownerOf(l2)).isEqualTo(adcb.getId());
        assertThat(nameOf(l2)).isEqualTo("ADCB - " + p2.getNameEn());
        assertThat(nameEnOf(l2)).isEqualTo("ADCB - " + p2.getNameEn());
        assertThat(nameOf(l1)).as("the first bank's own leaf keeps its name").isEqualTo("Emirates NBD - " + p1.getNameEn());
        // A typed name that only looks like another bank's stays when a bank takes it.
        Property p3 = property("Creek Point " + UUID.randomUUID().toString().substring(0, 8));
        UUID l3 = leafOf(p3);
        jdbc.update("update accounts set name = 'Emirates NBD - Creek Point escrow', name_en = 'Emirates NBD - Creek Point escrow' "
                + "where id = ?", l3);
        jdbc.update("delete from bank_account_ledgers where account_id = ?", l3);
        BankAccount fab = bank("FAB", "3030000000003", p3);
        assertThat(ownerOf(l3)).isEqualTo(fab.getId());
        assertThat(nameOf(l3)).isEqualTo("Emirates NBD - Creek Point escrow");
    }
}

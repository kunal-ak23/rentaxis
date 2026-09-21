package com.datagami.rentaxis.core.service.ledger;

import com.datagami.rentaxis.api.dto.ledger.ReverseRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.JournalEntry;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.enums.JournalDocType;
import com.datagami.rentaxis.domain.entity.enums.JournalSourceType;
import com.datagami.rentaxis.domain.entity.enums.JournalStatus;
import com.datagami.rentaxis.domain.repository.JournalEntryRepository;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code POST /journals/{id}/reverse} — and therefore {@link JournalService#reverse}
 * — belongs to manual journal vouchers and to nothing else.
 *
 * <p>Reversing the journal behind a document leaves the <em>document</em> posted
 * and its ledger empty, and neither screen says so: a voucher still reads POSTED
 * while the vendor's payable has vanished, and {@code amend} then dead-ends on
 * "already reversed". The same shape desyncs a lease, a cheque, a recognition
 * period and a settlement. Each of those is corrected where it was created, so the
 * refusal says where to go.</p>
 *
 * <p>{@code PostingService.reverse} — the internal call the documents themselves
 * make — is deliberately untouched by this guard.</p>
 */
@SpringBootTest
@Testcontainers
class JournalReverseSourceGuardIT {

    @Container @ServiceConnection
    static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JournalService journals;
    @Autowired PostingService posting;
    @Autowired AccountService accounts;
    @Autowired JournalEntryRepository entries;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired TransactionTemplate tx;

    UUID tenantId;
    Account bank, capital;

    @BeforeEach
    void setUp() {
        LandlordOrg org = new LandlordOrg();
        org.setName("JRG-" + UUID.randomUUID());
        tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        accounts.seedDefaultAccounts();
        bank = accounts.createLeaf("ENBD Main", accounts.getAccountByCode("A-02-02"), null);
        capital = accounts.createLeaf("Owner's Capital", accounts.getAccountByCode("F"), null);
    }

    @AfterEach void clear() { TenantContextHolder.clear(); }

    /** An entry of the given provenance, posted straight through the ledger's own write path. */
    private JournalEntry entryFrom(JournalSourceType sourceType) {
        return posting.post(new PostingRequest(
                JournalDocType.JV, LocalDate.of(2026, 10, 5), "fixture",
                PostingRequest.Dimensions.none(), sourceType, UUID.randomUUID(), null,
                List.of(PostingRequest.dr(bank.getId(), new BigDecimal("1000.00")),
                        PostingRequest.cr(capital.getId(), new BigDecimal("1000.00")))));
    }

    private JournalStatus statusOf(UUID entryId) {
        return tx.execute(s -> entries.findById(entryId).orElseThrow().getStatus());
    }

    private long entryCount() {
        return tx.execute(s -> entries.count());
    }

    @Test
    void aManualJournalIsStillReversible() {
        JournalEntry manual = entryFrom(JournalSourceType.MANUAL);

        journals.reverse(manual.getId(), new ReverseRequest(LocalDate.of(2026, 10, 6), "typo"));

        assertThat(statusOf(manual.getId())).isEqualTo(JournalStatus.REVERSED);
    }

    /**
     * One case per source-type family. The message names the document and where to
     * correct it, because "you cannot reverse this" without a destination is how a
     * clerk ends up editing the database.
     */
    @ParameterizedTest(name = "{0}-sourced journal is refused")
    @CsvSource({
            "VOUCHER,     voucher",
            "LEASE,       lease",
            "CHEQUE,      cheque",
            "RECOGNITION, rent recognition",
            "SETTLEMENT,  lease settlement",
            "PENALTY,     penalty",
            "IMPORT,      import batch",
            "OPENING_BALANCE, opening balance"
    })
    void aJournalThatBelongsToADocumentCannotBeReversedHere(JournalSourceType sourceType, String documentName) {
        JournalEntry e = entryFrom(sourceType);
        long before = entryCount();

        assertThatThrownBy(() -> journals.reverse(e.getId(), new ReverseRequest(LocalDate.of(2026, 10, 6), "x")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining(documentName);

        assertThat(statusOf(e.getId())).isEqualTo(JournalStatus.POSTED);
        assertThat(entryCount()).as("no mirror entry written").isEqualTo(before);
    }

    /**
     * A null source type is an entry written before the column existed — not a
     * manual voucher. The web mirrors this exact reading (it hides Reverse on a
     * null sourceType), so the two must not disagree.
     */
    @Test
    void aJournalWithNoSourceTypeCannotBeReversedHere() {
        JournalEntry e = entryFrom(null);
        long before = entryCount();

        assertThatThrownBy(() -> journals.reverse(e.getId(), new ReverseRequest(LocalDate.of(2026, 10, 6), "x")))
                .isInstanceOf(BusinessRuleViolationException.class);

        assertThat(statusOf(e.getId())).isEqualTo(JournalStatus.POSTED);
        assertThat(entryCount()).isEqualTo(before);
    }

    /** A mirror entry carries sourceType REVERSAL, so the same gate refuses reversing a reversal. */
    @Test
    void aReversalEntryCannotBeReversed() {
        JournalEntry manual = entryFrom(JournalSourceType.MANUAL);
        JournalEntry mirror = posting.reverse(manual.getId(), LocalDate.of(2026, 10, 6), "typo");

        assertThatThrownBy(() -> journals.reverse(mirror.getId(), new ReverseRequest(LocalDate.of(2026, 10, 7), "x")))
                .isInstanceOf(BusinessRuleViolationException.class);

        assertThat(statusOf(mirror.getId())).isEqualTo(JournalStatus.POSTED);
    }

    /** The second half of the immutability rule, enforced by PostingService itself. */
    @Test
    void anAlreadyReversedManualJournalIsRefused() {
        JournalEntry manual = entryFrom(JournalSourceType.MANUAL);
        journals.reverse(manual.getId(), new ReverseRequest(LocalDate.of(2026, 10, 6), "typo"));

        assertThatThrownBy(() -> journals.reverse(manual.getId(), new ReverseRequest(LocalDate.of(2026, 10, 7), "again")))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("already reversed");
    }
}

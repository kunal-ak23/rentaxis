package com.datagami.rentaxis.core.service.cutover;

import com.datagami.rentaxis.domain.entity.ImportBatch;
import com.datagami.rentaxis.domain.entity.ImportBatchEntity;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.enums.ImportBatchStatus;
import com.datagami.rentaxis.domain.entity.enums.ImportedEntityType;
import com.datagami.rentaxis.domain.repository.ImportBatchEntityRepository;
import com.datagami.rentaxis.domain.repository.ImportBatchRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR #369 R1 P2-2: a cut-over attaches a contract to an existing renter only on an
 * unambiguous match — one renter holding the email, named by the workbook, not from an
 * unposted batch. Everything else is a row error, never a guess.
 */
class CutoverRenterMatcherTest {

    private final UUID tenant = UUID.randomUUID();
    private final RenterRepository renters = mock(RenterRepository.class);
    private final ImportBatchEntityRepository links = mock(ImportBatchEntityRepository.class);
    private final ImportBatchRepository batches = mock(ImportBatchRepository.class);
    private final CutoverRenterMatcher matcher = new CutoverRenterMatcher(renters, links, batches);

    private Renter renter(String name) {
        Renter r = new Renter();
        r.setId(UUID.randomUUID());
        r.setNameEn(name);
        r.setEmail("accounts@group.ae");
        return r;
    }

    private void held(Renter... rs) {
        when(renters.findByTenantIdAndEmailNormalised(eq(tenant), any())).thenReturn(List.of(rs));
    }

    @Test
    void oneRenterNamedAlikeIsMatched() {
        Renter r = renter("Gulf Trading LLC");
        held(r);
        CutoverRenterMatcher.Match m = matcher.match(tenant, "  Accounts@Group.AE ", "gulf  trading llc");
        assertThat(m.reusable()).isTrue();
        assertThat(m.renterId()).isEqualTo(r.getId());
    }

    @Test
    void aSharedEmailIsRefusedNamingEveryone() {
        held(renter("Gulf Trading LLC"), renter("Gulf Logistics LLC"));
        CutoverRenterMatcher.Match m = matcher.match(tenant, "accounts@group.ae", "Gulf Trading LLC");
        assertThat(m.reusable()).isFalse();
        assertThat(m.problem()).contains("2 renters").contains("Gulf Logistics LLC");
    }

    @Test
    void aMistypedEmailThatBelongsToSomebodyElseIsRefused() {
        held(renter("Gulf Trading LLC"));
        CutoverRenterMatcher.Match m = matcher.match(tenant, "accounts@group.ae", "Ahmed Al Mansoori");
        assertThat(m.reusable()).isFalse();
        assertThat(m.nameProblem()).isTrue();
    }

    @Test
    void anUnnamedReferenceIsRefused() {
        held(renter("Gulf Trading LLC"));
        CutoverRenterMatcher.Match m = matcher.match(tenant, "accounts@group.ae", "");
        assertThat(m.reusable()).isFalse();
        assertThat(m.problem()).contains("List them on the Renters sheet").contains("RenterName");
    }

    @Test
    void aRenterFromAnUnpostedBatchIsRefused() {
        Renter r = renter("Gulf Trading LLC");
        held(r);
        UUID batchId = UUID.randomUUID();
        ImportBatchEntity link = new ImportBatchEntity();
        link.setBatchId(batchId);
        when(links.findByEntityTypeAndEntityId(ImportedEntityType.RENTER, r.getId())).thenReturn(List.of(link));
        ImportBatch b = new ImportBatch();
        b.setTenantId(tenant);
        b.setLabel("Workbook 1");
        b.setStatus(ImportBatchStatus.DRAFT);
        when(batches.findById(batchId)).thenReturn(Optional.of(b));
        assertThat(matcher.match(tenant, "accounts@group.ae", "Gulf Trading LLC").problem()).contains("DRAFT");
        b.setStatus(ImportBatchStatus.POSTED);
        assertThat(matcher.match(tenant, "accounts@group.ae", "Gulf Trading LLC").reusable()).isTrue();
    }

    @Test
    void noRenterIsNoMatch() {
        held();
        assertThat(matcher.match(tenant, "new@renter.ae", "New Renter")).isNull();
    }
}

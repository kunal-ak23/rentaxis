package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.api.dto.lease.LeaseChainLinkDTO;
import com.datagami.rentaxis.api.dto.lease.RenewLeaseRequest;
import com.datagami.rentaxis.api.dto.lease.TransferLeaseRequest;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.LeaseService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserPropertyAssignmentRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import com.datagami.rentaxis.testsupport.LeaseTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * #100-e: {@code GET /leases/{id}/chain} — the whole history of a lease through
 * renewals and unit transfers, oldest first, each link labelled, and only the
 * contracts the caller may read.
 */
@org.springframework.boot.test.context.SpringBootTest
class LeaseChainIT extends AbstractPostgresIT {

    @Autowired LeaseChainService chains;
    @Autowired LeaseRenewalService renewal;
    @Autowired LeaseTransferService transfers;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService chequeGeneration;
    @Autowired LeaseService leaseService;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;
    @Autowired TransactionTemplate tx;

    private LeaseTestFixtures fixtures;

    private static final LocalDate H_START = LocalDate.of(2025, 1, 1);
    private static final LocalDate H_END = LocalDate.of(2025, 12, 31);
    private static final LocalDate A_START = LocalDate.of(2026, 1, 1);
    private static final LocalDate A_END = LocalDate.of(2026, 12, 31);
    private static final LocalDate T = LocalDate.of(2026, 5, 15);

    @BeforeEach
    void setUp() {
        fixtures = new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap()
                .withLeaseServices(leaseService, chequeGeneration, posting);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void theWholeChainInOrderFromAnyLeaseInIt() {
        Chain c = renewThenTransfer();

        List<LeaseChainLinkDTO> fromMiddle = chains.chain(c.a);
        assertThat(fromMiddle).extracting(LeaseChainLinkDTO::id, LeaseChainLinkDTO::link, LeaseChainLinkDTO::unitIdentifier)
                .containsExactly(tuple(c.h, null, fixtures.unit().getUnitNumber()),
                        tuple(c.a, LeaseChainLinkDTO.RENEWED, fixtures.unit().getUnitNumber()),
                        tuple(c.b, LeaseChainLinkDTO.TRANSFERRED, "C-501"));
        assertThat(fromMiddle.get(0).status()).isEqualTo(LeaseStatus.RENEWED);
        assertThat(fromMiddle.get(1).startDate()).isEqualTo(A_START);
        assertThat(fromMiddle.get(1).endDate()).isEqualTo(A_END);
        assertThat(fromMiddle.get(2).status()).isEqualTo(LeaseStatus.DRAFT);
        assertThat(fromMiddle.get(2).startDate()).isEqualTo(T.plusDays(1));

        assertThat(chains.chain(c.h)).extracting(LeaseChainLinkDTO::id).containsExactly(c.h, c.a, c.b);
        assertThat(chains.chain(c.b)).extracting(LeaseChainLinkDTO::id).containsExactly(c.h, c.a, c.b);
    }

    @Test
    void aLeaseWithNoHistoryIsAChainOfOne() {
        UUID lone = fixtures.postedLease(H_START.minusDays(10), H_START, H_END,
                List.of(line("RENT", "60000")), 1, null).lease().getId();
        assertThat(chains.chain(lone)).singleElement().satisfies(l -> {
            assertThat(l.id()).isEqualTo(lone);
            assertThat(l.link()).isNull();
        });
    }

    /** A manager sees the contracts of their own building only; a lease elsewhere is not there at all. */
    @Test
    void aPropertyManagerDoesNotSeeTheChainOnBuildingsTheyCannotRead() {
        Chain c = renewThenTransfer();

        asPropertyManagerFor(fixtures.property().getId());
        assertThat(chains.chain(c.a)).extracting(LeaseChainLinkDTO::id).containsExactly(c.h, c.a);
        assertThatThrownBy(() -> chains.chain(c.b)).isInstanceOf(NotFoundException.class);

        asPropertyManagerFor(c.otherProperty);
        assertThat(chains.chain(c.b)).extracting(LeaseChainLinkDTO::id).containsExactly(c.b);
        assertThatThrownBy(() -> chains.chain(c.a)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anotherTenantsLeaseIsNotFound() {
        Chain c = renewThenTransfer();
        fixtures.newTenant();
        fixtures.asTenantAdmin();
        assertThatThrownBy(() -> chains.chain(c.a)).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------

    private record Chain(UUID h, UUID a, UUID b, UUID otherProperty) {
    }

    /** H (2025) renewed into A (2026), A transferred on 15 May to a unit in another building (B, still a draft). */
    private Chain renewThenTransfer() {
        UUID h = fixtures.postedLease(H_START.minusDays(10), H_START, H_END,
                List.of(line("RENT", "60000")), 1, null).lease().getId();
        UUID a = renewal.renew(h, new RenewLeaseRequest(H_END.minusDays(14), A_START, A_END,
                List.of(line("RENT", "60000")), false)).getId();
        fixtures.generateGrid(a, 1, A_START);
        posting.post(a);
        Property other = tx.execute(s -> fixtures.createProperty("CHN"));
        Unit target = tx.execute(s -> fixtures.createUnit(other, "C-501"));
        UUID b = transfers.draft(a, new TransferLeaseRequest(T, target.getId(), null, null,
                List.of(line("RENT", "37808.22")), null), posting).getId();
        return new Chain(h, a, b, other.getId());
    }

    private void asPropertyManagerFor(UUID propertyId) {
        User pm = new User();
        pm.setEmail("pm-" + UUID.randomUUID() + "@t.io");
        pm.setName("PM");
        pm.setRole(UserRole.PROPERTY_MANAGER);
        pm.setStatus(UserStatus.ACTIVE);
        pm.setPasswordHash("x");
        pm.setTenantId(fixtures.tenantId());
        UUID userId = userRepo.save(pm).getId();
        UserPropertyAssignment assignment = new UserPropertyAssignment();
        assignment.setUserId(userId);
        assignment.setPropertyId(propertyId);
        assignmentRepo.save(assignment);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_PROPERTY_MANAGER"))));
    }
}

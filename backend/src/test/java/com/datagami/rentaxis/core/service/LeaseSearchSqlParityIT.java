package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.LeaseDTO;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.service.lease.ChequeGenerationService;
import com.datagami.rentaxis.core.service.lease.LeasePostingService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.UserPropertyAssignment;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.datagami.rentaxis.testsupport.LeaseTestFixtures.line;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /leases/paged?search=} moved from an in-memory filter over the mapped DTOs to SQL
 * ({@code LeaseRepository.searchText}). This replays the old filter — verbatim, as the oracle —
 * over the same rows and checks the SQL answer is the same set, the same total and the same
 * order, page by page, for a seeded set with two organisations, Arabic and accented names,
 * LIKE metacharacters, Ejari numbers and every status the old filter matched by name.
 */
@SpringBootTest
class LeaseSearchSqlParityIT extends AbstractPostgresIT {

    @Autowired LeaseService leaseService;
    @Autowired LeasePostingService posting;
    @Autowired ChequeGenerationService generation;
    @Autowired AccountService accountService;
    @Autowired PropertyService propertyService;
    @Autowired PropertyAccountService propertyAccountService;
    @Autowired ChargeTypeService chargeTypeService;
    @Autowired LandlordOrgRepository orgRepo;
    @Autowired UserRepository userRepo;
    @Autowired RenterRepository renterRepo;
    @Autowired UnitRepository unitRepo;
    @Autowired LeaseRepository leaseRepo;
    @Autowired UserPropertyAssignmentRepository assignmentRepo;

    private static final String[] RENTERS = {
            "محمد العلي", "أحمد بن راشد", "Mohammed Al Ali", "José Émile", "OMAR nair", "Fifty%Off Trading",
            "snake_case LLC", "back\\slash", "Ahmed Kumar", "فاطمة الزهراء", "Ali Draft-Holder", "Activ8 Ltd"};
    private static final String[] UNITS = {"A-101", "A-102", "ب-12", "B_7", "PH%1", "101", "202", "Shop 3",
            "ACT-9", "x", "Ünit 5", "٣٠٢"};
    private static final LeaseStatus[] STATUSES = {LeaseStatus.ACTIVE, LeaseStatus.DRAFT, LeaseStatus.EXPIRED,
            LeaseStatus.RENEWED, LeaseStatus.TERMINATED, LeaseStatus.NOTICE_GIVEN, LeaseStatus.ACTIVE,
            LeaseStatus.ACTIVE, LeaseStatus.DRAFT, LeaseStatus.CLOSED, LeaseStatus.ACTIVE, LeaseStatus.EXPIRED};

    private static final List<String> TERMS = List.of("محمد", "العلي", "أحمد", "ب", "٣٠", "a", "A-1", "al ali",
            "MOHAMMED", "josé", "ÉMILE", "émile", "ünit", "%", "_", "\\", "fifty%", "e_c", "ej-2026", "EJ-2026-00",
            "act", "draft", "expired", "notice", "Closed", "zzz", "1", " Omar ", "tower", "برج", "palm",
            "heights", "x");

    private UUID tenantA;
    private UUID tenantB;
    private Property towerA;   // English name
    private Property towerB;   // Arabic name
    private final List<UUID> leasesA = new ArrayList<>();
    private final List<UUID> leasesB = new ArrayList<>();

    @BeforeEach
    void seed() {
        LeaseTestFixtures fb = fixtures();
        tenantB = fb.tenantId();
        Property bOnly = property(fb, "Palm Tower", "PTB");
        seedLeases(fb, List.of(bOnly), leasesB);

        LeaseTestFixtures fa = fixtures();
        tenantA = fa.tenantId();
        towerA = property(fa, "Palm Heights Tower", "PHT");
        towerB = property(fa, "برج النخيل", "BRJ");
        seedLeases(fa, List.of(towerA, towerB), leasesA);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        LeaseTestFixtures.clearAuth();
    }

    @Test
    void sqlSearchMatchesTheInMemoryFilterForEveryTermStatusAndExclusion() {
        asTenant(tenantA);
        int compared = 0;
        for (String term : TERMS) {
            for (LeaseStatus status : new LeaseStatus[]{null, LeaseStatus.ACTIVE, LeaseStatus.DRAFT}) {
                for (List<LeaseStatus> excluded : List.of(List.<LeaseStatus>of(), List.of(LeaseStatus.DRAFT, LeaseStatus.CLOSED))) {
                    for (Sort sort : List.of(Sort.by(Sort.Direction.DESC, "startDate"), Sort.by("endDate"), Sort.unsorted())) {
                        assertSameAsInMemory(term, status, null, excluded, sort);
                        compared++;
                    }
                }
            }
            assertSameAsInMemory(term, null, towerB.getId(), List.of(), Sort.by(Sort.Direction.DESC, "startDate"));
        }
        assertThat(compared).isEqualTo(TERMS.size() * 3 * 2 * 3);
    }

    @Test
    void anArabicTermFindsTheArabicRenterUnitAndProperty() {
        asTenant(tenantA);
        assertThat(ids(leaseService.getAllLeasesPaged("محمد", null, null, null, null, PageRequest.of(0, 50))))
                .hasSize(1);
        assertThat(ids(leaseService.getAllLeasesPaged("برج", null, null, null, null, PageRequest.of(0, 50))))
                .hasSize(RENTERS.length / 2);
        assertThat(ids(leaseService.getAllLeasesPaged("ب-12", null, null, null, null, PageRequest.of(0, 50))))
                .hasSize(1);
    }

    @Test
    void likeMetacharactersMatchLiterally() {
        asTenant(tenantA);
        // "%" is only in one renter and one unit; as a wildcard it would match every row.
        assertThat(leaseService.getAllLeasesPaged("%", null, null, null, null, PageRequest.of(0, 50))
                .getTotalElements()).isEqualTo(2);
        // "_": one renter, one unit, and the NOTICE_GIVEN contract (status names were always matched).
        assertThat(leaseService.getAllLeasesPaged("_", null, null, null, null, PageRequest.of(0, 50))
                .getTotalElements()).isEqualTo(3);
    }

    @Test
    void aPropertyManagerSearchesOnlyTheirOwnProperties() {
        asTenant(tenantA);
        Set<UUID> inTowerA = new java.util.HashSet<>(ids(leaseService.getAllLeasesPaged(null, null, towerA.getId(),
                null, null, PageRequest.of(0, 100))));
        assertThat(inTowerA).isNotEmpty().hasSizeLessThan(leasesA.size());

        asPropertyManagerFor(tenantA, towerA.getId());
        // "a" matches rows in both towers (and every status containing an "a").
        Page<LeaseDTO> page = leaseService.getAllLeasesPaged("a", null, null, null, null,
                PageRequest.of(0, 3, Sort.by(Sort.Direction.DESC, "startDate")));
        assertThat(page.getContent()).allMatch(l -> l.getPropertyId().equals(towerA.getId()));
        List<UUID> all = pageThrough("a", null, null, List.of(), Sort.by(Sort.Direction.DESC, "startDate"), 3);
        assertThat(all).isNotEmpty().allMatch(inTowerA::contains);
        assertThat(page.getTotalElements()).isEqualTo(all.size());

        // The same scope without a term (was in memory too, now in the query).
        Page<LeaseDTO> noTerm = leaseService.getAllLeasesPaged(null, null, null, null, null, PageRequest.of(0, 100));
        assertThat(ids(noTerm)).containsExactlyInAnyOrderElementsOf(inTowerA);
        assertThat(noTerm.getTotalElements()).isEqualTo(inTowerA.size());

        // Asking for the other tower's property id gives nothing, not that tower.
        assertThat(leaseService.getAllLeasesPaged("a", null, towerB.getId(), null, null, PageRequest.of(0, 100))
                .getTotalElements()).isZero();
    }

    @Test
    void aPropertyManagerWithNoAssignmentSeesNothing() {
        asTenant(tenantA);
        asPropertyManagerFor(tenantA, null);
        assertThat(leaseService.getAllLeasesPaged("a", null, null, null, null, PageRequest.of(0, 100))
                .getTotalElements()).isZero();
        assertThat(leaseService.getAllLeasesPaged(null, null, null, null, null, PageRequest.of(0, 100))
                .getTotalElements()).isZero();
    }

    @Test
    void aSearchNeverCrossesOrganisations() {
        // Tenant B has renters with the very same names.
        asTenant(tenantA);
        for (String term : List.of("محمد", "a", "Palm", "act", "%")) {
            assertThat(pageThrough(term, null, null, List.of(), Sort.by("startDate"), 4))
                    .as(term).allMatch(leasesA::contains).noneMatch(leasesB::contains);
        }
        asTenant(tenantB);
        assertThat(leaseService.getAllLeasesPaged(null, null, null, null, null, PageRequest.of(0, 100)).getContent())
                .extracting(LeaseDTO::getId).containsAll(leasesB);
        assertThat(leaseService.getAllLeasesPaged(null, null, null, null, null, PageRequest.of(0, 100)).getContent())
                .extracting(LeaseDTO::getRenterName).contains("محمد العلي");
        assertThat(pageThrough("محمد", null, null, List.of(), Sort.unsorted(), 4))
                .hasSize(1).allMatch(leasesB::contains);

        // The repository binds tenant_id itself: with no tenant in context, TenantAspect turns no
        // Hibernate tenant filter on, and with tenant A's id none of tenant B's rows come back.
        TenantContextHolder.clear();
        Page<Lease> raw = leaseRepo.searchText(tenantA, EnumSet.allOf(LeaseStatus.class), null, null, null,
                false, List.of(new UUID(0, 0)), "%a%", false, EnumSet.allOf(LeaseStatus.class), PageRequest.of(0, 500));
        assertThat(raw.getContent()).isNotEmpty().extracting(Lease::getId).allMatch(leasesA::contains);
        assertThat(raw.getContent()).extracting(Lease::getTenantId).containsOnly(tenantA);
        // A term that matches through the lease's own columns (every status name) leaves the
        // outer l.tenantId binding as the only guard: still tenant A's contracts only.
        Page<Lease> byStatus = leaseRepo.searchText(tenantA, EnumSet.allOf(LeaseStatus.class), null, null, null,
                false, List.of(new UUID(0, 0)), "%zzz-no-column-matches%", true, EnumSet.allOf(LeaseStatus.class),
                PageRequest.of(0, 500));
        assertThat(byStatus.getContent()).extracting(Lease::getId).containsAll(leasesA).noneMatch(leasesB::contains);
    }

    @Test
    void escapeLikeAndTiebreak() {
        assertThat(com.datagami.rentaxis.core.util.Search.like(" a%b_c\\D ")).isEqualTo("%a\\%b\\_c\\\\d%");
        Pageable p = LeaseService.withIdTiebreak(PageRequest.of(2, 10, Sort.by(Sort.Direction.DESC, "startDate")));
        assertThat(p.getSort().toList()).extracting(Sort.Order::getProperty).containsExactly("startDate", "id");
        assertThat(p.getPageNumber()).isEqualTo(2);
        assertThat(LeaseService.withIdTiebreak(Pageable.unpaged()).isUnpaged()).isTrue();
    }

    // ---------------------------------------------------------------------------------------------

    /** The SQL answer, page by page, against the old in-memory filter over the same rows. */
    private void assertSameAsInMemory(String term, LeaseStatus status, UUID propertyId, List<LeaseStatus> excluded,
                                      Sort sort) {
        List<LeaseDTO> universe = leaseService.getAllLeasesPaged(null, status, propertyId, null, excluded,
                Pageable.unpaged()).getContent();
        String token = term.trim().toLowerCase(Locale.ROOT);
        List<LeaseDTO> expected = universe.stream().filter(l -> oldFilter(l, token)).collect(Collectors.toList());
        expected.sort(comparator(sort));
        List<UUID> expectedIds = expected.stream().map(LeaseDTO::getId).toList();

        String what = "term=" + term + " status=" + status + " property=" + propertyId + " excl=" + excluded + " " + sort;
        List<UUID> actual = pageThrough(term, status, propertyId, excluded, sort, 4);
        assertThat(actual).as(what).containsExactlyElementsOf(expectedIds);
        assertThat(leaseService.getAllLeasesPaged(term, status, propertyId, null, excluded, PageRequest.of(0, 4, sort))
                .getTotalElements()).as(what).isEqualTo(expectedIds.size());
    }

    /** The filter getAllLeasesPaged ran in memory before the scale follow-up, verbatim. */
    private static boolean oldFilter(LeaseDTO l, String token) {
        return containsIgnoreCase(l.getUnitIdentifier(), token)
                || containsIgnoreCase(l.getRenterName(), token)
                || containsIgnoreCase(l.getPropertyName(), token)
                || containsIgnoreCase(l.getEjariNumber(), token)
                || (l.getStatus() != null && l.getStatus().name().toLowerCase(Locale.ROOT).contains(token));
    }

    private static boolean containsIgnoreCase(String value, String token) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(token);
    }

    /** The requested order, ties broken by id — what the database is asked for. */
    private static Comparator<LeaseDTO> comparator(Sort sort) {
        Comparator<LeaseDTO> c = (a, b) -> 0;
        for (Sort.Order o : sort) {
            Comparator<LeaseDTO> k = switch (o.getProperty()) {
                case "startDate" -> Comparator.comparing(LeaseDTO::getStartDate);
                case "endDate" -> Comparator.comparing(LeaseDTO::getEndDate);
                default -> throw new IllegalArgumentException(o.getProperty());
            };
            c = c.thenComparing(o.isDescending() ? k.reversed() : k);
        }
        // Postgres orders uuid bytewise, which is UUID's string order (not UUID.compareTo, which is signed).
        return c.thenComparing(l -> l.getId().toString());
    }

    private List<UUID> pageThrough(String term, LeaseStatus status, UUID propertyId, List<LeaseStatus> excluded,
                                   Sort sort, int size) {
        List<UUID> out = new ArrayList<>();
        for (int page = 0; ; page++) {
            Page<LeaseDTO> p = leaseService.getAllLeasesPaged(term, status, propertyId, null, excluded,
                    PageRequest.of(page, size, sort));
            out.addAll(ids(p));
            if (!p.hasNext()) return out;
        }
    }

    private static List<UUID> ids(Page<LeaseDTO> p) {
        return p.getContent().stream().map(LeaseDTO::getId).toList();
    }

    private LeaseTestFixtures fixtures() {
        return new LeaseTestFixtures(orgRepo, userRepo, renterRepo, unitRepo,
                propertyService, accountService, propertyAccountService, chargeTypeService)
                .bootstrap().withLeaseServices(leaseService, generation, posting);
    }

    private Property property(LeaseTestFixtures f, String name, String code) {
        Property p = new Property();
        p.setNameEn(name);
        p.setEmirate(Emirate.DUBAI);
        p.setCode(code + "_" + UUID.randomUUID().toString().substring(0, 4));
        p.setTenantId(f.tenantId());
        return propertyService.createProperty(p);
    }

    /** One contract per renter name, alternating the given properties; statuses and Ejari set directly. */
    private void seedLeases(LeaseTestFixtures f, List<Property> properties, List<UUID> out) {
        for (int i = 0; i < RENTERS.length; i++) {
            Unit u = f.createUnit(properties.get(i % properties.size()), UNITS[i]);
            Renter r = f.createRenter(RENTERS[i]);
            // Pairs of contracts share a start date, so the id tiebreak is exercised.
            LocalDate start = LocalDate.of(2025, 1 + (i / 2), 1);
            UUID id = f.draftLease(u, r, start.minusDays(10), start, start.plusYears(1).minusDays(1),
                    List.of(line("RENT", "48000")));
            Lease lease = leaseRepo.findById(id).orElseThrow();
            lease.setStatus(STATUSES[i]);
            if (i % 3 == 0) lease.setEjariNumber("EJ-2026-00" + i);
            leaseRepo.save(lease);
            out.add(id);
        }
    }

    private void asTenant(UUID tenantId) {
        TenantContextHolder.setTenantId(tenantId);
        LeaseTestFixtures.authenticateAsTenantAdmin();
    }

    private void asPropertyManagerFor(UUID tenantId, UUID propertyId) {
        User pm = new User();
        pm.setEmail("pm-" + UUID.randomUUID() + "@t.io");
        pm.setName("PM");
        pm.setRole(UserRole.PROPERTY_MANAGER);
        pm.setStatus(UserStatus.ACTIVE);
        pm.setPasswordHash("x");
        pm.setTenantId(tenantId);
        UUID userId = userRepo.save(pm).getId();
        if (propertyId != null) {
            UserPropertyAssignment a = new UserPropertyAssignment();
            a.setUserId(userId);
            a.setPropertyId(propertyId);
            assignmentRepo.save(a);
        }
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_PROPERTY_MANAGER"))));
    }
}

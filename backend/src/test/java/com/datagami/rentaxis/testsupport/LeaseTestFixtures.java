package com.datagami.rentaxis.testsupport;

import com.datagami.rentaxis.api.dto.CreateLeaseDTO;
import com.datagami.rentaxis.api.dto.lease.LeaseLineInput;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.PropertyService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.core.service.lease.ChargeTypeService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.entity.enums.UserStatus;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A tenant with a working chart of accounts, a property whose per-property
 * account set exists, a vacant unit and a renter — everything a lease needs
 * before it can be drafted, let alone posted.
 *
 * <p>It exists because assembling that is not one or two lines. A lease line
 * resolves its credit account through {@code AccountResolver}, which needs the
 * PACT chart seeded <em>and</em> the property-account template seeded <em>and</em>
 * the property created afterwards so {@code generateMissing} had a template to
 * work from. Get the order wrong and every line comes back with a null credit
 * account and the test still passes for the wrong reason. Plans 3 and 5 build on
 * the same ground, which is why this is a fixture rather than a private method on
 * one IT.</p>
 *
 * <p>A plain object rather than a Spring {@code @Component}: {@code testsupport}
 * sits under the application's scanned package, so a {@code @Component} here
 * would be registered into every Spring context in the suite, including the ones
 * that have nothing to do with leases. Tests construct it from autowired beans
 * instead — the same shape as {@link RenewalTestFixtures}.</p>
 *
 * <p><b>Transactions.</b> {@code TenantAspect} only enables the Hibernate tenant
 * filter around repository calls, and each call here is its own transaction. That
 * is fine for writing fixtures, but a test reading rows back should do so inside
 * a {@code TransactionTemplate} or a {@code @Transactional} helper, or lazy
 * associations will not resolve.</p>
 */
public class LeaseTestFixtures {

    private final LandlordOrgRepository orgRepo;
    private final UserRepository userRepo;
    private final RenterRepository renterRepo;
    private final UnitRepository unitRepo;
    private final PropertyService propertyService;
    private final AccountService accountService;
    private final PropertyAccountService propertyAccountService;
    private final ChargeTypeService chargeTypeService;

    private UUID tenantId;
    private Property property;
    private Unit unit;
    private Renter renter;

    public LeaseTestFixtures(LandlordOrgRepository orgRepo,
                             UserRepository userRepo,
                             RenterRepository renterRepo,
                             UnitRepository unitRepo,
                             PropertyService propertyService,
                             AccountService accountService,
                             PropertyAccountService propertyAccountService,
                             ChargeTypeService chargeTypeService) {
        this.orgRepo = orgRepo;
        this.userRepo = userRepo;
        this.renterRepo = renterRepo;
        this.unitRepo = unitRepo;
        this.propertyService = propertyService;
        this.accountService = accountService;
        this.propertyAccountService = propertyAccountService;
        this.chargeTypeService = chargeTypeService;
    }

    /**
     * Fresh tenant, seeded accounting, one property + unit + renter. Leaves the
     * tenant set on {@link TenantContextHolder}; the test clears it.
     */
    public LeaseTestFixtures bootstrap() {
        newTenant();
        asTenantAdmin();
        seedAccounting();
        this.property = createProperty("GLA");
        this.unit = createUnit(property, "101");
        this.renter = createRenter("Test Renter");
        return this;
    }

    /**
     * A TENANT_ADMIN in the security context.
     *
     * <p>Not optional scaffolding: {@code LeaseAccessPolicy} fails closed, so a
     * service call made with no {@code Authentication} at all resolves to "sees
     * nothing" and {@code getLeaseById} / {@code getLines} answer "Lease not
     * found" for a lease that was created two lines earlier. A test reading a
     * lease back through the service has to authenticate as somebody.</p>
     */
    public void asTenantAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        UUID.randomUUID().toString(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_TENANT_ADMIN"))));
    }

    /** Companion to {@link #asTenantAdmin()}; call alongside clearing the tenant. */
    public static void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    /** A new landlord org, made current. */
    public UUID newTenant() {
        LandlordOrg org = new LandlordOrg();
        org.setName("Lease-IT-" + UUID.randomUUID());
        this.tenantId = orgRepo.save(org).getId();
        TenantContextHolder.setTenantId(tenantId);
        return tenantId;
    }

    /**
     * Exactly what {@code POST /api/v1/finance/accounts/seed} does, in the same
     * order: the chart first, then the per-property template and tenant defaults
     * that reference its codes, then the charge-type catalogue. Seeding the
     * template before the chart silently skips every row whose parent code does
     * not exist yet.
     */
    public void seedAccounting() {
        accountService.seedDefaultAccounts();
        propertyAccountService.seedDefaultTemplateAndDefaults();
        chargeTypeService.seedDefaults();
    }

    /**
     * Created through {@link PropertyService} rather than the repository, because
     * that is what triggers {@code generateMissing} and gives the property its own
     * leaf accounts ("Advance Rent - <name>", …). A repository save would leave
     * every lease line unmapped.
     */
    public Property createProperty(String code) {
        Property p = new Property();
        p.setNameEn("Marina Heights " + UUID.randomUUID().toString().substring(0, 8));
        p.setEmirate(Emirate.DUBAI);
        p.setCode(code == null ? null : code + "_" + UUID.randomUUID().toString().substring(0, 4));
        p.setTenantId(tenantId);
        return propertyService.createProperty(p);
    }

    public Unit createUnit(Property property, String unitNumber) {
        Unit u = new Unit();
        u.setProperty(property);
        u.setUnitNumber(unitNumber);
        u.setTenantId(tenantId);
        return unitRepo.save(u);
    }

    public Renter createRenter(String name) {
        User u = new User();
        u.setEmail("renter+" + UUID.randomUUID() + "@test");
        u.setName(name);
        u.setRole(UserRole.RENTER);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash("x");
        u.setTenantId(tenantId);
        u = userRepo.save(u);

        Renter r = new Renter();
        r.setUserId(u.getId());
        r.setNameEn(name);
        r.setTenantId(tenantId);
        return renterRepo.save(r);
    }

    /** A draft-lease body for this fixture's unit and renter. */
    public CreateLeaseDTO draftDto(Unit unit, Renter renter, LocalDate start, LocalDate end,
                                   List<LeaseLineInput> lines) {
        CreateLeaseDTO dto = new CreateLeaseDTO();
        dto.setUnitId(unit.getId());
        dto.setRenterId(renter.getId());
        dto.setStartDate(start);
        dto.setEndDate(end);
        dto.setLines(lines);
        return dto;
    }

    public CreateLeaseDTO draftDto(LocalDate start, LocalDate end, List<LeaseLineInput> lines) {
        return draftDto(unit, renter, start, end, lines);
    }

    /** A line naming its charge type by catalogue code, with no discount. */
    public static LeaseLineInput line(String chargeTypeCode, String gross) {
        return new LeaseLineInput(null, chargeTypeCode, new BigDecimal(gross), BigDecimal.ZERO,
                null, null, null, null, null);
    }

    /** A line with a discount, which is what the net amount is reduced by. */
    public static LeaseLineInput line(String chargeTypeCode, String gross, String discount) {
        return new LeaseLineInput(null, chargeTypeCode, new BigDecimal(gross), new BigDecimal(discount),
                null, null, null, null, null);
    }

    public UUID tenantId() { return tenantId; }
    public Property property() { return property; }
    public Unit unit() { return unit; }
    public Renter renter() { return renter; }

    /** The generated property name, which the per-property account names embed. */
    public String propertyName() { return property.getNameEn(); }
}

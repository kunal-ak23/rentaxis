package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.entity.GateAccessPolicy;
import com.datagami.rentaxis.domain.entity.GatePass;
import com.datagami.rentaxis.domain.entity.GateVisitorProfile;
import com.datagami.rentaxis.domain.entity.GateVisitorUnitRegistration;
import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.GatePassOrigin;
import com.datagami.rentaxis.domain.entity.enums.GatePassStatus;
import com.datagami.rentaxis.domain.entity.enums.GatePassType;
import com.datagami.rentaxis.domain.entity.enums.GateVisitorType;
import com.datagami.rentaxis.domain.entity.enums.LeaseStatus;
import com.datagami.rentaxis.domain.repository.GateAccessPolicyRepository;
import com.datagami.rentaxis.domain.repository.GateVisitorProfileRepository;
import com.datagami.rentaxis.domain.repository.GateVisitorUnitRegistrationRepository;
import com.datagami.rentaxis.domain.repository.LeaseRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GateWalkInService}: visitor identity (phone normalization
 * and profile reuse), the policy resolution chain (tower override → property-wide →
 * hard defaults), and what a guard-raised walk-in becomes — pending or admitted,
 * photographed or refused, announced to the resident or silent.
 *
 * <p>RBAC, guard-to-property scoping and the HTTP shapes are the controller's job
 * and live in {@code GateWalkInControllerTest}.
 */
@ExtendWith(MockitoExtension.class)
class GateWalkInServiceTest {

    @Mock GateVisitorProfileRepository profileRepository;
    @Mock GateVisitorUnitRegistrationRepository registrationRepository;
    @Mock GateAccessPolicyRepository policyRepository;
    @Mock GatePassService gatePassService;
    @Mock LeaseRepository leaseRepository;
    @Mock UnitRepository unitRepository;
    @Mock NotificationService notificationService;
    @Mock BlobStorageService blobStorageService;

    private GateWalkInService service;

    private UUID tenantId;
    private UUID guardId;
    private UUID propertyId;
    private UUID unitId;
    private UUID profileId;
    private UUID passId;
    private Property property;
    private Unit unit;

    @BeforeEach
    void setUp() {
        service = new GateWalkInService(profileRepository, registrationRepository, policyRepository,
                gatePassService, leaseRepository, unitRepository, notificationService, blobStorageService);
        tenantId = UUID.randomUUID();
        guardId = UUID.randomUUID();
        propertyId = UUID.randomUUID();
        unitId = UUID.randomUUID();
        profileId = UUID.randomUUID();
        passId = UUID.randomUUID();
        property = new Property();
        property.setId(propertyId);
        property.setTenantId(tenantId);
        unit = new Unit();
        unit.setId(unitId);
        unit.setTenantId(tenantId);
        unit.setProperty(property);
        unit.setUnitNumber("1204");
    }

    // ---------------------------------------------------------- phone normalization

    @Test
    void normalizesFormattingAndInternationalDialPrefix() {
        assertThat(GateWalkInService.normalizePhone("00 971 50-123-4567"))
                .isEqualTo("+971501234567");
        assertThat(GateWalkInService.normalizePhone("+91 (98765) 43210"))
                .isEqualTo("+919876543210");
    }

    @Test
    void rejectsNumbersWithoutCountryCode() {
        assertThatThrownBy(() -> GateWalkInService.normalizePhone("0501234567"))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("country code");
    }

    @Test
    void rejectsBlankAndMalformedNumbers() {
        assertThatThrownBy(() -> GateWalkInService.normalizePhone("  "))
                .isInstanceOf(BusinessRuleViolationException.class);
        assertThatThrownBy(() -> GateWalkInService.normalizePhone("+12"))
                .isInstanceOf(BusinessRuleViolationException.class);
    }

    // ------------------------------------------------------------------- lookup

    @Test
    void lookupMapsEveryStoredFieldOfAKnownVisitor() {
        GateVisitorProfile profile = storedProfile();
        profile.setPhotoUrl("https://blob/gate-visitors/p.jpg");
        profile.setLastVehicleNumber("DXB-77777");
        Instant lastVisit = Instant.now().minus(3, ChronoUnit.DAYS);
        profile.setLastVisitedAt(lastVisit);
        profileIsKnown(profile);

        GateWalkInService.VisitorLookup lookup =
                service.lookup(tenantId, propertyId, "+971501234567", null).orElseThrow();

        assertThat(lookup.id()).isEqualTo(profileId);
        assertThat(lookup.name()).isEqualTo("Ramesh Kumar");
        assertThat(lookup.phone()).isEqualTo("+971501234567");
        assertThat(lookup.visitorType()).isEqualTo(GateVisitorType.MILK_VENDOR);
        assertThat(lookup.photoUrl()).isEqualTo("https://blob/gate-visitors/p.jpg");
        assertThat(lookup.vehicleNumber()).isEqualTo("DXB-77777");
        assertThat(lookup.lastUnitId()).isEqualTo(unitId);
        assertThat(lookup.lastVisitedAt()).isEqualTo(lastVisit);
    }

    @Test
    void lookupIsEmptyForAPhoneThatHasNeverVisited() {
        profileIsUnknown();

        assertThat(service.lookup(tenantId, propertyId, "+971501234567", unitId)).isEmpty();
        // No unit selected to check against, and nothing to check — the registration
        // table must not be consulted for a visitor that does not exist.
        verifyNoInteractions(registrationRepository);
    }

    @Test
    void lookupFlagsAVisitorRegisteredForTheSelectedUnit() {
        profileIsKnown(storedProfile());
        visitorIsRegisteredForTheUnit(true);

        assertThat(service.lookup(tenantId, propertyId, "+971501234567", unitId).orElseThrow()
                .registeredForSelectedUnit()).isTrue();
    }

    @Test
    void lookupDoesNotFlagRegistrationWhenNoUnitIsSelected() {
        profileIsKnown(storedProfile());

        assertThat(service.lookup(tenantId, propertyId, "+971501234567", null).orElseThrow()
                .registeredForSelectedUnit()).isFalse();
        // Short-circuited on the null unit: "registered" is meaningless without a
        // destination, and asking anyway would be a query per keystroke at the gate.
        verifyNoInteractions(registrationRepository);
    }

    @Test
    void lookupDoesNotFlagRegistrationWhenNoActiveRegistrationExists() {
        profileIsKnown(storedProfile());
        visitorIsRegisteredForTheUnit(false);

        assertThat(service.lookup(tenantId, propertyId, "+971501234567", unitId).orElseThrow()
                .registeredForSelectedUnit()).isFalse();
    }

    // ---------------------------------------------------------- effective policy

    @Test
    void towerPolicyWinsOverThePropertyWideOne() {
        UUID buildingId = UUID.randomUUID();
        when(policyRepository.findByTenantIdAndPropertyIdAndBuildingId(tenantId, propertyId, buildingId))
                .thenReturn(Optional.of(policy(false, true, false, false, 45)));

        GateWalkInService.EffectivePolicy effective = service.effectivePolicy(tenantId, propertyId, buildingId);

        assertThat(effective.requireUnregisteredApproval()).isFalse();
        assertThat(effective.requireRegisteredApproval()).isTrue();
        assertThat(effective.notifyRegisteredEntry()).isFalse();
        assertThat(effective.requireFreshPhoto()).isFalse();
        assertThat(effective.approvalTimeoutMinutes()).isEqualTo(45);
        // The property-wide row must not even be read once the tower has its own.
        verify(policyRepository, never()).findByTenantIdAndPropertyIdAndBuildingIdIsNull(any(), any());
    }

    @Test
    void propertyWidePolicyAppliesWhenTheTowerHasNoOverride() {
        UUID buildingId = UUID.randomUUID();
        when(policyRepository.findByTenantIdAndPropertyIdAndBuildingId(tenantId, propertyId, buildingId))
                .thenReturn(Optional.empty());
        when(policyRepository.findByTenantIdAndPropertyIdAndBuildingIdIsNull(tenantId, propertyId))
                .thenReturn(Optional.of(policy(true, false, true, false, 30)));

        GateWalkInService.EffectivePolicy effective = service.effectivePolicy(tenantId, propertyId, buildingId);

        assertThat(effective.requireFreshPhoto()).isFalse();
        assertThat(effective.approvalTimeoutMinutes()).isEqualTo(30);
    }

    @Test
    void propertyWidePolicyIsUsedDirectlyWhenNoTowerIsNamed() {
        when(policyRepository.findByTenantIdAndPropertyIdAndBuildingIdIsNull(tenantId, propertyId))
                .thenReturn(Optional.of(policy(false, false, false, true, 5)));

        GateWalkInService.EffectivePolicy effective = service.effectivePolicy(tenantId, propertyId, null);

        assertThat(effective.approvalTimeoutMinutes()).isEqualTo(5);
        verify(policyRepository, never()).findByTenantIdAndPropertyIdAndBuildingId(any(), any(), any());
    }

    /**
     * The defaults are what an unconfigured property runs on, so they are the policy
     * for most gates in practice: strangers wait for the resident, known vendors do
     * not, the resident is told either way, and every walk-in is photographed.
     */
    @Test
    void hardDefaultsApplyWhenNoPolicyRowExists() {
        when(policyRepository.findByTenantIdAndPropertyIdAndBuildingIdIsNull(tenantId, propertyId))
                .thenReturn(Optional.empty());

        GateWalkInService.EffectivePolicy effective = service.effectivePolicy(tenantId, propertyId, null);

        assertThat(effective.requireUnregisteredApproval()).isTrue();
        assertThat(effective.requireRegisteredApproval()).isFalse();
        assertThat(effective.notifyRegisteredEntry()).isTrue();
        assertThat(effective.requireFreshPhoto()).isTrue();
        assertThat(effective.approvalTimeoutMinutes()).isEqualTo(15);
    }

    // ------------------------------------------------------------------- create

    @Test
    void createStoresANewProfileForAnUnknownPhone() {
        profileIsUnknown();
        profileSaveAssignsAnId();
        propertyPolicy(policy(true, false, true, false, 15));
        visitorIsRegisteredForTheUnit(false);
        unitHasNoResidents();
        passCreationEchoesItsArguments();

        service.create(tenantId, guardId, unit, " Ramesh Kumar ", "00971501234567",
                GateVisitorType.MILK_VENDOR, " Milk delivery ", " dxb-77777 ", null);

        GateVisitorProfile saved = savedProfile();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getPropertyId()).isEqualTo(propertyId);
        // Stored normalized, so the next visit resolves the same person however the
        // guard types the number.
        assertThat(saved.getPhoneNormalized()).isEqualTo("+971501234567");
        assertThat(saved.getDisplayName()).isEqualTo("Ramesh Kumar");
        assertThat(saved.getVisitorType()).isEqualTo(GateVisitorType.MILK_VENDOR);
        assertThat(saved.getLastVehicleNumber()).isEqualTo("dxb-77777");
        assertThat(saved.getLastUnitId()).isEqualTo(unitId);
        assertThat(saved.getLastVisitedAt()).isNotNull();
    }

    @Test
    void createUpdatesTheProfileOfAReturningVisitor() {
        GateVisitorProfile existing = storedProfile();
        existing.setLastVehicleNumber("OLD-1");
        existing.setLastUnitId(UUID.randomUUID());
        existing.setLastVisitedAt(Instant.now().minus(30, ChronoUnit.DAYS));
        profileIsKnown(existing);
        profileSaveAssignsAnId();
        propertyPolicy(policy(true, false, true, false, 15));
        visitorIsRegisteredForTheUnit(false);
        unitHasNoResidents();
        passCreationEchoesItsArguments();

        service.create(tenantId, guardId, unit, "Ramesh K", "+971501234567",
                GateVisitorType.LAUNDRY_VENDOR, "Pickup", "DXB-99999", null);

        GateVisitorProfile saved = savedProfile();
        // The same row, re-pointed at this visit — a second profile for a phone the
        // gate already knows would split the visitor's history in two.
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getId()).isEqualTo(profileId);
        assertThat(saved.getDisplayName()).isEqualTo("Ramesh K");
        assertThat(saved.getVisitorType()).isEqualTo(GateVisitorType.LAUNDRY_VENDOR);
        assertThat(saved.getLastVehicleNumber()).isEqualTo("DXB-99999");
        assertThat(saved.getLastUnitId()).isEqualTo(unitId);
        assertThat(saved.getLastVisitedAt()).isAfter(Instant.now().minus(1, ChronoUnit.MINUTES));
    }

    @Test
    void createDefaultsAnUnnamedVisitorTypeToOther() {
        profileIsUnknown();
        profileSaveAssignsAnId();
        propertyPolicy(policy(true, false, true, false, 15));
        visitorIsRegisteredForTheUnit(false);
        unitHasNoResidents();
        passCreationEchoesItsArguments();

        service.create(tenantId, guardId, unit, "Walk In", "+971501234567", null, null, null, null);

        assertThat(savedProfile().getVisitorType()).isEqualTo(GateVisitorType.OTHER);
    }

    @Test
    void createRefusesAWalkInWithNoPhotoWhereThePolicyDemandsOne() {
        profileIsUnknown();
        profileSaveAssignsAnId();
        propertyPolicy(policy(true, false, true, true, 15));

        assertThatThrownBy(() -> service.create(tenantId, guardId, unit, "Ramesh", "+971501234567",
                GateVisitorType.GUEST, null, null, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("fresh visitor photo");

        // An empty part is no photo at all — a client that sends the field but no bytes
        // must not slip past the gate's camera rule.
        assertThatThrownBy(() -> service.create(tenantId, guardId, unit, "Ramesh", "+971501234567",
                GateVisitorType.GUEST, null, null, emptyPhoto()))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("fresh visitor photo");

        verifyNoInteractions(blobStorageService);
        verifyNoInteractions(gatePassService);
    }

    @Test
    void createUploadsTheFreshPhotoOntoBothTheProfileAndTheVisit() {
        profileIsUnknown();
        profileSaveAssignsAnId();
        propertyPolicy(policy(true, false, true, true, 15));
        visitorIsRegisteredForTheUnit(false);
        unitHasNoResidents();
        passCreationEchoesItsArguments();
        when(blobStorageService.uploadGateVisitor(eq(tenantId), eq(profileId), any(MultipartFile.class)))
                .thenReturn(new BlobStorageService.UploadResult(
                        "https://blob/gate-visitors/fresh.jpg", "gate-visitors/x/fresh.jpg"));

        GatePass pass = service.create(tenantId, guardId, unit, "Ramesh", "+971501234567",
                GateVisitorType.GUEST, null, null, photo());

        GateVisitorProfile saved = savedProfile();
        assertThat(saved.getPhotoUrl()).isEqualTo("https://blob/gate-visitors/fresh.jpg");
        assertThat(saved.getPhotoBlobPath()).isEqualTo("gate-visitors/x/fresh.jpg");
        verify(profileRepository).save(saved);
        // The pass carries its own copy: the profile's photo moves on with the next
        // visit, and the resident approving this one must see this face.
        assertThat(pass.getGuestPhotoUrl()).isEqualTo("https://blob/gate-visitors/fresh.jpg");
        assertThat(pass.getGuestPhotoBlobPath()).isEqualTo("gate-visitors/x/fresh.jpg");
    }

    @Test
    void createLeavesAnUnregisteredVisitorPendingAndAsksEveryResidentOfTheUnit() {
        UUID residentA = UUID.randomUUID();
        UUID residentB = UUID.randomUUID();
        profileIsUnknown();
        profileSaveAssignsAnId();
        propertyPolicy(policy(true, false, true, false, 15));
        visitorIsRegisteredForTheUnit(false);
        unitHasResidents(residentA, residentB);
        passCreationEchoesItsArguments();

        GatePass pass = service.create(tenantId, guardId, unit, "Ramesh", "+971501234567",
                GateVisitorType.GUEST, "Visiting", null, null);

        assertThat(pass.getStatus()).isEqualTo(GatePassStatus.PENDING_APPROVAL);
        // Every resident on the unit, not just the first: a household of two must not
        // depend on one person's phone being awake.
        verify(notificationService).notifyInApp(eq(tenantId), eq(residentA),
                eq("GATE_VISITOR_APPROVAL_REQUIRED"), eq("Visitor waiting at the gate"),
                eq("Ramesh is visiting unit 1204. Approve or reject the entry request."),
                eq("GATE_PASS"), eq(passId));
        verify(notificationService).notifyInApp(eq(tenantId), eq(residentB),
                eq("GATE_VISITOR_APPROVAL_REQUIRED"), any(), any(), eq("GATE_PASS"), eq(passId));
    }

    @Test
    void createSkipsAResidentWithNoLinkedUserAccount() {
        UUID residentA = UUID.randomUUID();
        profileIsUnknown();
        profileSaveAssignsAnId();
        propertyPolicy(policy(true, false, true, false, 15));
        visitorIsRegisteredForTheUnit(false);
        // A renter row that was never linked to a login — there is nobody to notify,
        // and a null userId would fail the notification insert for the whole household.
        when(leaseRepository.findByUnitIdAndStatus(unitId, LeaseStatus.ACTIVE))
                .thenReturn(List.of(activeLease(residentA), activeLease(null)));
        passCreationEchoesItsArguments();

        service.create(tenantId, guardId, unit, "Ramesh", "+971501234567",
                GateVisitorType.GUEST, null, null, null);

        verify(notificationService, times(1)).notifyInApp(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void createAdmitsARegisteredVendorWithoutApprovalOrANotification() {
        profileIsUnknown();
        profileSaveAssignsAnId();
        propertyPolicy(policy(true, false, true, false, 15));
        visitorIsRegisteredForTheUnit(true);
        passCreationEchoesItsArguments();

        GatePass pass = service.create(tenantId, guardId, unit, "Ramesh", "+971501234567",
                GateVisitorType.MILK_VENDOR, null, null, null);

        assertThat(pass.getStatus()).isEqualTo(GatePassStatus.ACTIVE);
        // The arrival notice for a registered vendor is sent on admission, not on
        // creation — announcing it here would tell the resident about a visitor who
        // may still turn around at the barrier.
        verifyNoInteractions(notificationService);
        verifyNoInteractions(leaseRepository);
    }

    @Test
    void createHoldsARegisteredVendorWhenThePolicyDemandsApprovalForThemToo() {
        profileIsUnknown();
        profileSaveAssignsAnId();
        propertyPolicy(policy(false, true, true, false, 15));
        visitorIsRegisteredForTheUnit(true);
        unitHasNoResidents();
        passCreationEchoesItsArguments();

        GatePass pass = service.create(tenantId, guardId, unit, "Ramesh", "+971501234567",
                GateVisitorType.MILK_VENDOR, null, null, null);

        assertThat(pass.getStatus()).isEqualTo(GatePassStatus.PENDING_APPROVAL);
    }

    @Test
    void createStampsTheWalkInWithItsOriginTypeAndVisitorProfile() {
        profileIsUnknown();
        profileSaveAssignsAnId();
        propertyPolicy(policy(true, false, true, false, 15));
        visitorIsRegisteredForTheUnit(false);
        unitHasNoResidents();
        passCreationEchoesItsArguments();

        GatePass pass = service.create(tenantId, guardId, unit, "Ramesh", "+971 50 123 4567",
                GateVisitorType.DELIVERY, "Parcel", "DXB-1", null);

        assertThat(pass.getOrigin()).isEqualTo(GatePassOrigin.GUARD_WALK_IN);
        // SINGLE_USE always: a walk-in is one visit that the guard is watching, never
        // a standing permission.
        assertThat(pass.getPassType()).isEqualTo(GatePassType.SINGLE_USE);
        assertThat(pass.getVisitorProfileId()).isEqualTo(profileId);
        assertThat(pass.getVisitorType()).isEqualTo(GateVisitorType.DELIVERY);
        assertThat(pass.getCreatedByUserId()).isEqualTo(guardId);
        assertThat(pass.getPropertyId()).isEqualTo(propertyId);
        assertThat(pass.getUnitId()).isEqualTo(unitId);
        assertThat(pass.getGuestName()).isEqualTo("Ramesh");
        assertThat(pass.getGuestPhone()).isEqualTo("+971501234567");
        assertThat(pass.getPurpose()).isEqualTo("Parcel");
        assertThat(pass.getVehicleNumber()).isEqualTo("DXB-1");
    }

    @Test
    void createOpensAValidityWindowOfExactlyTheApprovalTimeout() {
        profileIsUnknown();
        profileSaveAssignsAnId();
        propertyPolicy(policy(true, false, true, false, 40));
        visitorIsRegisteredForTheUnit(false);
        unitHasNoResidents();
        passCreationEchoesItsArguments();

        Instant before = Instant.now();
        GatePass pass = service.create(tenantId, guardId, unit, "Ramesh", "+971501234567",
                GateVisitorType.GUEST, null, null, null);

        assertThat(pass.getValidFrom()).isBetween(before, Instant.now());
        // The window and the approval deadline are the same thing: an unanswered
        // request must lapse rather than sit open at the barrier.
        assertThat(pass.getValidTo()).isEqualTo(pass.getValidFrom().plus(40, ChronoUnit.MINUTES));
    }

    @Test
    void createRejectsOverLongFreeTextFields() {
        profileIsUnknown();

        assertThatThrownBy(() -> service.create(tenantId, guardId, unit, "N".repeat(161), "+971501234567",
                GateVisitorType.GUEST, null, null, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Visitor name");
        assertThatThrownBy(() -> service.create(tenantId, guardId, unit, "Ramesh", "+971501234567",
                GateVisitorType.GUEST, null, "V".repeat(33), null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Vehicle number");
        assertThatThrownBy(() -> service.create(tenantId, guardId, unit, "Ramesh", "+971501234567",
                GateVisitorType.GUEST, "P".repeat(241), null, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Purpose");

        // Rejected at the door, so nothing reaches the columns these lengths mirror.
        verify(profileRepository, never()).saveAndFlush(any());
        verifyNoInteractions(gatePassService);
    }

    @Test
    void createRejectsAnUnusableMobileNumberBeforeTouchingAnything() {
        assertThatThrownBy(() -> service.create(tenantId, guardId, unit, "Ramesh", "0501234567",
                GateVisitorType.GUEST, null, null, null))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("country code");

        verifyNoInteractions(profileRepository);
        verifyNoInteractions(gatePassService);
    }

    // ------------------------------------------------- notifyRegisteredAdmission

    @Test
    void admissionOfARegisteredVendorIsAnnouncedToTheResident() {
        UUID resident = UUID.randomUUID();
        GatePass pass = admittedPass();
        unitIsLoadable();
        propertyPolicy(policy(true, false, true, false, 15));
        visitorIsRegisteredForTheUnit(true);
        unitHasResidents(resident);

        service.notifyRegisteredAdmission(tenantId, pass);

        verify(notificationService).notifyInApp(eq(tenantId), eq(resident),
                eq("GATE_REGISTERED_VENDOR_ARRIVED"), eq("Registered vendor checked in"),
                eq("Ramesh is visiting unit 1204."), eq("GATE_PASS"), eq(passId));
    }

    @Test
    void admissionIsSilentWhenThePolicyTurnsRegisteredEntryNoticesOff() {
        GatePass pass = admittedPass();
        unitIsLoadable();
        propertyPolicy(policy(true, false, false, false, 15));

        service.notifyRegisteredAdmission(tenantId, pass);

        verifyNoInteractions(notificationService);
        // The policy short-circuits before the registration lookup — a household that
        // opted out costs no query per admission.
        verifyNoInteractions(registrationRepository);
    }

    @Test
    void admissionIsSilentForAVisitorNotRegisteredForThatUnit() {
        GatePass pass = admittedPass();
        unitIsLoadable();
        propertyPolicy(policy(true, false, true, false, 15));
        visitorIsRegisteredForTheUnit(false);

        service.notifyRegisteredAdmission(tenantId, pass);

        // An unregistered visitor already had the resident's explicit approval; a
        // second "arrived" notice would be noise.
        verifyNoInteractions(notificationService);
    }

    @Test
    void admissionIsSilentForAPassWithNoVisitorProfile() {
        GatePass pass = admittedPass();
        pass.setVisitorProfileId(null);

        service.notifyRegisteredAdmission(tenantId, pass);

        verifyNoInteractions(unitRepository);
        verifyNoInteractions(policyRepository);
        verifyNoInteractions(notificationService);
    }

    @Test
    void admissionIsSilentWhenTheUnitIsGone() {
        GatePass pass = admittedPass();
        when(unitRepository.findById(unitId)).thenReturn(Optional.empty());

        service.notifyRegisteredAdmission(tenantId, pass);

        verifyNoInteractions(policyRepository);
        verifyNoInteractions(notificationService);
    }

    @Test
    void admissionIsSilentWhenTheUnitBelongsToAnotherTenant() {
        GatePass pass = admittedPass();
        unit.setTenantId(UUID.randomUUID());
        when(unitRepository.findById(unitId)).thenReturn(Optional.of(unit));

        service.notifyRegisteredAdmission(tenantId, pass);

        // findById is not tenant-scoped on its own here, so the service is the only
        // thing standing between a stray unit id and another tenant's residents.
        verifyNoInteractions(policyRepository);
        verifyNoInteractions(notificationService);
    }

    // -------------------------------------------------------------- registration

    @Test
    void registrationRejectsAnUnknownOrCrossTenantProfile() {
        when(profileRepository.findById(profileId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.registerForUnit(tenantId, profileId, unitId, null, null, true))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Visitor profile not found");

        GateVisitorProfile foreign = storedProfile();
        foreign.setTenantId(UUID.randomUUID());
        when(profileRepository.findById(profileId)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.registerForUnit(tenantId, profileId, unitId, null, null, true))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Visitor profile not found");

        verify(registrationRepository, never()).save(any());
    }

    @Test
    void registrationRejectsAnUnknownOrCrossTenantUnit() {
        when(profileRepository.findById(profileId)).thenReturn(Optional.of(storedProfile()));
        when(unitRepository.findById(unitId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.registerForUnit(tenantId, profileId, unitId, null, null, true))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Unit not found");

        unit.setTenantId(UUID.randomUUID());
        when(unitRepository.findById(unitId)).thenReturn(Optional.of(unit));
        assertThatThrownBy(() -> service.registerForUnit(tenantId, profileId, unitId, null, null, true))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Unit not found");

        verify(registrationRepository, never()).save(any());
    }

    @Test
    void registrationRejectsAUnitAtADifferentPropertyThanTheProfile() {
        GateVisitorProfile profile = storedProfile();
        profile.setPropertyId(UUID.randomUUID()); // registered at another building
        when(profileRepository.findById(profileId)).thenReturn(Optional.of(profile));
        when(unitRepository.findById(unitId)).thenReturn(Optional.of(unit));

        assertThatThrownBy(() -> service.registerForUnit(tenantId, profileId, unitId, null, null, true))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("same property");

        verify(registrationRepository, never()).save(any());
    }

    @Test
    void registrationRejectsAWindowThatEndsBeforeItStarts() {
        when(profileRepository.findById(profileId)).thenReturn(Optional.of(storedProfile()));
        when(unitRepository.findById(unitId)).thenReturn(Optional.of(unit));
        Instant from = Instant.now();

        assertThatThrownBy(() -> service.registerForUnit(tenantId, profileId, unitId, from,
                from.minus(1, ChronoUnit.DAYS), true))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("validTo must be after validFrom");
        // Equal endpoints are an empty window, not a valid instant-long one.
        assertThatThrownBy(() -> service.registerForUnit(tenantId, profileId, unitId, from, from, true))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("validTo must be after validFrom");

        verify(registrationRepository, never()).save(any());
    }

    @Test
    void registrationCreatesTheRowWhenTheVisitorIsNewToTheUnit() {
        when(profileRepository.findById(profileId)).thenReturn(Optional.of(storedProfile()));
        when(unitRepository.findById(unitId)).thenReturn(Optional.of(unit));
        when(registrationRepository.findByTenantIdAndVisitorProfileIdAndUnitId(tenantId, profileId, unitId))
                .thenReturn(Optional.empty());
        Instant from = Instant.now();
        Instant to = from.plus(90, ChronoUnit.DAYS);

        service.registerForUnit(tenantId, profileId, unitId, from, to, true);

        GateVisitorUnitRegistration saved = savedRegistration();
        assertThat(saved.getTenantId()).isEqualTo(tenantId);
        assertThat(saved.getVisitorProfileId()).isEqualTo(profileId);
        assertThat(saved.getUnitId()).isEqualTo(unitId);
        assertThat(saved.getValidFrom()).isEqualTo(from);
        assertThat(saved.getValidTo()).isEqualTo(to);
        assertThat(saved.isActive()).isTrue();
    }

    @Test
    void registrationUpdatesTheExistingRowRatherThanAddingASecond() {
        GateVisitorUnitRegistration existing = new GateVisitorUnitRegistration();
        existing.setId(UUID.randomUUID());
        existing.setTenantId(tenantId);
        existing.setVisitorProfileId(profileId);
        existing.setUnitId(unitId);
        existing.setActive(true);
        when(profileRepository.findById(profileId)).thenReturn(Optional.of(storedProfile()));
        when(unitRepository.findById(unitId)).thenReturn(Optional.of(unit));
        when(registrationRepository.findByTenantIdAndVisitorProfileIdAndUnitId(tenantId, profileId, unitId))
                .thenReturn(Optional.of(existing));
        Instant to = Instant.now().plus(30, ChronoUnit.DAYS);

        service.registerForUnit(tenantId, profileId, unitId, null, to, false);

        GateVisitorUnitRegistration saved = savedRegistration();
        // The same row: findActiveRegistration returns at most one, so a duplicate
        // would make "is this vendor registered" depend on which row Postgres returns.
        assertThat(saved).isSameAs(existing);
        assertThat(saved.getValidTo()).isEqualTo(to);
        assertThat(saved.isActive()).isFalse();
    }

    // ------------------------------------------------------ managed registration

    @Test
    void managedRegistrationRejectsAUnitOutsideTheNamedPropertyOrTenant() {
        when(unitRepository.findById(unitId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.upsertRegisteredVisitor(tenantId, propertyId, unitId,
                "Ramesh", "+971501234567", GateVisitorType.MILK_VENDOR, null, null, true))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Unit not found at property");

        // Real unit, real tenant, wrong property.
        when(unitRepository.findById(unitId)).thenReturn(Optional.of(unit));
        assertThatThrownBy(() -> service.upsertRegisteredVisitor(tenantId, UUID.randomUUID(), unitId,
                "Ramesh", "+971501234567", GateVisitorType.MILK_VENDOR, null, null, true))
                .isInstanceOf(BusinessRuleViolationException.class)
                .hasMessageContaining("Unit not found at property");

        verifyNoInteractions(profileRepository);
        verify(registrationRepository, never()).save(any());
    }

    @Test
    void managedRegistrationCreatesTheProfileAndItsUnitRegistration() {
        when(unitRepository.findById(unitId)).thenReturn(Optional.of(unit));
        profileIsUnknown();
        profileSaveAssignsAnId();
        when(profileRepository.findById(profileId)).thenReturn(Optional.of(storedProfile()));
        when(registrationRepository.findByTenantIdAndVisitorProfileIdAndUnitId(tenantId, profileId, unitId))
                .thenReturn(Optional.empty());
        Instant to = Instant.now().plus(180, ChronoUnit.DAYS);

        GateVisitorProfile created = service.upsertRegisteredVisitor(tenantId, propertyId, unitId,
                " Milk Man ", "00971501234567", GateVisitorType.MILK_VENDOR, null, to, true);

        assertThat(created.getPropertyId()).isEqualTo(propertyId);
        assertThat(created.getPhoneNormalized()).isEqualTo("+971501234567");
        assertThat(created.getDisplayName()).isEqualTo("Milk Man");
        assertThat(created.getVisitorType()).isEqualTo(GateVisitorType.MILK_VENDOR);

        GateVisitorUnitRegistration registration = savedRegistration();
        assertThat(registration.getVisitorProfileId()).isEqualTo(profileId);
        assertThat(registration.getUnitId()).isEqualTo(unitId);
        assertThat(registration.getValidTo()).isEqualTo(to);
        assertThat(registration.isActive()).isTrue();
    }

    @Test
    void managedRegistrationReusesTheProfileAPhoneAlreadyHas() {
        GateVisitorProfile existing = storedProfile();
        when(unitRepository.findById(unitId)).thenReturn(Optional.of(unit));
        profileIsKnown(existing);
        profileSaveAssignsAnId();
        when(profileRepository.findById(profileId)).thenReturn(Optional.of(existing));
        when(registrationRepository.findByTenantIdAndVisitorProfileIdAndUnitId(tenantId, profileId, unitId))
                .thenReturn(Optional.empty());

        GateVisitorProfile result = service.upsertRegisteredVisitor(tenantId, propertyId, unitId,
                "Renamed Vendor", "+971501234567", GateVisitorType.SERVICE_VENDOR, null, null, true);

        assertThat(result).isSameAs(existing);
        assertThat(result.getDisplayName()).isEqualTo("Renamed Vendor");
        assertThat(result.getVisitorType()).isEqualTo(GateVisitorType.SERVICE_VENDOR);
        verify(profileRepository, times(1)).saveAndFlush(any());
    }

    // ------------------------------------------------------------------ fixtures

    private GateVisitorProfile storedProfile() {
        GateVisitorProfile profile = new GateVisitorProfile();
        profile.setId(profileId);
        profile.setTenantId(tenantId);
        profile.setPropertyId(propertyId);
        profile.setPhoneNormalized("+971501234567");
        profile.setDisplayName("Ramesh Kumar");
        profile.setVisitorType(GateVisitorType.MILK_VENDOR);
        profile.setLastUnitId(unitId);
        return profile;
    }

    private GateAccessPolicy policy(boolean requireUnregisteredApproval, boolean requireRegisteredApproval,
                                    boolean notifyRegisteredEntry, boolean requireFreshPhoto, int timeoutMinutes) {
        GateAccessPolicy policy = new GateAccessPolicy();
        policy.setTenantId(tenantId);
        policy.setPropertyId(propertyId);
        policy.setRequireUnregisteredApproval(requireUnregisteredApproval);
        policy.setRequireRegisteredApproval(requireRegisteredApproval);
        policy.setNotifyRegisteredEntry(notifyRegisteredEntry);
        policy.setRequireFreshPhoto(requireFreshPhoto);
        policy.setApprovalTimeoutMinutes(timeoutMinutes);
        return policy;
    }

    private GatePass admittedPass() {
        GatePass pass = new GatePass();
        pass.setId(passId);
        pass.setTenantId(tenantId);
        pass.setPropertyId(propertyId);
        pass.setUnitId(unitId);
        pass.setVisitorProfileId(profileId);
        pass.setGuestName("Ramesh");
        pass.setOrigin(GatePassOrigin.GUARD_WALK_IN);
        pass.setStatus(GatePassStatus.USED);
        return pass;
    }

    private Lease activeLease(UUID renterUserId) {
        Renter renter = new Renter();
        renter.setId(UUID.randomUUID());
        renter.setTenantId(tenantId);
        renter.setUserId(renterUserId);
        Lease lease = new Lease();
        lease.setId(UUID.randomUUID());
        lease.setTenantId(tenantId);
        lease.setUnit(unit);
        lease.setRenter(renter);
        lease.setStatus(LeaseStatus.ACTIVE);
        return lease;
    }

    private MultipartFile photo() {
        return new MockMultipartFile("photo", "visitor.jpg", "image/jpeg", new byte[] {1, 2, 3});
    }

    private MultipartFile emptyPhoto() {
        return new MockMultipartFile("photo", "visitor.jpg", "image/jpeg", new byte[0]);
    }

    // -------------------------------------------------------------- stub helpers

    private void profileIsKnown(GateVisitorProfile profile) {
        when(profileRepository.findByTenantIdAndPropertyIdAndPhoneNormalized(
                eq(tenantId), eq(propertyId), any())).thenReturn(Optional.of(profile));
    }

    private void profileIsUnknown() {
        when(profileRepository.findByTenantIdAndPropertyIdAndPhoneNormalized(
                eq(tenantId), eq(propertyId), any())).thenReturn(Optional.empty());
    }

    private void profileSaveAssignsAnId() {
        when(profileRepository.saveAndFlush(any(GateVisitorProfile.class))).thenAnswer(invocation -> {
            GateVisitorProfile saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(profileId);
            }
            return saved;
        });
    }

    private void propertyPolicy(GateAccessPolicy policy) {
        when(policyRepository.findByTenantIdAndPropertyIdAndBuildingIdIsNull(tenantId, propertyId))
                .thenReturn(Optional.ofNullable(policy));
    }

    private void visitorIsRegisteredForTheUnit(boolean registered) {
        when(registrationRepository.findActiveRegistration(eq(tenantId), eq(profileId), eq(unitId), any()))
                .thenReturn(registered ? Optional.of(new GateVisitorUnitRegistration()) : Optional.empty());
    }

    private void unitIsLoadable() {
        when(unitRepository.findById(unitId)).thenReturn(Optional.of(unit));
    }

    private void unitHasResidents(UUID... renterUserIds) {
        when(leaseRepository.findByUnitIdAndStatus(unitId, LeaseStatus.ACTIVE))
                .thenReturn(Arrays.stream(renterUserIds).map(this::activeLease).toList());
    }

    private void unitHasNoResidents() {
        when(leaseRepository.findByUnitIdAndStatus(unitId, LeaseStatus.ACTIVE)).thenReturn(List.of());
    }

    /**
     * Echoes {@code GatePassService.create}'s arguments back as the pass it would
     * have persisted, so a test can assert on the pass the walk-in produced rather
     * than on seventeen positional captors.
     */
    private void passCreationEchoesItsArguments() {
        when(gatePassService.create(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                    GatePass pass = new GatePass();
                    pass.setId(passId);
                    pass.setTenantId(invocation.getArgument(0));
                    pass.setCreatedByUserId(invocation.getArgument(1));
                    pass.setPropertyId(invocation.getArgument(2));
                    pass.setUnitId(invocation.getArgument(3));
                    pass.setGuestName(invocation.getArgument(4));
                    pass.setGuestPhone(invocation.getArgument(5));
                    pass.setPurpose(invocation.getArgument(6));
                    pass.setVehicleNumber(invocation.getArgument(7));
                    pass.setPassType(invocation.getArgument(8));
                    pass.setValidFrom(invocation.getArgument(9));
                    pass.setValidTo(invocation.getArgument(10));
                    pass.setStatus(invocation.getArgument(11));
                    pass.setOrigin(invocation.getArgument(12));
                    pass.setVisitorType(invocation.getArgument(13));
                    pass.setVisitorProfileId(invocation.getArgument(14));
                    pass.setGuestPhotoUrl(invocation.getArgument(15));
                    pass.setGuestPhotoBlobPath(invocation.getArgument(16));
                    return pass;
                });
    }

    private GateVisitorProfile savedProfile() {
        ArgumentCaptor<GateVisitorProfile> captor = ArgumentCaptor.forClass(GateVisitorProfile.class);
        verify(profileRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private GateVisitorUnitRegistration savedRegistration() {
        ArgumentCaptor<GateVisitorUnitRegistration> captor =
                ArgumentCaptor.forClass(GateVisitorUnitRegistration.class);
        verify(registrationRepository).save(captor.capture());
        return captor.getValue();
    }
}

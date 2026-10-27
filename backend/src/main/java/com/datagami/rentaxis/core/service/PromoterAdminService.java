package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.PromoterAccessRequest;
import com.datagami.rentaxis.api.dto.PromoterDTO;
import com.datagami.rentaxis.api.dto.PromoterInviteRequest;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.domain.entity.PromoterAccess;
import com.datagami.rentaxis.domain.entity.PromoterBusiness;
import com.datagami.rentaxis.domain.entity.PromoterProperty;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.PromoBusinessRepository;
import com.datagami.rentaxis.domain.repository.PromoterAccessRepository;
import com.datagami.rentaxis.domain.repository.PromoterBusinessRepository;
import com.datagami.rentaxis.domain.repository.PromoterPropertyRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Promotions → Promoters (promoter plan, "Admin API"): an admin invites a promoter
 * into its own organisation and decides which businesses and properties it gets.
 * Every business and property id is checked against the organisation (404
 * otherwise), the way {@code PromotionService.replaceTargeting} checks targeting.
 */
@Service
@Transactional
public class PromoterAdminService {

    public static final String EXISTING_LOGIN_CODE = "promoter.existingLogin";
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserService userService;
    private final UserRepository userRepository;
    private final PromoterAccessRepository accessRepository;
    private final PromoterBusinessRepository grantRepository;
    private final PromoterPropertyRepository promoterPropertyRepository;
    private final PromoBusinessRepository businessRepository;
    private final PropertyRepository propertyRepository;

    public PromoterAdminService(UserService userService,
                                UserRepository userRepository,
                                PromoterAccessRepository accessRepository,
                                PromoterBusinessRepository grantRepository,
                                PromoterPropertyRepository promoterPropertyRepository,
                                PromoBusinessRepository businessRepository,
                                PropertyRepository propertyRepository) {
        this.userService = userService;
        this.userRepository = userRepository;
        this.accessRepository = accessRepository;
        this.grantRepository = grantRepository;
        this.promoterPropertyRepository = promoterPropertyRepository;
        this.businessRepository = businessRepository;
        this.propertyRepository = propertyRepository;
    }

    /** Every promoter of the organisation, oldest first (by when it was invited). */
    @Transactional(readOnly = true)
    public List<PromoterDTO> list(UUID tenantId) {
        List<User> promoters = userRepository.findByTenantIdAndRole(tenantId, UserRole.PROMOTER);
        if (promoters.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = promoters.stream().map(User::getId).toList();
        Map<UUID, PromoterAccess> access = accessRepository.findByTenantIdOrderByCreatedAtAscUserIdAsc(tenantId)
                .stream().collect(Collectors.toMap(PromoterAccess::getUserId, a -> a));
        Map<UUID, List<UUID>> businesses = grantRepository.findByTenantIdAndUserIdIn(tenantId, ids).stream()
                .collect(Collectors.groupingBy(PromoterBusiness::getUserId,
                        Collectors.mapping(PromoterBusiness::getBusinessId, Collectors.toList())));
        Map<UUID, List<UUID>> properties = promoterPropertyRepository.findByTenantIdAndUserIdIn(tenantId, ids).stream()
                .collect(Collectors.groupingBy(PromoterProperty::getUserId,
                        Collectors.mapping(PromoterProperty::getPropertyId, Collectors.toList())));
        Comparator<User> byInvited = Comparator.comparing(
                (User u) -> access.containsKey(u.getId()) ? access.get(u.getId()).getCreatedAt() : Instant.MAX)
                .thenComparing(User::getId);
        return promoters.stream().sorted(byInvited)
                .map(u -> new PromoterDTO(u.getId(), u.getName(), u.getEmail(), u.getPhoneNumber(),
                        u.getStatus().name(),
                        access.containsKey(u.getId()) && access.get(u.getId()).isCanCreateBusinesses(),
                        businesses.getOrDefault(u.getId(), List.of()),
                        properties.getOrDefault(u.getId(), List.of())))
                .toList();
    }

    /**
     * Creates the PROMOTER login through {@link UserService#createUser} (the usual
     * set-password invite email), then its access. An email that already has a
     * Miftah login anywhere is refused: a promoter is its own login in one
     * organisation, never another role's account given a second hat.
     */
    public PromoterDTO invite(UUID tenantId, PromoterInviteRequest req) {
        String name = req.name() == null ? null : req.name().trim();
        if (name == null || name.isEmpty()) {
            throw new BusinessRuleViolationException("A promoter needs a name.");
        }
        String email = req.email() == null ? null : req.email().trim().toLowerCase();
        if (email == null || !EMAIL.matcher(email).matches()) {
            throw new BusinessRuleViolationException("Enter a valid email address for the promoter.");
        }
        Set<UUID> businessIds = requireBusinesses(tenantId, req.businessIds());
        Set<UUID> propertyIds = requireProperties(tenantId, req.propertyIds());
        if (userRepository.existsAnyLoginWithEmail(email)) {
            throw new BusinessRuleViolationException(
                    "This email already has a Miftah login. Invite the promoter with another email address.",
                    EXISTING_LOGIN_CODE, Map.of());
        }
        String phone = req.phoneNumber() == null || req.phoneNumber().isBlank() ? null : req.phoneNumber().trim();
        User user = userService.createUser(email, null, name, UserRole.PROMOTER, tenantId.toString(), phone,
                "admin", true).user();
        PromoterAccess access = new PromoterAccess();
        access.setTenantId(tenantId);
        access.setUserId(user.getId());
        access.setCanCreateBusinesses(Boolean.TRUE.equals(req.canCreateBusinesses()));
        accessRepository.save(access);
        writeGrants(tenantId, user.getId(), businessIds, propertyIds);
        return new PromoterDTO(user.getId(), user.getName(), user.getEmail(), user.getPhoneNumber(),
                user.getStatus().name(), access.isCanCreateBusinesses(), List.copyOf(businessIds),
                List.copyOf(propertyIds));
    }

    /** Replaces the promoter's access in full. The target must be a PROMOTER of this organisation (else 404). */
    public PromoterDTO updateAccess(UUID tenantId, UUID userId, PromoterAccessRequest req) {
        User user = userRepository.findById(userId)
                .filter(u -> u.getRole() == UserRole.PROMOTER && Objects.equals(u.getTenantId(), tenantId))
                .orElseThrow(() -> new NotFoundException("Promoter not found"));
        Set<UUID> businessIds = requireBusinesses(tenantId, req.businessIds());
        Set<UUID> propertyIds = requireProperties(tenantId, req.propertyIds());
        PromoterAccess access = accessRepository.findByTenantIdAndUserId(tenantId, userId).orElseGet(() -> {
            PromoterAccess a = new PromoterAccess();
            a.setTenantId(tenantId);
            a.setUserId(userId);
            return a;
        });
        boolean canCreate = Boolean.TRUE.equals(req.canCreateBusinesses());
        access.setCanCreateBusinesses(canCreate);
        accessRepository.save(access);
        writeGrants(tenantId, userId, businessIds, propertyIds);
        return new PromoterDTO(user.getId(), user.getName(), user.getEmail(), user.getPhoneNumber(),
                user.getStatus().name(), canCreate, List.copyOf(businessIds), List.copyOf(propertyIds));
    }

    /**
     * Delete-then-insert. The bulk deletes flush pending writes first and clear the
     * persistence context, so nothing loaded above can shadow a re-inserted key.
     */
    private void writeGrants(UUID tenantId, UUID userId, Set<UUID> businessIds, Set<UUID> propertyIds) {
        grantRepository.deleteByTenantIdAndUserId(tenantId, userId);
        promoterPropertyRepository.deleteByTenantIdAndUserId(tenantId, userId);
        grantRepository.saveAll(businessIds.stream().map(b -> PromoterBusiness.of(tenantId, userId, b)).toList());
        promoterPropertyRepository.saveAll(
                propertyIds.stream().map(p -> PromoterProperty.of(tenantId, userId, p)).toList());
    }

    private Set<UUID> requireBusinesses(UUID tenantId, List<UUID> ids) {
        Set<UUID> wanted = distinct(ids, "businessIds");
        if (!wanted.isEmpty() && businessRepository.findByTenantIdAndIdIn(tenantId, wanted).size() != wanted.size()) {
            throw new NotFoundException("Business not found");
        }
        return wanted;
    }

    private Set<UUID> requireProperties(UUID tenantId, List<UUID> ids) {
        Set<UUID> wanted = distinct(ids, "propertyIds");
        if (!wanted.isEmpty() && propertyRepository.findByTenantIdAndIdIn(tenantId, wanted).size() != wanted.size()) {
            throw new NotFoundException("Property not found");
        }
        return wanted;
    }

    private static Set<UUID> distinct(List<UUID> ids, String field) {
        if (ids == null) {
            return Set.of();
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw new BusinessRuleViolationException(field + " must not contain nulls");
        }
        return new LinkedHashSet<>(ids);
    }
}

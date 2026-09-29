package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateRenterDTO;
import com.datagami.rentaxis.api.dto.RenterDTO;
import com.datagami.rentaxis.api.dto.UpdateRenterDTO;
import com.datagami.rentaxis.api.dto.lookup.RenterOptionDTO;
import com.datagami.rentaxis.core.security.PropertyScope;
import com.datagami.rentaxis.core.util.Search;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.api.exception.NotFoundException;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RenterService {

    private final RenterRepository renterRepository;
    private final UserService userService;
    private final UserRepository userRepository;

    /** Narrows a property manager's lists; absent in unit tests that build the service by hand. */
    private PropertyScope propertyScope;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setPropertyScope(PropertyScope propertyScope) {
        this.propertyScope = propertyScope;
    }

    /** The caller's property ids when they are a property manager, else {@code null}. */
    private List<UUID> scoped() {
        return propertyScope == null ? null : propertyScope.scopedPropertyIds();
    }

    private static final org.springframework.data.domain.Sort BY_NAME = org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Order.asc("nameEn"), org.springframework.data.domain.Sort.Order.asc("id"));

    /**
     * Controller ruling (Scale PR B2 final-review fix, task 5, revised): every entity list
     * sorts by createdAt ascending, not by name — but every existing renter ties on the same
     * backfilled {@code created_at} (changeset 155 ran once, at one instant, for all of
     * them), so a createdAt-then-id order would show today's renters in effectively random
     * (UUID) order. {@code nameEn} is the secondary key instead — the same field {@link
     * #BY_NAME} sorts by, reused as-is rather than re-decided here — so the existing list
     * stays stable and readable while renters created after the migration append in true
     * creation order. {@code id} remains the final tiebreak for two renters sharing both a
     * createdAt and a name. {@link #BY_NAME} itself stays reserved for {@link #search}, the
     * picker typeahead, where a caller is scanning by name regardless of when it was created.
     */
    private static final org.springframework.data.domain.Sort BY_CREATED = org.springframework.data.domain.Sort.by(
            org.springframework.data.domain.Sort.Order.asc("createdAt"),
            org.springframework.data.domain.Sort.Order.asc("nameEn"),
            org.springframework.data.domain.Sort.Order.asc("id"));

    /**
     * {@code GET /renters/paged} (scale P1-3): searched on name, phone and email in the
     * database, a page at a time, ordered by createdAt ascending, then name, then id (see
     * {@link #BY_CREATED}). A property manager sees the renters with a contract in their
     * buildings and the renters with none yet.
     */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<RenterDTO> searchPaged(String q, int page, int size) {
        UUID tenantId = Search.requireTenant();
        List<UUID> scoped = scoped();
        org.springframework.data.domain.Page<Renter> rows = renterRepository.searchPaged(tenantId, Search.like(q),
                scoped == null, Search.scopeIds(scoped), Search.page(page, size, BY_CREATED));
        Map<UUID, User> users = new HashMap<>();
        List<UUID> userIds = rows.getContent().stream().map(Renter::getUserId).filter(java.util.Objects::nonNull).toList();
        if (!userIds.isEmpty() && tenantId != null) {
            userRepository.findByTenantIdAndIdIn(tenantId, userIds).forEach(u -> users.put(u.getId(), u));
        }
        return rows.map(r -> mapToDTO(r, r.getUserId() == null ? null : users.get(r.getUserId())));
    }

    /** {@code GET /renters/search} (scale P1-6): the first {@code limit} matches, by name. */
    @Transactional(readOnly = true)
    public List<RenterOptionDTO> search(String q, int limit) {
        List<UUID> scoped = scoped();
        return renterRepository.searchPaged(Search.requireTenant(), Search.like(q), scoped == null,
                        Search.scopeIds(scoped), org.springframework.data.domain.PageRequest.of(0, Search.limit(limit), BY_NAME))
                .getContent().stream().map(RenterService::option).toList();
    }

    /** {@code GET /renters/names} (scale P1-6): the named renters the caller may see, at most 200 ids. */
    @Transactional(readOnly = true)
    public List<RenterOptionDTO> names(List<UUID> ids) {
        List<UUID> wanted = Search.names(ids);
        if (wanted.isEmpty()) return List.of();
        List<UUID> scoped = scoped();
        return renterRepository.findNamed(Search.requireTenant(), wanted, scoped == null, Search.scopeIds(scoped))
                .stream().map(RenterService::option).toList();
    }

    private static RenterOptionDTO option(Renter r) {
        return new RenterOptionDTO(r.getId(), r.getNameEn(), r.getNameAr(), r.getPhone(), r.getEmail());
    }

    /**
     * {@code GET /renters} (unpaged; mobile and back-compat). A property manager gets the
     * same renters {@link #searchPaged} shows them — with a contract in their buildings, or
     * with none yet — not the whole organisation (break round 1, F4).
     */
    @Transactional(readOnly = true)
    public List<RenterDTO> getAllRenters() {
        UUID tenantId = TenantContextHolder.getTenantId();
        List<UUID> scoped = scoped();
        List<Renter> renters = scoped == null
                ? renterRepository.findByTenantId(tenantId)
                : renterRepository.searchPaged(Search.requireTenant(), null, false, Search.scopeIds(scoped),
                        org.springframework.data.domain.Pageable.unpaged(BY_CREATED)).getContent();
        // One query for every portal account's invite state rather than one per row.
        Map<UUID, User> users = new HashMap<>();
        List<UUID> userIds = renters.stream().map(Renter::getUserId).filter(java.util.Objects::nonNull).toList();
        if (!userIds.isEmpty() && tenantId != null) {
            userRepository.findByTenantIdAndIdIn(tenantId, userIds).forEach(u -> users.put(u.getId(), u));
        }
        return renters.stream()
                .map(r -> mapToDTO(r, r.getUserId() == null ? null : users.get(r.getUserId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public RenterDTO getRenterById(UUID id) {
        return mapToDTO(requireReadable(id));
    }

    /**
     * 404 unless the caller may read this renter: in their organisation and, for a
     * property manager, visible under the rule of {@link #searchPaged} and {@link #names}
     * (a contract in one of their buildings, or no contract at all). Break round 1, F4:
     * {@code GET /renters/{id}} checked the tenant only, so a manager could read the
     * profile of any renter in the organisation. The renter's sibling reads
     * ({@code /renters/{id}/leases}, {@code /renters/{id}/cheques}) call this first too, so
     * an out-of-scope renter is a 404 everywhere rather than an empty list that confirms it
     * exists.
     */
    @Transactional(readOnly = true)
    public void requireReadableRenter(UUID id) {
        requireReadable(id);
    }

    private Renter requireReadable(UUID id) {
        Renter renter = requireInTenant(id);
        List<UUID> scoped = scoped();
        if (scoped != null && renterRepository.findNamed(renter.getTenantId(), List.of(renter.getId()), false,
                Search.scopeIds(scoped)).isEmpty()) {
            throw new NotFoundException("Renter not found");
        }
        return renter;
    }

    /**
     * The renter with this id in the caller's tenant, or a 404.
     *
     * <p>Inside a transaction the tenant filter already hides a foreign row on a
     * primary-key load ({@code applyToLoadByKey}); the explicit comparison is the
     * belt and braces for a caller without one. A foreign id and a missing one
     * get the same 404 (these used to be a bare RuntimeException, a 500).</p>
     */
    Renter requireInTenant(UUID id) {
        return renterRepository.findById(id)
                .filter(r -> TenantReferences.inCurrentTenant(r.getTenantId()))
                .orElseThrow(() -> new NotFoundException("Renter not found"));
    }

    @Transactional
    public RenterDTO createRenter(CreateRenterDTO dto) {
        Renter renter = new Renter();
        renter.setNameEn(dto.getNameEn());
        renter.setNameAr(dto.getNameAr());
        renter.setEmail(dto.getEmail());
        renter.setPhone(dto.getPhone());
        if (dto.getPrimaryLanguage() != null) {
            renter.setPrimaryLanguage(dto.getPrimaryLanguage());
        }

        Renter saved = renterRepository.save(renter);

        User portalUser = null;

        // Auto-create portal User account by default. Skip only when:
        //   (a) the caller explicitly opts out (createPortalAccount=false), or
        //   (b) no email was provided — login requires an email identifier.
        //
        // Failure to create the portal account aborts the whole txn — both
        // renter and portal user are atomically created (or neither). This
        // replaces the prior swallow-and-log behavior, which left orphan
        // renter rows with no portal access and made debugging painful.
        //
        // #7: no password is generated or returned. It used to be generated here
        // and sent back as portalPassword for the form to display and copy, which
        // is how credentials ended up pasted into WhatsApp. createUser gives an
        // invited RENTER an unusable secret and emails USER_INVITED; the
        // set-password link is the only way in.
        boolean shouldCreatePortal = dto.isCreatePortalAccount()
                && dto.getEmail() != null && !dto.getEmail().isBlank();
        if (shouldCreatePortal) {
            UUID tenantId = TenantContextHolder.getTenantId();
            portalUser = userService.createUser(
                    dto.getEmail(),
                    null,
                    dto.getNameEn(),
                    UserRole.RENTER,
                    tenantId != null ? tenantId.toString() : null,
                    dto.getPhone(),
                    "system"
            );
            saved.setUserId(portalUser.getId());
            renterRepository.save(saved);
            // USER_INVITED is fired by UserService.createUser for RENTER role.
        }

        return mapToDTO(saved, portalUser);
    }

    /** Web edit-tenant PR: the client-visible, translatable keys for {@link #updateRenter}'s refusals. */
    public static final String EMAIL_TAKEN_CODE = "renter.emailTaken";
    public static final String EMAIL_TAKEN_MESSAGE = "A user with this email already exists.";
    public static final String PORTAL_USER_MISMATCH_CODE = "renter.portalUserMismatch";
    public static final String PORTAL_USER_MISMATCH_MESSAGE =
            "This tenant's portal login could not be found in this organisation; the email was not changed.";
    public static final String PORTAL_EMAIL_REQUIRED_CODE = "renter.portalEmailRequired";
    public static final String PORTAL_EMAIL_REQUIRED_MESSAGE =
            "This tenant has a portal login, which needs an email; the email was not changed.";

    /**
     * Edit tenant: {@code PUT /renters/{id}}. A renter with a linked portal account
     * ({@code userId}) logs in by email, so changing it here must keep that login
     * consistent rather than leave it pointing at an address the tenant no longer
     * reads mail at — checked and applied before any other field on this renter is
     * touched, so a refused email change leaves the whole edit un-applied (this
     * method is {@code @Transactional}: nothing flushed here survives an exception
     * thrown out of it).
     *
     * <p>Every read below is tenant-scoped ({@link #requireInTenant} for the renter,
     * {@link UserRepository#findByTenantIdAndIdIn} for the linked user) and stays
     * inside this transaction — the tenant Hibernate filter is off outside one — so
     * this can never reach, let alone modify, another organisation's user. A
     * {@code userId} that does not resolve to a RENTER in the caller's own tenant
     * (a foreign row the filter hides, or a data mistake linking a staff account) is
     * refused with the same {@link #PORTAL_USER_MISMATCH_CODE} rather than silently
     * skipped or, worse, written to as if it were the renter's own login.
     */
    @Transactional
    public RenterDTO updateRenter(UUID id, UpdateRenterDTO dto) {
        Renter renter = requireInTenant(id);

        String newEmail = dto.getEmail();
        boolean emailChanged = !java.util.Objects.equals(renter.getEmail(), newEmail);

        if (emailChanged && renter.getUserId() != null) {
            String trimmedEmail = newEmail == null ? null : newEmail.trim();
            if (trimmedEmail == null || trimmedEmail.isBlank()) {
                throw new BusinessRuleViolationException(PORTAL_EMAIL_REQUIRED_MESSAGE, PORTAL_EMAIL_REQUIRED_CODE, null);
            }
            User portalUser = userRepository
                    .findByTenantIdAndIdIn(renter.getTenantId(), List.of(renter.getUserId()))
                    .stream().findFirst().orElse(null);
            if (portalUser == null || portalUser.getRole() != UserRole.RENTER) {
                throw new BusinessRuleViolationException(PORTAL_USER_MISMATCH_MESSAGE, PORTAL_USER_MISMATCH_CODE, null);
            }
            String normalizedEmail = trimmedEmail.toLowerCase();
            if (!portalUser.getEmail().equals(normalizedEmail)) {
                // Same pre-check UserService.updateUser makes, kept local rather than
                // routed through UserService: this is a narrow, renter-scoped sync,
                // not a general staff-user edit (role/tenant/password are untouched).
                if (userRepository.existsByTenantIdAndEmail(renter.getTenantId(), normalizedEmail)) {
                    throw new BusinessRuleViolationException(EMAIL_TAKEN_MESSAGE, EMAIL_TAKEN_CODE, null);
                }
                portalUser.setEmail(normalizedEmail);
                try {
                    userRepository.saveAndFlush(portalUser);
                } catch (org.springframework.dao.DataIntegrityViolationException e) {
                    // TOCTOU backstop against the partial unique index (migration 59),
                    // same shape as UserService.createUser/updateUser.
                    throw new BusinessRuleViolationException(EMAIL_TAKEN_MESSAGE, EMAIL_TAKEN_CODE, null);
                }
                // A changed login email ends every session opened with the old one —
                // the same rule UserService.updateUser applies on a password/role/
                // tenant change (audit P1-2). Atomic increment; never a read-modify-
                // write of the entity (see User.tokenVersion).
                userRepository.bumpTokenVersion(portalUser.getId());
            }
        }

        renter.setNameEn(dto.getNameEn());
        renter.setNameAr(dto.getNameAr());
        renter.setEmail(newEmail);
        renter.setPhone(dto.getPhone());
        if (dto.getPrimaryLanguage() != null) {
            renter.setPrimaryLanguage(dto.getPrimaryLanguage());
        }

        return mapToDTO(renterRepository.save(renter));
    }

    private RenterDTO mapToDTO(Renter renter) {
        User user = renter.getUserId() == null ? null
                : userRepository.findById(renter.getUserId())
                        .filter(u -> renter.getTenantId() != null && renter.getTenantId().equals(u.getTenantId()))
                        .orElse(null);
        return mapToDTO(renter, user);
    }

    private RenterDTO mapToDTO(Renter renter, User user) {
        RenterDTO dto = new RenterDTO();
        dto.setId(renter.getId());
        dto.setNameEn(renter.getNameEn());
        dto.setNameAr(renter.getNameAr());
        dto.setEmail(renter.getEmail());
        dto.setPhone(renter.getPhone());
        dto.setPrimaryLanguage(renter.getPrimaryLanguage());
        dto.setUserId(renter.getUserId());
        if (user != null && user.hasPendingInvite()) {
            dto.setInvitePending(true);
            dto.setInviteExpiresAt(user.getInviteTokenExpiresAt());
        }
        return dto;
    }
}

package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.api.dto.CreateRenterDTO;
import com.datagami.rentaxis.api.dto.RenterDTO;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import com.datagami.rentaxis.api.exception.NotFoundException;
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

    @Transactional(readOnly = true)
    public List<RenterDTO> getAllRenters() {
        UUID tenantId = TenantContextHolder.getTenantId();
        List<Renter> renters = renterRepository.findByTenantId(tenantId);
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
        return mapToDTO(requireInTenant(id));
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

    @Transactional
    public RenterDTO updateRenter(UUID id, CreateRenterDTO dto) {
        Renter renter = requireInTenant(id);

        renter.setNameEn(dto.getNameEn());
        renter.setNameAr(dto.getNameAr());
        renter.setEmail(dto.getEmail());
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
        if (user != null && user.getInviteToken() != null) {
            dto.setInvitePending(true);
            dto.setInviteExpiresAt(user.getInviteTokenExpiresAt());
        }
        return dto;
    }
}

package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.RecipientRole;
import com.datagami.rentaxis.domain.entity.Renter;
import com.datagami.rentaxis.domain.entity.User;
import com.datagami.rentaxis.domain.entity.enums.Language;
import com.datagami.rentaxis.domain.entity.enums.UserRole;
import com.datagami.rentaxis.domain.repository.RenterRepository;
import com.datagami.rentaxis.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecipientResolver {

    private final UserRepository userRepo;
    private final RenterRepository renterRepo;

    public List<ResolvedRecipient> resolve(EmailEventType type, Object payload) {
        List<ResolvedRecipient> result = new ArrayList<>();
        for (RecipientRole role : type.recipientRoles()) {
            for (UUID userId : userIdsFor(role, payload)) {
                userRepo.findById(userId).ifPresent(u -> {
                    if (u.getEmail() == null || u.getEmail().isBlank()) return;
                    result.add(new ResolvedRecipient(
                            u.getId(),
                            u.getEmail(),
                            u.getName(),
                            resolveLocale(u),
                            role));
                });
            }
        }
        if (result.isEmpty()) {
            log.warn("No email recipients resolved for event {} payload={}", type, payload);
        }
        // Re-review P2-A: a payload that names its address (toEmail) is mailed there,
        // not to the row's current email, and greets by that address rather than a
        // name the row's organisation wrote. The row only supplies id and language.
        String fixed = readString(payload, "toEmail");
        if (fixed != null && !fixed.isBlank()) {
            List<ResolvedRecipient> pinned = new ArrayList<>();
            for (ResolvedRecipient r : result) {
                pinned.add(new ResolvedRecipient(r.userId(), fixed, fixed, r.locale(), r.role()));
            }
            return dedupByUserId(pinned);
        }
        return dedupByUserId(result);
    }

    private Locale resolveLocale(User u) {
        return renterRepo.findByUserId(u.getId())
                .map(Renter::getPrimaryLanguage)
                .map(this::toLocale)
                .orElse(Locale.ENGLISH);
    }

    private Locale toLocale(Language lang) {
        return lang == Language.AR ? Locale.forLanguageTag("ar") : Locale.ENGLISH;
    }

    private List<UUID> userIdsFor(RecipientRole role, Object payload) {
        return switch (role) {
            case USER, INVITEE, NEW_ADMIN -> uuidField(payload, "userId", "inviteeUserId", "newAdminUserId");
            case RENTER -> uuidField(payload, "renterUserId");
            case PROPERTY_MANAGER -> uuidField(payload, "propertyManagerUserId");
            case TENANT_ADMIN, EXISTING_ADMINS -> tenantAdmins(payload);
            case SUPER_ADMIN -> superAdminsFor(payload);
        };
    }

    private List<UUID> uuidField(Object payload, String... candidates) {
        for (String f : candidates) {
            UUID id = readUuid(payload, f);
            if (id != null) return List.of(id);
        }
        return List.of();
    }

    private String readString(Object payload, String fieldName) {
        if (payload == null) return null;
        try {
            Object v = payload.getClass().getMethod(fieldName).invoke(payload);
            return v instanceof String str ? str : null;
        } catch (NoSuchMethodException e) {
            return null;
        } catch (Exception e) {
            log.warn("Failed to read {} on {}: {}", fieldName, payload.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    private UUID readUuid(Object payload, String fieldName) {
        try {
            var method = payload.getClass().getMethod(fieldName);
            return (UUID) method.invoke(payload);
        } catch (NoSuchMethodException e) { return null; }
        catch (Exception e) { log.warn("Failed to read {} on {}: {}", fieldName, payload, e.getMessage()); return null; }
    }

    private List<UUID> tenantAdmins(Object payload) {
        UUID tenantId = readUuid(payload, "tenantId");
        if (tenantId == null) return List.of();
        return userRepo.findByTenantIdAndRole(tenantId, UserRole.TENANT_ADMIN).stream()
                .map(User::getId).toList();
    }

    /**
     * A legacy {@code NotificationService.notify(...)} call names ONE recipient (its
     * userId) and its caller loops over the people to tell. Resolving SUPER_ADMIN to every
     * super admin for each of those events made event 2..N insert the rows event 1 had
     * already inserted (dedup_key collision, S18-02). So a legacy event goes only to the
     * user it names, and only if that user is a super admin.
     */
    private List<UUID> superAdminsFor(Object payload) {
        if (payload instanceof com.datagami.rentaxis.core.email.event.payload.LegacyNotificationPayload legacy) {
            UUID target = legacy.userId();
            return superAdmins().stream().filter(id -> id.equals(target)).toList();
        }
        return superAdmins();
    }

    private List<UUID> superAdmins() {
        return userRepo.findByRole(UserRole.SUPER_ADMIN).stream().map(User::getId).toList();
    }

    private List<ResolvedRecipient> dedupByUserId(List<ResolvedRecipient> in) {
        Map<UUID, ResolvedRecipient> byId = new LinkedHashMap<>();
        for (ResolvedRecipient r : in) byId.putIfAbsent(r.userId(), r);
        return new ArrayList<>(byId.values());
    }
}

package com.datagami.rentaxis.core.email;

import java.util.EnumSet;
import java.util.Set;

import static com.datagami.rentaxis.core.email.EmailCategory.TRANSACTIONAL;
import static com.datagami.rentaxis.core.email.EmailEventType.AttachmentPolicy.NONE;
import static com.datagami.rentaxis.core.email.EmailEventType.AttachmentPolicy.PDF;
import static com.datagami.rentaxis.core.email.EmailEventType.AttachmentPolicy.SIGNED_URL;
import static com.datagami.rentaxis.core.email.RecipientRole.*;

public enum EmailEventType {
    // Auth & Onboarding
    USER_INVITED            (TRANSACTIONAL, NONE,       EnumSet.of(INVITEE)),
    USER_WELCOMED           (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    PASSWORD_RESET_REQUESTED(TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    PASSWORD_CHANGED        (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    EMAIL_VERIFIED          (TRANSACTIONAL, NONE,       EnumSet.of(USER)),

    // Tenant / Org
    TENANT_PROVISIONED      (TRANSACTIONAL, NONE,       EnumSet.of(TENANT_ADMIN, SUPER_ADMIN)),
    TENANT_ADMIN_ADDED      (TRANSACTIONAL, NONE,       EnumSet.of(NEW_ADMIN, EXISTING_ADMINS)),
    STAFF_ROLE_CHANGED      (TRANSACTIONAL, NONE,       EnumSet.of(USER, TENANT_ADMIN)),

    // Lease
    LEASE_CREATED           (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    LEASE_CONTRACT_GENERATED(TRANSACTIONAL, SIGNED_URL, EnumSet.of(RENTER, PROPERTY_MANAGER)),
    LEASE_SIGNATURE_REQUESTED(TRANSACTIONAL, NONE,      EnumSet.of(RENTER)),
    LEASE_SIGNED            (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    LEASE_ACTIVATED         (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    LEASE_EXPIRING          (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    LEASE_RENEWED           (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    LEASE_TERMINATED        (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    LEASE_RENEWAL_REMINDER  (TRANSACTIONAL, NONE,       EnumSet.of(RENTER)),
    RENEWAL_INTENT_CAPTURED (TRANSACTIONAL, NONE,       EnumSet.of(TENANT_ADMIN, PROPERTY_MANAGER)),

    // Cheque & Payment
    CHEQUE_RECEIVED         (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    CHEQUE_DEPOSITED        (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    CHEQUE_CLEARED          (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    CHEQUE_BOUNCED          (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    PAYMENT_DUE_REMINDER    (TRANSACTIONAL, NONE,       EnumSet.of(RENTER)),
    PAYMENT_OVERDUE         (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    ONLINE_PAYMENT_RECEIVED (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    ONLINE_PAYMENT_FAILED   (TRANSACTIONAL, NONE,       EnumSet.of(RENTER)),
    RENT_RECEIPT_AVAILABLE  (TRANSACTIONAL, PDF,        EnumSet.of(RENTER)),

    // Penalties
    PENALTY_INCURRED        (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    PENALTY_CLEARED         (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),
    PENALTY_WAIVED          (TRANSACTIONAL, NONE,       EnumSet.of(RENTER, PROPERTY_MANAGER)),

    // Tickets
    TICKET_ASSIGNED         (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    TICKET_REPLY            (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    TICKET_RESOLVED         (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    TICKET_CREATED          (TRANSACTIONAL, NONE,       EnumSet.of(PROPERTY_MANAGER)),
    TICKET_REOPENED         (TRANSACTIONAL, NONE,       EnumSet.of(PROPERTY_MANAGER, RENTER)),

    // Meetings
    MEETING_REQUESTED       (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    MEETING_APPROVED        (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    MEETING_CANCELLED       (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    MEETING_COMPLETED       (TRANSACTIONAL, NONE,       EnumSet.of(USER)),
    MEETING_NO_SHOW         (TRANSACTIONAL, NONE,       EnumSet.of(USER));

    public enum AttachmentPolicy { NONE, PDF, SIGNED_URL }

    private final EmailCategory category;
    private final AttachmentPolicy attachmentPolicy;
    private final Set<RecipientRole> recipientRoles;

    EmailEventType(EmailCategory category, AttachmentPolicy attachmentPolicy, Set<RecipientRole> recipientRoles) {
        this.category = category;
        this.attachmentPolicy = attachmentPolicy;
        this.recipientRoles = recipientRoles;
    }

    /**
     * Account-access mail that goes out whether or not the tenant has turned on
     * EMAIL_NOTIFICATIONS (PR #342 review C1).
     *
     * <p>Invite-only onboarding (#7, #2) makes the set-password link the only way
     * into a new account, and the flag defaults to OFF, so gating these would lock
     * out every renter and staff member of a tenant that has not opted in —
     * including a new organisation's first admin. The flag exists to hold back
     * notification mail during rollout; these are credentials, not notifications.
     *
     * <p>An explicit list on purpose, not "every TRANSACTIONAL event": receipts,
     * reminders and the rest are exactly what the rollout flag holds back. The
     * global {@code rentaxis.email.outbox.enabled} kill-switch still applies.
     */
    public boolean bypassesTenantGate() {
        return switch (this) {
            case USER_INVITED, PASSWORD_RESET_REQUESTED, PASSWORD_CHANGED -> true;
            default -> false;
        };
    }

    public EmailCategory category() { return category; }
    public AttachmentPolicy attachmentPolicy() { return attachmentPolicy; }
    public Set<RecipientRole> recipientRoles() { return recipientRoles; }
    public String snake() { return name().toLowerCase(); }
}

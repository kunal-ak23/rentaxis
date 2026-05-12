package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.EmailEventType;
import org.apache.commons.text.StringEscapeUtils;

import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.Map;

public class PayloadVarsExtractor {

    /**
     * Escape a user-controlled string for safe interpolation into th:utext message fragments.
     * Variables whose key ends with "Url" (case-insensitive) are intentional URLs that go into
     * {@code th:href} — Thymeleaf already URL-encodes those, so we must NOT HTML-escape them.
     * Everything else that is a plain String gets escaped here to neutralise XSS via MessageFormat
     * {0}/{1} argument substitution.
     */
    private static String escapeHtml(String s) {
        return s == null ? null : StringEscapeUtils.escapeHtml4(s);
    }

    private static boolean isUrlKey(String key) {
        return key != null && key.toLowerCase().endsWith("url");
    }

    public static Map<String, Object> extract(EmailEventType type, Object payload, String portalBaseUrl, String localeLang) {
        Map<String, Object> vars = new HashMap<>();
        if (payload != null && payload.getClass().isRecord()) {
            for (RecordComponent c : payload.getClass().getRecordComponents()) {
                try {
                    Object val = c.getAccessor().invoke(payload);
                    // HTML-escape String values that are not URLs to prevent XSS via th:utext interpolation
                    if (val instanceof String s && !isUrlKey(c.getName())) {
                        val = escapeHtml(s);
                    }
                    vars.put(c.getName(), val);
                } catch (Exception ignored) {}
            }
        }
        vars.put("ctaUrl", computeCtaUrl(type, vars, portalBaseUrl, localeLang));
        Object[] subjectArgs = subjectArgsFor(type, vars);
        vars.put("__subjectArgs", subjectArgs);
        return vars;
    }

    private static String computeCtaUrl(EmailEventType type, Map<String, Object> vars, String base, String lang) {
        String prefix = base + "/" + lang + "/dashboard";
        return switch (type) {
            case USER_INVITED -> {
                String token = str(vars, "inviteToken");
                yield token.isBlank()
                        ? base + "/" + lang + "/auth/login"
                        : base + "/" + lang + "/auth/set-password?token=" + token;
            }
            case USER_WELCOMED, EMAIL_VERIFIED -> absolutize(str(vars, "dashboardUrl"), base, lang);
            case PASSWORD_RESET_REQUESTED -> absolutize(str(vars, "resetUrl"), base, lang);
            case LEASE_CONTRACT_GENERATED -> absolutize(str(vars, "contractSignedUrl"), base, lang);
            case LEASE_CREATED, LEASE_SIGNATURE_REQUESTED, LEASE_SIGNED, LEASE_ACTIVATED,
                 LEASE_EXPIRING, LEASE_RENEWED, LEASE_TERMINATED, RENT_RECEIPT_AVAILABLE
                    -> prefix + "/leases/" + str(vars, "leaseId");
            case LEASE_RENEWAL_REMINDER -> prefix + "/leases/" + str(vars, "leaseId");
            case RENEWAL_INTENT_CAPTURED -> prefix + "/leases/" + str(vars, "leaseId");
            case CHEQUE_RECEIVED, CHEQUE_DEPOSITED, CHEQUE_CLEARED, CHEQUE_BOUNCED,
                 PAYMENT_DUE_REMINDER, PAYMENT_OVERDUE, ONLINE_PAYMENT_RECEIVED, ONLINE_PAYMENT_FAILED,
                 PENALTY_INCURRED, PENALTY_CLEARED, PENALTY_WAIVED
                    -> prefix + "/finance/payments";
            case TICKET_ASSIGNED, TICKET_REPLY, TICKET_RESOLVED, TICKET_CREATED, TICKET_REOPENED
                    -> prefix + "/tickets/" + str(vars, "ticketId");
            case MEETING_REQUESTED, MEETING_APPROVED, MEETING_CANCELLED,
                 MEETING_COMPLETED, MEETING_NO_SHOW
                    -> prefix + "/meetings/" + str(vars, "meetingId");
            default -> base;
        };
    }

    private static Object[] subjectArgsFor(EmailEventType type, Map<String, Object> vars) {
        return switch (type) {
            case USER_INVITED -> new Object[]{ vars.getOrDefault("companyName", "RentAxis") };
            case TENANT_PROVISIONED -> new Object[]{ vars.get("tenantName") };
            case STAFF_ROLE_CHANGED -> new Object[]{ vars.get("userName") };
            case LEASE_CREATED, LEASE_SIGNED, LEASE_ACTIVATED, LEASE_EXPIRING,
                 LEASE_RENEWED, LEASE_TERMINATED, LEASE_SIGNATURE_REQUESTED
                    -> new Object[]{ vars.get("unitLabel"), vars.get("propertyName") };
            case LEASE_RENEWAL_REMINDER -> new Object[]{ vars.get("unitNumber"), vars.get("propertyNameEn") };
            case RENEWAL_INTENT_CAPTURED -> new Object[]{ vars.get("renterName"), vars.get("intent") };
            case CHEQUE_RECEIVED, CHEQUE_DEPOSITED, CHEQUE_CLEARED, CHEQUE_BOUNCED
                    -> new Object[]{ vars.get("chequeNumber"), vars.get("amountDisplay"), vars.get("installmentNumber") };
            case PAYMENT_DUE_REMINDER -> new Object[]{ vars.get("amountDisplay"), vars.get("dueDateIso"), vars.get("daysUntilDue") };
            case ONLINE_PAYMENT_RECEIVED, ONLINE_PAYMENT_FAILED -> new Object[]{ vars.get("amountDisplay") };
            case TICKET_ASSIGNED, TICKET_REPLY, TICKET_RESOLVED, TICKET_CREATED, TICKET_REOPENED
                    -> new Object[]{ vars.get("title") };
            case MEETING_REQUESTED, MEETING_APPROVED, MEETING_CANCELLED,
                 MEETING_COMPLETED, MEETING_NO_SHOW -> new Object[]{ vars.get("purpose") };
            case TENANT_ADMIN_ADDED -> new Object[]{ vars.get("newAdminName") };
            default -> new Object[0];
        };
    }

    private static String str(Map<String, Object> vars, String key) {
        Object v = vars.get(key);
        return v == null ? "" : v.toString();
    }

    /**
     * Promote a possibly-relative URL to an absolute one. Already-absolute URLs
     * (http(s)://...) are returned unchanged. Relative URLs starting with "/"
     * get prefixed with the base + locale; null/blank fall back to the base.
     */
    private static String absolutize(String url, String base, String lang) {
        if (url == null || url.isBlank()) return base;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        String langPrefix = base + "/" + lang;
        return url.startsWith("/") ? langPrefix + url : langPrefix + "/" + url;
    }
}

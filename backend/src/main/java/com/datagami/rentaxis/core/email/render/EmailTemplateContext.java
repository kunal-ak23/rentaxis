package com.datagami.rentaxis.core.email.render;

import com.datagami.rentaxis.core.email.EmailEventType;
import com.datagami.rentaxis.core.email.dispatch.TenantBranding;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public record EmailTemplateContext(
        EmailEventType type,
        Locale locale,
        UUID recipientUserId,
        String recipientName,
        String recipientEmail,
        String portalBaseUrl,
        TenantBranding tenantBranding,
        String unsubscribeUrl,
        Map<String, Object> payloadVars
) {}

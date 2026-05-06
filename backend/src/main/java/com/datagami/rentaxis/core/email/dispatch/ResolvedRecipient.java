package com.datagami.rentaxis.core.email.dispatch;

import com.datagami.rentaxis.core.email.RecipientRole;

import java.util.Locale;
import java.util.UUID;

public record ResolvedRecipient(
        UUID userId,
        String email,
        String name,
        Locale locale,
        RecipientRole role
) {}

package com.datagami.rentaxis.core.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The security boundary for ad click-throughs. An admin-entered URL that
 * passes here is one the renter mobile app will open, so this rejects by
 * default: https only, host must be an allowlisted hostname or a subdomain of
 * one, and anything unparseable is refused rather than guessed at.
 *
 * <p>Subdomain matching compares label boundaries, not string suffixes —
 * {@code evil-spice-bazaar.ae} ends with {@code spice-bazaar.ae} but is a
 * different domain and must not pass.
 */
@Component
public class PromotionUrlValidator {

    /** Normalises admin input into bare lowercase hostnames. */
    public List<String> parseDomains(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::toHost)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
    }

    /** Strips scheme, credentials, port and path from whatever the admin pasted. */
    private String toHost(String value) {
        String s = value.toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int at = s.indexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        int cut = s.length();
        for (char c : new char[]{'/', '?', '#', ':'}) {
            int i = s.indexOf(c);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        return s.substring(0, cut);
    }

    public boolean isAllowed(String url, String allowedDomainsRaw) {
        List<String> allowed = parseDomains(allowedDomainsRaw);
        if (allowed.isEmpty() || url == null || url.isBlank()) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return false;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return allowed.stream().anyMatch(d -> h.equals(d) || h.endsWith("." + d));
    }
}

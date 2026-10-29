package com.datagami.rentaxis.core.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The security boundary for ad click-throughs. An admin-entered URL that
 * passes here is one the renter mobile app will open, so this rejects by
 * default: {@link #isAllowed} is https only, host must be an allowlisted
 * hostname or a subdomain of one, and anything unparseable is refused rather
 * than guessed at. That is the whole rule for a WEBSITE ad's {@code ctaUrl}.
 *
 * <p>A coupon ad's redirect links ({@code redirectUrl}, {@code redirectUrlAndroid},
 * {@code redirectUrlIos}) also accept an app deep link with a custom scheme
 * ({@code glidewash://redeem}), see {@link #isAllowedRedirect} and
 * {@link #isAppLink} (owner ruling 2026-10-29). The domain allowlist cannot
 * apply to those — they have no web host — so the control is the admin's
 * review of the ad (promoter-written ads are approved before renters see them;
 * the review screen marks an app link). What this still refuses is any scheme a
 * phone would hand to a browser, a script engine, a file or the dialler.
 *
 * <p>Subdomain matching compares label boundaries, not string suffixes —
 * {@code evil-spice-bazaar.ae} ends with {@code spice-bazaar.ae} but is a
 * different domain and must not pass.
 *
 * <p><b>Known limitation:</b> internationalised (non-ASCII) domains are not
 * supported. {@code URI.getHost()} returns null for them, so an Arabic-script
 * domain entered in the admin panel is dropped by {@link #parseDomains} and
 * can never match. This is fail-closed, not a hole — a punycode host is a
 * distinct ASCII string that cannot collide with an allowlisted one, so
 * homograph attacks are impossible. If Arabic-script domains are ever needed,
 * run both sides through {@link java.net.IDN#toASCII} and compare punycode.
 */
@Component
public class PromotionUrlValidator {

    /**
     * A hostname as {@link URI#getHost()} would return it: dot-separated
     * alphanumeric-or-hyphen labels, at least two of them. Anything else an
     * admin types — a wildcard, a bare TLD, a stray word — could never match
     * a real URL, so it is dropped here rather than stored as an entry that
     * silently allows nothing.
     */
    private static final Pattern HOSTNAME = Pattern.compile(
            "^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$");

    /**
     * An app link's scheme: a letter, then 1–31 letters, digits, {@code +}, {@code .}
     * or {@code -} (RFC 3986's scheme alphabet, length-capped). Case-insensitive.
     */
    private static final Pattern APP_SCHEME = Pattern.compile(
            "^[a-z][a-z0-9+.-]{1,31}$", Pattern.CASE_INSENSITIVE);

    /**
     * Schemes an app link may never use, compared lower-cased. Web schemes stay
     * under {@link #isAllowed}'s domain rule (so an https link on a domain the
     * business does not claim is still refused, and plain http never passes);
     * the rest run script, read local data, reach system handlers (Android
     * {@code intent:}, iOS enterprise installs) or place calls and messages.
     */
    static final Set<String> REFUSED_APP_SCHEMES = Set.of(
            "http", "https", "javascript", "vbscript", "data", "file", "blob", "about",
            "content", "ftp", "ws", "wss", "intent", "itms-services", "chrome",
            "chrome-extension", "mailto", "tel", "sms", "facetime", "facetime-audio");

    /** Normalises admin input into bare lowercase hostnames, dropping anything invalid. */
    public List<String> parseDomains(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(this::toHost)
                .filter(HOSTNAME.asMatchPredicate())
                .distinct()
                .toList();
    }

    /**
     * Reduces whatever the admin pasted to a bare hostname.
     *
     * <p>Order matters and is the whole correctness argument. The authority
     * ends at the first {@code /}, {@code ?} or {@code #}, so the path, query
     * and fragment are cut FIRST — a {@code @} inside a query string
     * ({@code ?email=owner@gmail.com}) is not a credential separator, and
     * stripping userinfo before the cut would store {@code gmail.com} as the
     * business's allowlist. Only then is userinfo removed, splitting on the
     * LAST {@code @} because RFC 3986 permits {@code @} inside userinfo and
     * browsers split there too. Port comes off last, and a single trailing
     * dot is normalised away because {@code URI.getHost()} never returns one.
     */
    private String toHost(String value) {
        String s = value.trim().toLowerCase(Locale.ROOT);
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int cut = s.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int i = s.indexOf(c);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        s = s.substring(0, cut);
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            s = s.substring(at + 1);
        }
        int port = s.indexOf(':');
        if (port >= 0) {
            s = s.substring(0, port);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
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
        // Refuse userinfo outright. Nothing legitimate needs it in an ad link,
        // and `https://my-bank.com@spice-bazaar.ae/` reads as the bank in an
        // in-app browser's minimal URL chrome even though it navigates to the
        // allowed host. Checked on the raw authority so an unparsed '@' in a
        // registry-based authority is caught too.
        String authority = uri.getRawAuthority();
        if (authority == null || authority.indexOf('@') >= 0) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        // `host.` is the FQDN form of `host` and a browser treats them the
        // same. toHost strips it when storing, so strip it here too — without
        // this the two sides disagree and a business's own FQDN-form link is
        // refused. Stripping cannot widen anything: a stored entry can never
        // carry a trailing dot, so the "." + d suffix test is unaffected.
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        final String normalizedHost = h;
        return allowed.stream().anyMatch(d -> normalizedHost.equals(d) || normalizedHost.endsWith("." + d));
    }

    /**
     * The rule for a coupon redirect link: {@link #isAllowed} (https on the
     * business's allowed domains) or an {@link #isAppLink app link}. Length is
     * capped by the caller.
     */
    public boolean isAllowedRedirect(String url, String allowedDomainsRaw) {
        return isAllowed(url, allowedDomainsRaw) || isAppLink(url);
    }

    /**
     * An app deep link with a custom scheme: the scheme matches {@link #APP_SCHEME},
     * something follows {@code scheme:}, nothing anywhere is whitespace or a
     * control/format character (a zero-width or bidi-override character would let
     * a link read differently on the review screen than what the phone opens), and
     * the scheme is not one of {@link #REFUSED_APP_SCHEMES}. Not trimmed: a caller
     * passes the stored, already-trimmed value.
     */
    public boolean isAppLink(String url) {
        if (url == null || url.isEmpty()) {
            return false;
        }
        for (int i = 0; i < url.length(); ) {
            int cp = url.codePointAt(i);
            if (isWhitespaceOrControl(cp)) {
                return false;
            }
            i += Character.charCount(cp);
        }
        int colon = url.indexOf(':');
        if (colon < 0 || colon == url.length() - 1) {
            return false;
        }
        String scheme = url.substring(0, colon);
        return APP_SCHEME.matcher(scheme).matches()
                && !REFUSED_APP_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
    }

    private static boolean isWhitespaceOrControl(int cp) {
        int type = Character.getType(cp);
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp) || cp == 0xFEFF
                || type == Character.CONTROL || type == Character.FORMAT;
    }
}

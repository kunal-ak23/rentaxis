package com.datagami.rentaxis.core.service;

import java.util.List;

/**
 * The one hard-coded fixture of schemes an app link may never use, shared by the
 * validator test and the coupon redirect IT. Written out by hand on purpose: a
 * scheme dropped from {@link PromotionUrlValidator#REFUSED_APP_SCHEMES} by mistake
 * still has to be refused here. Keep in step with the web and renter-app fixtures.
 */
public final class RefusedAppSchemes {

    private RefusedAppSchemes() {
    }

    /** Every named refused scheme. */
    public static final List<String> NAMED = List.of(
            "http", "https", "javascript", "vbscript", "data", "file", "blob", "about",
            "content", "ftp", "ws", "wss", "intent", "itms-services", "chrome",
            "chrome-extension", "mailto", "tel", "sms", "facetime", "facetime-audio",
            "googlechrome", "googlechromes", "firefox", "focus", "brave", "opera", "opera-http",
            "microsoft-edge", "duckduckgo", "edge",
            "telprompt", "facetime-prompt", "facetime-audio-prompt", "smsto", "mms", "mmsto");

    /** Refused by pattern: ends in -http / -https, or starts with x-safari-. */
    public static final List<String> BY_PATTERN = List.of(
            "microsoft-edge-https", "microsoft-edge-http", "opera-https", "x-safari-https",
            "x-safari-http", "x-safari-anything", "somebrowser-https", "somebrowser-http");

    /** Legitimate app schemes that must keep working. */
    public static final List<String> ALLOWED = List.of("glidewash", "myapp", "fb-messenger");

    /** Every refused scheme, named and by pattern. */
    public static List<String> all() {
        return java.util.stream.Stream.concat(NAMED.stream(), BY_PATTERN.stream()).toList();
    }
}

package com.datagami.rentaxis.core.util;

import com.openhtmltopdf.extend.FSUriResolver;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

import java.util.Locale;

/**
 * What an HTML-to-PDF render may load: inline {@code data:} URIs and nothing
 * else.
 *
 * <p>openhtmltopdf's default user agent resolves every {@code src}, {@code href}
 * and CSS {@code url()} against the base URI and fetches it — {@code http(s)},
 * {@code file}, {@code jar}, whatever {@link java.net.URL} understands. The
 * contract and receipt templates carry user-authored text (charge-type names,
 * org details, narrations), so one unescaped value, or one logo URL, was enough
 * to make the backend fetch {@code http://169.254.169.254/...} or read
 * {@code file:///etc/passwd} into a PDF the attacker then downloads.</p>
 *
 * <p>The templates themselves reference nothing external: fonts are handed to
 * the builder as classpath streams through {@code useFont}, which does not go
 * through URI resolution, and the CSS is inline. So the only legitimate external
 * reference is an inline {@code data:} image. Everything else is refused twice —
 * by the URI resolver (returns {@code null}, so nothing is resolved) and by the
 * access controller, before and after resolution, so a future change to one of
 * them cannot quietly reopen the other.</p>
 */
public final class PdfResourcePolicy {

    private PdfResourcePolicy() {
    }

    /** True only for an inline {@code data:} URI. */
    public static boolean isAllowed(String uri) {
        if (uri == null) return false;
        return uri.strip().toLowerCase(Locale.ROOT).startsWith("data:");
    }

    /** Resolves only {@code data:} URIs; anything else resolves to nothing. */
    public static final FSUriResolver URI_RESOLVER =
            (baseUri, uri) -> isAllowed(uri) ? uri.strip() : null;

    /** Applies the policy to a builder. Call on every {@link PdfRendererBuilder}. */
    public static PdfRendererBuilder apply(PdfRendererBuilder builder) {
        builder.useUriResolver(URI_RESOLVER);
        builder.useExternalResourceAccessControl(
                (uri, type) -> isAllowed(uri), ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
        builder.useExternalResourceAccessControl(
                (uri, type) -> isAllowed(uri), ExternalResourceControlPriority.RUN_AFTER_RESOLVING_URI);
        return builder;
    }
}

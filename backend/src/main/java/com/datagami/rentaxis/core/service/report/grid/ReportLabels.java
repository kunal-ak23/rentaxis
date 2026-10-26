package com.datagami.rentaxis.core.service.report.grid;

import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * The report labels bundle ({@code classpath:messages/reports_{en,ar}.properties}).
 *
 * <p>It owns a private message source instead of registering a {@code MessageSource} bean:
 * {@code EmailMessageSourceConfig} already publishes one under the alias {@code messageSource},
 * and a second bean of that type would make by-type injection ambiguous.
 *
 * <p>Report languages are {@code ar} and {@code en}; anything else reads English. A key that is
 * missing from the bundle throws {@link NoSuchMessageException} rather than printing the key.
 */
@Component
public class ReportLabels {

    private static final Locale ARABIC = Locale.of("ar");

    private final ReloadableResourceBundleMessageSource source;

    public ReportLabels() {
        ReloadableResourceBundleMessageSource ms = new ReloadableResourceBundleMessageSource();
        ms.setBasename("classpath:messages/reports");
        ms.setDefaultEncoding("UTF-8");
        ms.setFallbackToSystemLocale(false);
        ms.setUseCodeAsDefaultMessage(false);
        this.source = ms;
    }

    /** {@code "ar"} (any case, surrounding spaces ignored) is Arabic; anything else, {@code null} included, is English. */
    public static String lang(String raw) {
        return raw != null && raw.trim().equalsIgnoreCase("ar") ? "ar" : "en";
    }

    /** The label for {@code key} in {@code lang}, with {@code args} filled in by MessageFormat. */
    public String get(String key, String lang, Object... args) {
        Locale locale = "ar".equals(lang(lang)) ? ARABIC : Locale.ENGLISH;
        return source.getMessage(key, args, locale);
    }

    /** These labels fixed to one language. */
    public Bound bind(String lang) {
        return new Bound(this, lang(lang));
    }

    /** The labels in one language, for a writer or sink that renders a single report. */
    public record Bound(ReportLabels labels, String lang) {
        public String get(String key, Object... args) {
            return labels.get(key, lang, args);
        }
    }
}

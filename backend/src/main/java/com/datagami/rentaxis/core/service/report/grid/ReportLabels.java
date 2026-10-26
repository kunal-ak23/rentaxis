package com.datagami.rentaxis.core.service.report.grid;

import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
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
 *
 * <p>Numeric arguments are turned into Latin-digit text before MessageFormat sees them, so an
 * Arabic label never shows Arabic-Indic digits: a {@link BigDecimal} reads as
 * {@link ReportFormat#money}, any other {@link Number} as {@code #,##0.###}. Bundle placeholders
 * are therefore plain {@code {0}}, never {@code {0,number}} or {@code {0,date}}.
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

    /** The label for {@code key} in {@code lang}, with {@code args} filled in by MessageFormat (numbers in Latin digits). */
    public String get(String key, String lang, Object... args) {
        Locale locale = "ar".equals(lang(lang)) ? ARABIC : Locale.ENGLISH;
        return source.getMessage(key, latinDigits(args), locale);
    }

    /** {@code args} with every number already written out in Latin digits; other arguments are passed through. */
    private static Object[] latinDigits(Object[] args) {
        if (args == null || args.length == 0) {
            return args;
        }
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            Object a = args[i];
            if (a instanceof BigDecimal b) {
                out[i] = ReportFormat.money(b);
            } else if (a instanceof Number n) {
                out[i] = NumberFormat.getInstance(Locale.ROOT).format(n);
            } else {
                out[i] = a;
            }
        }
        return out;
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

package com.datagami.rentaxis.config;

import java.time.ZoneId;
import java.util.TimeZone;

/**
 * The zone "today" is read in: the UAE's, whatever the server runs in.
 *
 * <p>Production runs in UTC, so a server-zone {@code LocalDate.now()} is
 * yesterday from 00:00 to 04:00 in Dubai — a PDC deposited on its own date is
 * refused as presented early, a ticket reported today is refused as reported in
 * the future. Rather than thread a zone through every call site, the zone is
 * made the JVM default: once at start-up in {@code RentAxisApplication.main}
 * (before any bean exists) and again by {@link ClockConfig} from
 * {@code app.time-zone}, so a {@code @SpringBootTest} context runs on it too.</p>
 *
 * <p>Only the JVM's zone changes. The database session is held on UTC (see
 * {@code spring.datasource.hikari.connection-init-sql}), because zone-less
 * {@code timestamp} columns filled by {@code now()} defaults have always been
 * written in UTC.</p>
 */
public final class AppTimeZone {

    public static final ZoneId DEFAULT = ZoneId.of("Asia/Dubai");

    /** System property and environment variable, in that order of precedence. */
    static final String PROPERTY = "app.time-zone";
    static final String ENV = "APP_TIME_ZONE";

    private AppTimeZone() {
    }

    /** The configured zone, or Asia/Dubai when none is configured. */
    public static ZoneId resolve(String configured) {
        return configured == null || configured.isBlank() ? DEFAULT : ZoneId.of(configured.trim());
    }

    /** Make {@code zone} the JVM default, so every {@code LocalDate.now()} reads it. */
    public static ZoneId apply(ZoneId zone) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        return zone;
    }

    /** For {@code main}, before Spring has read any configuration. */
    public static ZoneId applyFromSystem() {
        String configured = System.getProperty(PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(ENV);
        }
        return apply(resolve(configured));
    }
}

package com.datagami.rentaxis.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

@Configuration
public class ClockConfig {

    private final ZoneId zone;

    /**
     * Applies {@code app.time-zone} (default Asia/Dubai) as the JVM default as
     * soon as this configuration is built, whether or not the clock bean itself is
     * ever asked for — a test that replaces the bean still gets the zone, and so
     * does every bare {@code LocalDate.now()}. See {@link AppTimeZone}.
     */
    public ClockConfig(@Value("${app.time-zone:Asia/Dubai}") String timeZone) {
        this.zone = AppTimeZone.apply(AppTimeZone.resolve(timeZone));
    }

    @Bean
    public Clock systemClock() {
        AppTimeZone.apply(zone);
        return Clock.system(zone);
    }

    /** Explicit ObjectMapper bean so services that need one for internal JSON
     *  serialization can inject it — consistent with Spring MVC's mapper. */
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}

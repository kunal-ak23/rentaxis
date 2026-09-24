package com.datagami.rentaxis.core.service.report.statement;

import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Figure;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Section;
import com.datagami.rentaxis.domain.entity.Property;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** What a section is built for, and the sections already built (net cash reads three of them). */
public final class StatementContext {

    private final UUID tenantId;
    private final Property property;
    private final LocalDate from;
    private final LocalDate to;
    private final Map<String, Section> built = new LinkedHashMap<>();
    private final Map<String, Object> cache = new java.util.HashMap<>();

    public StatementContext(UUID tenantId, Property property, LocalDate from, LocalDate to) {
        this.tenantId = tenantId;
        this.property = property;
        this.from = from;
        this.to = to;
    }

    public UUID tenantId() { return tenantId; }
    public Property property() { return property; }
    public UUID propertyId() { return property.getId(); }
    public LocalDate from() { return from; }
    public LocalDate to() { return to; }

    /** A value computed once per statement and shared by the sections that need it. */
    @SuppressWarnings("unchecked")
    public <T> T cached(String key, java.util.function.Supplier<T> compute) {
        Object v = cache.get(key);
        if (v == null) {
            v = compute.get();
            cache.put(key, v);
        }
        return (T) v;
    }

    void add(Section s) { built.put(s.key(), s); }

    /** A figure from a section built earlier, or zero when either is missing. */
    public BigDecimal figure(String sectionKey, String figureKey) {
        Section s = built.get(sectionKey);
        if (s == null) return BigDecimal.ZERO;
        return s.figures().stream().filter(f -> f.key().equals(figureKey)).map(Figure::amount)
                .findFirst().orElse(BigDecimal.ZERO);
    }
}

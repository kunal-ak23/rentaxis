package com.datagami.rentaxis.core.service.report.statement;

import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO;
import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Section;
import com.datagami.rentaxis.core.service.report.PropertyPnlService;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.TenantFiscalSettings;
import com.datagami.rentaxis.api.exception.BusinessRuleViolationException;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The property statement pack (finance-ops spec §1): every {@link StatementSection}
 * bean, in order, computed live for one property and period. Read-only.
 */
@Service
@Transactional(readOnly = true)
public class PropertyStatementService {

    private final List<StatementSection> sections;
    private final PropertyPnlService pnl;
    private final TenantFiscalSettingsRepository fiscal;

    public PropertyStatementService(List<StatementSection> sections, PropertyPnlService pnl,
                                    TenantFiscalSettingsRepository fiscal) {
        this.sections = sections.stream().sorted(Comparator.comparingInt(StatementSection::order)).toList();
        this.pnl = pnl;
        this.fiscal = fiscal;
    }

    public PropertyStatementDTO statement(UUID propertyId, LocalDate from, LocalDate to, String generatedBy) {
        UUID tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) throw new BusinessRuleViolationException("Choose an organisation first");
        if (from == null || to == null) throw new BusinessRuleViolationException("'from' and 'to' are required");
        if (to.isBefore(from)) throw new BusinessRuleViolationException("'to' must not be before 'from'");
        if (from.plusYears(5).isBefore(to)) throw new BusinessRuleViolationException("A report covers at most five years");
        Property property = pnl.requireProperty(tenantId, propertyId);

        StatementContext ctx = new StatementContext(tenantId, property, from, to);
        List<Section> built = new ArrayList<>();
        for (StatementSection s : sections) {
            Section section = s.build(ctx);
            ctx.add(section);
            built.add(section);
        }
        LocalDate lockedThrough = fiscal.findById(tenantId).map(TenantFiscalSettings::getBooksLockedThrough).orElse(null);
        boolean isFinal = lockedThrough != null && !lockedThrough.isBefore(to);
        return new PropertyStatementDTO(property.getId(), property.getNameEn(), property.getNameAr(),
                property.getEmirate() == null ? null : property.getEmirate().name(), from, to, built,
                new PropertyStatementDTO.Footer(isFinal, lockedThrough, Instant.now(), generatedBy));
    }
}

package com.datagami.rentaxis.core.service.report.statement;

import com.datagami.rentaxis.api.dto.report.PropertyStatementDTO.Section;

/**
 * One section of the property statement pack (finance-ops spec §1). The pack is
 * the ordered list of every {@code StatementSection} bean, so a later owner layer
 * adds a section by adding a bean — the pack itself does not change.
 */
public interface StatementSection {

    /** Position in the pack; the spec's sections are 1–9. */
    int order();

    /** Computed live, read-only, inside the pack's transaction. Earlier sections are in {@code ctx}. */
    Section build(StatementContext ctx);
}

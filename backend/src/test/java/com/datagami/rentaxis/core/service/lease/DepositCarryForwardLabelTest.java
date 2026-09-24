package com.datagami.rentaxis.core.service.lease;

import com.datagami.rentaxis.domain.entity.Lease;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** #86: the carry-forward JV names its predecessor contract, never a UUID. */
class DepositCarryForwardLabelTest {

    private static Lease lease(Long contractNumber, String externalRef) {
        Property p = new Property();
        p.setCode("OLV");
        Unit u = new Unit();
        u.setUnitNumber("A-101");
        u.setProperty(p);
        Lease l = new Lease();
        l.setId(UUID.randomUUID());
        l.setUnit(u);
        l.setStartDate(LocalDate.of(2025, 10, 1));
        l.setEndDate(LocalDate.of(2026, 9, 30));
        l.setContractNumber(contractNumber);
        l.setExternalContractRef(externalRef);
        return l;
    }

    @Test
    void unitAndTerm_plusTheContractNumberWhenThereIsOne() {
        assertThat(DepositCarryForward.contractLabel(lease(12L, null)))
                .isEqualTo("A-101 · 01/10/2025–30/09/2026 (contract OLV/12)");
    }

    @Test
    void anImportedContractShowsItsExternalReference() {
        assertThat(DepositCarryForward.contractLabel(lease(null, "PACT-7781")))
                .isEqualTo("A-101 · 01/10/2025–30/09/2026 (contract PACT-7781)");
    }

    @Test
    void withNoNumberItIsUnitAndTerm_andNeverTheId() {
        Lease l = lease(null, null);
        assertThat(DepositCarryForward.contractLabel(l))
                .isEqualTo("A-101 · 01/10/2025–30/09/2026")
                .doesNotContain(l.getId().toString());
    }
}

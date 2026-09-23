package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.LandlordOrg;
import com.datagami.rentaxis.domain.entity.Property;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.enums.Emirate;
import com.datagami.rentaxis.domain.repository.LandlordOrgRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.UnitRepository;
import com.datagami.rentaxis.testsupport.AbstractPostgresIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A property's units come back in the order a human reads a building.
 *
 * <p>The query carried no {@code ORDER BY}, so the database answered in
 * insertion-adjacent but effectively arbitrary order. Creating A-101 through
 * A-302 in sequence listed back as A-101, A-201, A-102, A-103, A-203, A-202,
 * A-301, A-302 — unusable for a tower of any size, and the order shifted as rows
 * were updated.</p>
 */
@SpringBootTest
class UnitOrderingIT extends AbstractPostgresIT {

    @Autowired LandlordOrgRepository orgRepo;
    @Autowired PropertyRepository propertyRepo;
    @Autowired UnitRepository unitRepo;

    @Test
    void unitsComeBackOrderedByUnitNumberWhateverOrderTheyWereCreatedIn() {
        LandlordOrg org = new LandlordOrg();
        org.setName("UnitOrdering-" + UUID.randomUUID());
        UUID tenantId = orgRepo.save(org).getId();

        Property property = new Property();
        property.setNameEn("Ordering Tower " + UUID.randomUUID().toString().substring(0, 8));
        property.setEmirate(Emirate.DUBAI);
        property.setTenantId(tenantId);
        property = propertyRepo.save(property);

        // Deliberately shuffled on the way in: the ordering must come from the
        // query, not from the order rows happened to be written.
        for (String number : List.of("A-301", "A-102", "A-202", "A-101", "A-203", "A-302", "A-103", "A-201")) {
            Unit unit = new Unit();
            unit.setProperty(property);
            unit.setUnitNumber(number);
            unit.setTenantId(tenantId);
            unitRepo.save(unit);
        }

        assertThat(unitRepo.findByPropertyId(property.getId()))
                .extracting(Unit::getUnitNumber)
                .containsExactly("A-101", "A-102", "A-103", "A-201", "A-202", "A-203", "A-301", "A-302");
    }
}

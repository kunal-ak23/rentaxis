package com.datagami.rentaxis.api;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.entity.BankAccount;
import com.datagami.rentaxis.domain.entity.Building;
import com.datagami.rentaxis.domain.entity.Staff;
import com.datagami.rentaxis.domain.entity.Unit;
import com.datagami.rentaxis.domain.entity.Vendor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What Jackson actually does with a request body carrying an id.
 *
 * <p>The companion source-level test proves the annotation is present; this one
 * proves it has the effect intended. Several create methods are a bare
 * {@code repository.save(entity)}, and Hibernate treats a save with an id as an
 * update — so a POST carrying an existing id turned "create" into "silently
 * overwrite a different row in my own tenant".</p>
 *
 * <p>Also pins the other half: the id must still be present in responses.
 * READ_ONLY that accidentally became WRITE_ONLY or JsonIgnore would break every
 * client that reads an id back, and no create-path test would notice.</p>
 */
class RequestBodyIdBindingTest {

    // Matches how Spring Boot configures the mapper it actually binds request
    // bodies with: FAIL_ON_UNKNOWN_PROPERTIES is off by default there, so an
    // unknown key is ignored rather than rejected. A bare `new ObjectMapper()`
    // throws instead, which made this suite fail for a reason production would
    // never hit.
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature
                    .FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final String suppliedId = UUID.randomUUID().toString();

    @Test
    void unitIgnoresAClientSuppliedId() throws Exception {
        Unit unit = mapper.readValue(
                "{\"id\":\"%s\",\"unitNumber\":\"101\"}".formatted(suppliedId), Unit.class);

        assertThat(unit.getId()).as("a POSTed id must not survive binding").isNull();
        assertThat(unit.getUnitNumber()).as("real fields still bind").isEqualTo("101");
    }

    @Test
    void buildingIgnoresAClientSuppliedId() throws Exception {
        Building b = mapper.readValue("{\"id\":\"%s\"}".formatted(suppliedId), Building.class);
        assertThat(b.getId()).isNull();
    }

    @Test
    void accountIgnoresAClientSuppliedId() throws Exception {
        Account a = mapper.readValue(
                "{\"id\":\"%s\",\"name\":\"Rent\"}".formatted(suppliedId), Account.class);

        assertThat(a.getId()).isNull();
        assertThat(a.getName()).isEqualTo("Rent");
    }

    @Test
    void bankAccountIgnoresAClientSuppliedId() throws Exception {
        BankAccount b = mapper.readValue("{\"id\":\"%s\"}".formatted(suppliedId), BankAccount.class);
        assertThat(b.getId()).isNull();
    }

    @Test
    void staffIgnoresAClientSuppliedId() throws Exception {
        Staff s = mapper.readValue("{\"id\":\"%s\"}".formatted(suppliedId), Staff.class);
        assertThat(s.getId()).isNull();
    }

    @Test
    void vendorIgnoresAClientSuppliedId() throws Exception {
        Vendor v = mapper.readValue("{\"id\":\"%s\"}".formatted(suppliedId), Vendor.class);
        assertThat(v.getId()).isNull();
    }

    /**
     * isSystem gates "system accounts cannot be modified" and "cannot be
     * deleted". A client that could set it on create would own a row nothing in
     * the API can subsequently touch.
     */
    @Test
    void accountIgnoresAClientSuppliedSystemFlag() throws Exception {
        // Both spellings: "system" is the wire name the accessors produce,
        // "isSystem" is the field's own implicit name. They are separate
        // Jackson properties, and blocking only one leaves the other writable.
        assertThat(mapper.readValue("{\"name\":\"Mine\",\"system\":true}", Account.class).isSystem())
                .as("the accessor-named property must not bind")
                .isFalse();
        assertThat(mapper.readValue("{\"name\":\"Mine\",\"isSystem\":true}", Account.class).isSystem())
                .as("the field-named property must not bind either")
                .isFalse();
    }

    @Test
    void theSystemFlagIsStillSerializedForClients() throws Exception {
        // The web accounts page reads account.system to decide whether to offer
        // edit/delete. Blocking the binding must not blank the response field.
        Account a = new Account();
        a.setSystem(true);

        assertThat(mapper.writeValueAsString(a)).contains("\"system\":true");
    }

    @Test
    void idsAreStillSerializedInResponses() throws Exception {
        // The point of READ_ONLY rather than @JsonIgnore: clients read ids back.
        Unit unit = new Unit();
        UUID id = UUID.randomUUID();
        unit.setId(id);

        assertThat(mapper.writeValueAsString(unit)).contains(id.toString());
    }
}

package com.datagami.rentaxis.domain.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BankAccountController serializes the raw entity, so its Jackson property
 * names ARE the API contract. Lombok's {@code boolean isDefault} would
 * otherwise surface as {@code default} — a name neither the web dashboard nor
 * the manager app uses. These tests pin the explicit {@code isDefault} name in
 * both directions.
 */
class BankAccountJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void serializesDefaultFlagAsIsDefault() {
        BankAccount account = new BankAccount();
        account.setBankName("Emirates NBD");
        account.setBranchName("Deira");
        account.setDefault(true);

        JsonNode json = mapper.valueToTree(account);

        assertThat(json.path("isDefault").asBoolean()).isTrue();
        assertThat(json.has("default")).isFalse();
        // Frontends read branchName (not branch) and the active flag as-is.
        assertThat(json.path("branchName").asText()).isEqualTo("Deira");
        assertThat(json.has("active")).isTrue();
    }

    @Test
    void deserializesIsDefaultKeyFromClients() throws Exception {
        BankAccount account = mapper.readValue(
                "{\"bankName\":\"Emirates NBD\",\"isDefault\":true}",
                BankAccount.class);

        assertThat(account.isDefault()).isTrue();
        assertThat(account.getBankName()).isEqualTo("Emirates NBD");
    }
}

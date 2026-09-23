package com.datagami.rentaxis.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

/**
 * Request body for creating or updating a bank account.
 *
 * <p>The endpoints used to bind the {@code BankAccount} entity itself. Its
 * {@code coaAccount} arrived as {@code {id}}, and because {@code Account.id} is
 * READ_ONLY to Jackson, the stub came through with a null id: {@code save()} then
 * failed at flush on a transient reference and every create with a picked ledger
 * account was a 500 (gap #67). {@code property.id} was writable, so a PUT could
 * link another tenant's property. This record carries ids only; the service
 * resolves each one to a managed entity in the caller's tenant.</p>
 *
 * <p>The web form's shape ({@code property: {id}}, {@code coaAccount: {id}},
 * {@code isDefault}) is accepted unchanged. Anything else a client sends,
 * including the read-only fields of a GET it echoes back, is ignored.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BankAccountRequest(
        String bankName,
        String accountNumber,
        String iban,
        String branchName,
        String currency,
        Ref property,
        Ref coaAccount,
        @JsonProperty("isDefault") Boolean isDefault,
        Boolean active) {

    /** A reference to an existing row by id. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(UUID id) {}
}

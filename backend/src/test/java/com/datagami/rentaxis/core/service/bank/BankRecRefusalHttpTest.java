package com.datagami.rentaxis.core.service.bank;

import com.datagami.rentaxis.api.BankReconciliationController;
import com.datagami.rentaxis.api.GlobalExceptionHandler;
import com.datagami.rentaxis.api.dto.bank.BankRecDTOs;
import com.datagami.rentaxis.core.tenant.TenantContextHolder;
import com.datagami.rentaxis.domain.entity.BankStatementProfile;
import com.datagami.rentaxis.domain.repository.BankAccountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * F14-09: a bank-rec refusal reaches the client with its key and values beside
 * the unchanged English message, so /ar can say it in Arabic.
 */
class BankRecRefusalHttpTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final BankStatementImportService imports = mock(BankStatementImportService.class);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new BankReconciliationController(mock(BankAccountLedgerService.class), imports,
                    mock(BankMatchService.class), mock(BankLineActionService.class), mock(BankAccountRepository.class),
                    mock(NamedParameterJdbcTemplate.class), MAPPER))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @AfterEach
    void clear() {
        TenantContextHolder.clear();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return MAPPER.readValue(r.andReturn().getResponse().getContentAsString(), Map.class);
    }

    @Test
    void aServiceRefusalCarriesItsCodeThroughTheJson() throws Exception {
        TenantContextHolder.setTenantId(UUID.randomUUID());
        when(imports.saveProfile(any(), any())).thenAnswer(inv -> {
            BankStatementImportService.apply(new BankStatementProfile(), inv.getArgument(1));
            return null;
        });
        BankRecDTOs.Profile noDateFormat = new BankRecDTOs.Profile("CSV", null, 1, 2, ",", null,
                Map.of("txnDate", "Date"), "SIGNED", null, null, ".");
        var r = mvc.perform(put("/api/v1/finance/bank-reconciliation/bank-accounts/" + UUID.randomUUID() + "/profile")
                .contentType(MediaType.APPLICATION_JSON).content(MAPPER.writeValueAsString(noDateFormat)));
        assertThat(r.andReturn().getResponse().getStatus()).isEqualTo(400);
        Map<String, Object> b = body(r);
        assertThat(b).containsEntry("message", "Choose the statement's date format")
                .containsEntry("code", "bankrec.chooseDateFormat").containsEntry("args", Map.of());
    }

    @Test
    void aRefusalWithValuesSendsThemPreformatted() throws Exception {
        var r = mvc.perform(get("/api/v1/finance/bank-reconciliation/bank-accounts/" + UUID.randomUUID() + "/profile"));
        Map<String, Object> b = body(r);
        assertThat(b).containsEntry("code", "bankrec.selectOrganisation")
                .containsEntry("message", "Select an organisation first");
        assertThat(BankRecRefusal.refuse("notBalanced", "x", "statement", StatementValues.money(new java.math.BigDecimal("300")))
                .getArgs()).containsEntry("statement", "300.00");
    }
}

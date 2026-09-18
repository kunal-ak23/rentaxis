package com.datagami.rentaxis.api;

import com.datagami.rentaxis.core.service.AccountImportService;
import com.datagami.rentaxis.core.service.AccountService;
import com.datagami.rentaxis.core.service.ledger.PropertyAccountService;
import com.datagami.rentaxis.domain.entity.Account;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A v1 client body must not be accepted as if it had been understood.
 *
 * <p>{@code accounts.parent_code} is gone and the tree is bound as
 * {@code parentId}. The web accounts page still posts {@code parentCode}, and
 * Jackson dropped that field without a word: the POST returned 200 and the
 * account was created at the root of the chart instead of under A-02. A
 * silently misfiled account is worse than a rejected request — nobody goes
 * looking for it, and the chart is wrong from then on.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = false)} does not fix it, and
 * this test is where that was established: with the annotation in place the
 * {@code updateWithLegacyParentCode} case below still answered 200. Jackson
 * needs {@code FAIL_ON_UNKNOWN_PROPERTIES} enabled as well, and Spring's
 * object-mapper builder turns it off globally. The request records collect the
 * leftovers through {@code @JsonAnySetter} and the controller refuses them.
 *
 * <p>Standalone MockMvc with the real {@link GlobalExceptionHandler} so this
 * pins the status and body the caller actually sees, not just that the
 * controller throws. The mapper here is the same
 * {@code Jackson2ObjectMapperBuilder} default the Boot app runs with, which is
 * the whole reason the negative result above is trustworthy.
 */
class AccountRequestBindingTest {

    private final AccountService service = mock(AccountService.class);

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new AccountController(service, mock(AccountImportService.class),
                    mock(PropertyAccountService.class),
                    mock(com.datagami.rentaxis.core.service.lease.ChargeTypeService.class)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void createWithLegacyParentCodeIsRejected() throws Exception {
        mvc.perform(post("/api/v1/finance/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentCode\":\"A-02\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unrecognised field(s): parentCode"));

        verify(service, never()).createAccount(any(), any());
    }

    @Test
    void updateWithLegacyParentCodeIsRejected() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(put("/api/v1/finance/accounts/" + id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"parentCode\":\"A-02\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unrecognised field(s): parentCode"));

        verify(service, never()).updateAccount(any(), any());
    }

    @Test
    void createWithOnlyKnownFieldsStillWorks() throws Exception {
        when(service.createAccount(any(), any())).thenReturn(new Account());

        mvc.perform(post("/api/v1/finance/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Landscaping\",\"accountType\":\"EXPENSE\",\"parentId\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk());
    }
}

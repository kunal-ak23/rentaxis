package com.datagami.rentaxis.core.service;

import com.datagami.rentaxis.domain.entity.Account;
import com.datagami.rentaxis.domain.repository.AccountRepository;
import com.datagami.rentaxis.domain.repository.PropertyRepository;
import com.datagami.rentaxis.domain.repository.TenantFiscalSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every account code a posting path falls back to must be a code the seeder
 * actually creates.
 *
 * <p>{@code clearPayment} and {@code clearPaymentOnline} fell back to
 * {@code A-01-01}, which {@code seedDefaultAccounts()} has never created. Any
 * tenant with accounts but no {@code RENT_PAYMENT_CLEARED} mapping — which is
 * every tenant onboarded through {@code POST /finance/accounts/import}, since
 * {@code AccountMappingService.seedDefaults()} runs only inside
 * {@code seedDefaultAccounts()} and that early-returns once accounts exist —
 * therefore got a 500 on every cheque clear, the transaction rolled back, and
 * rent could never be recorded as collected.
 *
 * <p>Three integration tests hand-seeded {@code A-01-01}, so the suite was
 * green while production was broken. This test reads the fallback codes out of
 * the services themselves and checks them against what the seeder produces, so
 * a new fallback pointing at a non-existent code fails here rather than in
 * production.
 */
class ChartOfAccountsFallbackCodesTest {

    private AccountService service;

    @BeforeEach
    void setUp() {
        AccountRepository repository = mock(AccountRepository.class);
        when(repository.findAll()).thenReturn(List.of());
        // The seeder saves the tree as a map's values (parents before children), not a List.
        when(repository.saveAll(anyIterable()))
                .thenAnswer(inv -> new java.util.ArrayList<Account>(inv.getArgument(0)));
        service = new AccountService(repository, mock(TenantFiscalSettingsRepository.class),
                mock(PropertyRepository.class));
    }

    private Set<String> seededCodes() {
        return service.seedDefaultAccounts().stream()
                .map(Account::getCode)
                .collect(Collectors.toSet());
    }

    /** Pulls findByCodeAndTenantId("X-..") literals out of a service source file. */
    private Set<String> fallbackCodesIn(String relativePath) throws IOException {
        String src = Files.readString(Path.of("src/main/java/com/datagami/rentaxis/core/service/" + relativePath));
        Matcher m = Pattern.compile("findByCodeAndTenantId\\(\"([A-Z]-[0-9-]+)\"").matcher(src);
        Set<String> codes = new java.util.HashSet<>();
        while (m.find()) {
            codes.add(m.group(1));
        }
        return codes;
    }

    @Test
    void seederProducesTheBankAndRentalIncomeAccountsThePostingPathsNeed() {
        Set<String> seeded = seededCodes();

        // The two codes every cheque-clear and cheque-bounce leg resolves to.
        assertThat(seeded).contains("A-02-02", "C-01-01");
        // The code that was being looked up and never existed.
        assertThat(seeded).doesNotContain("A-01-01");
    }

    @Test
    void everyFallbackCodeInPaymentScheduleServiceIsActuallySeeded() throws IOException {
        assertThat(fallbackCodesIn("PaymentScheduleService.java"))
                .isNotEmpty()
                .allSatisfy(code -> assertThat(seededCodes()).contains(code));
    }

    @Test
    void everyFallbackCodeInOnlinePaymentServiceIsActuallySeeded() throws IOException {
        assertThat(fallbackCodesIn("OnlinePaymentService.java"))
                .isNotEmpty()
                .allSatisfy(code -> assertThat(seededCodes()).contains(code));
    }

    @Test
    void everyFallbackCodeInFinancialTransactionServiceIsActuallySeeded() throws IOException {
        assertThat(fallbackCodesIn("FinancialTransactionService.java"))
                .isNotEmpty()
                .allSatisfy(code -> assertThat(seededCodes()).contains(code));
    }
}

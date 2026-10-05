package com.datagami.rentaxis.core.service.cheque;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tutorial 20: every place that decides "is this cheque due / overdue" also asks the
 * ledger whether a returned cheque's debt is still open (the shared due rule:
 * {@code ChequeRepository.OPEN_DUE_CTE}, {@link BouncedDebt}, {@code ChequeDTO.ledgerSettled}).
 * #397 fixed the register and missed the owner statement, which applied
 * {@link ChequeDueRules} alone and told an owner a settled, written-off cheque was overdue.
 *
 * <p>A production class that calls the bare rule (or reads the raw due candidates) must
 * also consult one of the ledger-aware helpers. If a new caller genuinely does not need
 * it, say why in {@link #EXEMPT}.</p>
 */
class OverdueRuleCallersTest {

    /** Calls that answer "due / overdue" from the register row alone. */
    private static final Pattern BARE_RULE = Pattern.compile(
            "ChequeDueRules\\.(due|overdue|daysOverdue|tenantOwes|tenantOverdue)\\("
                    + "|findOwedCandidatesAt\\(|findAllDue\\(|findDueForLease\\(");

    /** Evidence the class prices a bounce by what the ledger still carries. */
    private static final Pattern LEDGER_AWARE = Pattern.compile(
            "BouncedDebt|bouncedDebt|ledgerSettled|bouncedOpenAmounts|OPEN_DUE_CTE");

    private static final List<String> EXEMPT = List.of(
            // The rule itself.
            "ChequeDueRules.java");

    @Test
    void everyDueRuleCallerAsksTheLedgerAboutBounces() throws IOException {
        Path main = Path.of("src/main/java");
        assertThat(main).isDirectory();
        try (Stream<Path> files = Files.walk(main)) {
            List<String> callers = files
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !EXEMPT.contains(f.getFileName().toString()))
                    .filter(f -> BARE_RULE.matcher(read(f)).find())
                    .map(f -> main.relativize(f).toString())
                    .toList();
            assertThat(callers).as("the guard still sees the callers it was written for")
                    .anySatisfy(f -> assertThat(f).endsWith("StandardStatementSections.java"))
                    .anySatisfy(f -> assertThat(f).endsWith("LeaseAssignmentService.java"));
            List<String> offenders = callers.stream()
                    .filter(f -> !LEDGER_AWARE.matcher(read(main.resolve(f))).find())
                    .toList();
            assertThat(offenders)
                    .as("price bounced rows through BouncedDebt / ChequeQueryService.bouncedOpenAmounts / OPEN_DUE_CTE")
                    .isEmpty();
        }
    }

    private static String read(Path f) {
        try {
            return Files.readString(f);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}

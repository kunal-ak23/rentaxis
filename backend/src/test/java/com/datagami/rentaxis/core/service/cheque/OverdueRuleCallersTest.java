package com.datagami.rentaxis.core.service.cheque;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * <p>Checked <b>per call site</b> (PR #399 R1 P3-2): with comments stripped, every call to
 * the bare rule — qualified, or unqualified under a static import — or to the raw due
 * candidates must have ledger-aware code within {@link #WINDOW} lines of it. A mention in a
 * comment is not evidence.</p>
 *
 * <p>Limits: this is a tripwire, not a proof. It cannot see a hand-rolled
 * {@code status == BOUNCED} sum, and "within N lines" stands in for "in the same method".
 * If a new caller genuinely does not need the ledger, say why in {@link #EXEMPT}.</p>
 */
class OverdueRuleCallersTest {

    static final String RULES = "due|overdue|daysOverdue|tenantOwes|tenantOverdue";

    /** Calls that answer "due / overdue" from the register row alone. */
    static final Pattern QUALIFIED = Pattern.compile(
            "ChequeDueRules\\s*\\.\\s*(" + RULES + ")\\s*\\(|\\b(findOwedCandidatesAt|findAllDue|findDueForLease)\\s*\\(");

    /** An unqualified call, which only counts in a file that statically imports the rule. */
    static final Pattern UNQUALIFIED = Pattern.compile("(?<![.\\w])(" + RULES + ")\\s*\\(");

    static final Pattern STATIC_IMPORT = Pattern.compile("import\\s+static\\s+[\\w.]*ChequeDueRules\\s*\\.");

    /** Evidence the call site prices a bounce by what the ledger still carries. */
    static final Pattern LEDGER_AWARE = Pattern.compile(
            "BouncedDebt|bouncedDebt|ledgerSettled|bouncedOpen|openAmountsAt|OPEN_DUE_CTE"
                    // a method handed the ledger's open amount by its caller (ChequeQueryService.Accumulator.add)
                    + "|BigDecimal\\s+open\\b");

    static final int WINDOW = 60;

    private static final List<String> EXEMPT = List.of(
            // The rule itself.
            "ChequeDueRules.java",
            // Declares the raw due queries (and OPEN_DUE_CTE); the callers are what is checked.
            "ChequeRepository.java");

    @Test
    void everyDueRuleCallSiteAsksTheLedgerAboutBounces() throws IOException {
        Path main = Path.of("src/main/java");
        assertThat(main).isDirectory();
        List<String> sites = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (EXEMPT.contains(f.getFileName().toString())) continue;
                String file = main.relativize(f).toString();
                for (int line : offenders(stripComments(Files.readString(f)), sites, file)) {
                    offenders.add(file + ":" + line);
                }
            }
        }
        assertThat(sites).as("the guard still sees the callers it was written for")
                .anySatisfy(s -> assertThat(s).contains("StandardStatementSections.java"))
                .anySatisfy(s -> assertThat(s).contains("LeaseAssignmentService.java"));
        assertThat(offenders)
                .as("price bounced rows through BouncedDebt / ChequeQueryService.bouncedOpenAmounts / OPEN_DUE_CTE")
                .isEmpty();
    }

    @Test
    void theScannerIgnoresCommentsAndChecksEachCallSite() {
        String commentOnly = """
                class A {
                    // BouncedDebt would be consulted here
                    boolean late(Cheque c) { return ChequeDueRules.overdue(c, 0, today); }
                }
                """;
        assertThat(offenders(stripComments(commentOnly), new ArrayList<>(), "A")).containsExactly(3);

        String aware = """
                class B {
                    boolean late(Cheque c) {
                        BigDecimal open = bouncedDebt.openAmounts(List.of(c)).get(c.getId());
                        return ChequeDueRules.overdue(c, 0, today) && open.signum() > 0;
                    }
                }
                """;
        assertThat(offenders(stripComments(aware), new ArrayList<>(), "B")).isEmpty();

        StringBuilder far = new StringBuilder("class C {\n    void a() { bouncedDebt.openAmounts(rows); }\n");
        for (int i = 0; i < WINDOW + 5; i++) far.append("    int x").append(i).append(";\n");
        far.append("    boolean late(Cheque c) { return ChequeDueRules.due(c, today); }\n}\n");
        assertThat(offenders(stripComments(far.toString()), new ArrayList<>(), "C")).hasSize(1);

        String staticImport = """
                import static com.datagami.rentaxis.core.service.cheque.ChequeDueRules.overdue;
                class D {
                    boolean late(Cheque c) { return overdue(c, 0, today); }
                }
                """;
        assertThat(offenders(stripComments(staticImport), new ArrayList<>(), "D")).containsExactly(3);

        String inAString = """
                class E {
                    String s = "// not a comment";
                    boolean late(Cheque c) { return ChequeDueRules.due(c, today); }
                }
                """;
        assertThat(offenders(stripComments(inAString), new ArrayList<>(), "E")).containsExactly(3);
    }

    /** 1-based line numbers of call sites with no ledger-aware code within the window. */
    static List<Integer> offenders(String src, List<String> sites, String file) {
        String[] lines = src.split("\n", -1);
        boolean staticImport = STATIC_IMPORT.matcher(src).find();
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.trim().startsWith("import ")) continue;
            boolean call = QUALIFIED.matcher(l).find() || staticImport && UNQUALIFIED.matcher(l).find();
            if (!call) continue;
            sites.add(file + ":" + (i + 1));
            int from = Math.max(0, i - WINDOW), to = Math.min(lines.length, i + WINDOW + 1);
            boolean aware = false;
            for (int k = from; k < to && !aware; k++) aware = LEDGER_AWARE.matcher(lines[k]).find();
            if (!aware) out.add(i + 1);
        }
        return out;
    }

    /** Java source with comments blanked out and line breaks kept (string literals are left alone). */
    static String stripComments(String src) {
        StringBuilder b = new StringBuilder(src.length());
        int n = src.length();
        int i = 0;
        while (i < n) {
            char c = src.charAt(i);
            if (c == '"') {
                int end;
                if (src.startsWith("\"\"\"", i)) {
                    end = src.indexOf("\"\"\"", i + 3);
                    end = end < 0 ? n : end + 3;
                } else {
                    end = endOfString(src, i);
                }
                b.append(src, i, end);
                i = end;
            } else if (c == '\'' && i + 2 < n) {
                int end = src.indexOf('\'', i + (src.charAt(i + 1) == '\\' ? 3 : 2));
                end = end < 0 ? n : end + 1;
                b.append(src, i, end);
                i = end;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                int end = src.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
                for (int k = i; k < end; k++) if (src.charAt(k) == '\n') b.append('\n');
                i = end;
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString();
    }

    private static int endOfString(String src, int start) {
        int i = start + 1;
        while (i < src.length()) {
            char c = src.charAt(i);
            if (c == '\\') { i += 2; continue; }
            if (c == '"' || c == '\n') return i + 1;
            i++;
        }
        return src.length();
    }
}

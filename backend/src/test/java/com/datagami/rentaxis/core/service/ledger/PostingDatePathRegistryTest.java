package com.datagami.rentaxis.core.service.ledger;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Break-it round 3 (money3): round 2 put the date rules on the actions it named and
 * missed the rest (cash receive, cheque cancel, settlement). This guard makes the
 * classification a precondition of posting: every method in the main sources that
 * calls {@code PostingService.post} or {@code PostingService.reverse} must be named by
 * some {@link PostingDatePath#sites()}, and every EVENT / PLANNED path must be asked
 * for somewhere ({@code PostingDatePath.X} referenced outside the enum).
 *
 * <p>Source scan rather than bytecode: the project has no ArchUnit, and a call site
 * is exactly what a reviewer reads. The scan finds the fields typed
 * {@code PostingService} in each file and the calls through them; the enclosing
 * method is the nearest member declaration above (4-space indent) — the codebase's
 * format. The scan itself is checked below against known sites.</p>
 */
class PostingDatePathRegistryTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern FIELD =
            Pattern.compile("(?<![A-Za-z])(?:[\\w.]*\\.)?PostingService\\s+(\\w+)\\s*[;,)=]");
    private static final Pattern DECL = Pattern.compile(
            "^    (?=\\S)(?:@\\w+(?:\\([^)]*\\))?\\s+)*(?:(?:public|protected|private|static|final|synchronized)\\s+)*"
                    + "(?:<[^>]+>\\s+)?[\\w.<>\\[\\], ?]+\\s+(\\w+)\\s*\\(");
    private static final Set<String> KEYWORDS = Set.of("return", "new", "throw", "if", "else", "for", "while",
            "switch", "catch", "case", "yield", "do", "try");

    /** {@code SimpleClass#method} of every PostingService.post/reverse call in the main sources. */
    static Set<String> postingCallSites() throws IOException {
        Set<String> out = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString().replace(".java", "");
                if (name.equals("PostingService")) continue;
                List<String> lines = Files.readAllLines(file);
                Set<String> fields = new HashSet<>();
                for (String l : lines) {
                    Matcher m = FIELD.matcher(l);
                    while (m.find()) fields.add(m.group(1));
                }
                if (fields.isEmpty()) continue;
                Pattern call = Pattern.compile("\\b(?:this\\.)?(?:"
                        + fields.stream().map(Pattern::quote).collect(Collectors.joining("|")) + ")\\.(post|reverse)\\(");
                for (int i = 0; i < lines.size(); i++) {
                    if (!call.matcher(lines.get(i)).find()) continue;
                    out.add(name + "#" + enclosingMethod(lines, i));
                }
            }
        }
        return out;
    }

    private static String enclosingMethod(List<String> lines, int from) {
        for (int j = from; j >= 0; j--) {
            Matcher d = DECL.matcher(lines.get(j));
            if (!d.find()) continue;
            String head = lines.get(j).split("\\(", 2)[0];
            if (head.contains("=") || KEYWORDS.contains(head.trim().split("\\s+")[0])) continue;
            return d.group(1);
        }
        return "?";
    }

    private static Set<String> registered() {
        Set<String> out = new LinkedHashSet<>();
        for (PostingDatePath p : PostingDatePath.values()) out.addAll(p.sites());
        return out;
    }

    @Test
    void theScanFindsKnownPostingSites() throws IOException {
        // Mutation check for the scanner itself: if it stopped seeing calls, the guard
        // below would pass vacuously.
        assertThat(postingCallSites()).contains(
                "ChequeService#applyClearing", "ChequeService#reversePdr", "SettlementService#postSettlement",
                "JournalService#postManual", "YearEndCloseService#reopen", "LeaseChequeRegistrar#post");
        assertThat(postingCallSites()).hasSizeGreaterThanOrEqualTo(40);
    }

    @Test
    void everyPostingCallSiteIsClassified() throws IOException {
        Set<String> unclassified = new TreeSet<>(postingCallSites());
        unclassified.removeAll(registered());
        assertThat(unclassified)
                .as("PostingService is called from a method no PostingDatePath names. Classify the date it posts on"
                        + " (EVENT: not after today; PLANNED: within a year; SCHEDULE: system-derived) and add the"
                        + " Class#method to that path's sites, asking ManualPostingDates.require(path, date) where the"
                        + " date comes in.")
                .isEmpty();
    }

    @Test
    void noPathNamesASiteThatNoLongerPosts() throws IOException {
        Set<String> stale = new TreeSet<>(registered());
        stale.removeAll(postingCallSites());
        assertThat(stale).as("sites listed in PostingDatePath that no longer call PostingService").isEmpty();
    }

    @Test
    void everyEventAndPlannedPathIsAskedFor() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(f -> f.toString().endsWith(".java")
                    && !f.getFileName().toString().equals("PostingDatePath.java")).toList()) {
                all.append(Files.readString(f)).append('\n');
            }
        }
        List<String> neverAsked = new ArrayList<>();
        for (PostingDatePath p : PostingDatePath.values()) {
            if (p.dateClass() == PostingDatePath.DateClass.SCHEDULE) continue;
            if (!Pattern.compile("PostingDatePath\\." + p.name() + "\\b").matcher(all).find()) neverAsked.add(p.name());
        }
        assertThat(neverAsked).as("EVENT/PLANNED paths no service checks").isEmpty();
    }
}

package com.datagami.rentaxis.core.service;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.MalformedInputException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CutOverTemplatePrivacyTest} guards the one workbook every landlord
 * downloads. This guards the repository itself, which is public.
 *
 * <p>Issue #304: the client's buildings, its company and two of its renters were
 * spread across test fixtures, four production comments, the demo seed script and
 * the accounting-v2 documents. They were replaced with synthetic names of the same
 * shape. Nothing in the product depends on any of those strings, which is exactly
 * why they came back twice: a fixture wants a realistic-looking name and the
 * nearest one to hand is in the export on the author's desk. This test is the
 * thing that says no.</p>
 *
 * <p>It reads {@link CutOverTemplatePrivacyTest#NAMES} — the same list, minus the
 * two banks, which are public institutions and allowed in ordinary fixtures. Add a
 * name there and both checks pick it up.</p>
 */
class RepositoryPrivacyTest {

    /** Where real names would plausibly be written. Everything else is generated or vendored. */
    private static final List<String> ROOTS = List.of(
            "backend/src", "web/src", "web/e2e", "web/e2e-prod", "web/walkthrough",
            "mobile/apps", "mobile/packages", "scripts", "docs", "tutorials/qa");

    /** Directories that are never hand-written source. */
    private static final Set<String> SKIP_DIRS = Set.of(
            ".git", ".superpowers", "node_modules", "build", "dist", "target", ".dart_tool",
            "graphify-out", "test-results", "playwright-report", ".next", "out", "output",
            "coverage", "Pods", ".gradle");

    /** Text we can read. Anything else (png, mp4, xlsx, pdf) is skipped. */
    private static final Set<String> TEXT_SUFFIXES = Set.of(
            ".java", ".kt", ".yaml", ".yml", ".sql", ".properties", ".xml",
            ".ts", ".tsx", ".js", ".jsx", ".mjs", ".dart", ".py", ".sh",
            ".md", ".txt", ".csv", ".json", ".html", ".css", ".srt");

    /** Files that carry the denylist itself, or a deliberate record of the scrub. */
    private static final Set<String> ALLOWED = Set.of(
            "backend/src/test/java/com/datagami/rentaxis/core/service/CutOverTemplatePrivacyTest.java",
            "backend/src/test/java/com/datagami/rentaxis/core/service/RepositoryPrivacyTest.java");

    @Test
    void noRealClientNameIsWrittenAnywhereInTheRepository() throws Exception {
        Path repo = repoRoot();
        Assumptions.assumeTrue(repo != null, "not running from a git checkout");

        // Only tracked files: an ignored local artifact (a seed run's output, a
        // downloaded export) is not what the public repository publishes.
        List<String> tracked = trackedFiles(repo);
        Assumptions.assumeFalse(tracked.isEmpty(), "git ls-files returned nothing");
        assertThat(tracked).describedAs("sanity: the scan sees the repository").hasSizeGreaterThan(100);

        List<String> hits = new ArrayList<>();
        for (String rel : tracked) {
            if (!underAScannedRoot(rel) || !isScannable(rel)) continue;
            Path file = repo.resolve(rel);
            if (Files.isRegularFile(file)) hits.addAll(offendingLines(rel, file));
        }

        assertThat(hits)
                .describedAs("real client names found in repository sources (issue #304)")
                .isEmpty();
    }

    private static List<String> trackedFiles(Path repo) throws Exception {
        Process p = new ProcessBuilder("git", "ls-files", "-z")
                .directory(repo.toFile()).redirectErrorStream(false).start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        p.waitFor();
        if (p.exitValue() != 0) return List.of();
        return Stream.of(out.split("\0")).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean underAScannedRoot(String rel) {
        return ROOTS.stream().anyMatch(r -> rel.equals(r) || rel.startsWith(r + "/"));
    }

    /**
     * Proof the check can fail. Every offending line is assembled from the denylist
     * itself — including its upper-case form, since that is how the names were
     * actually written — so this file spells out no real name of its own.
     */
    @Test
    void theCheckWouldCatchARealNameIfOneCameBack() {
        for (String name : CutOverTemplatePrivacyTest.NAMES) {
            assertThat(denied("        renter.setNameEn(\"" + name.toUpperCase(Locale.ROOT) + " 2\");"))
                    .describedAs("denylist entry '%s' is matched case-insensitively", name)
                    .contains(name);
        }
        assertThat(denied("        p.setNameEn(\"Sample Tower\");")).isEmpty();
        assertThat(denied("        r.setNameEn(\"Sample Renter One\");")).isEmpty();
    }

    private static List<String> denied(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return CutOverTemplatePrivacyTest.NAMES.stream().filter(lower::contains).toList();
    }

    private static List<String> offendingLines(String rel, Path file) {
        if (ALLOWED.contains(rel)) return List.of();
        List<String> out = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                List<String> found = denied(lines.get(i));
                if (!found.isEmpty()) out.add(rel + ":" + (i + 1) + " " + found + " in: " + lines.get(i).strip());
            }
        } catch (MalformedInputException e) {
            return List.of(); // not UTF-8 text after all
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private static boolean isScannable(String rel) {
        for (String part : rel.split("/")) {
            if (SKIP_DIRS.contains(part)) return false;
        }
        int slash = rel.lastIndexOf('/');
        String name = slash < 0 ? rel : rel.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_SUFFIXES.contains(name.substring(dot).toLowerCase(Locale.ROOT));
    }

    /** The test runs with backend/ as its working directory; walk up to the checkout. */
    private static Path repoRoot() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null) {
            if (Files.isDirectory(p.resolve(".git")) || Files.isRegularFile(p.resolve(".git"))) return p;
            p = p.getParent();
        }
        return null;
    }
}

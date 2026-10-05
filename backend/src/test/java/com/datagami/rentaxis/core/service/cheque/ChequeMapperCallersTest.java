package com.datagami.rentaxis.core.service.cheque;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #397 R1-P2-2: every cheque response reports {@code due}, {@code ledgerSettled} and
 * {@code openAmount} from one derivation, so production code maps a cheque only through
 * {@link ChequeQueryService#dtos} — a service calling {@link ChequeMapper#toDto} directly
 * would answer "due" for a bounce the ledger has closed.
 */
class ChequeMapperCallersTest {

    private static final List<String> ALLOWED = List.of("ChequeMapper.java", "ChequeQueryService.java");

    @Test
    void onlyTheQueryServiceMapsCheques() throws IOException {
        Path main = Path.of("src/main/java");
        assertThat(main).isDirectory();
        try (Stream<Path> files = Files.walk(main)) {
            List<String> offenders = files
                    .filter(f -> f.toString().endsWith(".java"))
                    .filter(f -> !ALLOWED.contains(f.getFileName().toString()))
                    .filter(f -> {
                        try {
                            return mapsOutsideTheQueryService(f.getFileName().toString(), Files.readString(f));
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .map(f -> main.relativize(f).toString())
                    .toList();
            assertThat(offenders).as("map through ChequeQueryService.dtos instead").isEmpty();
        }
    }

    /**
     * PR #397 R1-P3-a: a static import of the mapper's {@code toDto} (then a bare
     * {@code toDto(...)}) and a hand-built {@code new ChequeDTO(} map a cheque just as
     * surely as a qualified call, so both count; the record is built only in the mapper.
     */
    private static final java.util.regex.Pattern STATIC_IMPORT = java.util.regex.Pattern.compile(
            "import\\s+static\\s+[\\w.]*\\bChequeMapper\\.(toDto|\\*)\\s*;");
    private static final java.util.regex.Pattern NEW_DTO = java.util.regex.Pattern.compile(
            "new\\s+(?:[\\w.]*\\.)?ChequeDTO\\s*\\(");

    static boolean mapsOutsideTheQueryService(String fileName, String src) {
        if (!"ChequeMapper.java".equals(fileName) && NEW_DTO.matcher(src).find()) {
            return true;
        }
        if (ALLOWED.contains(fileName)) {
            return false;
        }
        return src.contains("ChequeMapper.toDto(") || src.contains("ChequeMapper::toDto")
                || STATIC_IMPORT.matcher(src).find();
    }

    @Test
    void theGuardCatchesEveryWayOfMappingACheque() {
        assertThat(mapsOutsideTheQueryService("X.java", "return ChequeMapper.toDto(c, today);")).isTrue();
        assertThat(mapsOutsideTheQueryService("X.java", "rows.stream().map(ChequeMapper::toDto)")).isTrue();
        assertThat(mapsOutsideTheQueryService("X.java",
                "import static com.datagami.rentaxis.core.service.cheque.ChequeMapper.toDto;\n toDto(c, d);")).isTrue();
        assertThat(mapsOutsideTheQueryService("X.java",
                "import static com.datagami.rentaxis.core.service.cheque.ChequeMapper.*;")).isTrue();
        assertThat(mapsOutsideTheQueryService("X.java", "return new ChequeDTO(id, leaseId);")).isTrue();
        assertThat(mapsOutsideTheQueryService("X.java",
                "return new com.datagami.rentaxis.api.dto.cheque.ChequeDTO (id);")).isTrue();
        // The query service maps, but never hand-builds one.
        assertThat(mapsOutsideTheQueryService("ChequeQueryService.java", "ChequeMapper.toDto(c, d)")).isFalse();
        assertThat(mapsOutsideTheQueryService("ChequeQueryService.java", "new ChequeDTO(id)")).isTrue();
        assertThat(mapsOutsideTheQueryService("ChequeMapper.java", "return new ChequeDTO(id);")).isFalse();
        assertThat(mapsOutsideTheQueryService("X.java", "List<ChequeDTO> rows = queries.dtos(cheques);")).isFalse();
        assertThat(mapsOutsideTheQueryService("X.java", "new RenterChequeDTO(id)")).isFalse();
    }
}

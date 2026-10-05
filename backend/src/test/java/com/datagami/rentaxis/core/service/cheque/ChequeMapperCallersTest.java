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
                            String src = Files.readString(f);
                            return src.contains("ChequeMapper.toDto(") || src.contains("ChequeMapper::toDto");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    })
                    .map(f -> main.relativize(f).toString())
                    .toList();
            assertThat(offenders).as("map through ChequeQueryService.dtos instead").isEmpty();
        }
    }
}

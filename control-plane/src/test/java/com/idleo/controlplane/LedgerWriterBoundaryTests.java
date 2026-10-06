package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Only {@code Ledger} may write the ledger or the wallet cache (ADR 0014). Scans every main source file
 * for SQL that inserts into, updates, deletes from or truncates either table.
 */
class LedgerWriterBoundaryTests {

    private static final Pattern WRITE = Pattern.compile(
            "(insert\\s+into|update|delete\\s+from|truncate)\\s+billing\\.(ledger_entries|wallets)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Path LEDGER = Path.of("src/main/java/com/idleo/controlplane/billing/internal/Ledger.java");

    @Test
    void onlyTheLedgerWritesLedgerEntriesOrWallets() throws IOException {
        List<Path> writers;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            writers = files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> {
                        try {
                            return WRITE.matcher(Files.readString(file)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }

        assertThat(writers).containsExactly(LEDGER);
    }

    @Test
    void theScanRecognisesAWrite() {
        assertThat(WRITE.matcher("update billing.wallets set balance_paise = 0").find())
                .isTrue();
        assertThat(WRITE.matcher("INSERT INTO billing.ledger_entries (id) values (1)")
                        .find())
                .isTrue();
        assertThat(WRITE.matcher("select * from billing.wallets").find()).isFalse();
    }
}

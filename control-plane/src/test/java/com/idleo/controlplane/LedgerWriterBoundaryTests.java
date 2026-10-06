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
 * Only {@code Ledger} may write the ledger or the wallet cache (ADR 0014), and only {@code
 * CreditReservationService} may write credit holds (ADR 0015). Scans every main source file for SQL that
 * inserts into, updates, deletes from or truncates those tables.
 */
class LedgerWriterBoundaryTests {

    private static final Pattern WRITE = Pattern.compile(
            "(insert\\s+into|update|delete\\s+from|truncate)\\s+billing\\.(ledger_entries|wallets)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RESERVATION_WRITE = Pattern.compile(
            "(insert\\s+into|update|delete\\s+from|truncate)\\s+billing\\.reservations\\b", Pattern.CASE_INSENSITIVE);

    private static final Path BILLING = Path.of("src/main/java/com/idleo/controlplane/billing/internal");

    private static List<Path> writers(Pattern write) throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            return files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> {
                        try {
                            return write.matcher(Files.readString(file)).find();
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .toList();
        }
    }

    @Test
    void onlyTheLedgerWritesLedgerEntriesOrWallets() throws IOException {
        assertThat(writers(WRITE)).containsExactly(BILLING.resolve("Ledger.java"));
    }

    @Test
    void onlyCreditReservationsWritesHolds() throws IOException {
        assertThat(writers(RESERVATION_WRITE)).containsExactly(BILLING.resolve("CreditReservationService.java"));
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

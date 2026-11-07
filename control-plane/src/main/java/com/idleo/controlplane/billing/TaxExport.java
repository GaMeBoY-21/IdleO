package com.idleo.controlplane.billing;

import java.io.IOException;
import java.io.OutputStream;
import java.time.YearMonth;
import java.util.Map;

/**
 * One file for the CA (ADR 0055): the invoice register, GSTR-1's data, the TDS a quarter deducted, the month's journal.
 * Each module declares its own as beans; billing asks for any of them, as an approved export with a factor ({@code
 * tax.export}), and gathers those that belong in the month's CA pack into one zip with a manifest of their hashes.
 *
 * <p>Every file's figures tie to the ledger: {@link #totals} names what the file adds up to, and a test checks each
 * against the entries behind it.
 */
public interface TaxExport {

    /** Its name in the API and the pack: {@code invoice-register}, {@code tds-26q}. */
    String name();

    /** What it is, in a few words, for the console. CA(confirm) where its format waits on the CA. */
    String description();

    /** Its file's name for {@code month}: a quarterly file names the quarter that holds the month. */
    String fileName(YearMonth month);

    /** Its media type: {@code text/csv} or {@code application/json}. */
    String contentType();

    /** Whether the month's CA pack holds it. */
    boolean inPack();

    /** Writes the file for {@code month} (or the quarter that holds it) to {@code out}, row by row. */
    void write(YearMonth month, OutputStream out) throws IOException;

    /** What the file adds up to, in paise, by name, for the tie to the ledger and the export's audit row. */
    Map<String, Long> totals(YearMonth month);
}

package com.idleo.controlplane.sessions;

import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Where a person's data export is kept (ADR 0037): one zip in the sessions' bucket, under {@code exports/{id}/}, which
 * only the control plane writes and only a short link behind sign-in reads. The privacy module makes and deletes them.
 */
public interface PersonalArchives {

    /** Stores the export's zip, replacing any earlier one of the same export. */
    void store(UUID exportId, Path zip);

    /** A link that downloads the zip for {@code ttl}, named {@code fileName} on the person's disk. */
    URL link(UUID exportId, String fileName, Duration ttl);

    /** Deletes the export's objects and checks none is left; how many it deleted. */
    long delete(UUID exportId);
}

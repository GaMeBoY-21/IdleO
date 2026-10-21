package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.PersonalArchives;
import java.net.URL;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class StoredArchives implements PersonalArchives {

    private static final String ZIP = "data.zip";

    private final SessionStorage storage;

    StoredArchives(SessionStorage storage) {
        this.storage = storage;
    }

    @Override
    public void store(UUID exportId, Path zip) {
        storage.put(StorageLayout.exportPrefix(exportId) + ZIP, zip, "application/zip");
    }

    @Override
    public URL link(UUID exportId, String fileName, Duration ttl) {
        return storage.downloadLink(StorageLayout.exportPrefix(exportId) + ZIP, fileName, ttl);
    }

    @Override
    public long delete(UUID exportId) {
        return storage.deletePrefix(StorageLayout.exportPrefix(exportId));
    }
}

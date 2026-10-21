package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.ErasureMarks;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class StoredErasureMarks implements ErasureMarks {

    private final SessionStorage storage;

    StoredErasureMarks(SessionStorage storage) {
        this.storage = storage;
    }

    @Override
    public void mark(UUID userId, Instant erasedAt) {
        storage.write(StorageLayout.ERASURES + userId, erasedAt.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public Map<UUID, Instant> marked() {
        var marked = new LinkedHashMap<UUID, Instant>();
        for (var object : storage.list(StorageLayout.ERASURES)) {
            var name = object.key().substring(StorageLayout.ERASURES.length());
            try {
                var at = storage.read(object.key())
                        .map(bytes -> Instant.parse(new String(bytes, StandardCharsets.UTF_8).strip()))
                        .orElse(object.lastModified());
                marked.put(UUID.fromString(name), at);
            } catch (IllegalArgumentException | java.time.DateTimeException notOurs) {
                // Not a mark: nothing else is written here, so it's skipped rather than trusted.
            }
        }
        return marked;
    }
}

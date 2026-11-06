package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.UserUploads;
import java.net.URL;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class UserUploadsService implements UserUploads {

    private final JdbcClient jdbc;
    private final UploadStore uploads;
    private final UploadFileStore files;
    private final SessionStorage storage;

    UserUploadsService(JdbcClient jdbc, UploadStore uploads, UploadFileStore files, SessionStorage storage) {
        this.jdbc = jdbc;
        this.uploads = uploads;
        this.files = files;
        this.storage = storage;
    }

    @Override
    public Optional<URL> link(UUID user, UUID upload, Duration ttl) {
        return uploads.find(upload)
                .filter(found -> found.userId().equals(user) && found.status() == UploadStore.Status.READY)
                .flatMap(found -> {
                    var stored = files.files(upload);
                    if (stored.size() != 1) {
                        return Optional.empty();
                    }
                    var file = stored.getFirst();
                    var name = file.path()
                            .substring(file.path().lastIndexOf('/') + 1)
                            .replace("\"", "");
                    return Optional.of(storage.downloadLink(file.objectKey(), name, ttl));
                });
    }

    @Override
    public boolean owns(UUID user, UUID upload) {
        return jdbc.sql("select count(*) from sessions.uploads where id = :upload and user_id = :user")
                        .param("upload", upload)
                        .param("user", user)
                        .query(Long.class)
                        .single()
                > 0;
    }
}

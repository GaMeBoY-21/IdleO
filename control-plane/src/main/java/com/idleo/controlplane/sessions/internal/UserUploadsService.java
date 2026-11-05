package com.idleo.controlplane.sessions.internal;

import com.idleo.controlplane.sessions.UserUploads;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

@Component
class UserUploadsService implements UserUploads {

    private final JdbcClient jdbc;

    UserUploadsService(JdbcClient jdbc) {
        this.jdbc = jdbc;
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

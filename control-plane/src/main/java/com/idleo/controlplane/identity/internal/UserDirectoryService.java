package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.UserDirectory;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class UserDirectoryService implements UserDirectory {

    private final UserRepository users;
    private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

    UserDirectoryService(UserRepository users, org.springframework.jdbc.core.simple.JdbcClient jdbc) {
        this.jdbc = jdbc;
        this.users = users;
    }

    @Override
    public boolean exists(UUID userId) {
        return users.existsById(userId);
    }

    @Override
    public Optional<Instant> createdAt(UUID userId) {
        return users.findById(userId).map(User::createdAt);
    }

    @Override
    public Optional<String> verifiedEmail(UUID userId) {
        return users.findById(userId).filter(User::emailVerified).map(User::email);
    }

    @Override
    public Optional<String> displayName(UUID userId) {
        return users.findById(userId).map(User::displayName);
    }

    @Override
    public java.util.List<UUID> activeUsers() {
        return jdbc.sql("select id from identity.users where status = 'active' order by id")
                .query(UUID.class)
                .list();
    }
}

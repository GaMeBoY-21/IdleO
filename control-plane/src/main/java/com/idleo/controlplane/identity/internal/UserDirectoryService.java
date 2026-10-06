package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.UserDirectory;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class UserDirectoryService implements UserDirectory {

    private final UserRepository users;

    UserDirectoryService(UserRepository users) {
        this.users = users;
    }

    @Override
    public boolean exists(UUID userId) {
        return users.existsById(userId);
    }
}

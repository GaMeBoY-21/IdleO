package com.idleo.controlplane.privacy.internal;

import com.idleo.controlplane.PersonalData;
import com.idleo.controlplane.sessions.PersonalArchives;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The person's own exports are theirs too (ADR 0037): erasure deletes the zips, then the rows. */
@Component
class PrivacyPersonalData implements PersonalData {

    private final JdbcClient jdbc;
    private final PersonalArchives archives;

    PrivacyPersonalData(JdbcClient jdbc, PersonalArchives archives) {
        this.jdbc = jdbc;
        this.archives = archives;
    }

    @Override
    public String module() {
        return "privacy";
    }

    @Override
    public List<Group> summary(UUID userId) {
        return List.of();
    }

    @Override
    public List<Part> export(UUID userId) {
        return List.of();
    }

    @Override
    public void erase(UUID userId, ErasureStep step) {
        switch (step) {
            case DELETE_FILES -> {
                for (var id : jdbc.sql("select id from privacy.exports where user_id = :user")
                        .param("user", userId)
                        .query(UUID.class)
                        .list()) {
                    archives.delete(id);
                }
            }
            case DELETE_RECORDS ->
                jdbc.sql("delete from privacy.exports where user_id = :user")
                        .param("user", userId)
                        .update();
            case LOCK, REMOVE_HOSTS, DE_IDENTIFY -> {}
        }
    }
}

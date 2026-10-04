package com.idleo.controlplane.hosts.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

interface HostRepository extends CrudRepository<Host, UUID> {

    Optional<Host> findByIdAndOwnerUserId(UUID id, UUID ownerUserId);

    List<Host> findAllByOwnerUserIdOrderByCreatedAt(UUID ownerUserId);
}

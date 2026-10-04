package com.idleo.controlplane.identity.internal;

import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

interface UserRepository extends CrudRepository<User, UUID> {}

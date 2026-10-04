package com.idleo.controlplane.identity;

import java.io.Serializable;
import java.util.UUID;

/**
 * The signed-in user, as every other module sees them: our own user id, never the identity
 * provider's. Available as the authentication principal ({@code @AuthenticationPrincipal}).
 */
public record CurrentUser(UUID id) implements Serializable {}

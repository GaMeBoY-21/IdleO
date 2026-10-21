package com.idleo.controlplane.identity;

import java.util.List;
import java.util.UUID;

/**
 * The person's accounts at the sign-in provider (ADR 0037): erasing an account deletes them there too, through the
 * provider's admin API (Identity Platform's, with a service account from the environment). The development sign-in
 * server has none to delete; the call is recorded instead.
 */
public interface SignInAccounts {

    /** One account at a provider: the token's issuer and subject. */
    record ProviderAccount(String issuer, String subject) {}

    /** The person's provider accounts, while they're still linked to the account. */
    List<ProviderAccount> of(UUID userId);

    /** Deletes the account at its provider; one already gone counts as deleted. */
    void delete(ProviderAccount account);
}

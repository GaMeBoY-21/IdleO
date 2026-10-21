package com.idleo.controlplane.identity.internal;

import com.idleo.controlplane.identity.SignInAccounts;
import java.util.List;

/** What the development sign-in provider was asked to delete, for tests outside this package (ADR 0037). */
public final class ProviderAccountsProbe {

    private ProviderAccountsProbe() {}

    public static List<SignInAccounts.ProviderAccount> recorded(SignInAccounts accounts) {
        return ((ProviderAccounts) accounts).recorded();
    }
}

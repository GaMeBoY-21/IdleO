package com.idleo.controlplane.billing.internal;

import com.idleo.controlplane.Paise;
import java.util.UUID;
import org.springframework.context.ApplicationContext;

/** Free credit for tests in any package, granted the real way. */
public final class TestCredit {

    private TestCredit() {}

    public static void grant(ApplicationContext context, UUID userId, long paise) {
        context.getBean(CreditGrants.class)
                .grant(userId, Paise.of(paise), "Test credit", "test-" + UUID.randomUUID(), "test-operator");
    }
}

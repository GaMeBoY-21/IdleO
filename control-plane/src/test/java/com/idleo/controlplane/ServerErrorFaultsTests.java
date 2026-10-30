package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

/**
 * The broken release on purpose (ServerErrorFaults, ADR 0046): off unless set, and when set, that share of API calls
 * fail with the catalog's internal error. The prod profile refuses it (ProductionSettingsGuardTests).
 */
class ServerErrorFaultsTests {

    private final WebApplicationContextRunner runner =
            new WebApplicationContextRunner().withUserConfiguration(ServerErrorFaults.class);

    @Test
    void itIsOffUnlessSet() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ServerErrorFaults.class));
        runner.withPropertyValues("web.faults.server-error-percent=0")
                .run(context -> assertThat(context).doesNotHaveBean(ServerErrorFaults.class));
        runner.withPropertyValues("web.faults.server-error-percent=30")
                .run(context -> assertThat(context).hasSingleBean(ServerErrorFaults.class));
    }

    @Test
    void itFailsAboutThatShareOfCallsWithTheInternalError() throws Exception {
        var fault = new ServerErrorFaults.Fault(30, new Random(46));
        int failed = 0;
        for (int i = 0; i < 10_000; i++) {
            try {
                fault.preHandle(null, null, new Object());
            } catch (ApiProblem problem) {
                assertThat(problem.problem()).isEqualTo(Problem.INTERNAL_ERROR);
                failed++;
            }
        }
        assertThat(failed).isBetween(2_800, 3_200);
    }

    @Test
    void everyCallFailsAtOneHundred() {
        var fault = new ServerErrorFaults.Fault(100, new Random());
        assertThatThrownBy(() -> fault.preHandle(null, null, new Object())).isInstanceOf(ApiProblem.class);
    }
}

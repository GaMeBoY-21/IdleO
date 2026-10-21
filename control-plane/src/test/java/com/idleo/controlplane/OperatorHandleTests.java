package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@code OPERATOR} is a handle, never an email or a full name, because the audit log keeps it (ADR 0037). */
class OperatorHandleTests {

    @Test
    void takesAHandleAndRefusesAnEmailOrAName() {
        assertThat(OperatorCommandRunner.OPERATOR_HANDLE.matcher("nikki").matches())
                .isTrue();
        assertThat(OperatorCommandRunner.OPERATOR_HANDLE
                        .matcher("operator-start")
                        .matches())
                .isTrue();
        assertThat(OperatorCommandRunner.OPERATOR_HANDLE
                        .matcher("nikki@example.com")
                        .matches())
                .isFalse();
        assertThat(OperatorCommandRunner.OPERATOR_HANDLE.matcher("Nikki K").matches())
                .isFalse();
    }
}

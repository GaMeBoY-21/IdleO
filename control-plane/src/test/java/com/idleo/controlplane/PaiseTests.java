package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PaiseTests {

    @Test
    void arithmeticIsExact() {
        assertThat(Paise.of(1_200).minus(Paise.of(960))).isEqualTo(Paise.of(240));
        assertThat(Paise.of(80).times(15)).isEqualTo(Paise.of(1_200));
        assertThat(Paise.of(5).plus(Paise.of(-7)).isNegative()).isTrue();
    }

    @Test
    void overflowThrowsInsteadOfWrapping() {
        var max = Paise.of(Long.MAX_VALUE);
        assertThatThrownBy(() -> max.plus(Paise.of(1))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> max.times(2)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Paise.of(Long.MIN_VALUE).minus(Paise.of(1))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Paise.of(Long.MIN_VALUE).negate()).isInstanceOf(ArithmeticException.class);
    }
}

package com.idleo.controlplane.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idleo.controlplane.Paise;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A refund for a report (ADR 0028) comes out of the platform's commission first, then the hosts' shares in proportion
 * to what each kept, and never more than the renter paid or anyone kept.
 */
class SessionRefundSplitTests {

    private static final UUID HOST_A = UUID.fromString("00000000-0000-7000-8000-00000000000a");
    private static final UUID HOST_B = UUID.fromString("00000000-0000-7000-8000-00000000000b");
    private static final UUID HOST_C = UUID.fromString("00000000-0000-7000-8000-00000000000c");

    private static SessionRefunds.Paid paid(long commission, SessionRefunds.HostPart... hosts) {
        long kept = 0;
        for (var host : hosts) {
            kept += host.amount().value();
        }
        return new SessionRefunds.Paid(Paise.of(commission + kept), Paise.of(commission), List.of(hosts));
    }

    private static SessionRefunds.HostPart host(UUID id, long paise) {
        return new SessionRefunds.HostPart(id, Paise.of(paise));
    }

    @Test
    void aRefundTheCommissionCoversTakesNothingFromTheHost() {
        var split = SessionRefunds.split(paid(60, host(HOST_A, 240)), Paise.of(60));
        assertThat(split.fromCommission()).isEqualTo(Paise.of(60));
        assertThat(split.fromHosts()).isEmpty();
    }

    @Test
    void theRestComesFromTheHostOnceTheCommissionIsUsedUp() {
        var split = SessionRefunds.split(paid(60, host(HOST_A, 240)), Paise.of(100));
        assertThat(split.fromCommission()).isEqualTo(Paise.of(60));
        assertThat(split.fromHosts()).containsExactly(host(HOST_A, 40));
        assertThat(split.fromHostsTotal()).isEqualTo(Paise.of(40));

        var whole = SessionRefunds.split(paid(60, host(HOST_A, 240)), Paise.of(300));
        assertThat(whole.fromCommission()).isEqualTo(Paise.of(60));
        assertThat(whole.fromHosts()).containsExactly(host(HOST_A, 240));
    }

    @Test
    void severalHostsGiveBackInProportionAndThePaiseLeftOverGoWhereRoundingCutMost() {
        // 2 from hosts that kept 1 each: two thirds each, so 1 each to two of them, and nothing from the third.
        var even = SessionRefunds.split(paid(5, host(HOST_A, 1), host(HOST_B, 1), host(HOST_C, 1)), Paise.of(7));
        assertThat(even.fromCommission()).isEqualTo(Paise.of(5));
        assertThat(even.fromHostsTotal()).isEqualTo(Paise.of(2));
        assertThat(even.fromHosts()).containsExactly(host(HOST_A, 1), host(HOST_B, 1));

        // 100 from hosts that kept 300 and 100: 75 and 25.
        var uneven = SessionRefunds.split(paid(0, host(HOST_A, 300), host(HOST_B, 100)), Paise.of(100));
        assertThat(uneven.fromHosts()).containsExactly(host(HOST_A, 75), host(HOST_B, 25));

        // 10 from hosts that kept 2,000 and 1,000: 6.67 and 3.33, so 7 and 3.
        var rounded = SessionRefunds.split(paid(0, host(HOST_A, 2_000), host(HOST_B, 1_000)), Paise.of(10));
        assertThat(rounded.fromHosts()).containsExactly(host(HOST_A, 7), host(HOST_B, 3));
    }

    @Test
    void noOneGivesBackMoreThanTheyKept() {
        for (long amount = 1; amount <= 13; amount++) {
            var split =
                    SessionRefunds.split(paid(3, host(HOST_A, 7), host(HOST_B, 2), host(HOST_C, 1)), Paise.of(amount));
            assertThat(split.fromCommission().plus(split.fromHostsTotal())).isEqualTo(Paise.of(amount));
            assertThat(split.fromCommission().value()).isLessThanOrEqualTo(3);
            for (var part : split.fromHosts()) {
                long kept = part.hostId().equals(HOST_A) ? 7 : part.hostId().equals(HOST_B) ? 2 : 1;
                assertThat(part.amount().value()).isLessThanOrEqualTo(kept);
            }
        }
    }

    @Test
    void aRefundIsMoreThanZeroAndNeverMoreThanTheRenterPaid() {
        var paid = paid(60, host(HOST_A, 240));
        assertThatThrownBy(() -> SessionRefunds.split(paid, Paise.of(301)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionRefunds.split(paid, Paise.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SessionRefunds.split(SessionRefunds.Paid.NOTHING, Paise.of(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

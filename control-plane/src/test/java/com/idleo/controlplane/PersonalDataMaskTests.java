package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The one masking function (ADR 0051), kind by kind, and what it leaves alone. */
class PersonalDataMaskTests {

    @Test
    void masksEachKindAsTheConsoleShowsIt() {
        assertThat(PersonalDataMask.email("nikki@gmail.com")).isEqualTo("ni•••@gmail.com");
        assertThat(PersonalDataMask.email("n@gmail.com")).isEqualTo("n•••@gmail.com");
        assertThat(PersonalDataMask.upi("nikki@okhdfc")).isEqualTo("ni•••@okhdfc");
        assertThat(PersonalDataMask.phone("+91 98765 43210")).isEqualTo("•••10");
        assertThat(PersonalDataMask.pan("ABCPE9999F")).isEqualTo("•••999F");
        assertThat(PersonalDataMask.bank("123456789012")).isEqualTo("•••9012");
        assertThat(PersonalDataMask.ip("203.0.113.9")).isEqualTo("203.0.113.0/24");
        assertThat(PersonalDataMask.ip("2001:db8:85a3:8d3:1319:8a2e:370:7348")).isEqualTo("2001:db8:85a3::/48");
        assertThat(PersonalDataMask.ip("not an address")).isEqualTo("•••");
    }

    @Test
    void findsThemInFreeText() {
        assertThat(PersonalDataMask.text(
                        "Mail nikki@gmail.com or pay nikki@okhdfc, call 9876543210, PAN ABCPE9999F, from 203.0.113.9"))
                .isEqualTo("Mail ni•••@gmail.com or pay ni•••@okhdfc, call •••10, PAN •••999F, from 203.0.113.0/24");
    }

    @Test
    void leavesWhatOnlyLooksLikeThemAlone() {
        for (var text : new String[] {
            "01a1a77d-839a-7026-89a0-b3b8b84b1dae",
            "2026-11-04T16:56:21.495908Z",
            "12:34:56",
            "pay_ABCDE1234F",
            "order_9876543210",
            "GPU-6a8b0d1c-2f3e-4a5b-8c7d-9e0f1a2b3c4d",
            "ghcr.io/acme/model@sha256:b6bfe7239bfc83fb90d31612d9704d86039dd714f7904b3f1ad68f211e602372",
            "the job failed after 1234567890123 bytes",
        }) {
            assertThat(PersonalDataMask.text(text)).isEqualTo(text);
        }
    }

    @Test
    void readsAFieldByWhatItHolds() {
        assertThat(PersonalDataMask.field("address", "203.0.113.9")).isEqualTo("203.0.113.0/24");
        assertThat(PersonalDataMask.field("address", "12 MG Road, Bengaluru")).isEqualTo("•••");
        assertThat(PersonalDataMask.field("postalAddress", "12 MG Road")).isEqualTo("•••");
        assertThat(PersonalDataMask.field("vpa", "ravi.k@ybl")).isEqualTo("ra•••@ybl");
        assertThat(PersonalDataMask.field("accountNumber", "123456789012")).isEqualTo("•••9012");
        assertThat(PersonalDataMask.field("phone", "98765 43210")).isEqualTo("•••10");
        assertThat(PersonalDataMask.field("userAgent", "Chrome/120.0.0.0")).isEqualTo("Chrome/120.0.0.0");
        assertThat(PersonalDataMask.field("agentVersion", "1.2.3.4")).isEqualTo("1.2.3.4");
    }

    @Test
    void walksAResponseAndCopiesEverythingElse() {
        var json = JsonMapper.builder().build();
        var masked = PersonalDataMask.json(json.readTree("""
                {"email":"nikki@gmail.com","amountPaise":9876543210,"ok":true,"none":null,
                 "rows":[{"message":"write to ravi@example.com"},"203.0.113.9"]}
                """));
        assertThat(masked.get("email").asString()).isEqualTo("ni•••@gmail.com");
        assertThat(masked.get("amountPaise").asLong()).isEqualTo(9876543210L);
        assertThat(masked.get("ok").asBoolean()).isTrue();
        assertThat(masked.get("none").isNull()).isTrue();
        assertThat(masked.get("rows").get(0).get("message").asString()).isEqualTo("write to ra•••@example.com");
        assertThat(masked.get("rows").get(1).asString()).isEqualTo("203.0.113.0/24");
    }
}

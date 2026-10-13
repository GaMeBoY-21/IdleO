package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** RFC 6238's own test vectors, base32 as RFC 4648 writes it, and the sealing of secrets (ADR 0027). */
class TotpTests {

    /** RFC 6238 appendix B's SHA-1 secret: "12345678901234567890" in ASCII. */
    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void makesRfc6238sCodesTheirLastSixDigits() {
        long[][] vectors = {
            {59L, 287082L},
            {1111111109L, 81804L},
            {1111111111L, 50471L},
            {1234567890L, 5924L},
            {2000000000L, 279037L},
            {20000000000L, 353130L}
        };
        for (var vector : vectors) {
            var code = Totp.code(RFC_SECRET, Totp.step(Instant.ofEpochSecond(vector[0])));
            assertThat(code).isEqualTo(String.format("%06d", vector[1]));
        }
    }

    @Test
    void acceptsTheStepEitherSideOfNowOnceAndNothingFurther() {
        var now = Instant.ofEpochSecond(1111111111L);
        long step = Totp.step(now);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, Long.MIN_VALUE))
                .hasValue(step - 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 1), now, Long.MIN_VALUE))
                .hasValue(step + 1);
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, Long.MIN_VALUE))
                .isEmpty();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step + 2), now, Long.MIN_VALUE))
                .isEmpty();
        // Typed with a space, as apps show it; but never again once accepted, nor an older one.
        var code = Totp.code(RFC_SECRET, step);
        assertThat(Totp.verify(RFC_SECRET, code.substring(0, 3) + " " + code.substring(3), now, Long.MIN_VALUE))
                .hasValue(step);
        assertThat(Totp.verify(RFC_SECRET, code, now, step)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, step))
                .isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "12345", now, Long.MIN_VALUE)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, "abcdef", now, Long.MIN_VALUE)).isEmpty();
        assertThat(Totp.verify(RFC_SECRET, null, now, Long.MIN_VALUE)).isEmpty();
    }

    @Test
    void writesSecretsInBase32AndAnAuthenticatorLink() {
        assertThat(Totp.base32("f".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MY");
        assertThat(Totp.base32("foobar".getBytes(StandardCharsets.US_ASCII))).isEqualTo("MZXW6YTBOI");
        assertThat(Totp.base32(RFC_SECRET)).isEqualTo("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        assertThat(Totp.uri("IdleO", "nikki+admin@example.com", RFC_SECRET))
                .isEqualTo("otpauth://totp/IdleO:nikki%2Badmin%40example.com?secret=GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
                        + "&issuer=IdleO&algorithm=SHA1&digits=6&period=30");
    }

    @Test
    void sealsASecretSoOnlyThatAdminsRowOpensItWithTheKey() {
        var key = Base64.getEncoder().encodeToString(new byte[32]);
        var secrets = new TotpSecrets(key, new SecureRandom());
        var admin = UUID.randomUUID();
        var sealed = secrets.seal(admin, RFC_SECRET);
        assertThat(sealed).hasSize(12 + RFC_SECRET.length + 16).isNotEqualTo(secrets.seal(admin, RFC_SECRET));
        assertThat(secrets.open(admin, sealed)).isEqualTo(RFC_SECRET);
        assertThatThrownBy(() -> secrets.open(UUID.randomUUID(), sealed)).isInstanceOf(IllegalStateException.class);
        var otherKey =
                new TotpSecrets(Base64.getEncoder().encodeToString(new byte[32]).replace('A', 'B'), new SecureRandom());
        assertThatThrownBy(() -> otherKey.open(admin, sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAKeyThatIsntThirtyTwoBytes() {
        assertThatThrownBy(() -> new TotpSecrets(Base64.getEncoder().encodeToString(new byte[16]), new SecureRandom()))
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> new TotpSecrets("not base64!", new SecureRandom()))
                .hasMessageContaining("base64");
    }
}

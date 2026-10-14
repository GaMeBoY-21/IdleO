package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * Invite and recovery codes are kept as HMAC-SHA-256 under a server key (ADR 0024): a copy of the database alone can't
 * be reversed by trying every code, because the plain hash it would be compared with is never stored.
 */
class CodeHashesTests {

    private static final byte[] KEY = "tests-only-invite-code-hmac-key!".getBytes(StandardCharsets.US_ASCII);
    private static final String CONFIGURED = Base64.getEncoder().encodeToString(KEY);

    private final CodeHashes hashes = new CodeHashes(CONFIGURED);

    @Test
    void aCodeIsKeptAsItsKeyedHashNeverItsPlainHash() throws Exception {
        var stored = hashes.hash(CodeHashes.Kind.INVITE, "IDLE-7K2Q-9XMD");

        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY, "HmacSHA256"));
        mac.update("INVITE:".getBytes(StandardCharsets.US_ASCII));
        assertThat(stored).isEqualTo(mac.doFinal("IDLE7K2Q9XMD".getBytes(StandardCharsets.US_ASCII)));
        var plain = MessageDigest.getInstance("SHA-256").digest("IDLE7K2Q9XMD".getBytes(StandardCharsets.US_ASCII));
        assertThat(stored).isNotEqualTo(plain);
    }

    @Test
    void theSameCodeTypedAnyWayHasOneHash() {
        assertThat(hashes.hash(CodeHashes.Kind.INVITE, "idle 7k2q 9xmd"))
                .isEqualTo(hashes.hash(CodeHashes.Kind.INVITE, "IDLE-7K2Q-9XMD"));
    }

    @Test
    void anotherKeyOrAnotherKindOfCodeGivesAnotherHash() {
        var other = new CodeHashes(Base64.getEncoder().encodeToString(new byte[32]));
        assertThat(other.hash(CodeHashes.Kind.INVITE, "ABCDE-FGHJK"))
                .isNotEqualTo(hashes.hash(CodeHashes.Kind.INVITE, "ABCDE-FGHJK"));
        assertThat(hashes.hash(CodeHashes.Kind.RECOVERY, "ABCDE-FGHJK"))
                .isNotEqualTo(hashes.hash(CodeHashes.Kind.INVITE, "ABCDE-FGHJK"));
    }

    @Test
    void aKeyThatIsntThirtyTwoBytesIsRefused() {
        assertThatThrownBy(() -> new CodeHashes(Base64.getEncoder().encodeToString(new byte[16])))
                .hasMessageContaining("identity.invite-code-key must be 32 bytes");
        assertThatThrownBy(() -> new CodeHashes("not base64!")).hasMessageContaining("identity.invite-code-key");
    }
}

package com.idleo.controlplane.identity.internal;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * What the database keeps of a short code a person types (ADR 0024, ADR 0027): HMAC-SHA-256 under {@code
 * identity.invite-code-key}, a server secret from the environment, of the code as {@link InviteCodes#normalise}
 * writes it. An invite code has about 40 bits and a recovery code about 50, few enough that a plain hash read from a
 * copy of the database could be reversed by trying every code; without the key, the hashes say nothing. Each kind of
 * code has its own prefix, so an invite code's hash can never match a recovery code's.
 */
@Component
class CodeHashes {

    enum Kind {
        INVITE,
        RECOVERY
    }

    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    @Autowired
    CodeHashes(IdentityProperties properties) {
        this(properties.inviteCodeKey());
    }

    CodeHashes(String configured) {
        this.key = new SecretKeySpec(ConfiguredKeys.read(configured, "identity.invite-code-key"), ALGORITHM);
    }

    byte[] hash(Kind kind, String code) {
        try {
            var mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update((kind.name() + ":").getBytes(StandardCharsets.US_ASCII));
            return mac.doFinal(InviteCodes.normalise(code).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("a code couldn't be hashed with identity.invite-code-key", e);
        }
    }
}

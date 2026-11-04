package com.idleo.controlplane;

import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A software authenticator for tests (ADR 0051): what a phone or a security key does, so a registration and an assertion
 * go through Spring Security's real checks. It verifies its user unless told not to.
 */
public final class TestPasskey {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String RP_ID = "localhost";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final byte UP = 0x01;
    private static final byte UV = 0x04;
    private static final byte AT = 0x40;

    private final KeyPair keys;
    private final byte[] credentialId = random();
    private int counter;

    /** Whether it says it verified its user: a PIN or a fingerprint. */
    public boolean verifiesItsUser = true;

    public TestPasskey() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        this.keys = generator.generateKeyPair();
    }

    private static byte[] random() {
        var bytes = new byte[32];
        new java.security.SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private static String base64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** The body of {@code POST /admin/v1/passkeys}, answering the options the server gave. */
    public String register(JsonNode options, String name) throws Exception {
        var clientData = clientData("webauthn.create", options.get("challenge").asString());
        var objects = new ObjectConverter();
        var credential = new AttestedCredentialData(
                AAGUID.ZERO,
                credentialId,
                EC2COSEKey.create((ECPublicKey) keys.getPublic(), COSEAlgorithmIdentifier.ES256));
        var flags = (byte) (UP | AT | (verifiesItsUser ? UV : 0));
        var authenticatorData = new AuthenticatorData<RegistrationExtensionAuthenticatorOutput>(
                MessageDigest.getInstance("SHA-256").digest(RP_ID.getBytes(StandardCharsets.UTF_8)),
                flags,
                counter,
                credential);
        var attestation = new AttestationObject(authenticatorData, new NoneAttestationStatement());
        return JSON.writeValueAsString(java.util.Map.of(
                "challengeId", options.get("challengeId").asString(),
                "name", name,
                "credentialId", base64(credentialId),
                "clientDataJson", base64(clientData),
                "attestationObject", base64(new AttestationObjectConverter(objects).convertToBytes(attestation)),
                "transports", java.util.List.of("internal")));
    }

    /** The {@code passkey} member of a sign-in or a step-up, answering the options the server gave. */
    public java.util.Map<String, String> assertion(JsonNode options) throws Exception {
        var clientData = clientData("webauthn.get", options.get("challenge").asString());
        counter++;
        var data = new ByteArrayOutputStream();
        data.write(MessageDigest.getInstance("SHA-256").digest(RP_ID.getBytes(StandardCharsets.UTF_8)));
        data.write((byte) (UP | (verifiesItsUser ? UV : 0)));
        data.write(ByteBuffer.allocate(4).putInt(counter).array());
        var authenticatorData = data.toByteArray();
        var signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(keys.getPrivate());
        signature.update(authenticatorData);
        signature.update(MessageDigest.getInstance("SHA-256").digest(clientData));
        return java.util.Map.of(
                "challengeId", options.get("challengeId").asString(),
                "credentialId", base64(credentialId),
                "clientDataJson", base64(clientData),
                "authenticatorData", base64(authenticatorData),
                "signature", base64(signature.sign()));
    }

    /** Another credential's id, as a stranger's assertion would carry. */
    public String id() {
        return base64(credentialId);
    }

    private static byte[] clientData(String type, String challenge) {
        return JSON.writeValueAsBytes(
                java.util.Map.of("type", type, "challenge", challenge, "origin", ORIGIN, "crossOrigin", false));
    }
}

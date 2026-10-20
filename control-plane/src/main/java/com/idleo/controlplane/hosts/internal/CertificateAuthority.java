package com.idleo.controlplane.hosts.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Our online intermediate certificate authority (ADR 0010, technical design: offline root, online
 * intermediate). It issues 30-day host client certificates and nothing else: ECDSA P-256, a random
 * serial, CA:false, digitalSignature, clientAuth only, and the host's identity as the single URI
 * name {@code urn:idleo:host:<host-id>}. The identity is written by us, never taken from the CSR.
 *
 * <p>At startup it checks that the intermediate is a CA, that its key matches its certificate, and
 * that the root signed it, so a misconfigured deployment fails at once rather than at the first
 * enrolment.
 */
@Component
@EnableConfigurationProperties(CertificateAuthorityProperties.class)
class CertificateAuthority {

    static final Duration HOST_CERTIFICATE_LIFETIME = Duration.ofDays(30);
    static final String HOST_URN_PREFIX = "urn:idleo:host:";
    private static final String SIGNATURE_ALGORITHM = "SHA256withECDSA";

    private final X509CertificateHolder root;
    private final X509CertificateHolder intermediate;
    private final PrivateKey intermediateKey;
    private final SecureRandom random = new SecureRandom();

    CertificateAuthority(CertificateAuthorityProperties properties, ResourceLoader resources) {
        this.root = Pem.certificate(properties.rootCertificate(), resources);
        this.intermediate = Pem.certificate(properties.intermediateCertificate(), resources);
        this.intermediateKey = Pem.privateKey(
                properties.intermediatePrivateKey(), properties.intermediatePrivateKeyPassword(), resources);
        checkConfiguration();
    }

    record IssuedHostCertificate(X509CertificateHolder certificate, byte[] fingerprintSha256) {}

    /** Whether the key is an EC P-256 public key, the only kind we certify. */
    static boolean isP256(SubjectPublicKeyInfo key) {
        var algorithm = key.getAlgorithm();
        return X9ObjectIdentifiers.id_ecPublicKey.equals(algorithm.getAlgorithm())
                && SECObjectIdentifiers.secp256r1.equals(algorithm.getParameters());
    }

    IssuedHostCertificate issueHostCertificate(UUID hostId, SubjectPublicKeyInfo publicKey, Instant now) {
        if (!isP256(publicKey)) {
            throw new IllegalArgumentException("only EC P-256 keys are certified");
        }
        var notBefore = now.truncatedTo(ChronoUnit.SECONDS);
        var notAfter = notBefore.plus(HOST_CERTIFICATE_LIFETIME);
        try {
            var extensions = new JcaX509ExtensionUtils();
            var builder = new X509v3CertificateBuilder(
                            intermediate.getSubject(),
                            newSerial(),
                            Date.from(notBefore),
                            Date.from(notAfter),
                            new X500Name(new RDN[0]),
                            publicKey)
                    .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
                    .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature))
                    .addExtension(
                            Extension.extendedKeyUsage, false, new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth))
                    // The subject is empty, so the name extension is critical (RFC 5280, 4.2.1.6).
                    .addExtension(
                            Extension.subjectAlternativeName,
                            true,
                            new GeneralNames(
                                    new GeneralName(GeneralName.uniformResourceIdentifier, HOST_URN_PREFIX + hostId)))
                    .addExtension(
                            Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(publicKey))
                    .addExtension(
                            Extension.authorityKeyIdentifier,
                            false,
                            extensions.createAuthorityKeyIdentifier(intermediate.getSubjectPublicKeyInfo()));
            var certificate = builder.build(new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(intermediateKey));
            return new IssuedHostCertificate(certificate, sha256(certificate.getEncoded()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException | OperatorCreationException e) {
            throw new IllegalStateException("could not issue the host certificate", e);
        }
    }

    X509CertificateHolder intermediate() {
        return intermediate;
    }

    X509CertificateHolder root() {
        return root;
    }

    /** At least 64 random bits (here 127), positive and non-zero, as RFC 5280 and the CA/B rules ask. */
    private BigInteger newSerial() {
        BigInteger serial;
        do {
            serial = new BigInteger(127, random);
        } while (serial.bitLength() < 64);
        return serial;
    }

    private void checkConfiguration() {
        try {
            var constraints = BasicConstraints.fromExtensions(intermediate.getExtensions());
            if (constraints == null || !constraints.isCA()) {
                throw new IllegalStateException("the intermediate certificate is not a CA certificate");
            }
            if (!isP256(intermediate.getSubjectPublicKeyInfo())) {
                throw new IllegalStateException("the intermediate key must be EC P-256");
            }
            var verifierFromRoot = new JcaContentVerifierProviderBuilder().build(root);
            if (!intermediate.isSignatureValid(verifierFromRoot)) {
                throw new IllegalStateException("the intermediate certificate was not signed by the root");
            }
            var probe = new X509v3CertificateBuilder(
                            intermediate.getSubject(),
                            BigInteger.ONE,
                            new Date(),
                            new Date(),
                            new X500Name(new RDN[0]),
                            intermediate.getSubjectPublicKeyInfo())
                    .build(new JcaContentSignerBuilder(SIGNATURE_ALGORITHM).build(intermediateKey));
            if (!probe.isSignatureValid(new JcaContentVerifierProviderBuilder().build(intermediate))) {
                throw new IllegalStateException("the intermediate private key does not match its certificate");
            }
        } catch (GeneralSecurityException | OperatorCreationException | org.bouncycastle.cert.CertException e) {
            throw new IllegalStateException("the certificate authority is misconfigured", e);
        }
    }

    private static byte[] sha256(byte[] bytes) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(bytes);
    }
}

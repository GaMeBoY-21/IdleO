package com.idleo.controlplane;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * A throwaway PKI for tests, generated in memory and never written to disk: a root, an intermediate
 * whose key is password-encrypted (the production path), and a localhost server certificate with
 * serverAuth only. Mirrors the shape the runbook builds for production.
 */
final class TestPki {

    static final String INTERMEDIATE_KEY_PASSWORD = "test-only-intermediate-password";

    private static final TestPki INSTANCE = new TestPki();

    final X509Certificate root;
    final X509Certificate intermediate;
    final X509Certificate server;
    final String rootPem;
    final String intermediatePem;
    final String encryptedIntermediateKeyPem;
    final String serverChainPem;
    final String serverKeyPem;

    private TestPki() {
        try {
            var rootKeys = p256();
            var intermediateKeys = p256();
            var serverKeys = p256();
            var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            root = sign(
                    new JcaX509v3CertificateBuilder(
                                    new X500Name("CN=Test Root CA"),
                                    BigInteger.valueOf(1),
                                    Date.from(now.minusSeconds(60)),
                                    Date.from(now.plus(3650, ChronoUnit.DAYS)),
                                    new X500Name("CN=Test Root CA"),
                                    rootKeys.getPublic())
                            .addExtension(Extension.basicConstraints, true, new BasicConstraints(1))
                            .addExtension(
                                    Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign)),
                    rootKeys);
            intermediate = sign(
                    new JcaX509v3CertificateBuilder(
                                    root,
                                    BigInteger.valueOf(2),
                                    Date.from(now.minusSeconds(60)),
                                    Date.from(now.plus(365, ChronoUnit.DAYS)),
                                    new X500Name("CN=Test Intermediate CA"),
                                    intermediateKeys.getPublic())
                            .addExtension(Extension.basicConstraints, true, new BasicConstraints(0))
                            .addExtension(
                                    Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign)),
                    rootKeys);
            server = sign(
                    new JcaX509v3CertificateBuilder(
                                    intermediate,
                                    BigInteger.valueOf(3),
                                    Date.from(now.minusSeconds(60)),
                                    Date.from(now.plus(90, ChronoUnit.DAYS)),
                                    new X500Name(new org.bouncycastle.asn1.x500.RDN[0]),
                                    serverKeys.getPublic())
                            .addExtension(Extension.basicConstraints, true, new BasicConstraints(false))
                            .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature))
                            .addExtension(
                                    Extension.extendedKeyUsage,
                                    false,
                                    new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
                            .addExtension(
                                    Extension.subjectAlternativeName,
                                    true,
                                    new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost"))),
                    intermediateKeys);
            rootPem = pem(root);
            intermediatePem = pem(intermediate);
            serverChainPem = pem(server) + intermediatePem;
            serverKeyPem = pem(new JcaPKCS8Generator(serverKeys.getPrivate(), null));
            encryptedIntermediateKeyPem = pem(new JcaPKCS8Generator(
                    intermediateKeys.getPrivate(),
                    new JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC)
                            .setProvider(new BouncyCastleProvider())
                            .setPassword(INTERMEDIATE_KEY_PASSWORD.toCharArray())
                            .build()));
        } catch (GeneralSecurityException | OperatorCreationException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static TestPki get() {
        return INSTANCE;
    }

    static KeyPair p256() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static X509Certificate sign(X509v3CertificateBuilder builder, KeyPair signer)
            throws GeneralSecurityException, OperatorCreationException {
        X509CertificateHolder holder =
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer.getPrivate()));
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    static String pem(Object object) {
        var out = new StringWriter();
        try (var writer = new JcaPEMWriter(out)) {
            writer.writeObject(object);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toString();
    }
}

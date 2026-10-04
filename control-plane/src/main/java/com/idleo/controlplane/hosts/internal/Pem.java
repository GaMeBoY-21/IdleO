package com.idleo.controlplane.hosts.internal;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ResourceLoader;

/** Reads one PEM object from PEM content or from a resource location. */
final class Pem {

    private static final String PEM_START = "-----BEGIN ";

    private Pem() {}

    static X509CertificateHolder certificate(String contentOrLocation, ResourceLoader resources) {
        if (read(contentOrLocation, resources) instanceof X509CertificateHolder certificate) {
            return certificate;
        }
        throw new IllegalStateException("expected a PEM certificate");
    }

    static PrivateKey privateKey(String contentOrLocation, @Nullable String password, ResourceLoader resources) {
        var object = read(contentOrLocation, resources);
        var converter = new JcaPEMKeyConverter();
        try {
            return switch (object) {
                case PKCS8EncryptedPrivateKeyInfo encrypted -> {
                    if (password == null || password.isEmpty()) {
                        throw new IllegalStateException("the private key is encrypted but no password is set");
                    }
                    var decryptor = new JceOpenSSLPKCS8DecryptorProviderBuilder()
                            .setProvider(new BouncyCastleProvider())
                            .build(password.toCharArray());
                    yield converter.getPrivateKey(encrypted.decryptPrivateKeyInfo(decryptor));
                }
                case PrivateKeyInfo plain -> converter.getPrivateKey(plain);
                case PEMKeyPair pair -> converter.getPrivateKey(pair.getPrivateKeyInfo());
                default -> throw new IllegalStateException("expected a PEM private key");
            };
        } catch (IOException | OperatorCreationException | PKCSException e) {
            throw new IllegalStateException("could not read the private key", e);
        }
    }

    private static Object read(String contentOrLocation, ResourceLoader resources) {
        try (var reader = open(contentOrLocation.strip(), resources);
                var parser = new PEMParser(reader)) {
            var object = parser.readObject();
            if (object == null) {
                throw new IllegalStateException("no PEM object found");
            }
            return object;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Reader open(String contentOrLocation, ResourceLoader resources) throws IOException {
        if (contentOrLocation.startsWith(PEM_START)) {
            return new StringReader(contentOrLocation);
        }
        return new InputStreamReader(
                resources.getResource(contentOrLocation).getInputStream(), StandardCharsets.US_ASCII);
    }
}

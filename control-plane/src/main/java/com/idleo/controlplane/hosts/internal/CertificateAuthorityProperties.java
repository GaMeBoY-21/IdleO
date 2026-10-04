package com.idleo.controlplane.hosts.internal;

import jakarta.validation.constraints.NotBlank;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where the online intermediate CA comes from (ADR 0010). Each value is either PEM content or a
 * resource location such as {@code file:/run/secrets/ca-intermediate.pem}. In production every value
 * comes from the environment (the secret store); the root's private key is never here, only its
 * certificate.
 *
 * @param rootCertificate our offline root's certificate
 * @param intermediateCertificate the intermediate's certificate, signed by the root
 * @param intermediatePrivateKey the intermediate's PKCS#8 private key, encrypted or not
 * @param intermediatePrivateKeyPassword the password for an encrypted key; empty for an unencrypted one
 */
@ConfigurationProperties("hosts.ca")
@Validated
record CertificateAuthorityProperties(
        @NotBlank String rootCertificate,
        @NotBlank String intermediateCertificate,
        @NotBlank String intermediatePrivateKey,
        @Nullable String intermediatePrivateKeyPassword) {}

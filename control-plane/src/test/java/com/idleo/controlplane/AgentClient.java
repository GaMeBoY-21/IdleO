package com.idleo.controlplane;

import com.google.protobuf.ByteString;
import gpu.agent.v1.Enrollment.EnrollRequest;
import gpu.agent.v1.Enrollment.EnrollResponse;
import gpu.agent.v1.EnrollmentServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.jspecify.annotations.Nullable;

/**
 * What an agent does at enrolment, for tests: a TLS connection to the gRPC port that trusts only our
 * test root (never public CAs), and a CSR for its own fresh key pair.
 */
final class AgentClient implements AutoCloseable {

    private final ManagedChannel channel;
    private final EnrollmentServiceGrpc.EnrollmentServiceBlockingStub stub;

    AgentClient(int port) {
        try {
            var tls =
                    GrpcSslContexts.forClient().trustManager(TestPki.get().root).build();
            channel = NettyChannelBuilder.forAddress("localhost", port)
                    .sslContext(tls)
                    .build();
        } catch (SSLException e) {
            throw new IllegalStateException(e);
        }
        stub = EnrollmentServiceGrpc.newBlockingStub(channel);
    }

    EnrollResponse enroll(String code, byte[] csrDer) {
        return stub.withDeadlineAfter(15, TimeUnit.SECONDS)
                .enroll(EnrollRequest.newBuilder()
                        .setEnrollmentCode(code)
                        .setCsrDer(ByteString.copyFrom(csrDer))
                        .build());
    }

    /** A CSR for {@code keys}. If {@code requestedUri} is set, the CSR asks for it as a SAN. */
    static byte[] csr(KeyPair keys, String signatureAlgorithm, @Nullable String requestedUri) {
        try {
            var builder = new JcaPKCS10CertificationRequestBuilder(
                    new X500Name("CN=" + (requestedUri == null ? "agent" : requestedUri)), keys.getPublic());
            if (requestedUri != null) {
                var extensions = new ExtensionsGenerator();
                extensions.addExtension(
                        Extension.subjectAlternativeName,
                        false,
                        new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, requestedUri)));
                builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions.generate());
            }
            return builder.build(new JcaContentSignerBuilder(signatureAlgorithm).build(keys.getPrivate()))
                    .getEncoded();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (OperatorCreationException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] p256Csr(KeyPair keys) {
        return csr(keys, "SHA256withECDSA", null);
    }

    static KeyPair newP256Keys() {
        try {
            return TestPki.p256();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        try {
            channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

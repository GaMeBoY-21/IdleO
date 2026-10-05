package com.idleo.controlplane.agentgateway.internal;

import com.idleo.controlplane.hosts.HostCertificates;
import com.idleo.controlplane.hosts.HostCertificates.CertificateCheck;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Mutual TLS for {@code AgentGatewayService}, and only there (ADR 0011). The TLS handshake has
 * already checked the client certificate chains to our root, the only trust anchor. This interceptor
 * then asks the hosts module which host the certificate belongs to: the row must exist and not be
 * revoked, now must be inside its validity, and the host must not be suspended. As defence in depth,
 * the certificate's {@code urn:idleo:host:} name must be that same host.
 *
 * <p>The host id goes into the gRPC {@link Context}; nothing else identifies the caller.
 */
@Component
class AgentCertificateInterceptor implements ServerInterceptor {

    static final String NO_CERTIFICATE = "A client certificate from host enrolment is required.";
    static final String INVALID_CERTIFICATE = "The client certificate is not valid.";
    static final String HOST_SUSPENDED = "This host is suspended.";
    private static final String HOST_URN_PREFIX = "urn:idleo:host:";
    private static final int SAN_URI = 6;

    private final HostCertificates certificates;
    private final AgentGatewayMetrics metrics;

    AgentCertificateInterceptor(HostCertificates certificates, AgentGatewayMetrics metrics) {
        this.certificates = certificates;
        this.metrics = metrics;
    }

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
        var certificate = peerCertificate(call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION));
        if (certificate == null) {
            return refuse(call, Status.UNAUTHENTICATED.withDescription(NO_CERTIFICATE), "no_certificate");
        }
        var fingerprint = fingerprint(certificate);
        return switch (certificates.authenticate(fingerprint)) {
            case CertificateCheck.Invalid _ ->
                refuse(call, Status.UNAUTHENTICATED.withDescription(INVALID_CERTIFICATE), "invalid_certificate");
            case CertificateCheck.HostSuspended _ ->
                refuse(call, Status.PERMISSION_DENIED.withDescription(HOST_SUSPENDED), "host_suspended");
            case CertificateCheck.Valid valid -> {
                if (!namesOnlyHost(certificate, valid.hostId())) {
                    yield refuse(call, Status.UNAUTHENTICATED.withDescription(INVALID_CERTIFICATE), "name_mismatch");
                }
                var context = Context.current()
                        .withValue(AgentContext.HOST_ID, valid.hostId())
                        .withValue(AgentContext.CERTIFICATE_FINGERPRINT, fingerprint);
                yield Contexts.interceptCall(context, call, headers, next);
            }
        };
    }

    private <ReqT, RespT> ServerCall.Listener<ReqT> refuse(ServerCall<ReqT, RespT> call, Status status, String reason) {
        metrics.connectRefused(reason);
        call.close(status, new Metadata());
        return new ServerCall.Listener<>() {};
    }

    private static @Nullable X509Certificate peerCertificate(@Nullable SSLSession session) {
        if (session == null) {
            return null;
        }
        try {
            var chain = session.getPeerCertificates();
            return chain.length > 0 && chain[0] instanceof X509Certificate x509 ? x509 : null;
        } catch (SSLPeerUnverifiedException none) {
            return null;
        }
    }

    private static boolean namesOnlyHost(X509Certificate certificate, UUID hostId) {
        try {
            var names = certificate.getSubjectAlternativeNames();
            if (names == null) {
                return false;
            }
            var hostNames = names.stream()
                    .filter(name -> name.size() == 2 && Integer.valueOf(SAN_URI).equals(name.get(0)))
                    .map(name -> String.valueOf(name.get(1)))
                    .filter(uri -> uri.startsWith(HOST_URN_PREFIX))
                    .toList();
            return hostNames.equals(List.of(HOST_URN_PREFIX + hostId));
        } catch (CertificateParsingException e) {
            return false;
        }
    }

    private static byte[] fingerprint(X509Certificate certificate) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

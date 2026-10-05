package com.idleo.controlplane.agentgateway.internal;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.idleo.controlplane.hosts.AlreadyRenewedException;
import com.idleo.controlplane.hosts.HostCertificates;
import com.idleo.controlplane.hosts.InvalidCertificateRequestException;
import com.idleo.controlplane.hosts.RenewalNotOpenException;
import gpu.agent.v1.AgentGateway.OpenStreamRequest;
import gpu.agent.v1.AgentGateway.OpenStreamResponse;
import gpu.agent.v1.AgentGateway.RenewCertificateRequest;
import gpu.agent.v1.AgentGateway.RenewCertificateResponse;
import gpu.agent.v1.AgentGatewayServiceGrpc;
import gpu.agent.v1.Enrollment.IssuedCertificate;
import io.grpc.Status;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

/**
 * {@code AgentGatewayService}: the agent's live stream and certificate renewal. Every call has
 * passed {@link AgentCertificateInterceptor}, so the host comes from the client certificate.
 */
@GrpcService(interceptors = AgentCertificateInterceptor.class)
class AgentGatewayGrpcService extends AgentGatewayServiceGrpc.AgentGatewayServiceImplBase {

    private final AgentStreamServices services;
    private final HostCertificates certificates;

    AgentGatewayGrpcService(AgentStreamServices services, HostCertificates certificates) {
        this.services = services;
        this.certificates = certificates;
    }

    @Override
    public StreamObserver<OpenStreamRequest> openStream(StreamObserver<OpenStreamResponse> responses) {
        var stream = new AgentStream(
                AgentContext.HOST_ID.get(),
                services.ids().newId(),
                (ServerCallStreamObserver<OpenStreamResponse>) responses,
                services);
        stream.start();
        return stream;
    }

    @Override
    public void renewCertificate(RenewCertificateRequest request, StreamObserver<RenewCertificateResponse> responses) {
        if (request.getCsrDer().isEmpty()) {
            responses.onError(Status.INVALID_ARGUMENT
                    .withDescription("csr_der is required.")
                    .asRuntimeException());
            return;
        }
        try {
            var renewed = certificates.renew(
                    AgentContext.HOST_ID.get(),
                    AgentContext.CERTIFICATE_FINGERPRINT.get(),
                    request.getCsrDer().toByteArray());
            var certificate = IssuedCertificate.newBuilder()
                    .setNotAfter(Timestamp.newBuilder()
                            .setSeconds(renewed.notAfter().getEpochSecond())
                            .setNanos(renewed.notAfter().getNano()));
            renewed.certificateChainDer().forEach(der -> certificate.addCertificateChainDer(ByteString.copyFrom(der)));
            responses.onNext(RenewCertificateResponse.newBuilder()
                    .setCertificate(certificate)
                    .build());
            responses.onCompleted();
        } catch (RenewalNotOpenException | AlreadyRenewedException e) {
            responses.onError(
                    Status.FAILED_PRECONDITION.withDescription(e.getMessage()).asRuntimeException());
        } catch (InvalidCertificateRequestException e) {
            responses.onError(
                    Status.INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }
    }
}

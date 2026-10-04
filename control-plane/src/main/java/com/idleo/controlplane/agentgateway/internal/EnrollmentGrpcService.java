package com.idleo.controlplane.agentgateway.internal;

import com.google.protobuf.ByteString;
import com.google.protobuf.Timestamp;
import com.idleo.controlplane.hosts.HostEnrolment;
import com.idleo.controlplane.hosts.InvalidCertificateRequestException;
import com.idleo.controlplane.hosts.InvalidEnrolmentCodeException;
import gpu.agent.v1.Enrollment.EnrollRequest;
import gpu.agent.v1.Enrollment.EnrollResponse;
import gpu.agent.v1.Enrollment.IssuedCertificate;
import gpu.agent.v1.EnrollmentServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

/**
 * {@code EnrollmentService.Enroll}: a one-time code and a CSR in, the host's certificate chain out.
 * Needs no client certificate. Every bad code gets the same UNAUTHENTICATED answer; a bad CSR or an
 * empty field gets INVALID_ARGUMENT. No error or log line ever contains the code or the CSR.
 */
@GrpcService
class EnrollmentGrpcService extends EnrollmentServiceGrpc.EnrollmentServiceImplBase {

    private final HostEnrolment enrolment;

    EnrollmentGrpcService(HostEnrolment enrolment) {
        this.enrolment = enrolment;
    }

    @Override
    public void enroll(EnrollRequest request, StreamObserver<EnrollResponse> responses) {
        if (request.getEnrollmentCode().isBlank()) {
            responses.onError(invalid("enrollment_code is required."));
            return;
        }
        if (request.getCsrDer().isEmpty()) {
            responses.onError(invalid("csr_der is required."));
            return;
        }
        try {
            var enrolled = enrolment.enrol(
                    request.getEnrollmentCode(), request.getCsrDer().toByteArray());
            var certificate = IssuedCertificate.newBuilder()
                    .setNotAfter(Timestamp.newBuilder()
                            .setSeconds(enrolled.notAfter().getEpochSecond())
                            .setNanos(enrolled.notAfter().getNano()));
            enrolled.certificateChainDer().forEach(der -> certificate.addCertificateChainDer(ByteString.copyFrom(der)));
            responses.onNext(
                    EnrollResponse.newBuilder().setCertificate(certificate).build());
            responses.onCompleted();
        } catch (InvalidEnrolmentCodeException e) {
            responses.onError(
                    Status.UNAUTHENTICATED.withDescription(e.getMessage()).asRuntimeException());
        } catch (InvalidCertificateRequestException e) {
            responses.onError(invalid(e.getMessage()));
        }
    }

    private static RuntimeException invalid(String description) {
        return Status.INVALID_ARGUMENT.withDescription(description).asRuntimeException();
    }
}

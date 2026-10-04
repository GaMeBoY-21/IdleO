package com.idleo.controlplane.hosts;

import java.time.Instant;
import java.util.List;

/**
 * A newly issued host certificate: the DER-encoded chain, the host's certificate first and then our
 * intermediate (never the root), and when it expires.
 */
public record EnrolledCertificate(List<byte[]> certificateChainDer, Instant notAfter) {

    public EnrolledCertificate {
        certificateChainDer = List.copyOf(certificateChainDer);
    }
}

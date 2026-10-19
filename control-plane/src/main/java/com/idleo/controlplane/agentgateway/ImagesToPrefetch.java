package com.idleo.controlplane.agentgateway;

import java.util.List;

/**
 * A port the agent gateway declares and the sessions module implements (ADR 0035): the job images worth having on a host
 * before a job needs them, sent to every agent in its Welcome. The sessions module owns the template catalog and already
 * depends on the agent gateway, so the dependency still points one way.
 */
public interface ImagesToPrefetch {

    /**
     * @param image the image, pinned by digest
     * @param minVramMb the GPU memory, in MB, the least demanding template using it needs
     */
    record Image(String image, int minVramMb) {}

    /** Every image of the catalog's visible services, once each, in the catalog's order. */
    List<Image> images();
}

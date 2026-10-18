package com.idleo.controlplane.hosts.internal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * A tray's pause, resume and leave are the web app's own code (ADR 0034): each goes to {@link HostLifecycle}, as the
 * host's owner, and nowhere else. The integration tests check the outcome; this checks the path.
 */
class HostAgentRequestServiceTests {

    private final UUID owner = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final HostLifecycle lifecycle = mock(HostLifecycle.class);
    private final HostRepository hosts = mock(HostRepository.class);
    private final HostAgentRequestService service = new HostAgentRequestService(lifecycle, hosts, Clock.systemUTC());

    HostAgentRequestServiceTests() {
        when(hosts.findById(hostId))
                .thenReturn(Optional.of(Host.newHost(hostId, owner, "Laptop", Instant.parse("2026-10-18T00:00:00Z"))));
    }

    @Test
    void pausingResumingAndLeavingGoThroughTheOwnersLifecycle() {
        var until = Instant.parse("2026-10-18T18:00:00Z");
        service.pause(hostId, until);
        verify(lifecycle).pause(owner, hostId, until);
        service.pause(hostId, null);
        verify(lifecycle).pause(owner, hostId, null);
        service.resume(hostId);
        verify(lifecycle).resume(owner, hostId);
        service.leave(hostId);
        verify(lifecycle).remove(owner, hostId);
    }

    @Test
    void leavingARemovedHostChangesNothingButABusyOneIsRefused() {
        doThrow(ApiProblem.of(Problem.HOST_REMOVED, "gone")).when(lifecycle).remove(owner, hostId);
        service.leave(hostId);

        doThrow(ApiProblem.of(Problem.HOST_BUSY, "busy")).when(lifecycle).remove(owner, hostId);
        assertThatThrownBy(() -> service.leave(hostId)).isInstanceOf(ApiProblem.class);
    }
}

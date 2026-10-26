package com.idleo.controlplane.sessions.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.idleo.controlplane.ControlPlaneTest;
import com.idleo.controlplane.TestUsers;
import com.idleo.controlplane.billing.internal.TestCredit;
import com.idleo.controlplane.hosts.internal.TestBlocklists;
import com.idleo.controlplane.images.internal.TestImages;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * A session of the renter's own image (ADR 0042) starts with the image as checked, pinned by digest; the renter's command
 * with the image's shell in its own working directory, or the image's own; the pull's time limit and size; and a private
 * image's token, only in the StartSession as delivered, never in the stored command. Its download shows on the session's
 * own screen, from the host's heartbeats, before minute 0.
 */
@ControlPlaneTest
class OwnImageStartTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvc mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ImagePullProgress pulls;

    @LocalGrpcServerPort
    int grpcPort;

    private TestBookings bookings;

    @BeforeEach
    void bookings() {
        TestBlocklists.ensureOne(context);
        bookings = new TestBookings(mvc, context, jdbc, grpcPort);
    }

    @AfterEach
    void disconnect() {
        bookings.close();
    }

    @Test
    void thePrivateImageStartsPinnedWithTheRenterCommandItsLimitAndItsTokenOnlyOnTheWire() throws Exception {
        var renter = TestUsers.signUpLasting(mvc, Duration.ofDays(30));
        TestImages.verifyPan(mvc, renter);
        TestCredit.grant(context, renter.id(), 50_000);
        var token = "ghp_readonly_" + UUID.randomUUID();
        var imageId = TestImages.ready(mvc, jdbc, renter, "ghcr.io/renter/job:v1", "renter:" + token);
        var booked = bookings.bookOwnImageAndAccept(renter, imageId, "{\"command\": \"python train.py --epochs 3\"}");

        var start = booked.host().awaitStart(TestBookings.WAIT).getStartSession();
        var spec = start.getSpec();
        assertThat(spec.getImage()).startsWith("ghcr.io/renter/job@sha256:");
        assertThat(spec.getOwnImage()).isTrue();
        assertThat(spec.getEntrypointList()).containsExactly("/bin/sh", "-c");
        assertThat(spec.getArgsList()).containsExactly("python train.py --epochs 3");
        assertThat(spec.getWorkingDir()).isEqualTo("/app");
        assertThat(spec.getImagePullTimeout().getSeconds()).isEqualTo(1200);
        assertThat(spec.getImageSizeBytes()).isEqualTo(3_100_000_000L);
        assertThat(start.getCredentials().getRegistryUsername()).isEqualTo("renter");
        assertThat(start.getCredentials().getRegistryToken()).isEqualTo(token);
        // Queued without it: the token is only ever in the message as it goes.
        assertThat(jdbc.queryForObject(
                        "select count(*) from agentgateway.agent_commands where session_id = ? and encode(payload, 'escape') like ?",
                        Integer.class,
                        booked.sessionId(),
                        "%" + token + "%"))
                .isZero();

        // Its download, from the host's heartbeats, on the session's own screen.
        pulls.onImagePull(booked.host().id(), booked.sessionId(), 1, 1_000_000_000L, 3_100_000_000L);
        var view = JSON.readTree(
                mvc.perform(get("/v1/sessions/" + booked.sessionId()).header("Authorization", renter.bearer()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(view.at("/imageDownload/doneBytes").asLong()).isEqualTo(1_000_000_000L);
        assertThat(view.at("/imageDownload/totalBytes").asLong()).isEqualTo(3_100_000_000L);
        assertThat(view.get("imageId").asString()).isEqualTo(imageId.toString());
    }

    @Test
    void leftEmptyTheImageRunsItsOwnEntrypointAndCommand() throws Exception {
        var renter = TestUsers.signUpLasting(mvc, Duration.ofDays(30));
        TestImages.verifyPan(mvc, renter);
        TestCredit.grant(context, renter.id(), 50_000);
        var imageId = TestImages.ready(mvc, jdbc, renter, "pytorch/pytorch:2.5.1", null);
        var booked = bookings.bookOwnImageAndAccept(renter, imageId, "{}");
        var start = booked.host().awaitStart(TestBookings.WAIT).getStartSession();
        assertThat(start.getSpec().getEntrypointList()).isEmpty();
        assertThat(start.getSpec().getArgsList()).isEmpty();
        assertThat(start.getCredentials().getRegistryToken())
                .as("a public image needs none")
                .isEmpty();
    }
}

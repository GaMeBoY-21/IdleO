package com.idleo.controlplane.console.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.idleo.controlplane.ControlPlaneServerTest;
import com.idleo.controlplane.MutableClock;
import com.idleo.controlplane.TestAdmins;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dashboard's stream as a browser gets it (ADR 0056): over a real port, read as it arrives. MockMvc can't show this:
 * it never hands an async response's later writes to the test.
 */
@ControlPlaneServerTest
class ControlRoomStreamOverHttpTests {

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    ControlRoomRollups rollups;

    @Autowired
    JsonMapper json;

    @Test
    void theDashboardArrivesAtOnceAndAgainAboutTenSecondsLaterWhileTheStreamStaysOpen() throws Exception {
        var owner = TestAdmins.signIn(mvc, jdbc, clock);
        rollups.rollUp(true);
        var started = System.nanoTime();
        var answer = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/admin/v1/control-room/stream"))
                                .header("Authorization", owner.bearer())
                                .header("Admin-Session", owner.session())
                                .timeout(Duration.ofSeconds(40))
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
        assertThat(answer.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
        assertThat(answer.headers().firstValue("X-Accel-Buffering").orElseThrow())
                .isEqualTo("no");

        var arrived = new ArrayList<Long>();
        try (InputStream body = answer.body();
                var lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String name = null;
            for (String line = lines.readLine(); line != null && arrived.size() < 3; line = lines.readLine()) {
                if (line.startsWith("event:")) {
                    name = line.substring(6).strip();
                } else if (line.startsWith("data:") && "dashboard".equals(name)) {
                    arrived.add(System.nanoTime());
                    var dashboard = json.readTree(line.substring(5).strip());
                    assertThat(dashboard.has("health")).isTrue();
                    assertThat(dashboard.has("computedAt")).isTrue();
                }
            }
        }
        assertThat(arrived).hasSize(3);
        assertThat(Duration.ofNanos(arrived.get(0) - started)).isLessThan(Duration.ofSeconds(3));
        var first = Duration.ofNanos(arrived.get(1) - arrived.get(0));
        var second = Duration.ofNanos(arrived.get(2) - arrived.get(1));
        System.out.println("control room: stream events " + first.toMillis() + " and " + second.toMillis()
                + " ms apart over HTTP");
        assertThat(first).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(12));
        assertThat(second).isBetween(Duration.ofSeconds(9), Duration.ofSeconds(12));
    }
}

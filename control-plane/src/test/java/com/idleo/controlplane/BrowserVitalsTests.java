package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** What real visits meet, as the pages send it (ADR 0036): no sign-in, plain text, bounded in every way. */
@ControlPlaneTest
@ExtendWith(OutputCaptureExtension.class)
class BrowserVitalsTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    MeterRegistry meters;

    private org.springframework.test.web.servlet.ResultActions send(String body) throws Exception {
        // As navigator.sendBeacon sends it: plain text, no Authorization, from the web app's origin.
        return mvc.perform(post("/v1/vitals")
                .contentType(MediaType.TEXT_PLAIN)
                .header("Origin", "http://localhost:3000")
                .content(body));
    }

    @Test
    void aVisitsVitalsBecomeMetersByAppRouteAndDevice() throws Exception {
        send("""
                        {"app": "app", "release": "2026.10.20-abc1234", "items": [
                          {"type": "vital", "name": "LCP", "value": 1840.5, "route": "/session/", "device": "phone"},
                          {"type": "vital", "name": "CLS", "value": 0.02, "route": "/session/", "device": "phone"},
                          {"type": "vital", "name": "INP", "value": 96, "route": "/book/", "device": "laptop"}
                        ]}
                        """).andExpect(status().isNoContent());

        var lcp = meters.get("browser.vitals.lcp")
                .tags("app", "app", "route", "/session/", "device", "phone")
                .summary();
        assertThat(lcp.count()).isGreaterThanOrEqualTo(1);
        assertThat(lcp.max()).isGreaterThanOrEqualTo(1.8405);
        assertThat(meters.get("browser.vitals.cls")
                        .tags("route", "/session/")
                        .summary()
                        .max())
                .isGreaterThanOrEqualTo(0.02);
        assertThat(meters.get("browser.vitals.inp")
                        .tags("device", "laptop")
                        .summary()
                        .count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void anErrorIsCountedByReleaseAndLoggedWithoutWhatIsPersonal(CapturedOutput output) throws Exception {
        var before =
                meters.find("browser.errors").tags("release", "2026.10.20-err").counter();
        var already = before == null ? 0 : before.count();

        send("""
                        {"app": "site", "release": "2026.10.20-err", "items": [
                          {"type": "error", "message": "TypeError: x is undefined for asha@example.com at /book/?token=abc",
                           "source": "/site-assets/pricing-1a2b.js", "line": 1, "column": 2048, "route": "/pricing/"}
                        ]}
                        """).andExpect(status().isNoContent());

        assertThat(meters.get("browser.errors")
                                .tags("app", "site", "route", "/pricing/", "release", "2026.10.20-err")
                                .counter()
                                .count()
                        - already)
                .isEqualTo(1);
        assertThat(output.getAll())
                .contains("JavaScript error in the site (2026.10.20-err) on /pricing/: TypeError: x is undefined for")
                .contains("/site-assets/pricing-1a2b.js:1:2048")
                .doesNotContain("asha@example.com")
                .doesNotContain("token=abc");
    }

    @Test
    void whatIsntAPlainCodeIsCountedAsOther() throws Exception {
        send("""
                        {"app": "app", "release": "not a release!", "items": [
                          {"type": "error", "message": "x", "route": "/sessions/0199f0a2 7c1e?x=1"}
                        ]}
                        """).andExpect(status().isNoContent());

        assertThat(meters.get("browser.errors")
                        .tags("route", "other", "release", "other")
                        .counter()
                        .count())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void aBatchTooBigOrNotABatchIsRefused() throws Exception {
        send("{\"app\": \"app\", \"items\": [" + "{},".repeat(4000) + "{}]}")
                .andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.type").value("/problems/request-too-large"));
        send("{\"app\": \"spy\", \"items\": []}").andExpect(status().isBadRequest());
        send("{\"app\": \"app\", \"items\": [" + "{},".repeat(20) + "{}]}").andExpect(status().isBadRequest());
        send("not json").andExpect(status().isBadRequest());
    }
}

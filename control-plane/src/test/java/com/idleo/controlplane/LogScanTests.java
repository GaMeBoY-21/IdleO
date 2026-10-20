package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The log scan's patterns find what they must and leave ordinary log lines alone. */
class LogScanTests {

    @Test
    void findsPersonalDataAndSecrets() {
        assertThat(LogScan.finding("Granted admin to nikki@example.com")).contains("email address");
        assertThat(LogScan.finding("token eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJl"))
                .contains("JSON web token");
        assertThat(LogScan.finding("Authorization: Bearer abcdefghijklmnopqrstuvwxyz012345"))
                .contains("bearer token");
        assertThat(LogScan.finding("key idleo_k7Qm2Xv9Lp4Rt8Wn3Zs6")).contains("API key");
        assertThat(LogScan.finding("GET /b/o?X-Amz-Credential=AKIAEXAMPLE%2F20261020&X-Amz-Signature=abc"))
                .contains("signed storage link");
        assertThat(LogScan.finding("secretAccessKey=wJalrXUtnFEMI/K7MDENG")).contains("storage secret");
        assertThat(LogScan.finding("staging uploads/0199f0a2-7c1e-7d3a-9b2f-3c4d5e6f7a8b/files/my thesis.pdf"))
                .contains("renter's file key");
        assertThat(LogScan.finding("Upload 0199: weights/model 00001.safetensors came to 3 bytes"))
                .contains("renter's file name");
    }

    @Test
    void leavesOrdinaryLinesAlone() {
        for (var line : new String[] {
            "Emailed message 0199f0a2-7c1e-7d3a-9b2f-3c4d5e6f7a8b (session-completed)",
            "HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection@6b1e8f3a",
            "Sender IdleO <notifications@localhost>",
            "Session 0199f0a2-7c1e-7d3a-9b2f-3c4d5e6f7a8b attempt 2 checkpoint at sessions/0199/attempt-2/checkpoints/3",
            "GET /v1/sessions/{id} 200 in 12 ms"
        }) {
            assertThat(LogScan.finding(line)).as(line).isEmpty();
        }
    }
}

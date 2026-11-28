package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.MockMvc;

/**
 * A query string Tomcat can't parse, such as a parameter with no name, is the client's mistake: a 400 that says so, not
 * the 500 it was when a handler read its parameters. Over a real port, since MockMvc never parses a query as Tomcat does.
 */
@ControlPlaneServerTest
class MalformedQueryTests {

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @Test
    void aQueryTomcatCantParseIsAMalformedRequest() throws Exception {
        var user = TestUsers.signUp(mvc);
        var answer = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/sessions?=x"))
                                .header("Authorization", user.bearer())
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(400);
        assertThat(answer.body()).contains("/problems/malformed-request");
    }
}

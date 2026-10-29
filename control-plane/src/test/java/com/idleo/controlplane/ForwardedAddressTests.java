package com.idleo.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The client's address in a cluster (application-cluster.yaml, ADR 0045): Tomcat believes X-Forwarded-For only from
 * the ingress's own addresses, so a request from anywhere else that writes its own is counted under its real address
 * (docs/runbooks/deployment.md); from the ingress, under the client's. The address is what the request limits count
 * ({@code request.getRemoteAddr()}, RequestLimits). The cluster profile's own file configures a Tomcat that serves only
 * that address, so nothing else in the application is in play; scripts/k8s/smoke.sh checks it through Traefik.
 */
class ForwardedAddressTests {

    private static final String FORGED = "203.0.113.7";

    /** A plain configuration, so @SpringBootTest's search for the application's never finds it. */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({
        TomcatServletWebServerAutoConfiguration.class,
        DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class
    })
    @RestController
    static class RemoteAddress {

        @GetMapping("/remote-address")
        String remoteAddress(HttpServletRequest request) {
            return request.getRemoteAddr();
        }
    }

    /** The cluster profile's Tomcat, with the ingress at {@code ingressProxies}; this test's requests are from 127.0.0.1. */
    private static ConfigurableApplicationContext tomcat(String ingressProxies) {
        return new SpringApplicationBuilder(RemoteAddress.class)
                .web(WebApplicationType.SERVLET)
                .profiles("cluster")
                .properties("server.port=0", "INGRESS_PROXIES=" + ingressProxies)
                .run();
    }

    private static String remoteAddressSeenBy(ConfigurableApplicationContext context) throws Exception {
        var port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/remote-address"))
                                .header("X-Forwarded-For", FORGED)
                                .build(),
                        HttpResponse.BodyHandlers.ofString())
                .body();
    }

    @Test
    void aForgedForwardedForFromOutsideTheIngressIsIgnored() throws Exception {
        try (var context = tomcat("192\\.0\\.2\\.10")) {
            assertThat(remoteAddressSeenBy(context)).isEqualTo("127.0.0.1");
        }
    }

    @Test
    void theIngressSaysWhoTheClientIs() throws Exception {
        try (var context = tomcat("127\\.0\\.0\\.1")) {
            assertThat(remoteAddressSeenBy(context)).isEqualTo(FORGED);
        }
    }
}

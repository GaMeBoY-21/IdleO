package com.idleo.controlplane;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * The development sign-in server from compose.yaml, with the same image and the same claim configuration
 * ({@code local-sign-in.json}), so the tests prove its tokens sign in (ADR 0013).
 */
class LocalSignInContainer extends GenericContainer<LocalSignInContainer> {

    /** Same image as compose.yaml; {@link ComposeImageTests} keeps the two identical. */
    static final String IMAGE =
            "ghcr.io/navikt/mock-oauth2-server:6.0.4@sha256:47374fe063995d9b6d39ebb6ea21eb5f765025bf0aa08b61b08b439eb91adaf0";

    /** The issuer id in local-sign-in.json, which is also the audience it writes into tokens. */
    static final String ISSUER_ID = "idleo-local";

    LocalSignInContainer() {
        super(DockerImageName.parse(IMAGE));
        withCopyFileToContainer(MountableFile.forHostPath("local-sign-in.json"), "/config/local-sign-in.json");
        withEnv("JSON_CONFIG_PATH", "/config/local-sign-in.json");
        withExposedPorts(8080);
        waitingFor(Wait.forHttp("/" + ISSUER_ID + "/.well-known/openid-configuration"));
    }

    String issuer() {
        return "http://" + getHost() + ":" + getMappedPort(8080) + "/" + ISSUER_ID;
    }
}

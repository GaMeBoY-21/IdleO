package com.idleo.controlplane;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Test-only endpoint that fails, to prove a 500 reveals nothing about the failure. */
@TestConfiguration(proxyBeanMethods = false)
@RestController
class ThrowingController {

    static final String PATH = "/test-only/throw";
    static final String SECRET_MESSAGE = "secret-detail-that-must-never-leak";

    @GetMapping(PATH)
    String fail() {
        throw new IllegalStateException(SECRET_MESSAGE);
    }
}

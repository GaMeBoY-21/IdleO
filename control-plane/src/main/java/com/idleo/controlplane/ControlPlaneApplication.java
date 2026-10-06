package com.idleo.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The control plane: one Spring Boot application split into Spring Modulith modules, one package
 * each under this one. The cross-cutting web configuration (security and problem details) lives in
 * this root package, which belongs to no module.
 */
@SpringBootApplication
public class ControlPlaneApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(ControlPlaneApplication.class, args);
        // An operator command (the ops profile) has run by now: exit with its status (ADR 0014).
        if (context.getEnvironment().matchesProfiles("ops")) {
            System.exit(SpringApplication.exit(context));
        }
    }
}

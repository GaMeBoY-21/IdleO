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
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}

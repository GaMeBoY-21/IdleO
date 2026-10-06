package com.idleo.controlplane;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Event listeners that do slow work (matching a session to a host) run off the publishing thread, on
 * Spring Boot's virtual-thread executor (ADR 0015). Never in an operator command.
 */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@Profile("!ops")
class AsyncConfiguration {}

package com.idleo.controlplane;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduled jobs run in the server, never in an operator command (the ops profile, ADR 0014). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!ops")
class SchedulingConfiguration {}

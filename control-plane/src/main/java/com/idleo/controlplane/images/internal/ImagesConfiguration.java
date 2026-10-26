package com.idleo.controlplane.images.internal;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The images module's settings, {@code images.*} (ADR 0042). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ImageProperties.class)
class ImagesConfiguration {}

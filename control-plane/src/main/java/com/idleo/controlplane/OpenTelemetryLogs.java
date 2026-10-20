package com.idleo.controlplane;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Every log line also leaves through OTLP, with the trace it was written in (ADR 0036). Spring Boot sets up the console
 * (ECS JSON, or readable when {@code logging.structured.format.console} is empty) and the OpenTelemetry SDK with its
 * log exporter, but leaves Logback's side to the application: {@link Attach} adds OpenTelemetry's appender to the root
 * logger as soon as Spring Boot has set up logging, and this connects it to the SDK once the SDK exists. Until then the
 * appender keeps what is logged, so startup's lines go out too.
 */
@Component
class OpenTelemetryLogs implements InitializingBean {

    private final OpenTelemetry openTelemetry;

    OpenTelemetryLogs(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(openTelemetry);
    }

    /** Registered in META-INF/spring.factories, so it runs before any bean, right after Spring Boot's logging. */
    public static final class Attach implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

        static final String NAME = "OTLP";

        @Override
        public int getOrder() {
            return LoggingApplicationListener.DEFAULT_ORDER + 1;
        }

        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext context)) {
                return;
            }
            var root = context.getLogger(Logger.ROOT_LOGGER_NAME);
            if (root.getAppender(NAME) != null) {
                return;
            }
            var appender = new OpenTelemetryAppender();
            appender.setName(NAME);
            appender.setContext(context);
            // The message, level, logger, exception and trace context; never the MDC wholesale, which could carry
            // whatever a library put there.
            appender.setCaptureExperimentalAttributes(false);
            appender.setCaptureCodeAttributes(false);
            appender.setCaptureKeyValuePairAttributes(true);
            appender.start();
            root.addAppender(appender);
        }
    }
}

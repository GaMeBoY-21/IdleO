package com.idleo.controlplane;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Random;
import java.util.random.RandomGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * A broken release on purpose (ADR 0046), for the proof that a canary which fails is rolled back by itself
 * (scripts/k8s/canary-test.sh): with {@code web.faults.server-error-percent} above 0, that share of API calls
 * ({@code /v1/**}) answer 500. Development only: the prod profile refuses to start with it (ProductionSettingsGuard).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@ConditionalOnExpression("${web.faults.server-error-percent:0} > 0")
class ServerErrorFaults implements WebMvcConfigurer {

    private final int percent;

    ServerErrorFaults(@Value("${web.faults.server-error-percent}") int percent) {
        this.percent = percent;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Fault(percent, new Random())).addPathPatterns("/v1/**");
    }

    /** Fails {@code percent} of the requests it sees, at random. */
    static final class Fault implements HandlerInterceptor {

        private final int percent;
        private final RandomGenerator random;

        Fault(int percent, RandomGenerator random) {
            this.percent = percent;
            this.random = random;
        }

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            if (random.nextInt(100) < percent) {
                throw ApiProblem.of(
                        Problem.INTERNAL_ERROR,
                        "A fault web.faults.server-error-percent made on purpose (development only).");
            }
            return true;
        }
    }
}

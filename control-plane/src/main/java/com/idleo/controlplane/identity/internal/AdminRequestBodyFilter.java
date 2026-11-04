package com.idleo.controlplane.identity.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lets the admin interceptor read a POST's body for its idempotency fingerprint and still leave it for the handler. It
 * costs nothing until asked: a request no one asks about is read as usual.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
class AdminRequestBodyFilter {

    /** A request whose body can be read twice, once {@link #body} has been asked for. */
    static final class ReadableBody extends HttpServletRequestWrapper {

        private byte @Nullable [] body;

        ReadableBody(HttpServletRequest request) {
            super(request);
        }

        byte[] body() throws IOException {
            if (body == null) {
                body = super.getInputStream().readAllBytes();
            }
            return body;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (body == null) {
                return super.getInputStream();
            }
            var bytes = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return bytes.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("this body is read at once");
                }

                @Override
                public int read() {
                    return bytes.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> adminRequestBody() {
        var filter = new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !"POST".equals(request.getMethod());
            }

            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                chain.doFilter(new ReadableBody(request), response);
            }
        };
        var registration = new FilterRegistrationBean<OncePerRequestFilter>(filter);
        registration.setOrder(-200);
        return registration;
    }
}

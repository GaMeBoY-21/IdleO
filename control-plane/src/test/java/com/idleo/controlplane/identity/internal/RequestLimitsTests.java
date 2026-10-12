package com.idleo.controlplane.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idleo.controlplane.ApiProblem;
import com.idleo.controlplane.Problem;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The request limit's own rules, without a server: once per request (a stream's second dispatch isn't counted),
 * when to retry, and that an unreachable Valkey never refuses anyone.
 */
class RequestLimitsTests {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> counters = mock(ValueOperations.class);

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-12T10:00:20Z"), ZoneOffset.UTC);
    private final RequestLimits limits = new RequestLimits(redis, new RequestLimitsProperties(60, 10), clock);

    RequestLimitsTests() {
        when(redis.opsForValue()).thenReturn(counters);
    }

    private static MockHttpServletRequest request(DispatcherType type) {
        var request = new MockHttpServletRequest("GET", "/v1/sessions/1/events");
        request.setDispatcherType(type);
        request.setRemoteAddr("10.1.1.1");
        return request;
    }

    @Test
    void overTheLimitIsA429ThatSaysWhenTheMinuteEnds() {
        when(counters.increment(anyString())).thenReturn(61L);

        assertThatThrownBy(() -> limits.preHandle(request(DispatcherType.REQUEST), new MockHttpServletResponse(), ""))
                .isInstanceOfSatisfying(ApiProblem.class, problem -> {
                    assertThat(problem.problem()).isEqualTo(Problem.RATE_LIMITED);
                    assertThat(problem.retryAfter())
                            .hasValueSatisfying(
                                    wait -> assertThat(wait.toSeconds()).isEqualTo(40));
                });
    }

    @Test
    void aStreamsSecondDispatchIsntCountedAgain() {
        when(counters.increment(anyString())).thenReturn(61L);

        assertThat(limits.preHandle(request(DispatcherType.ASYNC), new MockHttpServletResponse(), ""))
                .isTrue();
        verify(counters, never()).increment(anyString());
    }

    @Test
    void anUnreachableValkeyLetsTheRequestThrough() {
        when(counters.increment(anyString())).thenThrow(new QueryTimeoutException("down"));

        assertThat(limits.preHandle(request(DispatcherType.REQUEST), new MockHttpServletResponse(), ""))
                .isTrue();
    }
}

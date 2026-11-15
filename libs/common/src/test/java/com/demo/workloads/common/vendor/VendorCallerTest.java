package com.demo.workloads.common.vendor;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VendorCallerTest {
    @RegisterExtension
    static WireMockExtension vendor = WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

    CircuitBreakerRegistry breakers;
    VendorCaller caller;
    SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        VendorProperties props = new VendorProperties();
        VendorProperties.Vendor v = new VendorProperties.Vendor();
        v.setBaseUrl(vendor.baseUrl() + "/paylane");
        v.setReadTimeout(Duration.ofMillis(300));
        props.getVendors().put("paylane", v);

        breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .recordExceptions(VendorUnavailableException.class)
                .ignoreExceptions(VendorRejectedException.class)
                .build());
        RetryRegistry retries = RetryRegistry.of(RetryConfig.custom().maxAttempts(2).waitDuration(Duration.ofMillis(10))
                .retryExceptions(VendorUnavailableException.class).build());
        meters = new SimpleMeterRegistry();
        caller = new VendorCaller(new VendorClients(props, RestClient.builder()), breakers, retries,
                RateLimiterRegistry.ofDefaults(), BulkheadRegistry.ofDefaults(), meters);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> charge() {
        return caller.call("paylane", "charge", c -> c.post().uri("/v1/charges").body(Map.of("amount", 10))
                .retrieve().body(Map.class));
    }

    @Test
    void returnsVendorBodyAndRecordsTimer() {
        vendor.stubFor(post("/paylane/v1/charges").willReturn(okJson("{\"id\":\"ch_1\"}")));

        assertThat(charge()).containsEntry("id", "ch_1");
        assertThat(meters.get("vendor.call.duration").tag("vendor", "paylane").tag("outcome", "success").timer().count())
                .isEqualTo(1);
    }

    @Test
    void fourXxIsABusinessRejectionNotRetriedAndIgnoredByTheBreaker() {
        vendor.stubFor(post("/paylane/v1/charges").willReturn(jsonResponse("{\"error\":\"card_declined\"}", 402)));

        assertThatThrownBy(this::charge).isInstanceOf(VendorRejectedException.class)
                .satisfies(e -> assertThat(((VendorRejectedException) e).status()).isEqualTo(402));
        vendor.verify(1, postRequestedFor(urlEqualTo("/paylane/v1/charges")));
        assertThat(breakers.circuitBreaker("paylane").getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @Test
    void fiveXxIsRetriedOnceThenSurfacesAsUnavailable() {
        vendor.stubFor(post("/paylane/v1/charges").willReturn(serverError()));

        assertThatThrownBy(this::charge).isInstanceOf(VendorUnavailableException.class).hasMessageContaining("HTTP 500");
        vendor.verify(2, postRequestedFor(urlEqualTo("/paylane/v1/charges")));
    }

    @Test
    void slowVendorTimesOutAsUnavailable() {
        vendor.stubFor(post("/paylane/v1/charges").willReturn(okJson("{}").withFixedDelay(1000)));

        assertThatThrownBy(this::charge).isInstanceOf(VendorUnavailableException.class).hasMessageContaining("I/O");
    }

    @Test
    void breakerOpensAndShortCircuitsWithoutCallingTheVendor() {
        vendor.stubFor(post("/paylane/v1/charges").willReturn(serviceUnavailable()));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(this::charge).isInstanceOf(VendorUnavailableException.class);
        }
        assertThat(breakers.circuitBreaker("paylane").getState()).isEqualTo(CircuitBreaker.State.OPEN);
        int calls = vendor.getAllServeEvents().size();

        assertThatThrownBy(this::charge).isInstanceOf(VendorUnavailableException.class).hasMessageContaining("circuit open");
        assertThat(vendor.getAllServeEvents()).hasSize(calls);
    }

    @Test
    void everyHttpAttemptCarriesItsOwnAttemptId() {
        vendor.stubFor(post("/paylane/v1/charges").willReturn(serverError()));
        assertThatThrownBy(this::charge).isInstanceOf(VendorUnavailableException.class);

        Set<String> ids = vendor.getAllServeEvents().stream()
                .map(e -> e.getRequest().getHeader(VendorClients.ATTEMPT_HEADER)).collect(Collectors.toSet());
        assertThat(ids).hasSize(2).doesNotContainNull();
    }
}

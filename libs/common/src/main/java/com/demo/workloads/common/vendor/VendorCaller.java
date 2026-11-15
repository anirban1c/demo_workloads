package com.demo.workloads.common.vendor;

import com.demo.workloads.common.journey.Journey;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Every outbound SaaS call goes through here:
 * Retry( CircuitBreaker( RateLimiter( Bulkhead( http-call-with-timeouts ))))
 * wrapped in a "vendor &lt;name&gt;.&lt;op&gt;" span. HTTP errors are translated into
 * {@link VendorRejectedException} (4xx, business outcome) or {@link VendorUnavailableException} (transient).
 */
public class VendorCaller {
    private final VendorClients clients;
    private final CircuitBreakerRegistry breakers;
    private final RetryRegistry retries;
    private final RateLimiterRegistry rateLimiters;
    private final BulkheadRegistry bulkheads;
    private final MeterRegistry meters;
    private final Tracer tracer = GlobalOpenTelemetry.getTracer("demo-workloads.vendor");

    public VendorCaller(VendorClients clients, CircuitBreakerRegistry breakers, RetryRegistry retries,
                        RateLimiterRegistry rateLimiters, BulkheadRegistry bulkheads, MeterRegistry meters) {
        this.clients = clients;
        this.breakers = breakers;
        this.retries = retries;
        this.rateLimiters = rateLimiters;
        this.bulkheads = bulkheads;
        this.meters = meters;
    }

    public <T> T call(String vendor, String operation, Function<RestClient, T> request) {
        CircuitBreaker cb = breakers.circuitBreaker(vendor);
        Retry retry = retries.retry(vendor);
        RateLimiter rl = rateLimiters.rateLimiter(vendor);
        Bulkhead bh = bulkheads.bulkhead(vendor);

        Supplier<T> http = () -> translate(vendor, () -> request.apply(clients.client(vendor)));
        Supplier<T> decorated = Retry.decorateSupplier(retry,
                CircuitBreaker.decorateSupplier(cb,
                        RateLimiter.decorateSupplier(rl,
                                Bulkhead.decorateSupplier(bh, http))));

        Span span = tracer.spanBuilder("vendor " + vendor + "." + operation)
                .setAttribute("vendor.name", vendor)
                .setAttribute("vendor.operation", operation)
                .setAttribute("peer.service", vendor)
                .startSpan();
        Timer.Sample sample = Timer.start(meters);
        String outcome = "success";
        try (Scope ignored = span.makeCurrent()) {
            Journey.tagCurrentSpan();
            return decorated.get();
        } catch (VendorRejectedException e) {
            outcome = "rejected";
            span.setAttribute("vendor.outcome", outcome);
            span.setAttribute("vendor.status_code", e.status());
            throw e;
        } catch (CallNotPermittedException e) {
            outcome = "circuit_open";
            throw fail(span, new VendorUnavailableException(vendor, "circuit open", e));
        } catch (RequestNotPermitted e) {
            outcome = "rate_limited";
            throw fail(span, new VendorUnavailableException(vendor, "local rate limit", e));
        } catch (BulkheadFullException e) {
            outcome = "bulkhead_full";
            throw fail(span, new VendorUnavailableException(vendor, "bulkhead full", e));
        } catch (VendorUnavailableException e) {
            outcome = "unavailable";
            throw fail(span, e);
        } finally {
            span.setAttribute("vendor.outcome", outcome);
            span.setAttribute("vendor.circuit_state", cb.getState().name());
            sample.stop(Timer.builder("vendor.call.duration")
                    .description("Outbound SaaS vendor call latency incl. resilience decorators")
                    .tag("vendor", vendor).tag("operation", operation).tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(meters));
            span.end();
        }
    }

    private static RuntimeException fail(Span span, VendorUnavailableException e) {
        span.recordException(e);
        span.setStatus(StatusCode.ERROR, e.getMessage());
        return e;
    }

    private static <T> T translate(String vendor, Supplier<T> call) {
        try {
            return call.get();
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            if (code == 429 || code >= 500) {
                throw new VendorUnavailableException(vendor, "HTTP " + code, e);
            }
            throw new VendorRejectedException(vendor, code, e.getResponseBodyAsString());
        } catch (ResourceAccessException e) {
            throw new VendorUnavailableException(vendor, "I/O: " + e.getMessage(), e);
        }
    }
}

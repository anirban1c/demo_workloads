package com.demo.workloads.gateway.throttle;

import com.demo.workloads.gateway.GatewayProperties;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Throttle #1, at the edge: Redis-backed token buckets (shared by every gateway replica).
 * A per-client bucket stops one noisy client starving the rest; a global bucket caps the total admitted rate so a
 * burst bigger than the estate can drain is shed with 429 + Retry-After instead of growing lag without bound.
 * Status polls are exempt from the global bucket so in-flight async journeys can still complete under shedding.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RateLimitFilter extends OncePerRequestFilter {
    private final ProxyManager<byte[]> buckets;
    private final GatewayProperties.RateLimit cfg;
    private final MeterRegistry meters;
    private final BucketConfiguration perClient;
    private final BucketConfiguration global;

    public RateLimitFilter(RedisClient redis, GatewayProperties props, MeterRegistry meters) {
        this.cfg = props.rateLimit();
        this.meters = meters;
        this.buckets = Bucket4jLettuce.casBasedBuilder(redis)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(30)))
                .build();
        this.perClient = BucketConfiguration.builder()
                .addLimit(l -> l.capacity(cfg.perClientBurst()).refillGreedy(cfg.perClientRps(), Duration.ofSeconds(1)))
                .build();
        this.global = BucketConfiguration.builder()
                .addLimit(l -> l.capacity(cfg.globalBurst()).refillGreedy(cfg.globalRps(), Duration.ofSeconds(1)))
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !cfg.enabled() || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String client = req.getHeader("X-Client-Id");
        if (client == null || client.isBlank()) client = req.getRemoteAddr();

        ConsumptionProbe probe = bucket("rl:client:" + client, perClient).tryConsumeAndReturnRemaining(1);
        if (!probe.isConsumed()) {
            reject(res, "client", probe);
            return;
        }
        if (!req.getRequestURI().startsWith("/api/requests/")) {
            ConsumptionProbe g = bucket("rl:global", global).tryConsumeAndReturnRemaining(1);
            if (!g.isConsumed()) {
                reject(res, "global", g);
                return;
            }
        }
        res.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
        chain.doFilter(req, res);
    }

    private io.github.bucket4j.distributed.BucketProxy bucket(String key, BucketConfiguration conf) {
        return buckets.builder().build(key.getBytes(StandardCharsets.UTF_8), () -> conf);
    }

    private void reject(HttpServletResponse res, String scope, ConsumptionProbe probe) throws IOException {
        long retryAfter = Math.max(1, Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds() + 1);
        meters.counter("gateway.throttled", "scope", scope).increment();
        Span.current().setAttribute("throttle.scope", scope);
        res.setStatus(429);
        res.setHeader("Retry-After", String.valueOf(retryAfter));
        res.setContentType("application/problem+json");
        res.getWriter().write("{\"title\":\"Too Many Requests\",\"status\":429,\"scope\":\"" + scope
                + "\",\"retryAfterSeconds\":" + retryAfter + "}");
    }
}

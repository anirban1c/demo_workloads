package com.demo.workloads.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param services   downstream base URLs for the synchronous paths
 * @param rateLimit  throttle #1: token buckets at the edge
 * @param statusTtl  how long async request status is kept in Redis
 */
@ConfigurationProperties("gateway")
public record GatewayProperties(Services services, RateLimit rateLimit, Duration statusTtl, Duration downstreamTimeout) {

    public record Services(String login, String balance, String user, String profile, String insights) {}

    /**
     * @param enabled        turn edge throttling on/off
     * @param perClientRps   sustained requests/s per client (X-Client-Id header, else remote address)
     * @param perClientBurst bucket capacity per client
     * @param globalRps      sustained requests/s across all clients (protects the whole estate)
     * @param globalBurst    global bucket capacity: absorbs short spikes before shedding with 429
     */
    public record RateLimit(boolean enabled, long perClientRps, long perClientBurst, long globalRps, long globalBurst) {}
}

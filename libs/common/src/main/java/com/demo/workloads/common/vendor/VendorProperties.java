package com.demo.workloads.common.vendor;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * demo.vendors.&lt;name&gt;.base-url / connect-timeout / read-timeout. The vendor name is also the Resilience4j
 * instance name (circuit breaker, retry, bulkhead, rate limiter) and the span's vendor.name / peer.service.
 */
@ConfigurationProperties("demo")
public class VendorProperties {
    private Map<String, Vendor> vendors = new LinkedHashMap<>();

    public Map<String, Vendor> getVendors() { return vendors; }

    public void setVendors(Map<String, Vendor> vendors) { this.vendors = vendors; }

    public static class Vendor {
        private String baseUrl;
        private Duration connectTimeout = Duration.ofSeconds(1);
        private Duration readTimeout = Duration.ofSeconds(3);

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public Duration getConnectTimeout() { return connectTimeout; }
        public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
        public Duration getReadTimeout() { return readTimeout; }
        public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }
    }
}

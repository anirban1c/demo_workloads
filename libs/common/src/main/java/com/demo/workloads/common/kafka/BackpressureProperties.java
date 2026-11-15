package com.demo.workloads.common.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * demo.backpressure.bindings.&lt;vendor&gt;: listener-id prefixes to pause while that vendor's circuit is OPEN.
 * Prefix matching also covers the retry-topic containers (ids derived from the main listener id).
 */
@ConfigurationProperties("demo.backpressure")
public class BackpressureProperties {
    private boolean enabled = true;
    private Map<String, List<String>> bindings = new LinkedHashMap<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Map<String, List<String>> getBindings() { return bindings; }
    public void setBindings(Map<String, List<String>> bindings) { this.bindings = bindings; }
}

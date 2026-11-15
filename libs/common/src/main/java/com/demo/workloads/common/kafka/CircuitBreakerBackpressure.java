package com.demo.workloads.common.kafka;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Backpressure: when a vendor's circuit opens, pause the Kafka listeners that depend on it so work queues up as
 * consumer lag (cheap, durable) instead of failing fast through the retry topics. Resume on HALF_OPEN / CLOSED.
 * Breakers must have automatic-transition-from-open-to-half-open-enabled=true (no calls arrive while paused).
 */
public class CircuitBreakerBackpressure {
    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerBackpressure.class);

    private final KafkaListenerEndpointRegistry listeners;
    private final Map<String, List<String>> bindings;
    private final Map<String, Integer> paused = new ConcurrentHashMap<>();

    public CircuitBreakerBackpressure(CircuitBreakerRegistry breakers, KafkaListenerEndpointRegistry listeners,
                                      BackpressureProperties props, MeterRegistry meters) {
        this.listeners = listeners;
        this.bindings = props.getBindings();
        bindings.keySet().forEach(vendor -> {
            CircuitBreaker cb = breakers.circuitBreaker(vendor);
            cb.getEventPublisher().onStateTransition(this::onTransition);
            paused.put(vendor, 0);
            Gauge.builder("consumer.backpressure.paused", paused, m -> m.getOrDefault(vendor, 0))
                    .description("1 while listeners are paused because the vendor circuit is open")
                    .tag("vendor", vendor).register(meters);
        });
    }

    private void onTransition(CircuitBreakerOnStateTransitionEvent event) {
        String vendor = event.getCircuitBreakerName();
        CircuitBreaker.State to = event.getStateTransition().getToState();
        boolean pause = to == CircuitBreaker.State.OPEN || to == CircuitBreaker.State.FORCED_OPEN;
        for (MessageListenerContainer c : matching(vendor)) {
            if (pause) c.pause(); else c.resume();
        }
        paused.put(vendor, pause ? 1 : 0);
        log.warn("circuit {} -> {}: {} listeners for vendor {}", vendor, to, pause ? "paused" : "resumed", vendor);
    }

    private List<MessageListenerContainer> matching(String vendor) {
        List<String> prefixes = bindings.getOrDefault(vendor, List.of());
        return listeners.getAllListenerContainers().stream()
                .filter(c -> c.getListenerId() != null && prefixes.stream().anyMatch(p -> c.getListenerId().startsWith(p)))
                .toList();
    }
}

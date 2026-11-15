package com.demo.workloads.common.kafka;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CircuitBreakerBackpressureTest {

    @Test
    void pausesBoundListenersIncludingRetryTopicsWhileTheCircuitIsOpen() {
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
        MessageListenerContainer main = container("funds-commands");
        MessageListenerContainer retry = container("funds-commands-retry-5000");
        MessageListenerContainer other = container("balance-projection");
        KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
        when(registry.getAllListenerContainers()).thenReturn(List.of(main, retry, other));
        BackpressureProperties props = new BackpressureProperties();
        props.setBindings(Map.of("paylane", List.of("funds-commands")));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();

        new CircuitBreakerBackpressure(breakers, registry, props, meters);
        CircuitBreaker cb = breakers.circuitBreaker("paylane");

        cb.transitionToOpenState();
        verify(main).pause();
        verify(retry).pause();
        verify(other, never()).pause();
        assertThat(meters.get("consumer.backpressure.paused").tag("vendor", "paylane").gauge().value()).isEqualTo(1.0);

        cb.transitionToHalfOpenState();
        verify(main).resume();
        verify(retry).resume();
        assertThat(meters.get("consumer.backpressure.paused").gauge().value()).isZero();
    }

    private static MessageListenerContainer container(String id) {
        MessageListenerContainer c = mock(MessageListenerContainer.class);
        when(c.getListenerId()).thenReturn(id);
        return c;
    }
}
